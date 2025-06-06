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
package org.apache.gluten.ras.specific

import org.apache.gluten.ras._
import org.apache.gluten.ras.RasConfig.PlannerType
import org.apache.gluten.ras.RasSuiteBase._
import org.apache.gluten.ras.property.PropertySet
import org.apache.gluten.ras.rule.{EnforcerRuleFactory, RasRule, Shape, Shapes}

import org.scalatest.funsuite.AnyFunSuite

class ExhaustivePlannerDistributedSuite extends DistributedSuite {
  override protected def conf: RasConfig = RasConfig(plannerType = PlannerType.Exhaustive)
}

class DpPlannerDistributedSuite extends DistributedSuite {
  override protected def conf: RasConfig = RasConfig(plannerType = PlannerType.Dp)
}

abstract class DistributedSuite extends AnyFunSuite {
  import DistributedSuite._

  protected def conf: RasConfig

  test("Project - dry run") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DProject(DLeaf())
    val planner = ras.newPlanner(plan, PropertySet(List(AnyDistribution, AnyOrdering)))
    val out = planner.plan()
    assert(out == DProject(DLeaf()))
  }

  test("Project - required distribution") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DProject(DLeaf())
    val planner =
      ras.newPlanner(plan, PropertySet(List(HashDistribution(List("a", "b")), AnyOrdering)))
    val out = planner.plan()
    val alternatives = Set[TestNode](
      DProject(DExchange(List("a", "b"), DLeaf())),
      DExchange(List("a", "b"), DProject(DLeaf()))
    )
    assert(alternatives.contains(out))
  }

  test("Aggregate - none-distribution constraint") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DAggregate(List("a", "b"), DLeaf())
    val planner =
      ras.newPlanner(plan, PropertySet(List(HashDistribution(List("b", "c")), AnyOrdering)))
    val out = planner.plan()
    assert(
      out == DExchange(
        List("b", "c"),
        DAggregate(List("a", "b"), DExchange(List("a", "b"), DLeaf()))))
  }

  test("Project - required ordering") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DProject(DLeaf())
    val planner =
      ras.newPlanner(plan, PropertySet(List(AnyDistribution, SimpleOrdering(List("a", "b")))))
    val out = planner.plan()
    val alternatives = Set[TestNode](
      DProject(DSort(List("a", "b"), DLeaf())),
      DSort(List("a", "b"), DProject(DLeaf()))
    )
    assert(alternatives.contains(out))
  }

  test("Project - required distribution and ordering") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DProject(DLeaf())
    val planner =
      ras.newPlanner(
        plan,
        PropertySet(List(HashDistribution(List("a", "b")), SimpleOrdering(List("b", "c")))))
    val out = planner.plan()

    val alternatives = Set[TestNode](
      DProject(DSort(List("b", "c"), DExchange(List("a", "b"), DLeaf()))),
      DProject(DExchange(List("a", "b"), DSort(List("b", "c"), DLeaf()))),
      DSort(List("b", "c"), DProject(DExchange(List("a", "b"), DLeaf()))),
      DSort(List("b", "c"), DExchange(List("a", "b"), DProject(DLeaf()))),
      DExchange(List("a", "b"), DSort(List("b", "c"), DProject(DLeaf()))),
      DExchange(List("a", "b"), DProject(DSort(List("b", "c"), DLeaf())))
    )

    assert(alternatives.contains(out))
  }

  test("Aggregate - avoid re-exchange") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DAggregate(List("a"), DProject(DAggregate(List("a", "b"), DLeaf())))
    val planner = ras.newPlanner(plan, PropertySet(List(AnyDistribution, AnyOrdering)))
    val out = planner.plan()
    assert(
      out == DAggregate(
        List("a"),
        DProject(DAggregate(List("a", "b"), DExchange(List("a"), DLeaf())))))
  }

  test("Aggregate - avoid re-exchange, required ordering") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.none())
        .withNewConfig(_ => conf)

    val plan = DAggregate(List("a"), DProject(DAggregate(List("a", "b"), DLeaf())))
    val planner =
      ras.newPlanner(plan, PropertySet(List(AnyDistribution, SimpleOrdering(List("b", "c")))))
    val out = planner.plan()
    assert(
      out == DSort(
        List("b", "c"),
        DAggregate(List("a"), DProject(DAggregate(List("a", "b"), DExchange(List("a"), DLeaf()))))))
  }

  ignore("Aggregate - avoid re-exchange, partial") {
    val ras =
      Ras[TestNode](
        PlanModelImpl,
        CostModelImpl,
        MetadataModelImpl,
        DistributedPropertyModel,
        ExplainImpl,
        RasRule.Factory.reuse(List(PartialAggregateRule)))
        .withNewConfig(_ => conf)

    val plan = DAggregate(List("a"), DProject(DAggregate(List("a", "b"), DLeaf())))
    val planner = ras.newPlanner(plan, PropertySet(List(AnyDistribution, AnyOrdering)))
    val out = planner.plan()
    // FIXME: Should push partial agg down through exchange, otherwise we'd have to write
    //  a rule for that
    assert(
      out == DFinalAggregate(
        List("a"),
        DPartialAggregate(
          List("a"),
          DProject(
            DFinalAggregate(
              List("a", "b"),
              DExchange(List("a"), DPartialAggregate(List("a", "b"), DLeaf())))))))
  }
}

