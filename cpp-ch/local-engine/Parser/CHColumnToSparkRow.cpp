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
#include "CHColumnToSparkRow.h"
#include <Columns/ColumnArray.h>
#include <Columns/ColumnConst.h>
#include <Columns/ColumnNullable.h>
#include <Columns/ColumnString.h>
#include <Columns/IColumn.h>
#include <DataTypes/DataTypeArray.h>
#include <DataTypes/DataTypeLowCardinality.h>
#include <DataTypes/DataTypeMap.h>
#include <DataTypes/DataTypeNullable.h>
#include <DataTypes/DataTypeTuple.h>
#include <DataTypes/DataTypesDecimal.h>
#include <DataTypes/ObjectUtils.h>
#include <jni/jni_common.h>
#include <Common/Exception.h>

namespace DB
{
namespace ErrorCodes
{
extern const int LOGICAL_ERROR;
extern const int UNKNOWN_TYPE;
}
}


namespace local_engine
{
using namespace DB;

int64_t calculateBitSetWidthInBytes(int64_t num_fields)
{
    return ((num_fields + 63) / 64) * 8;
}

static int64_t calculatedFixeSizePerRow(int64_t num_cols)
{
    return calculateBitSetWidthInBytes(num_cols) + num_cols * 8;
}

int64_t roundNumberOfBytesToNearestWord(int64_t num_bytes)
{
    auto remainder = num_bytes & 0x07; // This is equivalent to `numBytes % 8`
    return num_bytes + ((8 - remainder) & 0x7);
}


void bitSet(char * bitmap, size_t index)
{
    int64_t mask = 1L << (index & 0x3f); // mod 64 and shift
    int64_t word_offset = (index >> 6) * 8;
    int64_t word;
    memcpy(&word, bitmap + word_offset, sizeof(int64_t));
    int64_t value = word | mask;
    memcpy(bitmap + word_offset, &value, sizeof(int64_t));
}

ALWAYS_INLINE bool isBitSet(const char * bitmap, size_t index)
{
    assert(index >= 0);
    int64_t mask = 1L << (index & 63);
    int64_t word_offset = static_cast<int64_t>(index >> 6) * 8L;
    int64_t word = *reinterpret_cast<const int64_t *>(bitmap + word_offset);
    return word & mask;
}

static void writeFixedLengthNonNullableValue(
    char * buffer_address,
    int64_t field_offset,
    const ColumnWithTypeAndName & col,
    size_t num_rows,
    const std::vector<int64_t> & offsets,
    const MaskVector & masks = nullptr)
{
    FixedLengthDataWriter writer(col.type);

    if (writer.getWhichDataType().isDecimal32())
    {
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            auto field = (*col.column)[row_idx];
            writer.write(field, buffer_address + offsets[i] + field_offset);
        }
    }
    else
    {
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            writer.unsafeWrite(col.column->getDataAt(row_idx), buffer_address + offsets[i] + field_offset);
        }
    }
}

static void writeFixedLengthNullableValue(
    char * buffer_address,
    int64_t field_offset,
    const ColumnWithTypeAndName & col,
    int32_t col_index,
    size_t num_rows,
    const std::vector<int64_t> & offsets,
    const MaskVector & masks = nullptr)
{
    const auto & nullable_column = checkAndGetColumn<ColumnNullable>(*col.column);
    const auto & null_map = nullable_column.getNullMapData();
    const auto & nested_column = nullable_column.getNestedColumn();
    FixedLengthDataWriter writer(col.type);

    if (writer.getWhichDataType().isDecimal32())
    {
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            if (null_map[row_idx])
                bitSet(buffer_address + offsets[i], col_index);
            else
            {
                auto field = (*col.column)[row_idx];
                writer.write(field, buffer_address + offsets[i] + field_offset);
            }
        }
    }
    else
    {
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            if (null_map[row_idx])
                bitSet(buffer_address + offsets[i], col_index);
            else
                writer.unsafeWrite(nested_column.getDataAt(row_idx), buffer_address + offsets[i] + field_offset);
        }
    }
}

