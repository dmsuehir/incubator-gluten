/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.execution.datasources.v1.clickhouse

import org.apache.gluten.execution.{BatchCarrierRow, PlaceholderRow, TerminalRow}
import org.apache.gluten.execution.datasource.GlutenFormatFactory

import org.apache.spark.internal.Logging
import org.apache.spark.internal.io.{FileCommitProtocol, FileNameSpec}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.catalog.ExternalCatalogUtils
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.FileFormatWriter.ConcurrentOutputWriterSpec
import org.apache.spark.sql.execution.metric.{CustomMetrics, SQLMetric}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StringType

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.TaskAttemptContext

import scala.collection.mutable

/** Writes data to a single directory (used for non-dynamic-partition writes). */
class SingleDirectoryDataWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol,
    customMetrics: Map[String, SQLMetric] = Map.empty)
  extends FileFormatDataWriter(description, taskAttemptContext, committer, customMetrics) {
  private var fileCounter: Int = _
  private var recordsInFile: Long = _
  // Initialize currentWriter and statsTrackers
  newOutputWriter()

  private def newOutputWriter(): Unit = {
    recordsInFile = 0
    releaseResources()

    val ext = description.outputWriterFactory.getFileExtension(taskAttemptContext)
    val currentPath =
      committer.newTaskTempFile(taskAttemptContext, None, f"-c$fileCounter%03d" + ext)

    currentWriter = description.outputWriterFactory.newInstance(
      path = currentPath,
      dataSchema = description.dataColumns.toStructType,
      context = taskAttemptContext)

    statsTrackers.foreach(_.newFile(currentPath))
  }

  override def write(record: InternalRow): Unit = {
    if (description.maxRecordsPerFile > 0 && recordsInFile >= description.maxRecordsPerFile) {
      fileCounter += 1
      assert(
        fileCounter < MAX_FILE_COUNTER,
        s"File counter $fileCounter is beyond max value $MAX_FILE_COUNTER")

      newOutputWriter()
    }

    currentWriter.write(record)
    statsTrackers.foreach(_.newRow(currentWriter.path, record))
    recordsInFile += 1
  }
}

/**
 * Holds common logic for writing data with dynamic partition writes, meaning it can write to
 * multiple directories (partitions) or files (bucketing).
 */
abstract class BaseDynamicPartitionDataWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol,
    customMetrics: Map[String, SQLMetric])
  extends FileFormatDataWriter(description, taskAttemptContext, committer, customMetrics) {

  /** Flag saying whether or not the data to be written out is partitioned. */
  protected val isPartitioned = description.partitionColumns.nonEmpty

  /** Flag saying whether or not the data to be written out is bucketed. */
  protected val isBucketed = description.bucketSpec.isDefined

  assert(
    isPartitioned || isBucketed,
    s"""DynamicPartitionWriteTask should be used for writing out data that's either
       |partitioned or bucketed. In this case neither is true.
       |WriteJobDescription: $description
       """.stripMargin
  )

  /** Number of records in current file. */
  protected var recordsInFile: Long = _

  /**
   * File counter for writing current partition or bucket. For same partition or bucket, we may have
   * more than one file, due to number of records limit per file.
   */
  protected var fileCounter: Int = _

  /** Extracts the partition values out of an input row. */
  protected lazy val getPartitionValues: InternalRow => UnsafeRow = {
    val proj = UnsafeProjection.create(description.partitionColumns, description.allColumns)
    row => proj(row)
  }

  /** Expression that given partition columns builds a path string like: col1=val/col2=val/... */
  private lazy val partitionPathExpression: Expression = Concat(
    description.partitionColumns.zipWithIndex.flatMap {
      case (c, i) =>
        val partitionName = ScalaUDF(
          ExternalCatalogUtils.getPartitionPathString _,
          StringType,
          Seq(Literal(c.name), Cast(c, StringType, Option(description.timeZoneId))))
        if (i == 0) Seq(partitionName) else Seq(Literal(Path.SEPARATOR), partitionName)
    })

  /**
   * Evaluates the `partitionPathExpression` above on a row of `partitionValues` and returns the
   * partition string.
   */
  private lazy val getPartitionPath: InternalRow => String = {
    val proj = UnsafeProjection.create(Seq(partitionPathExpression), description.partitionColumns)
    row => proj(row).getString(0)
  }

  /** Given an input row, returns the corresponding `bucketId` */
  protected lazy val getBucketId: InternalRow => Int = {
    val proj =
      UnsafeProjection.create(
        Seq(description.bucketSpec.get.bucketIdExpression),
        description.allColumns)
    row => proj(row).getInt(0)
  }

  /** Returns the data columns to be written given an input row */
  protected val getOutputRow =
    UnsafeProjection.create(description.dataColumns, description.allColumns)

  /**
   * Opens a new OutputWriter given a partition key and/or a bucket id. If bucket id is specified,
   * we will append it to the end of the file name, but before the file extension, e.g.
   * part-r-00009-ea518ad4-455a-4431-b471-d24e03814677-00002.gz.parquet
   *
   * @param partitionValues
   *   the partition which all tuples being written by this OutputWriter belong to
   * @param bucketId
   *   the bucket which all tuples being written by this OutputWriter belong to
   * @param closeCurrentWriter
   *   close and release resource for current writer
   */
  protected def renewCurrentWriter(
      partitionValues: Option[InternalRow],
      bucketId: Option[Int],
      closeCurrentWriter: Boolean): Unit = {

    recordsInFile = 0
    if (closeCurrentWriter) {
      releaseCurrentWriter()
    }

    val partDir = partitionValues.map(getPartitionPath(_))
    partDir.foreach(updatedPartitions.add)

    val bucketIdStr = bucketId.map(BucketingUtils.bucketIdToString).getOrElse("")

    // The prefix and suffix must be in a form that matches our bucketing format. See BucketingUtils
    // for details. The prefix is required to represent bucket id when writing Hive-compatible
    // bucketed table.
    val prefix = bucketId match {
      case Some(id) => description.bucketSpec.get.bucketFileNamePrefix(id)
      case _ => ""
    }
    val suffix = f"$bucketIdStr.c$fileCounter%03d" +
      description.outputWriterFactory.getFileExtension(taskAttemptContext)
    val fileNameSpec = FileNameSpec(prefix, suffix)

    val customPath = partDir.flatMap {
      dir => description.customPartitionLocations.get(PartitioningUtils.parsePathFragment(dir))
    }
    val currentPath = if (customPath.isDefined) {
      committer.newTaskTempFileAbsPath(taskAttemptContext, customPath.get, fileNameSpec)
    } else {
      committer.newTaskTempFile(taskAttemptContext, partDir, fileNameSpec)
    }

    currentWriter = description.outputWriterFactory.newInstance(
      path = currentPath,
      dataSchema = description.dataColumns.toStructType,
      context = taskAttemptContext)

    statsTrackers.foreach(_.newFile(currentPath))
  }

  /**
   * Open a new output writer when number of records exceeding limit.
   *
   * @param partitionValues
   *   the partition which all tuples being written by this `OutputWriter` belong to
   * @param bucketId
   *   the bucket which all tuples being written by this `OutputWriter` belong to
   */
  protected def renewCurrentWriterIfTooManyRecords(
      partitionValues: Option[InternalRow],
      bucketId: Option[Int]): Unit = {
    // Exceeded the threshold in terms of the number of records per file.
    // Create a new file by increasing the file counter.
    fileCounter += 1
    assert(
      fileCounter < MAX_FILE_COUNTER,
      s"File counter $fileCounter is beyond max value $MAX_FILE_COUNTER")
    renewCurrentWriter(partitionValues, bucketId, closeCurrentWriter = true)
  }

  /**
   * Writes the given record with current writer.
   *
   * @param record
   *   The record to write
   */
  protected def writeRecord(record: InternalRow): Unit = {
    val outputRow = getOutputRow(record)
    currentWriter.write(outputRow)
    statsTrackers.foreach(_.newRow(currentWriter.path, outputRow))
    recordsInFile += 1
  }
}

/**
 * Dynamic partition writer with single writer, meaning only one writer is opened at any time for
 * writing. The records to be written are required to be sorted on partition and/or bucket column(s)
 * before writing.
 */
class DynamicPartitionDataSingleWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol,
    customMetrics: Map[String, SQLMetric] = Map.empty)
  extends BaseDynamicPartitionDataWriter(
    description,
    taskAttemptContext,
    committer,
    customMetrics) {

  private var currentPartitionValues: Option[UnsafeRow] = None
  private var currentBucketId: Option[Int] = None

  private val partitionColIndice: Array[Int] =
    description.partitionColumns.flatMap {
      pcol =>
        description.allColumns.zipWithIndex.collect {
          case (acol, index) if acol.name == pcol.name && acol.exprId == pcol.exprId => index
        }
    }.toArray

  private def beforeWrite(record: InternalRow): Unit = {
    val nextPartitionValues = if (isPartitioned) Some(getPartitionValues(record)) else None
    val nextBucketId = if (isBucketed) Some(getBucketId(record)) else None

    if (currentPartitionValues != nextPartitionValues || currentBucketId != nextBucketId) {
      // See a new partition or bucket - write to a new partition dir (or a new bucket file).
      if (isPartitioned && currentPartitionValues != nextPartitionValues) {
        currentPartitionValues = Some(nextPartitionValues.get.copy())
        statsTrackers.foreach(_.newPartition(currentPartitionValues.get))
      }
      if (isBucketed) {
        currentBucketId = nextBucketId
      }

      fileCounter = 0
      renewCurrentWriter(currentPartitionValues, currentBucketId, closeCurrentWriter = true)
    } else if (
      description.maxRecordsPerFile > 0 &&
      recordsInFile >= description.maxRecordsPerFile
    ) {
      renewCurrentWriterIfTooManyRecords(currentPartitionValues, currentBucketId)
    }
  }

  override def write(record: InternalRow): Unit = {
    record match {
      case carrierRow: BatchCarrierRow =>
        carrierRow match {
          case placeholderRow: PlaceholderRow =>
          // Do nothing.
          case terminalRow: TerminalRow =>
            val numRows = terminalRow.batch().numRows()
            if (numRows > 0) {
              val blockStripes = GlutenFormatFactory.rowSplitter
                .splitBlockByPartitionAndBucket(terminalRow.batch(), partitionColIndice, isBucketed)

              val iter = blockStripes.iterator()
              while (iter.hasNext) {
                val blockStripe = iter.next()
                val headingRow = blockStripe.getHeadingRow
                beforeWrite(headingRow)
                val columnBatch = blockStripe.getColumnarBatch
                currentWriter.write(terminalRow.withNewBatch(columnBatch))
                columnBatch.close()
              }
              blockStripes.release()
              for (_ <- 0 until numRows) {
                statsTrackers.foreach(_.newRow(currentWriter.path, record))
              }
              recordsInFile += numRows
            }
        }
      case _ =>
        beforeWrite(record)
        writeRecord(record)
    }
  }
}

/**
 * Dynamic partition writer with concurrent writers, meaning multiple concurrent writers are opened
 * for writing.
 *
 * The process has the following steps:
 *   - Step 1: Maintain a map of output writers per each partition and/or bucket columns. Keep all
 *     writers opened and write rows one by one.
 *   - Step 2: If number of concurrent writers exceeds limit, sort rest of rows on partition and/or
 *     bucket column(s). Write rows one by one, and eagerly close the writer when finishing each
 *     partition and/or bucket.
 *
 * Caller is expected to call `writeWithIterator()` instead of `write()` to write records.
 */