object DistributedSuite {

  object PartialAggregateRule extends RasRule[TestNode] {

    override def shift(node: TestNode): Iterable[TestNode] = node match {
      case DAggregate(keys, child) => List(DFinalAggregate(keys, DPartialAggregate(keys, child)))
      case _ => List.empty
    }

    override def shape(): Shape[TestNode] = Shapes.fixedHeight(1)
  }

  trait Distribution extends Property[TestNode] {
    override def definition(): PropertyDef[TestNode, _ <: Property[TestNode]] = DistributionDef
  }

  case class HashDistribution(keys: Seq[String]) extends Distribution

  case object AnyDistribution extends Distribution

  case object NoneDistribution extends Distribution

  private object DistributionDef extends PropertyDef[TestNode, Distribution] {
    override def getProperty(plan: TestNode): Distribution = plan match {
      case g: Group => g.constraintSet.get(this)
      case d: DNode => d.getDistribution()
      case _ =>
        throw new UnsupportedOperationException()
    }

    override def getChildrenConstraints(
        plan: TestNode,
        constraint: Property[TestNode]): Seq[Distribution] = (constraint, plan) match {
      case (NoneDistribution, p: DNode) => p.children().map(_ => NoneDistribution)
      case (d: Distribution, p: DNode) => p.getDistributionConstraints(d)
      case _ => throw new UnsupportedOperationException()
    }

    override def any(): Distribution = AnyDistribution

    override def satisfies(
        property: Property[TestNode],
        constraint: Property[TestNode]): Boolean = {
      (property, constraint) match {
        case (_, NoneDistribution) => false
        case (_, AnyDistribution) => true
        case (HashDistribution(keys), HashDistribution(otherKeys)) if keys.size > otherKeys.size =>
          false
        case (HashDistribution(keys), HashDistribution(otherKeys)) =>
          // (a) satisfies (a, b)
          keys.zipWithIndex.forall {
            case (key, index) =>
              key == otherKeys(index)
          }
        case _ => false
      }
    }

    override def assignToGroup(
        group: GroupLeafBuilder[TestNode],
        constraint: Property[TestNode]): GroupLeafBuilder[TestNode] = {
      (group, constraint) match {
        case (builder: Group.Builder, c: Distribution) =>
          builder.withConstraint(c)
      }
    }
  }

  trait Ordering extends Property[TestNode] {
    override def definition(): PropertyDef[TestNode, _ <: Property[TestNode]] = OrderingDef
  }

  case class SimpleOrdering(keys: Seq[String]) extends Ordering

  case object AnyOrdering extends Ordering

  case object NoneOrdering extends Ordering

  // FIXME: Handle non-ordering as well as non-distribution
  private object OrderingDef extends PropertyDef[TestNode, Ordering] {
    override def getProperty(plan: TestNode): Ordering = plan match {
      case g: Group => g.constraintSet.get(this)
      case d: DNode => d.getOrdering()
      case _ => throw new UnsupportedOperationException()
    }
    override def getChildrenConstraints(
        plan: TestNode,
        constraint: Property[TestNode]): Seq[Ordering] =
      (constraint, plan) match {
        case (NoneOrdering, p: DNode) => p.children().map(_ => NoneOrdering)
        case (o: Ordering, p: DNode) => p.getOrderingConstraints(o)
        case _ => throw new UnsupportedOperationException()
      }

    override def any(): Ordering = AnyOrdering

