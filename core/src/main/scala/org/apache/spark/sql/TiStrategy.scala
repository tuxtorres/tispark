/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import java.time.ZonedDateTime

import com.pingcap.tikv.exception.IgnoreUnsupportedTypeException
import com.pingcap.tikv.expression.AggregateFunction.FunctionType
import com.pingcap.tikv.expression._
import com.pingcap.tikv.expression.visitor.{ColumnMatcher, MetaResolver}
import com.pingcap.tikv.meta.TiDAGRequest
import com.pingcap.tikv.meta.TiDAGRequest.PushDownType
import com.pingcap.tikv.predicates.ScanAnalyzer.ScanPlan
import com.pingcap.tikv.predicates.{PredicateUtils, ScanAnalyzer}
import com.pingcap.tikv.statistics.TableStatistics
import com.pingcap.tispark.TiUtils._
import com.pingcap.tispark.statistics.StatisticsManager
import com.pingcap.tispark.{BasicExpression, TiConfigConst, TiDBRelation, TiUtils}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.NamedExpression.newExprId
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression, _}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, AttributeSet, Cast, Divide, Expression, IntegerLiteral, Literal, NamedExpression, SortOrder}
import org.apache.spark.sql.catalyst.planning.{PhysicalAggregation, PhysicalOperation}
import org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

import scala.collection.JavaConverters._
import scala.collection.mutable