class DynamicPartitionDataConcurrentWriter(
    description: WriteJobDescription,
    taskAttemptContext: TaskAttemptContext,
    committer: FileCommitProtocol,
    concurrentOutputWriterSpec: ConcurrentOutputWriterSpec,
    customMetrics: Map[String, SQLMetric] = Map.empty)
  extends BaseDynamicPartitionDataWriter(description, taskAttemptContext, committer, customMetrics)
  with Logging {

  /** Wrapper class to index a unique concurrent output writer. */
  private case class WriterIndex(var partitionValues: Option[UnsafeRow], var bucketId: Option[Int])

  /** Wrapper class for status of a unique concurrent output writer. */
  private class WriterStatus(
      var outputWriter: OutputWriter,
      var recordsInFile: Long,
      var fileCounter: Int)

  /**
   * State to indicate if we are falling back to sort-based writer. Because we first try to use
   * concurrent writers, its initial value is false.
   */
  private var sorted: Boolean = false
  private val concurrentWriters = mutable.HashMap[WriterIndex, WriterStatus]()

  /**
   * The index for current writer. Intentionally make the index mutable and reusable. Avoid JVM GC
   * issue when many short-living `WriterIndex` objects are created if switching between concurrent
   * writers frequently.
   */
  private val currentWriterId = WriterIndex(None, None)

  /** Release resources for all concurrent output writers. */
  override protected def releaseResources(): Unit = {
    currentWriter = null
    concurrentWriters.values.foreach(
      status => {
        if (status.outputWriter != null) {
          try {
            status.outputWriter.close()
          } finally {
            status.outputWriter = null
          }
        }
      })
    concurrentWriters.clear()
  }

  override def write(record: InternalRow): Unit = {
    val nextPartitionValues = if (isPartitioned) Some(getPartitionValues(record)) else None
    val nextBucketId = if (isBucketed) Some(getBucketId(record)) else None

    if (
      currentWriterId.partitionValues != nextPartitionValues ||
      currentWriterId.bucketId != nextBucketId
    ) {
      // See a new partition or bucket - write to a new partition dir (or a new bucket file).
      if (currentWriter != null) {
        if (!sorted) {
          // Update writer status in concurrent writers map, because the writer is probably needed
          // again later for writing other rows.
          updateCurrentWriterStatusInMap()
        } else {
          // Remove writer status in concurrent writers map and release current writer resource,
          // because the writer is not needed any more.
          concurrentWriters.remove(currentWriterId)
          releaseCurrentWriter()
        }
      }

      if (isBucketed) {
        currentWriterId.bucketId = nextBucketId
      }
      if (isPartitioned && currentWriterId.partitionValues != nextPartitionValues) {
        currentWriterId.partitionValues = Some(nextPartitionValues.get.copy())
        if (!concurrentWriters.contains(currentWriterId)) {
          statsTrackers.foreach(_.newPartition(currentWriterId.partitionValues.get))
        }
      }
      setupCurrentWriterUsingMap()
    }

    if (
      description.maxRecordsPerFile > 0 &&
      recordsInFile >= description.maxRecordsPerFile
    ) {
      renewCurrentWriterIfTooManyRecords(currentWriterId.partitionValues, currentWriterId.bucketId)
      // Update writer status in concurrent writers map, as a new writer is created.
      updateCurrentWriterStatusInMap()
    }
    writeRecord(record)
  }

  /** Write iterator of records with concurrent writers. */
  override def writeWithIterator(iterator: Iterator[InternalRow]): Unit = {
    var count = 0L
    while (iterator.hasNext && !sorted) {
      writeWithMetrics(iterator.next(), count)
      count += 1
    }
    CustomMetrics.updateMetrics(currentMetricsValues, customMetrics)

    if (iterator.hasNext) {
      count = 0L
      clearCurrentWriterStatus()
      val sorter = concurrentOutputWriterSpec.createSorter()
      val sortIterator = sorter.sort(iterator.asInstanceOf[Iterator[UnsafeRow]])
      while (sortIterator.hasNext) {
        writeWithMetrics(sortIterator.next(), count)
        count += 1
      }
      CustomMetrics.updateMetrics(currentMetricsValues, customMetrics)
    }
  }

  /** Update current writer status in map. */
  private def updateCurrentWriterStatusInMap(): Unit = {
    val status = concurrentWriters(currentWriterId)
    status.outputWriter = currentWriter
    status.recordsInFile = recordsInFile
    status.fileCounter = fileCounter
  }

  /** Retrieve writer in map, or create a new writer if not exists. */
  private def setupCurrentWriterUsingMap(): Unit = {
    if (concurrentWriters.contains(currentWriterId)) {
      val status = concurrentWriters(currentWriterId)
      currentWriter = status.outputWriter
      recordsInFile = status.recordsInFile
      fileCounter = status.fileCounter
    } else {
      fileCounter = 0
      renewCurrentWriter(
        currentWriterId.partitionValues,
        currentWriterId.bucketId,
        closeCurrentWriter = false)
      if (!sorted) {
        assert(
          concurrentWriters.size <= concurrentOutputWriterSpec.maxWriters,
          s"Number of concurrent output file writers is ${concurrentWriters.size} " +
            s" which is beyond max value ${concurrentOutputWriterSpec.maxWriters}"
        )
      } else {
        assert(
          concurrentWriters.size <= concurrentOutputWriterSpec.maxWriters + 1,
          s"Number of output file writers after sort is ${concurrentWriters.size} " +
            s" which is beyond max value ${concurrentOutputWriterSpec.maxWriters + 1}"
        )
      }
      concurrentWriters.put(
        currentWriterId.copy(),
        new WriterStatus(currentWriter, recordsInFile, fileCounter))
      if (concurrentWriters.size >= concurrentOutputWriterSpec.maxWriters && !sorted) {
        // Fall back to sort-based sequential writer mode.
        logInfo(
          s"Number of concurrent writers ${concurrentWriters.size} reaches the threshold. " +
            "Fall back from concurrent writers to sort-based sequential writer. You may change " +
            s"threshold with configuration ${SQLConf.MAX_CONCURRENT_OUTPUT_FILE_WRITERS.key}")
        sorted = true
      }
    }
  }

  /** Clear the current writer status in map. */
  private def clearCurrentWriterStatus(): Unit = {
    if (currentWriterId.partitionValues.isDefined || currentWriterId.bucketId.isDefined) {
      updateCurrentWriterStatusInMap()
    }
    currentWriterId.partitionValues = None
    currentWriterId.bucketId = None
    currentWriter = null
    recordsInFile = 0
    fileCounter = 0
  }
}