static void writeVariableLengthNonNullableValue(
    char * buffer_address,
    int64_t field_offset,
    const ColumnWithTypeAndName & col,
    size_t num_rows,
    const std::vector<int64_t> & offsets,
    std::vector<int64_t> & buffer_cursor,
    const MaskVector & masks = nullptr)
{
    const auto type_without_nullable{removeNullable(col.type)};
    const bool use_raw_data = BackingDataLengthCalculator::isDataTypeSupportRawData(type_without_nullable);
    const bool big_endian = BackingDataLengthCalculator::isBigEndianInSparkRow(type_without_nullable);
    VariableLengthDataWriter writer(col.type, buffer_address, offsets, buffer_cursor);
    if (use_raw_data)
    {
        if (!big_endian)
        {
            for (size_t i = 0; i < num_rows; i++)
            {
                size_t row_idx = masks == nullptr ? i : masks->at(i);
                StringRef str = col.column->getDataAt(row_idx);
                int64_t offset_and_size = writer.writeUnalignedBytes(i, str.data, str.size, 0);
                memcpy(buffer_address + offsets[i] + field_offset, &offset_and_size, 8);
            }
        }
        else
        {
            Field field;
            for (size_t i = 0; i < num_rows; i++)
            {
                size_t row_idx = masks == nullptr ? i : masks->at(i);
                StringRef str_view = col.column->getDataAt(row_idx);
                String buf(str_view.data, str_view.size);
                BackingDataLengthCalculator::swapDecimalEndianBytes(buf);
                int64_t offset_and_size = writer.writeUnalignedBytes(i, buf.data(), buf.size(), 0);
                memcpy(buffer_address + offsets[i] + field_offset, &offset_and_size, 8);
            }
        }
    }
    else
    {
        Field field;
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            field = (*col.column)[row_idx];
            int64_t offset_and_size = writer.write(i, field, 0);
            memcpy(buffer_address + offsets[i] + field_offset, &offset_and_size, 8);
        }
    }
}

static void writeVariableLengthNullableValue(
    char * buffer_address,
    int64_t field_offset,
    const ColumnWithTypeAndName & col,
    int32_t col_index,
    size_t num_rows,
    const std::vector<int64_t> & offsets,
    std::vector<int64_t> & buffer_cursor,
    const MaskVector & masks = nullptr)
{
    const auto & nullable_column = checkAndGetColumn<ColumnNullable>(*col.column);
    const auto & null_map = nullable_column.getNullMapData();
    const auto & nested_column = nullable_column.getNestedColumn();
    const auto type_without_nullable{removeNullable(col.type)};
    const bool use_raw_data = BackingDataLengthCalculator::isDataTypeSupportRawData(type_without_nullable);
    const bool big_endian = BackingDataLengthCalculator::isBigEndianInSparkRow(type_without_nullable);
    VariableLengthDataWriter writer(col.type, buffer_address, offsets, buffer_cursor);
    if (use_raw_data)
    {
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            if (null_map[row_idx])
                bitSet(buffer_address + offsets[i], col_index);
            else if (!big_endian)
            {
                StringRef str = nested_column.getDataAt(row_idx);
                int64_t offset_and_size = writer.writeUnalignedBytes(i, str.data, str.size, 0);
                memcpy(buffer_address + offsets[i] + field_offset, &offset_and_size, 8);
            }
            else
            {
                Field field;
                nested_column.get(row_idx, field);
                StringRef str_view = nested_column.getDataAt(row_idx);
                String buf(str_view.data, str_view.size);
                BackingDataLengthCalculator::swapDecimalEndianBytes(buf);
                int64_t offset_and_size = writer.writeUnalignedBytes(i, buf.data(), buf.size(), 0);
                memcpy(buffer_address + offsets[i] + field_offset, &offset_and_size, 8);
            }
        }
    }
    else
    {
        Field field;
        for (size_t i = 0; i < num_rows; i++)
        {
            size_t row_idx = masks == nullptr ? i : masks->at(i);
            if (null_map[row_idx])
                bitSet(buffer_address + offsets[i], col_index);
            else
            {
                field = nested_column[row_idx];
                int64_t offset_and_size = writer.write(i, field, 0);
                memcpy(buffer_address + offsets[i] + field_offset, &offset_and_size, 8);
            }
        }
    }
}


static void writeValue(
    char * buffer_address,
    int64_t field_offset,
    const ColumnWithTypeAndName & col,
    int32_t col_index,
    int64_t num_rows,
    const std::vector<int64_t> & offsets,
    std::vector<int64_t> & buffer_cursor,
    const MaskVector & masks = nullptr)
{
    const auto type_without_nullable{removeNullable(col.type)};
    const auto is_nullable = isColumnNullable(*col.column);
    if (BackingDataLengthCalculator::isFixedLengthDataType(type_without_nullable))
    {
        if (is_nullable)
            writeFixedLengthNullableValue(buffer_address, field_offset, col, col_index, num_rows, offsets, masks);
        else
            writeFixedLengthNonNullableValue(buffer_address, field_offset, col, num_rows, offsets, masks);
    }
    else if (BackingDataLengthCalculator::isVariableLengthDataType(type_without_nullable))
    {
        if (is_nullable)
            writeVariableLengthNullableValue(buffer_address, field_offset, col, col_index, num_rows, offsets, buffer_cursor, masks);
        else
            writeVariableLengthNonNullableValue(buffer_address, field_offset, col, num_rows, offsets, buffer_cursor, masks);
    }
    else
        throw Exception(ErrorCodes::UNKNOWN_TYPE, "Doesn't support type {} for writeValue", col.type->getName());
}