// TODO: Too many hacks here since we hijack the planning
// but we don't have full control over planning stage
// We cannot pass context around during planning so
// a re-extract needed for push-down since
// a plan tree might contain Join which causes a single tree
// have multiple plans to push-down
case class TiStrategy(getOrCreateTiContext: SparkSession => TiContext)(sparkSession: SparkSession)
    extends Strategy
    with Logging {
  val sqlConf: SQLConf = sparkSession.sqlContext.conf
  type TiExpression = com.pingcap.tikv.expression.Expression
  type TiColumnRef = com.pingcap.tikv.expression.ColumnRef

  private def blacklist: ExpressionBlacklist = {
    val blacklistString = sqlConf.getConfString(TiConfigConst.UNSUPPORTED_PUSHDOWN_EXPR, "")
    new ExpressionBlacklist(blacklistString)
  }

  def typeBlackList: TypeBlacklist = {
    val blacklistString =
      sqlConf.getConfString(TiConfigConst.UNSUPPORTED_TYPES, "time")
    new TypeBlacklist(blacklistString)
  }

  private def allowAggregationPushdown(): Boolean =
    sqlConf.getConfString(TiConfigConst.ALLOW_AGG_PUSHDOWN, "true").toBoolean

  private def allowIndexDoubleRead(): Boolean =
    sqlConf.getConfString(TiConfigConst.ALLOW_INDEX_READ, "false").toBoolean

  private def useStreamingProcess(): Boolean =
    sqlConf.getConfString(TiConfigConst.COPROCESS_STREAMING, "false").toBoolean

  private def timeZoneOffset(): Int =
    sqlConf
      .getConfString(
        TiConfigConst.KV_TIMEZONE_OFFSET,
        String.valueOf(ZonedDateTime.now.getOffset.getTotalSeconds)
      )
      .toInt

  private def pushDownType(): PushDownType =
    if (useStreamingProcess()) {
      PushDownType.STREAMING
    } else {
      PushDownType.NORMAL
    }

  override def apply(plan: LogicalPlan): Seq[SparkPlan] =
    plan
      .collectFirst {
        case LogicalRelation(relation: TiDBRelation, _, _, _) =>
          doPlan(relation, plan)
      }
      .toSeq
      .flatten

  private def toCoprocessorRDD(
    source: TiDBRelation,
    output: Seq[Attribute],
    dagRequest: TiDAGRequest
  ): SparkPlan = {
    val table = source.table
    dagRequest.setTableInfo(table)
    // Need to resolve column info after add aggregation push downs
    dagRequest.resolve()

    val notAllowPushDown = dagRequest.getFields.asScala
      .map { _.getColumnInfo.getType.getType }
      .exists { typeBlackList.isUnsupportedType }

    if (notAllowPushDown) {
      throw new IgnoreUnsupportedTypeException("Unsupported type found in fields: " + typeBlackList)
    } else {
      if (dagRequest.isDoubleRead) {
        source.dagRequestToRegionTaskExec(dagRequest, output)
      } else {
        val tiRdd = source.logicalPlanToRDD(dagRequest)
        CoprocessorRDD(output, tiRdd)
      }
    }
  }

  private def aggregationToDAGRequest(
    groupByList: Seq[NamedExpression],
    aggregates: Seq[AggregateExpression],
    source: TiDBRelation,
    dagRequest: TiDAGRequest = new TiDAGRequest(pushDownType(), timeZoneOffset())
  ): TiDAGRequest = {
    aggregates.map { _.aggregateFunction }.foreach {
      case _: Average =>
        throw new IllegalArgumentException("Should never be here")

      case f @ Sum(BasicExpression(arg)) =>
        dagRequest
          .addAggregate(AggregateFunction.newCall(FunctionType.Sum, arg), fromSparkType(f.dataType))

      case f @ PromotedSum(BasicExpression(arg)) =>
        dagRequest
          .addAggregate(AggregateFunction.newCall(FunctionType.Sum, arg), fromSparkType(f.dataType))

      case f @ Count(args) if args.length == 1 =>
        val tiArgs = args.flatMap(BasicExpression.convertToTiExpr)
        dagRequest.addAggregate(
          AggregateFunction.newCall(FunctionType.Count, tiArgs.head),
          fromSparkType(f.dataType)
        )

      case f @ Min(BasicExpression(arg)) =>
        dagRequest
          .addAggregate(AggregateFunction.newCall(FunctionType.Min, arg), fromSparkType(f.dataType))

      case f @ Max(BasicExpression(arg)) =>
        dagRequest
          .addAggregate(AggregateFunction.newCall(FunctionType.Max, arg), fromSparkType(f.dataType))

      case f @ First(BasicExpression(arg), _) =>
        dagRequest
          .addAggregate(
            AggregateFunction.newCall(FunctionType.First, arg),
            fromSparkType(f.dataType)
          )

      case _ =>
    }

    groupByList.foreach {
      case BasicExpression(keyExpr) =>
        dagRequest.addGroupByItem(ByItem.create(keyExpr, false))
        // We need to add a `First` function in DAGRequest along with group by
        dagRequest.resolve()
        dagRequest.getFields.asScala
          .filter(ColumnMatcher.`match`(_, keyExpr))
          .foreach(
            (ref: TiColumnRef) =>
              dagRequest.addAggregate(
                AggregateFunction.newCall(FunctionType.First, ref),
                ref.getType
            )
          )

      case _ =>
    }

    dagRequest
  }

  def referencedTiColumns(expression: TiExpression): Seq[TiColumnRef] =
    PredicateUtils.extractColumnRefFromExpression(expression).asScala.toSeq

  def extractTiColumnRefFromExpression(expression: TiExpression): mutable.HashSet[TiColumnRef] = {
    val set: mutable.HashSet[TiColumnRef] = mutable.HashSet.empty[TiColumnRef]
    expression match {
      case r: TiColumnRef => set += r
      case _: Constant    =>
      case e: TiExpression =>
        for (child <- e.getChildren.asScala) {
          extractTiColumnRefFromExpression(child).foreach { set += _ }
        }
    }
    set
  }

  def extractTiColumnRefFromExpressions(expressions: Seq[TiExpression]): Seq[TiColumnRef] = {
    val set: mutable.HashSet[TiColumnRef] = mutable.HashSet.empty[TiColumnRef]
    for (expression <- expressions) {
      extractTiColumnRefFromExpression(expression).foreach { set += _ }
    }
    set.toSeq
  }

  /**
   * build a Seq of used TiColumnRef from AttributeSet and bound them to souce table
   *
   * @param attributeSet AttributeSet containing projects w/ or w/o filters
   * @param source source TiDBRelation
   * @return a Seq of TiColumnRef extracted
   */
  def buildTiColumnRefFromColumnSet(attributeSet: AttributeSet,
                                    source: TiDBRelation): Seq[TiColumnRef] = {
    val tiColumnSet: Seq[TiExpression] = attributeSet.toSeq.collect {
      case BasicExpression(expr) => expr
    }
    val resolver = new MetaResolver(source.table)
    val tiColumns: Seq[TiColumnRef] = extractTiColumnRefFromExpressions(tiColumnSet)
    tiColumns.foreach { resolver.resolve(_) }
    tiColumns
  }

  private def filterToDAGRequest(
    tiColumns: Seq[TiColumnRef],
    filters: Seq[Expression],
    source: TiDBRelation,
    dagRequest: TiDAGRequest = new TiDAGRequest(pushDownType(), timeZoneOffset())
  ): TiDAGRequest = {
    val tiFilters: Seq[TiExpression] = filters.collect { case BasicExpression(expr) => expr }

    val scanBuilder: ScanAnalyzer = new ScanAnalyzer

    val tblStatistics: TableStatistics = StatisticsManager.getTableStatistics(source.table.getId)

    val tableScanPlan: ScanPlan =
      scanBuilder.buildTableScan(tiFilters.asJava, source.table, tblStatistics)
    val scanPlan: ScanPlan = if (allowIndexDoubleRead()) {
      // We need to prepare downgrade information in case of index scan downgrade happens.
      tableScanPlan.getFilters.asScala.foreach { dagRequest.addDowngradeFilter }
      scanBuilder.buildScan(
        // need to bind all columns needed
        tiColumns.map { _.getColumnInfo }.asJava,
        tiFilters.asJava,
        source.table,
        tblStatistics
      )
    } else {
      tableScanPlan
    }

    dagRequest.addRanges(scanPlan.getKeyRanges)
    scanPlan.getFilters.asScala.foreach { dagRequest.addFilter }
    if (scanPlan.isIndexScan) {
      dagRequest.setIndexInfo(scanPlan.getIndex)
      // need to set isDoubleRead to true for dagRequest in case of double read
      dagRequest.setIsDoubleRead(scanPlan.isDoubleRead)
    }
    dagRequest.setTableInfo(source.table)
    dagRequest.setEstimatedCount(scanPlan.getEstimatedRowCount)
    dagRequest
  }

  private def addSortOrder(request: TiDAGRequest, sortOrder: Seq[SortOrder]): Unit =
    sortOrder.foreach { order: SortOrder =>
      request.addOrderByItem(
        ByItem.create(
          BasicExpression.convertToTiExpr(order.child).get,
          order.direction.sql.equalsIgnoreCase("DESC")
        )
      )
    }

  private def pruneTopNFilterProject(
    limit: Int,
    projectList: Seq[NamedExpression],
    filterPredicates: Seq[Expression],
    source: TiDBRelation,
    sortOrder: Seq[SortOrder]
  ): SparkPlan = {
    val request = new TiDAGRequest(pushDownType(), timeZoneOffset())
    request.setLimit(limit)
    addSortOrder(request, sortOrder)
    pruneFilterProject(projectList, filterPredicates, source, request)
  }

  private def collectLimit(limit: Int, child: LogicalPlan): SparkPlan = child match {
    case PhysicalOperation(projectList, filters, LogicalRelation(source: TiDBRelation, _, _, _))
        if filters.forall(TiUtils.isSupportedFilter(_, source, blacklist)) =>
      pruneTopNFilterProject(limit, projectList, filters, source, Nil)
    case _ => planLater(child)
  }

  private def takeOrderedAndProject(
    limit: Int,
    sortOrder: Seq[SortOrder],
    child: LogicalPlan,
    project: Seq[NamedExpression]
  ): SparkPlan = {
    // If sortOrder is empty, limit must be greater than 0
    if (limit < 0 || (sortOrder.isEmpty && limit == 0)) {
      return execution.TakeOrderedAndProjectExec(limit, sortOrder, project, planLater(child))
    }

    child match {
      case PhysicalOperation(projectList, filters, LogicalRelation(source: TiDBRelation, _, _, _))
          if filters.forall(TiUtils.isSupportedFilter(_, source, blacklist)) =>
        execution.TakeOrderedAndProjectExec(
          limit,
          sortOrder,
          project,
          pruneTopNFilterProject(limit, projectList, filters, source, sortOrder)
        )
      case _ => execution.TakeOrderedAndProjectExec(limit, sortOrder, project, planLater(child))
    }
  }

  private def pruneFilterProject(
    projectList: Seq[NamedExpression],
    filterPredicates: Seq[Expression],
    source: TiDBRelation,
    dagRequest: TiDAGRequest = new TiDAGRequest(pushDownType(), timeZoneOffset())
  ): SparkPlan = {

    val projectSet = AttributeSet(projectList.flatMap(_.references))
    val filterSet = AttributeSet(filterPredicates.flatMap(_.references))

    val (pushdownFilters: Seq[Expression], residualFilters: Seq[Expression]) =
      filterPredicates.partition(
        (expression: Expression) => TiUtils.isSupportedFilter(expression, source, blacklist)
      )

    val residualFilter: Option[Expression] =
      residualFilters.reduceLeftOption(catalyst.expressions.And)

    val tiColumns = buildTiColumnRefFromColumnSet(projectSet ++ filterSet, source)

    filterToDAGRequest(tiColumns, pushdownFilters, source, dagRequest)

    if (tiColumns.isEmpty) {
      // if tiColumns is empty, add a random column so that the plan will contain at least one column.
      val column = source.table.getColumn(0)
      dagRequest.addRequiredColumn(ColumnRef.create(column.getName))
    }

    // Right now we still use a projection even if the only evaluation is applying an alias
    // to a column.  Since this is a no-op, it could be avoided. However, using this
    // optimization with the current implementation would change the output schema.
    // TODO: Decouple final output schema from expression evaluation so this copy can be
    // avoided safely.
    if (AttributeSet(projectList.map(_.toAttribute)) == projectSet &&
        filterSet.subsetOf(projectSet)) {
      // When it is possible to just use column pruning to get the right projection and
      // when the columns of this projection are enough to evaluate all filter conditions,
      // just do a scan followed by a filter, with no extra project.
      val projectSeq: Seq[Attribute] = projectList.asInstanceOf[Seq[Attribute]]
      projectSeq.foreach(attr => dagRequest.addRequiredColumn(ColumnRef.create(attr.name)))
      val scan = toCoprocessorRDD(source, projectSeq, dagRequest)
      residualFilter.map(FilterExec(_, scan)).getOrElse(scan)
    } else {
      // for now all column used will be returned for old interface
      // TODO: once switch to new interface we change this pruning logic
      val projectSeq: Seq[Attribute] = (projectSet ++ filterSet).toSeq
      projectSeq.foreach(attr => dagRequest.addRequiredColumn(ColumnRef.create(attr.name)))
      val scan = toCoprocessorRDD(source, projectSeq, dagRequest)
      ProjectExec(projectList, residualFilter.map(FilterExec(_, scan)).getOrElse(scan))
    }
  }

  private def groupAggregateProjection(
    tiColumns: Seq[TiColumnRef],
    groupingExpressions: Seq[NamedExpression],
    aggregateExpressions: Seq[AggregateExpression],
    resultExpressions: Seq[NamedExpression],
    source: TiDBRelation,
    dagReq: TiDAGRequest
  ): Seq[SparkPlan] = {
    val deterministicAggAliases = aggregateExpressions.collect {
      case e if e.deterministic => e.canonicalized -> Alias(e, e.toString())()
    }.toMap

    def aliasPushedPartialResult(e: AggregateExpression): Alias =
      deterministicAggAliases.getOrElse(e.canonicalized, Alias(e, e.toString())())

    val residualAggregateExpressions = aggregateExpressions.map { aggExpr =>
      // As `aggExpr` is being pushing down to TiKV, we need to replace the original Catalyst
      // aggregate expressions with new ones that merges the partial aggregation results returned by
      // TiKV.
      //
      // NOTE: Unlike simple aggregate functions (e.g., `Max`, `Min`, etc.), `Count` must be
      // replaced with a `Sum` to sum up the partial counts returned by TiKV.
      //
      // NOTE: All `Average`s should have already been rewritten into `Sum`s and `Count`s by the
      // `TiAggregation` pattern extractor.

      // An attribute referring to the partial aggregation results returned by TiKV.
      val partialResultRef = aliasPushedPartialResult(aggExpr).toAttribute

      aggExpr.aggregateFunction match {
        case e: Max        => aggExpr.copy(aggregateFunction = e.copy(child = partialResultRef))
        case e: Min        => aggExpr.copy(aggregateFunction = e.copy(child = partialResultRef))
        case e: Sum        => aggExpr.copy(aggregateFunction = e.copy(child = partialResultRef))
        case e: SpecialSum => aggExpr.copy(aggregateFunction = e.copy(child = partialResultRef))
        case e: First      => aggExpr.copy(aggregateFunction = e.copy(child = partialResultRef))
        case _: Count =>
          aggExpr.copy(aggregateFunction = SumNotNullable(partialResultRef))
        case _: Average => throw new IllegalStateException("All AVGs should have been rewritten.")
        case _          => aggExpr
      }
    }

    tiColumns foreach { dagReq.addRequiredColumn }

    aggregationToDAGRequest(groupingExpressions, aggregateExpressions.distinct, source, dagReq)

    val aggregateAttributes =
      aggregateExpressions.map(expr => aliasPushedPartialResult(expr).toAttribute)
    val groupAttributes = groupingExpressions.map(_.toAttribute)

    // output of Coprocessor plan should contain all references within
    // aggregates and group by expressions
    val output = aggregateAttributes ++ groupAttributes

    val groupExpressionMap = groupingExpressions.map(expr => expr.exprId -> expr.toAttribute).toMap

    // resultExpression might refer to some of the group by expressions
    // Those expressions originally refer to table columns but now it refers to
    // results of coprocessor.
    // For example, select a + 1 from t group by a + 1
    // expression a + 1 has been pushed down to coprocessor
    // and in turn a + 1 in projection should be replaced by
    // reference of coprocessor output entirely
    val rewrittenResultExpressions = resultExpressions.map {
      _.transform {
        case e: NamedExpression => groupExpressionMap.getOrElse(e.exprId, e)
      }.asInstanceOf[NamedExpression]
    }

    aggregate.AggUtils.planAggregateWithoutDistinct(
      groupAttributes,
      residualAggregateExpressions,
      rewrittenResultExpressions,
      toCoprocessorRDD(source, output, dagReq)
    )
  }

  private def isValidAggregates(
    groupingExpressions: Seq[NamedExpression],
    aggregateExpressions: Seq[AggregateExpression],
    filters: Seq[Expression],
    source: TiDBRelation
  ): Boolean =
    allowAggregationPushdown &&
      filters.forall(TiUtils.isSupportedFilter(_, source, blacklist)) &&
      groupingExpressions.forall(TiUtils.isSupportedGroupingExpr(_, source, blacklist)) &&
      aggregateExpressions.forall(TiUtils.isSupportedAggregate(_, source, blacklist)) &&
      !aggregateExpressions.exists(_.isDistinct)

  // We do through similar logic with original Spark as in SparkStrategies.scala
  // Difference is we need to test if a sub-plan can be consumed all together by TiKV
  // and then we don't return (don't planLater) and plan the remaining all at once
  private def doPlan(source: TiDBRelation, plan: LogicalPlan): Seq[SparkPlan] =
    // TODO: This test should be done once for all children
    plan match {
      case logical.ReturnAnswer(rootPlan) =>
        rootPlan match {
          case logical.Limit(IntegerLiteral(limit), logical.Sort(order, true, child)) =>
            takeOrderedAndProject(limit, order, child, child.output) :: Nil
          case logical.Limit(
              IntegerLiteral(limit),
              logical.Project(projectList, logical.Sort(order, true, child))
              ) =>
            takeOrderedAndProject(limit, order, child, projectList) :: Nil
          case logical.Limit(IntegerLiteral(limit), child) =>
            execution.CollectLimitExec(limit, collectLimit(limit, child)) :: Nil
          case other => planLater(other) :: Nil
        }
      case logical.Limit(IntegerLiteral(limit), logical.Sort(order, true, child)) =>
        takeOrderedAndProject(limit, order, child, child.output) :: Nil
      case logical.Limit(
          IntegerLiteral(limit),
          logical.Project(projectList, logical.Sort(order, true, child))
          ) =>
        takeOrderedAndProject(limit, order, child, projectList) :: Nil

      // Collapse filters and projections and push plan directly
      case PhysicalOperation(
          projectList,
          filters,
          LogicalRelation(source: TiDBRelation, _, _, _)
          ) =>
        pruneFilterProject(projectList, filters, source) :: Nil

      // Basic logic of original Spark's aggregation plan is:
      // PhysicalAggregation extractor will rewrite original aggregation
      // into aggregateExpressions and resultExpressions.
      // resultExpressions contains only references [[AttributeReference]]
      // to the result of aggregation. resultExpressions might contain projections
      // like Add(sumResult, 1).
      // For a aggregate like agg(expr) + 1, the rewrite process is: rewrite agg(expr) ->
      // 1. pushdown: agg(expr) as agg1, if avg then sum(expr), count(expr)
      // 2. residual expr (for Spark itself): agg(agg1) as finalAgg1 the parameter is a
      // reference to pushed plan's corresponding aggregation
      // 3. resultExpressions: finalAgg1 + 1, the finalAgg1 is the reference to final result
      // of the aggregation
      case TiAggregation(
          groupingExpressions,
          aggregateExpressions,
          resultExpressions,
          TiAggregationProjection(filters, _, `source`, projects)
          ) if isValidAggregates(groupingExpressions, aggregateExpressions, filters, source) =>
        val projectSet = AttributeSet((projects ++ filters).flatMap { _.references })
        val tiColumns = buildTiColumnRefFromColumnSet(projectSet, source)
        val dagReq: TiDAGRequest = filterToDAGRequest(tiColumns, filters, source)
        groupAggregateProjection(
          tiColumns,
          groupingExpressions,
          aggregateExpressions,
          resultExpressions,
          `source`,
          dagReq
        )
      case _ => Nil
    }
}