    override def satisfies(
        property: Property[TestNode],
        constraint: Property[TestNode]): Boolean = {
      (property, constraint) match {
        case (_, NoneOrdering) => false
        case (_, AnyOrdering) => true
        case (SimpleOrdering(keys), SimpleOrdering(otherKeys)) if keys.size > otherKeys.size =>
          false
        case (SimpleOrdering(keys), SimpleOrdering(otherKeys)) =>
          // (a, b) satisfies (a)
          otherKeys.zipWithIndex.forall {
            case (otherKey, index) =>
              otherKey == keys(index)
          }
        case _ => false
      }
    }

    override def assignToGroup(
        group: GroupLeafBuilder[TestNode],
        constraint: Property[TestNode]): GroupLeafBuilder[TestNode] = {
      (group, constraint) match {
        case (builder: Group.Builder, c: Ordering) =>
          builder.withConstraint(c)
      }
    }
  }

  private class EnforceDistribution() extends EnforcerRuleFactory.SubRule[TestNode] {
    override def enforce(node: TestNode, constraint: Property[TestNode]): Iterable[TestNode] = {
      val distribution = constraint.asInstanceOf[Distribution]
      (node, distribution) match {
        case (d: DNode, HashDistribution(keys)) => List(DExchange(keys, d))
        case (d: DNode, AnyDistribution) => List(d)
        case (d: DNode, NoneDistribution) => List.empty
        case _ =>
          throw new UnsupportedOperationException()
      }
    }
  }

  private class EnforceOrdering() extends EnforcerRuleFactory.SubRule[TestNode] {
    override def enforce(node: TestNode, constraint: Property[TestNode]): Iterable[TestNode] = {
      val ordering = constraint.asInstanceOf[Ordering]
      (node, ordering) match {
        case (d: DNode, SimpleOrdering(keys)) => List(DSort(keys, d))
        case (d: DNode, AnyOrdering) => List(d)
        case (d: DNode, NoneOrdering) => List.empty
        case _ => throw new UnsupportedOperationException()
      }
    }
  }

  private object DistributedPropertyModel extends PropertyModel[TestNode] {
    override def propertyDefs: Seq[PropertyDef[TestNode, _ <: Property[TestNode]]] =
      List(DistributionDef, OrderingDef)

    override def newEnforcerRuleFactory(): EnforcerRuleFactory[TestNode] =
      EnforcerRuleFactory.fromSubRules(Seq(new EnforcerRuleFactory.SubRuleFactory[TestNode] {
        override def newSubRule(constraintDef: PropertyDef[TestNode, _ <: Property[TestNode]])
            : EnforcerRuleFactory.SubRule[TestNode] = {
          constraintDef match {
            case DistributionDef => new EnforceDistribution()
            case OrderingDef => new EnforceOrdering()
            case _ => throw new UnsupportedOperationException()
          }
        }

        override def ruleShape: Shape[TestNode] = Shapes.fixedHeight(1)
      }))
  }

  trait DNode extends TestNode {
    def getDistribution(): Distribution
    def getDistributionConstraints(req: Distribution): Seq[Distribution]
    def getOrdering(): Ordering
    def getOrderingConstraints(req: Ordering): Seq[Ordering]
    def card(): Int
  }

  case class DLeaf() extends DNode with LeafLike {
    override def getDistribution(): Distribution = AnyDistribution
    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = List.empty
    override def getOrdering(): Ordering = AnyOrdering
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = List.empty
    override def makeCopy(): LeafLike = this
    override def selfCost(): Long = card()
    override def card(): Int = 1000
  }

  case class DAggregate(keys: Seq[String], override val child: TestNode)
    extends DNode
    with UnaryLike {
    override def getDistribution(): Distribution = {
      val childDistribution = child match {
        case g: Group => g.constraintSet.get(DistributionDef)
        case other => DistributionDef.getProperty(other)
      }
      if (childDistribution == NoneDistribution) {
        return NoneDistribution
      }
      if (childDistribution.satisfies(HashDistribution(keys))) {
        return childDistribution
      }
      HashDistribution(keys)
    }

    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = {
      if (HashDistribution(keys).satisfies(req)) {
        return List(HashDistribution(keys))
      }
      if (req.satisfies(HashDistribution(keys))) {
        return List(req)
      }
      List(NoneDistribution)
    }
    override def getOrdering(): Ordering = AnyOrdering
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = List(AnyOrdering)
    override def withNewChildren(child: TestNode): UnaryLike = copy(child = child)
    override def selfCost(): Long = 100 * child.asInstanceOf[DNode].card()
    override def card(): Int = (0.2 * child.asInstanceOf[DNode].card()).toInt
  }