SparkRowInfo::SparkRowInfo(
    const DB::ColumnsWithTypeAndName & cols, const DB::DataTypes & dataTypes, size_t col_size, size_t row_size, const MaskVector & masks)
    : types(dataTypes)
    , num_rows(masks == nullptr ? row_size : masks->size())
    , num_cols(col_size)
    , null_bitset_width_in_bytes(calculateBitSetWidthInBytes(num_cols))
    , total_bytes(0)
    , offsets(num_rows, 0)
    , lengths(num_rows, 0)
    , buffer_cursor(num_rows, 0)
    , buffer_address(nullptr)
{
    int64_t fixed_size_per_row = calculatedFixeSizePerRow(num_cols);

    /// Initialize lengths and buffer_cursor
    for (size_t i = 0; i < num_rows; i++)
    {
        lengths[i] = fixed_size_per_row;
        buffer_cursor[i] = fixed_size_per_row;
    }

    for (int64_t col_idx = 0; col_idx < num_cols; ++col_idx)
    {
        const auto & col = cols[col_idx];
        /// No need to calculate backing data length for fixed length types
        const auto type_without_nullable = removeLowCardinalityAndNullable(col.type);
        if (BackingDataLengthCalculator::isVariableLengthDataType(type_without_nullable))
        {
            if (BackingDataLengthCalculator::isDataTypeSupportRawData(type_without_nullable))
            {
                auto column = col.column->convertToFullIfNeeded();
                if (const auto * nullable_column = checkAndGetColumn<ColumnNullable>(&*column))
                {
                    const auto & nested_column = nullable_column->getNestedColumn();
                    const auto & null_map = nullable_column->getNullMapData();
                    for (size_t i = 0; i < num_rows; ++i)
                    {
                        size_t row_idx = masks == nullptr ? i : masks->at(i);
                        if (!null_map[row_idx])
                            lengths[i] += roundNumberOfBytesToNearestWord(nested_column.getDataAt(row_idx).size);
                    }
                }
                else
                {
                    for (size_t i = 0; i < num_rows; ++i)
                    {
                        size_t row_idx = masks == nullptr ? i : masks->at(i);
                        lengths[i] += roundNumberOfBytesToNearestWord(column->getDataAt(row_idx).size);
                    }
                }
            }
            else
            {
                BackingDataLengthCalculator calculator(type_without_nullable);
                for (size_t i = 0; i < num_rows; ++i)
                {
                    size_t row_idx = masks == nullptr ? i : masks->at(i);
                    const auto field = (*col.column)[row_idx];
                    lengths[i] += calculator.calculate(field);
                }
            }
        }
    }

    /// Initialize offsets
    for (size_t i = 1; i < num_rows; ++i)
        offsets[i] = offsets[i - 1] + lengths[i - 1];

    /// Initialize total_bytes
    for (size_t i = 0; i < num_rows; ++i)
        total_bytes += lengths[i];
}

SparkRowInfo::SparkRowInfo(const Block & block, const MaskVector & masks)
    : SparkRowInfo(block.getColumnsWithTypeAndName(), block.getDataTypes(), block.columns(), block.rows(), masks)
{
}

const DB::DataTypes & SparkRowInfo::getDataTypes() const
{
    return types;
}

int64_t SparkRowInfo::getFieldOffset(int32_t col_idx) const
{
    return null_bitset_width_in_bytes + 8L * col_idx;
}

int64_t SparkRowInfo::getNullBitsetWidthInBytes() const
{
    return null_bitset_width_in_bytes;
}

void SparkRowInfo::setNullBitsetWidthInBytes(int64_t null_bitset_width_in_bytes_)
{
    null_bitset_width_in_bytes = null_bitset_width_in_bytes_;
}

int64_t SparkRowInfo::getNumCols() const
{
    return num_cols;
}

void SparkRowInfo::setNumCols(int64_t num_cols_)
{
    num_cols = num_cols_;
}

int64_t SparkRowInfo::getNumRows() const
{
    return num_rows;
}

void SparkRowInfo::setNumRows(int64_t num_rows_)
{
    num_rows = num_rows_;
}

char * SparkRowInfo::getBufferAddress() const
{
    return buffer_address;
}

