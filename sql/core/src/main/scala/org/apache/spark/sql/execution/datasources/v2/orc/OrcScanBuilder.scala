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

package org.apache.spark.sql.execution.datasources.v2.orc

import scala.collection.JavaConverters._

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.connector.expressions.aggregate.Aggregation
import org.apache.spark.sql.connector.read.{Scan, SupportsPushDownAggregates}
import org.apache.spark.sql.execution.datasources.{AggregatePushDownUtils, PartitioningAwareFileIndex}
import org.apache.spark.sql.execution.datasources.orc.OrcFilters
import org.apache.spark.sql.execution.datasources.v2.FileScanBuilder
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.{ArrayType, BinaryType, DataType, MapType, StructType, TimestampType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap

case class OrcScanBuilder(
    sparkSession: SparkSession,
    fileIndex: PartitioningAwareFileIndex,
    schema: StructType,
    dataSchema: StructType,
    options: CaseInsensitiveStringMap)
  extends FileScanBuilder(sparkSession, fileIndex, dataSchema)
  with SupportsPushDownAggregates {

  lazy val hadoopConf = {
    val caseSensitiveMap = options.asCaseSensitiveMap.asScala.toMap
    // Hadoop Configurations are case sensitive.
    sparkSession.sessionState.newHadoopConfWithOptions(caseSensitiveMap)
  }

  private var finalSchema = new StructType()

  private var pushedAggregations = Option.empty[Aggregation]

  override protected val supportsNestedSchemaPruning: Boolean = true

  override def build(): Scan = {
    // the `finalSchema` is either pruned in pushAggregation (if aggregates are
    // pushed down), or pruned in readDataSchema() (in regular column pruning). These
    // two are mutual exclusive.
    if (pushedAggregations.isEmpty) {
      finalSchema = readDataSchema()
    }
    OrcScan(sparkSession, hadoopConf, fileIndex, dataSchema, finalSchema,
      readPartitionSchema(), options, pushedAggregations, pushedDataFilters, partitionFilters,
      dataFilters)
  }

  override def pushDataFilters(dataFilters: Array[Filter]): Array[Filter] = {
    if (sparkSession.sessionState.conf.orcFilterPushDown) {
      val dataTypeMap = OrcFilters.getSearchableTypeMap(
        readDataSchema(), SQLConf.get.caseSensitiveAnalysis)
      OrcFilters.convertibleFilters(dataTypeMap, dataFilters).toArray
    } else {
      Array.empty[Filter]
    }
  }

  override def pushAggregation(aggregation: Aggregation): Boolean = {
    if (!sparkSession.sessionState.conf.orcAggregatePushDown) {
      return false
    }

    def isAllowedTypeForMinMaxAggregate(dataType: DataType): Boolean = {
      dataType match {
        // Not push down complex and Timestamp type.
        // Not push down Binary type as ORC does not write min/max statistics for it.
        case StructType(_) | ArrayType(_, _) | MapType(_, _, _) | TimestampType | BinaryType =>
          false
        case _ => true
      }
    }

    AggregatePushDownUtils.getSchemaForPushedAggregation(
      aggregation,
      schema,
      partitionNameSet,
      dataFilters,
      isAllowedTypeForMinMaxAggregate,
      sparkSession) match {

      case Some(schema) =>
        finalSchema = schema
        this.pushedAggregations = Some(aggregation)
        true
      case _ => false
    }
  }
}