  case class DPartialAggregate(keys: Seq[String], override val child: TestNode)
    extends DNode
    with UnaryLike {
    override def getDistribution(): Distribution = child match {
      case g: Group => g.constraintSet.get(DistributionDef)
      case other => DistributionDef.getProperty(other)
    }

    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = List(req)
    override def getOrdering(): Ordering = AnyOrdering
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = List(AnyOrdering)
    override def withNewChildren(child: TestNode): UnaryLike = copy(child = child)
    override def selfCost(): Long = 20 * child.asInstanceOf[DNode].card()

    override def card(): Int = (0.2 * child.asInstanceOf[DNode].card()).toInt
  }

  case class DFinalAggregate(keys: Seq[String], override val child: TestNode)
    extends DNode
    with UnaryLike {
    override def getDistribution(): Distribution = {
      val childDistribution = child match {
        case g: Group => g.constraintSet.get(DistributionDef)
        case other => DistributionDef.getProperty(other)
      }
      if (childDistribution == NoneDistribution) {
        return NoneDistribution
      }
      if (childDistribution.satisfies(HashDistribution(keys))) {
        return childDistribution
      }
      HashDistribution(keys)
    }
    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = {
      if (HashDistribution(keys).satisfies(req)) {
        return List(HashDistribution(keys))
      }
      if (req.satisfies(HashDistribution(keys))) {
        return List(req)
      }
      List(NoneDistribution)
    }
    override def getOrdering(): Ordering = AnyOrdering
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = List(AnyOrdering)
    override def withNewChildren(child: TestNode): UnaryLike = copy(child = child)
    override def selfCost(): Long = 50 * child.asInstanceOf[DNode].card()

    override def card(): Int = (0.2 * child.asInstanceOf[DNode].card()).toInt
  }

  case class DProject(override val child: TestNode) extends DNode with UnaryLike {
    override def getDistribution(): Distribution = child match {
      case g: Group => g.constraintSet.get(DistributionDef)
      case other => DistributionDef.getProperty(other)
    }
    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = List(req)
    override def getOrdering(): Ordering = child match {
      case g: Group => g.constraintSet.get(OrderingDef)
      case other => OrderingDef.getProperty(other)
    }
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = List(req)
    override def withNewChildren(child: TestNode): UnaryLike = copy(child)
    override def selfCost(): Long = 10 * child.asInstanceOf[DNode].card()
    override def card(): Int = child.asInstanceOf[DNode].card()
  }

  case class DExchange(keys: Seq[String], override val child: TestNode)
    extends DNode
    with UnaryLike {
    override def getDistribution(): Distribution = {
      val childDistribution = child match {
        case g: Group => g.constraintSet.get(DistributionDef)
        case other => DistributionDef.getProperty(other)
      }
      if (childDistribution == NoneDistribution) {
        return NoneDistribution
      }
      HashDistribution(keys)
    }
    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = List(
      AnyDistribution)
    override def getOrdering(): Ordering = AnyOrdering
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = Seq(AnyOrdering)
    override def withNewChildren(child: TestNode): UnaryLike = copy(child = child)
    override def selfCost(): Long = 50 * child.asInstanceOf[DNode].card()
    override def card(): Int = child.asInstanceOf[DNode].card()
  }

  case class DSort(keys: Seq[String], override val child: TestNode) extends DNode with UnaryLike {
    override def getDistribution(): Distribution = child match {
      case g: Group => g.constraintSet.get(DistributionDef)
      case other => DistributionDef.getProperty(other)
    }
    override def getDistributionConstraints(req: Distribution): Seq[Distribution] = List(req)
    override def getOrdering(): Ordering = {
      val childOrdering = child match {
        case g: Group => g.constraintSet.get(OrderingDef)
        case other => OrderingDef.getProperty(other)
      }
      if (childOrdering.satisfies(SimpleOrdering(keys))) {
        return childOrdering
      }
      SimpleOrdering(keys)
    }
    override def getOrderingConstraints(req: Ordering): Seq[Ordering] = Seq(AnyOrdering)
    override def withNewChildren(child: TestNode): UnaryLike = copy(child = child)
    override def selfCost(): Long = 40 * child.asInstanceOf[DNode].card()
    override def card(): Int = child.asInstanceOf[DNode].card()
  }
}