void SparkRowInfo::setBufferAddress(char * buffer_address_)
{
    buffer_address = buffer_address_;
}

const std::vector<int64_t> & SparkRowInfo::getOffsets() const
{
    return offsets;
}

const std::vector<int64_t> & SparkRowInfo::getLengths() const
{
    return lengths;
}

std::vector<int64_t> & SparkRowInfo::getBufferCursor()
{
    return buffer_cursor;
}

int64_t SparkRowInfo::getTotalBytes() const
{
    return total_bytes;
}

std::unique_ptr<SparkRowInfo> CHColumnToSparkRow::convertCHColumnToSparkRow(const Block & block, const MaskVector & masks)
{
    if (!block.columns())
        throw DB::Exception(DB::ErrorCodes::LOGICAL_ERROR, "A block with empty columns");
    std::unique_ptr<SparkRowInfo> spark_row_info = std::make_unique<SparkRowInfo>(block, masks);
    spark_row_info->setBufferAddress(static_cast<char *>(alloc(spark_row_info->getTotalBytes(), 64)));
    // spark_row_info->setBufferAddress(alignedAlloc(spark_row_info->getTotalBytes(), 64));
    memset(spark_row_info->getBufferAddress(), 0, spark_row_info->getTotalBytes());
    for (auto col_idx = 0; col_idx < spark_row_info->getNumCols(); col_idx++)
    {
        const auto & col = block.getByPosition(col_idx);
        int64_t field_offset = spark_row_info->getFieldOffset(col_idx);

        ColumnWithTypeAndName col_full{col.column->convertToFullIfNeeded(), removeLowCardinality(col.type), col.name};
        writeValue(
            spark_row_info->getBufferAddress(),
            field_offset,
            col_full,
            col_idx,
            spark_row_info->getNumRows(),
            spark_row_info->getOffsets(),
            spark_row_info->getBufferCursor(),
            masks);
    }
    return spark_row_info;
}

void CHColumnToSparkRow::freeMem(char * address, size_t size)
{
    free(address, size);
    // rollback(size);
}

BackingDataLengthCalculator::BackingDataLengthCalculator(const DataTypePtr & type_)
    : type_without_nullable(removeNullable(type_)), which(type_without_nullable)
{
    if (!isFixedLengthDataType(type_without_nullable) && !isVariableLengthDataType(type_without_nullable))
        throw Exception(
            ErrorCodes::UNKNOWN_TYPE, "Doesn't support type {} for BackingDataLengthCalculator", type_without_nullable->getName());
}

int64_t BackingDataLengthCalculator::calculate(const Field & field) const
{
    if (field.isNull())
        return 0;

    if (which.isNativeInt() || which.isNativeUInt() || which.isFloat() || which.isDateOrDate32() || which.isDateTime64()
        || which.isDecimal32() || which.isDecimal64())
        return 0;

    if (which.isStringOrFixedString())
    {
        const auto & str = field.safeGet<String>();
        return roundNumberOfBytesToNearestWord(str.size());
    }

    if (which.isDecimal128())
        return 16;

    if (which.isArray())
    {
        /// 内存布局：numElements(8B) | null_bitmap(与numElements成正比) | values(每个值长度与类型有关) | backing buffer
        const auto & array = field.safeGet<Array>(); /// Array can not be wrapped with Nullable
        const auto num_elems = array.size();
        int64_t res = 8 + calculateBitSetWidthInBytes(num_elems);

        const auto * array_type = typeid_cast<const DataTypeArray *>(type_without_nullable.get());
        const auto & nested_type = array_type->getNestedType();
        res += roundNumberOfBytesToNearestWord(getArrayElementSize(nested_type) * num_elems);

        BackingDataLengthCalculator calculator(nested_type);
        for (size_t i = 0; i < array.size(); ++i)
            res += calculator.calculate(array[i]);
        return res;
    }

    if (which.isMap())
    {
        /// 内存布局：Length of UnsafeArrayData of key(8B) |  UnsafeArrayData of key | UnsafeArrayData of value
        int64_t res = 8;

        /// Construct Array of keys and values from Map
        const auto & map = field.safeGet<Map>(); /// Map can not be wrapped with Nullable
        const auto num_keys = map.size();
        auto array_key = Array();
        auto array_val = Array();
        array_key.reserve(num_keys);
        array_val.reserve(num_keys);
        for (size_t i = 0; i < num_keys; ++i)
        {
            const auto & pair = map[i].safeGet<DB::Tuple>();
            array_key.push_back(pair[0]);
            array_val.push_back(pair[1]);
        }

        const auto * map_type = typeid_cast<const DB::DataTypeMap *>(type_without_nullable.get());

        const auto & key_type = map_type->getKeyType();
        const auto key_array_type = std::make_shared<DataTypeArray>(key_type);
        BackingDataLengthCalculator calculator_key(key_array_type);
        res += calculator_key.calculate(array_key);

        const auto & val_type = map_type->getValueType();
        const auto type_array_val = std::make_shared<DataTypeArray>(val_type);
        BackingDataLengthCalculator calculator_val(type_array_val);
        res += calculator_val.calculate(array_val);
        return res;
    }

    if (which.isTuple())
    {
        /// 内存布局：null_bitmap(字节数与字段数成正比) | field1 value(8B) | field2 value(8B) | ... | fieldn value(8B) | backing buffer
        const auto & tuple = field.safeGet<Tuple>(); /// Tuple can not be wrapped with Nullable
        const auto * type_tuple = typeid_cast<const DataTypeTuple *>(type_without_nullable.get());
        const auto & type_fields = type_tuple->getElements();
        const auto num_fields = type_fields.size();
        int64_t res = calculateBitSetWidthInBytes(num_fields) + 8 * num_fields;
        for (size_t i = 0; i < num_fields; ++i)
        {
            BackingDataLengthCalculator calculator(type_fields[i]);
            res += calculator.calculate(tuple[i]);
        }
        return res;
    }

    throw Exception(
        ErrorCodes::UNKNOWN_TYPE, "Doesn't support type {} for BackingBufferLengthCalculator", type_without_nullable->getName());
}