object TiAggregation {
  type ReturnType = PhysicalAggregation.ReturnType

  def unapply(plan: LogicalPlan): Option[ReturnType] = plan match {
    case PhysicalAggregation(groupingExpressions, aggregateExpressions, resultExpressions, child) =>
      // Rewrites all `Average`s into the form of `Divide(Sum / Count)` so that we can push the
      // converted `Sum`s and `Count`s down to TiKV.
      val (averages, averagesEliminated) = aggregateExpressions.partition {
        case AggregateExpression(_: Average, _, _, _) => true
        case _                                        => false
      }

      // An auxiliary map that maps result attribute IDs of all detected `Average`s to corresponding
      // converted `Sum`s and `Count`s.
      val rewriteMap = averages.map {
        case a @ AggregateExpression(Average(ref), _, _, _) =>
          // We need to do a type promotion on Sum(Long) to avoid LongType overflow in Average rewrite
          // scenarios to stay consistent with original spark's Average behaviour
          val sum = if (ref.dataType.eq(LongType)) PromotedSum(ref) else Sum(ref)
          a.resultAttribute -> Seq(
            a.copy(aggregateFunction = sum, resultId = newExprId),
            a.copy(aggregateFunction = Count(ref), resultId = newExprId)
          )
      }.toMap

      val rewrite: PartialFunction[Expression, Expression] = rewriteMap.map {
        case (ref, Seq(sum, count)) =>
          val castedSum = Cast(sum.resultAttribute, DoubleType)
          val castedCount = Cast(count.resultAttribute, DoubleType)
          val division = Cast(Divide(castedSum, castedCount), ref.dataType)
          (ref: Expression) -> Alias(division, ref.name)(exprId = ref.exprId)
      }

      val rewrittenResultExpressions = resultExpressions
        .map { _ transform rewrite }
        .map { case e: NamedExpression => e }

      val rewrittenAggregateExpressions = {
        val extraSumsAndCounts = rewriteMap.values.reduceOption { _ ++ _ } getOrElse Nil
        (averagesEliminated ++ extraSumsAndCounts).distinct
      }

      Some(groupingExpressions, rewrittenAggregateExpressions, rewrittenResultExpressions, child)

    case _ => Option.empty[ReturnType]
  }
}

object TiAggregationProjection {
  type ReturnType = (Seq[Expression], LogicalPlan, TiDBRelation, Seq[NamedExpression])

  def unapply(plan: LogicalPlan): Option[ReturnType] = plan match {
    // Only push down aggregates projection when all filters can be applied and
    // all projection expressions are column references
    case PhysicalOperation(projects, filters, rel @ LogicalRelation(source: TiDBRelation, _, _, _))
        if projects.forall(_.isInstanceOf[Attribute]) =>
      Some((filters, rel, source, projects))
    case _ => Option.empty[ReturnType]
  }
}