int64_t BackingDataLengthCalculator::getArrayElementSize(const DataTypePtr & nested_type)
{
    const WhichDataType nested_which(removeNullable(nested_type));
    if (nested_which.isUInt8() || nested_which.isInt8())
        return 1;
    else if (nested_which.isUInt16() || nested_which.isInt16() || nested_which.isDate())
        return 2;
    else if (nested_which.isUInt32() || nested_which.isInt32() || nested_which.isFloat32() || nested_which.isDate32())
        return 4;
    else if (
        nested_which.isUInt64() || nested_which.isInt64() || nested_which.isFloat64() || nested_which.isDateTime64()
        || nested_which.isDecimal32() || nested_which.isDecimal64())
        return 8;
    else
        return 8;
}

bool BackingDataLengthCalculator::isFixedLengthDataType(const DataTypePtr & type_without_nullable)
{
    const WhichDataType which(type_without_nullable);
    return which.isUInt8() || which.isInt8() || which.isUInt16() || which.isInt16() || which.isDate() || which.isUInt32() || which.isInt32()
        || which.isFloat32() || which.isDate32() || which.isDecimal32() || which.isUInt64() || which.isInt64() || which.isFloat64()
        || which.isDateTime64() || which.isDecimal64() || which.isNothing();
}

bool BackingDataLengthCalculator::isVariableLengthDataType(const DataTypePtr & type_without_nullable)
{
    const WhichDataType which(type_without_nullable);
    return which.isStringOrFixedString() || which.isDecimal128() || which.isArray() || which.isMap() || which.isTuple();
}

bool BackingDataLengthCalculator::isDataTypeSupportRawData(const DB::DataTypePtr & type_without_nullable)
{
    const WhichDataType which(type_without_nullable);
    return isFixedLengthDataType(type_without_nullable) || which.isStringOrFixedString() || which.isDecimal128();
}

bool BackingDataLengthCalculator::isBigEndianInSparkRow(const DB::DataTypePtr & type_without_nullable)
{
    const WhichDataType which(type_without_nullable);
    return which.isDecimal128();
}

void BackingDataLengthCalculator::swapDecimalEndianBytes(String & buf)
{
    assert(buf.size() == 16);

    using base_type = Decimal128::NativeType::base_type;
    auto * decimal128 = reinterpret_cast<Decimal128 *>(buf.data());
    for (size_t i = 0; i != std::size(decimal128->value.items); ++i)
        decimal128->value.items[i] = __builtin_bswap64(decimal128->value.items[i]);

    base_type * high = reinterpret_cast<base_type *>(buf.data() + 8);
    base_type * low = reinterpret_cast<base_type *>(buf.data());
    std::swap(*high, *low);
}

VariableLengthDataWriter::VariableLengthDataWriter(
    const DataTypePtr & type_, char * buffer_address_, const std::vector<int64_t> & offsets_, std::vector<int64_t> & buffer_cursor_)
    : type_without_nullable(removeNullable(type_))
    , which(type_without_nullable)
    , buffer_address(buffer_address_)
    , offsets(offsets_)
    , buffer_cursor(buffer_cursor_)
{
    assert(buffer_address);
    assert(offsets.size() == buffer_cursor.size());

    if (!BackingDataLengthCalculator::isVariableLengthDataType(type_without_nullable))
        throw Exception(ErrorCodes::UNKNOWN_TYPE, "VariableLengthDataWriter doesn't support type {}", type_without_nullable->getName());
}

int64_t VariableLengthDataWriter::writeArray(size_t row_idx, const DB::Array & array, int64_t parent_offset)
{
    /// 内存布局：numElements(8B) | null_bitmap(与numElements成正比) | values(每个值长度与类型有关) | backing data
    const auto & offset = offsets[row_idx];
    auto & cursor = buffer_cursor[row_idx];
    const auto num_elems = array.size();
    const auto * array_type = typeid_cast<const DataTypeArray *>(type_without_nullable.get());
    const auto & nested_type = array_type->getNestedType();

    /// Write numElements(8B)
    const auto start = cursor;
    memcpy(buffer_address + offset + cursor, &num_elems, 8);
    cursor += 8;
    if (num_elems == 0)
        return BackingDataLengthCalculator::getOffsetAndSize(start - parent_offset, 8);

    /// Skip null_bitmap(already reset to zero)
    const auto len_null_bitmap = calculateBitSetWidthInBytes(num_elems);
    cursor += len_null_bitmap;

    /// Skip values(already reset to zero)
    const auto elem_size = BackingDataLengthCalculator::getArrayElementSize(nested_type);
    const auto len_values = roundNumberOfBytesToNearestWord(elem_size * num_elems);
    cursor += len_values;

    if (BackingDataLengthCalculator::isFixedLengthDataType(removeNullable(nested_type)))
    {
        /// If nested type is fixed-length data type, update null_bitmap and values in place
        FixedLengthDataWriter writer(nested_type);
        for (size_t i = 0; i < num_elems; ++i)
        {
            const auto & elem = array[i];
            if (elem.isNull())
                bitSet(buffer_address + offset + start + 8, i);
            else
                writer.write(elem, buffer_address + offset + start + 8 + len_null_bitmap + i * elem_size);
        }
    }
    else
    {
        /// If nested type is not fixed-length data type, update null_bitmap in place
        /// And append values in backing data recursively
        VariableLengthDataWriter writer(nested_type, buffer_address, offsets, buffer_cursor);
        for (size_t i = 0; i < num_elems; ++i)
        {
            const auto & elem = array[i];
            if (elem.isNull())
                bitSet(buffer_address + offset + start + 8, i);
            else
            {
                const auto offset_and_size = writer.write(row_idx, elem, start);
                memcpy(buffer_address + offset + start + 8 + len_null_bitmap + i * elem_size, &offset_and_size, 8);
            }
        }
    }
    return BackingDataLengthCalculator::getOffsetAndSize(start - parent_offset, cursor - start);
}

int64_t VariableLengthDataWriter::writeMap(size_t row_idx, const DB::Map & map, int64_t parent_offset)
{
    /// 内存布局：Length of UnsafeArrayData of key(8B) |  UnsafeArrayData of key | UnsafeArrayData of value
    const auto & offset = offsets[row_idx];
    auto & cursor = buffer_cursor[row_idx];

    /// Skip length of UnsafeArrayData of key(8B)
    const auto start = cursor;
    cursor += 8;

    /// Even if Map is empty, still write as [unsafe key array numBytes] [unsafe key array] [unsafe value array]
    const auto num_pairs = map.size();

    /// Construct array of keys and array of values from map
    auto key_array = Array();
    auto val_array = Array();
    key_array.reserve(num_pairs);
    val_array.reserve(num_pairs);
    for (size_t i = 0; i < num_pairs; ++i)
    {
        const auto & pair = map[i].safeGet<DB::Tuple>();
        key_array.push_back(pair[0]);
        val_array.push_back(pair[1]);
    }

    const auto * map_type = typeid_cast<const DB::DataTypeMap *>(type_without_nullable.get());

    /// Append UnsafeArrayData of key
    const auto & key_type = map_type->getKeyType();
    const auto key_array_type = std::make_shared<DataTypeArray>(key_type);
    VariableLengthDataWriter key_writer(key_array_type, buffer_address, offsets, buffer_cursor);
    const auto key_array_size = BackingDataLengthCalculator::extractSize(key_writer.write(row_idx, key_array, start + 8));

    /// Fill length of UnsafeArrayData of key
    memcpy(buffer_address + offset + start, &key_array_size, 8);

    /// Append UnsafeArrayData of value
    const auto & val_type = map_type->getValueType();
    const auto val_array_type = std::make_shared<DataTypeArray>(val_type);
    VariableLengthDataWriter val_writer(val_array_type, buffer_address, offsets, buffer_cursor);
    val_writer.write(row_idx, val_array, start + 8 + key_array_size);
    return BackingDataLengthCalculator::getOffsetAndSize(start - parent_offset, cursor - start);
}

int64_t VariableLengthDataWriter::writeStruct(size_t row_idx, const DB::Tuple & tuple, int64_t parent_offset)
{
    /// 内存布局：null_bitmap(字节数与字段数成正比) | values(num_fields * 8B) | backing data
    const auto & offset = offsets[row_idx];
    auto & cursor = buffer_cursor[row_idx];
    const auto start = cursor;

    /// Skip null_bitmap
    const auto * tuple_type = typeid_cast<const DataTypeTuple *>(type_without_nullable.get());
    const auto & field_types = tuple_type->getElements();
    const auto num_fields = field_types.size();
    if (num_fields == 0)
        return BackingDataLengthCalculator::getOffsetAndSize(start - parent_offset, 0);
    const auto len_null_bitmap = calculateBitSetWidthInBytes(num_fields);
    cursor += len_null_bitmap;

    /// Skip values
    cursor += num_fields * 8;

    /// If field type is fixed-length, fill field value in values region
    /// else append it to backing data region, and update offset_and_size in values region
    for (size_t i = 0; i < num_fields; ++i)
    {
        const auto & field_value = tuple[i];
        const auto & field_type = field_types[i];
        if (field_value.isNull())
        {
            bitSet(buffer_address + offset + start, i);
            continue;
        }

        if (BackingDataLengthCalculator::isFixedLengthDataType(removeNullable(field_type)))
        {
            FixedLengthDataWriter writer(field_type);
            writer.write(field_value, buffer_address + offset + start + len_null_bitmap + i * 8);
        }
        else
        {
            VariableLengthDataWriter writer(field_type, buffer_address, offsets, buffer_cursor);
            const auto offset_and_size = writer.write(row_idx, field_value, start);
            memcpy(buffer_address + offset + start + len_null_bitmap + 8 * i, &offset_and_size, 8);
        }
    }
    return BackingDataLengthCalculator::getOffsetAndSize(start - parent_offset, cursor - start);
}

int64_t VariableLengthDataWriter::write(size_t row_idx, const DB::Field & field, int64_t parent_offset)
{
    assert(row_idx < offsets.size());

    if (field.isNull())
        return 0;

    if (which.isStringOrFixedString())
    {
        const auto & str = field.safeGet<String>();
        return writeUnalignedBytes(row_idx, str.data(), str.size(), parent_offset);
    }

    if (which.isDecimal128())
    {
        const auto & decimal_field = field.safeGet<DecimalField<Decimal128>>();
        auto decimal128 = decimal_field.getValue();
        String buf(reinterpret_cast<const char *>(&decimal128), sizeof(decimal128));
        BackingDataLengthCalculator::swapDecimalEndianBytes(buf);
        return writeUnalignedBytes(row_idx, buf.c_str(), sizeof(Decimal128), parent_offset);
    }

    if (which.isArray())
    {
        const auto & array = field.safeGet<Array>();
        return writeArray(row_idx, array, parent_offset);
    }

    if (which.isMap())
    {
        const auto & map = field.safeGet<Map>();
        return writeMap(row_idx, map, parent_offset);
    }

    if (which.isTuple())
    {
        const auto & tuple = field.safeGet<Tuple>();
        return writeStruct(row_idx, tuple, parent_offset);
    }

    throw Exception(ErrorCodes::UNKNOWN_TYPE, "Doesn't support type {} for BackingDataWriter", type_without_nullable->getName());
}

int64_t BackingDataLengthCalculator::getOffsetAndSize(int64_t cursor, int64_t size)
{
    return (cursor << 32) | size;
}

int64_t BackingDataLengthCalculator::extractOffset(int64_t offset_and_size)
{
    return offset_and_size >> 32;
}

int64_t BackingDataLengthCalculator::extractSize(int64_t offset_and_size)
{
    return offset_and_size & 0xffffffff;
}

int64_t VariableLengthDataWriter::writeUnalignedBytes(size_t row_idx, const char * src, size_t size, int64_t parent_offset)
{
    memcpy(buffer_address + offsets[row_idx] + buffer_cursor[row_idx], src, size);
    auto res = BackingDataLengthCalculator::getOffsetAndSize(buffer_cursor[row_idx] - parent_offset, size);
    buffer_cursor[row_idx] += roundNumberOfBytesToNearestWord(size);
    return res;
}


FixedLengthDataWriter::FixedLengthDataWriter(const DB::DataTypePtr & type_)
    : type_without_nullable(removeNullable(type_)), which(type_without_nullable)
{
    if (!BackingDataLengthCalculator::isFixedLengthDataType(type_without_nullable))
        throw Exception(ErrorCodes::UNKNOWN_TYPE, "FixedLengthWriter doesn't support type {}", type_without_nullable->getName());
}

void FixedLengthDataWriter::write(const DB::Field & field, char * buffer)
{
    /// Skip null value
    if (field.isNull())
        return;

    if (which.isUInt8())
    {
        const auto value = static_cast<UInt8>(field.safeGet<UInt8>());
        memcpy(buffer, &value, 1);
    }
    else if (which.isUInt16() || which.isDate())
    {
        const auto value = static_cast<UInt16>(field.safeGet<UInt16>());
        memcpy(buffer, &value, 2);
    }
    else if (which.isUInt32() || which.isDate32())
    {
        const auto value = static_cast<UInt32>(field.safeGet<UInt32>());
        memcpy(buffer, &value, 4);
    }
    else if (which.isUInt64())
    {
        const auto & value = field.safeGet<UInt64>();
        memcpy(buffer, &value, 8);
    }
    else if (which.isInt8())
    {
        const auto value = static_cast<Int8>(field.safeGet<Int8>());
        memcpy(buffer, &value, 1);
    }
    else if (which.isInt16())
    {
        const auto value = static_cast<Int16>(field.safeGet<Int16>());
        memcpy(buffer, &value, 2);
    }
    else if (which.isInt32())
    {
        const auto value = static_cast<Int32>(field.safeGet<Int32>());
        memcpy(buffer, &value, 4);
    }
    else if (which.isInt64())
    {
        const auto & value = field.safeGet<Int64>();
        memcpy(buffer, &value, 8);
    }
    else if (which.isFloat32())
    {
        const auto value = static_cast<Float32>(field.safeGet<Float32>());
        memcpy(buffer, &value, 4);
    }
    else if (which.isFloat64())
    {
        const auto & value = field.safeGet<Float64>();
        memcpy(buffer, &value, 8);
    }
    else if (which.isDecimal32())
    {
        const auto & value = field.safeGet<Decimal32>();
        const Int64 decimal = static_cast<Int64>(value.getValue());
        memcpy(buffer, &decimal, 8);
    }
    else if (which.isDecimal64() || which.isDateTime64())
    {
        const auto & value = field.safeGet<Decimal64>();
        const auto decimal = value.getValue();
        memcpy(buffer, &decimal, 8);
    }
    else
        throw Exception(ErrorCodes::UNKNOWN_TYPE, "FixedLengthDataWriter doesn't support type {}", type_without_nullable->getName());
}

void FixedLengthDataWriter::unsafeWrite(const StringRef & str, char * buffer)
{
    memcpy(buffer, str.data, str.size);
}

void FixedLengthDataWriter::unsafeWrite(const char * __restrict src, char * __restrict buffer)
{
    memcpy(buffer, src, type_without_nullable->getSizeOfValueInMemory());
}

namespace SparkRowInfoJNI
{
static jclass spark_row_info_class;
static jmethodID spark_row_info_constructor;
void init(JNIEnv * env)
{
    spark_row_info_class = CreateGlobalClassReference(env, "Lorg/apache/gluten/row/SparkRowInfo;");
    spark_row_info_constructor = GetMethodID(env, spark_row_info_class, "<init>", "([J[JJJJ)V");
}
void destroy(JNIEnv * env)
{
    env->DeleteGlobalRef(spark_row_info_class);
}
jobject create(JNIEnv * env, const SparkRowInfo & spark_row_info)
{
    auto * offsets_arr = env->NewLongArray(spark_row_info.getNumRows());
    const auto * offsets_src = spark_row_info.getOffsets().data();
    env->SetLongArrayRegion(offsets_arr, 0, spark_row_info.getNumRows(), reinterpret_cast<const jlong *>(offsets_src));
    auto * lengths_arr = env->NewLongArray(spark_row_info.getNumRows());
    const auto * lengths_src = spark_row_info.getLengths().data();
    env->SetLongArrayRegion(lengths_arr, 0, spark_row_info.getNumRows(), reinterpret_cast<const jlong *>(lengths_src));
    int64_t address = reinterpret_cast<int64_t>(spark_row_info.getBufferAddress());
    int64_t column_number = spark_row_info.getNumCols();
    int64_t total_size = spark_row_info.getTotalBytes();

    return env->NewObject(
        spark_row_info_class, spark_row_info_constructor, offsets_arr, lengths_arr, address, column_number, total_size);
}
}

}
