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

package org.apache.spark.sql.execution.datasources

import java.sql.{Date, Timestamp}

import org.apache.spark.SparkConf
import org.apache.spark.sql.{ExplainSuiteHelper, QueryTest, Row}
import org.apache.spark.sql.execution.datasources.orc.OrcTest
import org.apache.spark.sql.execution.datasources.parquet.ParquetTest
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2ScanRelation
import org.apache.spark.sql.functions.min
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.{BinaryType, BooleanType, ByteType, DateType, Decimal, DecimalType, DoubleType, FloatType, IntegerType, LongType, ShortType, StringType, StructField, StructType, TimestampType}

/**
 * A test suite that tests aggregate push down for Parquet and ORC.
 */
trait FileSourceAggregatePushDownSuite
  extends QueryTest
  with FileBasedDataSourceTest
  with SharedSparkSession
  with ExplainSuiteHelper {

  import testImplicits._

  protected def format: String
  // The SQL config key for enabling aggregate push down.
  protected val aggPushDownEnabledKey: String

  test("nested column: Max(top level column) not push down") {
    val data = (1 to 10).map(i => Tuple1((i, Seq(s"val_$i"))))
    withSQLConf(aggPushDownEnabledKey -> "true") {
      withDataSourceTable(data, "t") {
        val max = sql("SELECT Max(_1) FROM t")
        max.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"
            checkKeywordsExistsInExplain(max, expected_plan_fragment)
        }
      }
    }
  }

  test("nested column: Count(top level column) push down") {
    val data = (1 to 10).map(i => Tuple1((i, Seq(s"val_$i"))))
    withSQLConf(aggPushDownEnabledKey -> "true") {
      withDataSourceTable(data, "t") {
        val count = sql("SELECT Count(_1) FROM t")
        count.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: [COUNT(_1)]"
            checkKeywordsExistsInExplain(count, expected_plan_fragment)
        }
        checkAnswer(count, Seq(Row(10)))
      }
    }
  }

  test("nested column: Max(nested sub-field) not push down") {
    val data = (1 to 10).map(i => Tuple1((i, Seq(s"val_$i"))))
    withSQLConf(aggPushDownEnabledKey-> "true") {
      withDataSourceTable(data, "t") {
        val max = sql("SELECT Max(_1._2[0]) FROM t")
        max.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"
            checkKeywordsExistsInExplain(max, expected_plan_fragment)
        }
      }
    }
  }

  test("nested column: Count(nested sub-field) not push down") {
    val data = (1 to 10).map(i => Tuple1((i, Seq(s"val_$i"))))
    withSQLConf(aggPushDownEnabledKey -> "true") {
      withDataSourceTable(data, "t") {
        val count = sql("SELECT Count(_1._2[0]) FROM t")
        count.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"
            checkKeywordsExistsInExplain(count, expected_plan_fragment)
        }
        checkAnswer(count, Seq(Row(10)))
      }
    }
  }

  test("Max(partition column): not push down") {
    withTempPath { dir =>
      spark.range(10).selectExpr("id", "id % 3 as p")
        .write.partitionBy("p").format(format).save(dir.getCanonicalPath)
      withTempView("tmp") {
        spark.read.format(format).load(dir.getCanonicalPath).createOrReplaceTempView("tmp");
        withSQLConf(aggPushDownEnabledKey -> "true") {
          val max = sql("SELECT Max(p) FROM tmp")
          max.queryExecution.optimizedPlan.collect {
            case _: DataSourceV2ScanRelation =>
              val expected_plan_fragment =
                "PushedAggregation: []"
              checkKeywordsExistsInExplain(max, expected_plan_fragment)
          }
          checkAnswer(max, Seq(Row(2)))
        }
      }
    }
  }

  test("filter alias over aggregate") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 6))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        val selectAgg = sql("SELECT min(_1) + max(_1) as res FROM t having res > 1")
        selectAgg.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: [MIN(_1), MAX(_1)]"
            checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
        }
        checkAnswer(selectAgg, Seq(Row(7)))
      }
    }
  }

  test("alias over aggregate") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 6))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        val selectAgg = sql("SELECT min(_1) + 1 as minPlus1, min(_1) + 2 as minPlus2 FROM t")
        selectAgg.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: [MIN(_1)]"
            checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
        }
        checkAnswer(selectAgg, Seq(Row(-1, 0)))
      }
    }
  }

  test("aggregate over alias not push down") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 6))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        val df = spark.table("t")
        val query = df.select($"_1".as("col1")).agg(min($"col1"))
        query.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"  // aggregate alias not pushed down
            checkKeywordsExistsInExplain(query, expected_plan_fragment)
        }
        checkAnswer(query, Seq(Row(-2)))
      }
    }
  }

  test("query with group by not push down") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 7))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        // aggregate not pushed down if there is group by
        val selectAgg = sql("SELECT min(_1) FROM t GROUP BY _3 ")
        selectAgg.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"
            checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
        }
        checkAnswer(selectAgg, Seq(Row(-2), Row(0), Row(2), Row(3)))
      }
    }
  }

  test("query with filter not push down") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 7))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        // aggregate not pushed down if there is filter
        val selectAgg = sql("SELECT min(_3) FROM t WHERE _1 > 0")
        selectAgg.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"
            checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
        }
        checkAnswer(selectAgg, Seq(Row(2)))
      }
    }
  }

  test("push down only if all the aggregates can be pushed down") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 7))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        // not push down since sum can't be pushed down
        val selectAgg = sql("SELECT min(_1), sum(_3) FROM t")
        selectAgg.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: []"
            checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
        }
        checkAnswer(selectAgg, Seq(Row(-2, 41)))
      }
    }
  }

  test("aggregate push down - MIN/MAX/COUNT") {
    val data = Seq((-2, "abc", 2), (3, "def", 4), (6, "ghi", 2), (0, null, 19),
      (9, "mno", 7), (2, null, 6))
    withDataSourceTable(data, "t") {
      withSQLConf(aggPushDownEnabledKey -> "true") {
        val selectAgg = sql("SELECT min(_3), min(_3), max(_3), min(_1), max(_1), max(_1)," +
          " count(*), count(_1), count(_2), count(_3) FROM t")
        selectAgg.queryExecution.optimizedPlan.collect {
          case _: DataSourceV2ScanRelation =>
            val expected_plan_fragment =
              "PushedAggregation: [MIN(_3), " +
                "MAX(_3), " +
                "MIN(_1), " +
                "MAX(_1), " +
                "COUNT(*), " +
                "COUNT(_1), " +
                "COUNT(_2), " +
                "COUNT(_3)]"
            checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
        }

        checkAnswer(selectAgg, Seq(Row(2, 2, 19, -2, 9, 9, 6, 6, 4, 6)))
      }
    }
  }

  test("aggregate push down - different data types") {
    implicit class StringToDate(s: String) {
      def date: Date = Date.valueOf(s)
    }

    implicit class StringToTs(s: String) {
      def ts: Timestamp = Timestamp.valueOf(s)
    }

    val rows =
      Seq(
        Row(
          "a string",
          true,
          10.toByte,
          "Spark SQL".getBytes,
          12.toShort,
          3,
          Long.MaxValue,
          0.15.toFloat,
          0.75D,
          Decimal("12.345678"),
          ("2021-01-01").date,
          ("2015-01-01 23:50:59.123").ts),
        Row(
          "test string",
          false,
          1.toByte,
          "Parquet".getBytes,
          2.toShort,
          null,
          Long.MinValue,
          0.25.toFloat,
          0.85D,
          Decimal("1.2345678"),
          ("2015-01-01").date,
          ("2021-01-01 23:50:59.123").ts),
        Row(
          null,
          true,
          10000.toByte,
          "Spark ML".getBytes,
          222.toShort,
          113,
          11111111L,
          0.25.toFloat,
          0.75D,
          Decimal("12345.678"),
          ("2004-06-19").date,
          ("1999-08-26 10:43:59.123").ts)
      )

    val schema = StructType(List(StructField("StringCol", StringType, true),
      StructField("BooleanCol", BooleanType, false),
      StructField("ByteCol", ByteType, false),
      StructField("BinaryCol", BinaryType, false),
      StructField("ShortCol", ShortType, false),
      StructField("IntegerCol", IntegerType, true),
      StructField("LongCol", LongType, false),
      StructField("FloatCol", FloatType, false),
      StructField("DoubleCol", DoubleType, false),
      StructField("DecimalCol", DecimalType(25, 5), true),
      StructField("DateCol", DateType, false),
      StructField("TimestampCol", TimestampType, false)).toArray)

    val rdd = sparkContext.parallelize(rows)
    withTempPath { file =>
      spark.createDataFrame(rdd, schema).write.format(format).save(file.getCanonicalPath)
      withTempView("test") {
        spark.read.format(format).load(file.getCanonicalPath).createOrReplaceTempView("test")

        Seq("false", "true").foreach { enableVectorizedReader =>
          withSQLConf(aggPushDownEnabledKey -> "true",
            vectorizedReaderEnabledKey -> enableVectorizedReader) {

            val testMinWithTS = sql("SELECT min(StringCol), min(BooleanCol), min(ByteCol), " +
              "min(ShortCol), min(IntegerCol), min(LongCol), min(FloatCol), " +
              "min(DoubleCol), min(DecimalCol), min(DateCol), min(TimestampCol) FROM test")

            // INT96 (Timestamp) sort order is undefined, Parquet doesn't return stats for this type
            // so aggregates are not pushed down. Also do not push down for ORC for safety now.
            testMinWithTS.queryExecution.optimizedPlan.collect {
              case _: DataSourceV2ScanRelation =>
                val expected_plan_fragment =
                  "PushedAggregation: []"
                checkKeywordsExistsInExplain(testMinWithTS, expected_plan_fragment)
            }

            checkAnswer(testMinWithTS, Seq(Row("a string", false, 1.toByte,
              2.toShort, 3, -9223372036854775808L, 0.15.toFloat, 0.75D, 1.23457,
              ("2004-06-19").date, ("1999-08-26 10:43:59.123").ts)))

            val testMinWithOutTS = sql("SELECT min(StringCol), min(BooleanCol), min(ByteCol), " +
              "min(ShortCol), min(IntegerCol), min(LongCol), min(FloatCol), " +
              "min(DoubleCol), min(DecimalCol), min(DateCol) FROM test")

            testMinWithOutTS.queryExecution.optimizedPlan.collect {
              case _: DataSourceV2ScanRelation =>
                val expected_plan_fragment =
                  "PushedAggregation: [MIN(StringCol), " +
                    "MIN(BooleanCol), " +
                    "MIN(ByteCol), " +
                    "MIN(ShortCol), " +
                    "MIN(IntegerCol), " +
                    "MIN(LongCol), " +
                    "MIN(FloatCol), " +
                    "MIN(DoubleCol), " +
                    "MIN(DecimalCol), " +
                    "MIN(DateCol)]"
                checkKeywordsExistsInExplain(testMinWithOutTS, expected_plan_fragment)
            }

            checkAnswer(testMinWithOutTS, Seq(Row("a string", false, 1.toByte,
              2.toShort, 3, -9223372036854775808L, 0.15.toFloat, 0.75D, 1.23457,
              ("2004-06-19").date)))

            val testMaxWithTS = sql("SELECT max(StringCol), max(BooleanCol), max(ByteCol), " +
              "max(ShortCol), max(IntegerCol), max(LongCol), max(FloatCol), " +
              "max(DoubleCol), max(DecimalCol), max(DateCol), max(TimestampCol) FROM test")

            // INT96 (Timestamp) sort order is undefined, parquet doesn't return stats for this type
            // so aggregates are not pushed down
            testMaxWithTS.queryExecution.optimizedPlan.collect {
              case _: DataSourceV2ScanRelation =>
                val expected_plan_fragment =
                  "PushedAggregation: []"
                checkKeywordsExistsInExplain(testMaxWithTS, expected_plan_fragment)
            }

            checkAnswer(testMaxWithTS, Seq(Row("test string", true, 16.toByte,
              222.toShort, 113, 9223372036854775807L, 0.25.toFloat, 0.85D,
              12345.678, ("2021-01-01").date, ("2021-01-01 23:50:59.123").ts)))

            val testMaxWithoutTS = sql("SELECT max(StringCol), max(BooleanCol), max(ByteCol), " +
              "max(ShortCol), max(IntegerCol), max(LongCol), max(FloatCol), " +
              "max(DoubleCol), max(DecimalCol), max(DateCol) FROM test")

            testMaxWithoutTS.queryExecution.optimizedPlan.collect {
              case _: DataSourceV2ScanRelation =>
                val expected_plan_fragment =
                  "PushedAggregation: [MAX(StringCol), " +
                    "MAX(BooleanCol), " +
                    "MAX(ByteCol), " +
                    "MAX(ShortCol), " +
                    "MAX(IntegerCol), " +
                    "MAX(LongCol), " +
                    "MAX(FloatCol), " +
                    "MAX(DoubleCol), " +
                    "MAX(DecimalCol), " +
                    "MAX(DateCol)]"
                checkKeywordsExistsInExplain(testMaxWithoutTS, expected_plan_fragment)
            }

            checkAnswer(testMaxWithoutTS, Seq(Row("test string", true, 16.toByte,
              222.toShort, 113, 9223372036854775807L, 0.25.toFloat, 0.85D,
              12345.678, ("2021-01-01").date)))

            val testCount = sql("SELECT count(StringCol), count(BooleanCol)," +
              " count(ByteCol), count(BinaryCol), count(ShortCol), count(IntegerCol)," +
              " count(LongCol), count(FloatCol), count(DoubleCol)," +
              " count(DecimalCol), count(DateCol), count(TimestampCol) FROM test")

            testCount.queryExecution.optimizedPlan.collect {
              case _: DataSourceV2ScanRelation =>
                val expected_plan_fragment =
                  "PushedAggregation: [" +
                    "COUNT(StringCol), " +
                    "COUNT(BooleanCol), " +
                    "COUNT(ByteCol), " +
                    "COUNT(BinaryCol), " +
                    "COUNT(ShortCol), " +
                    "COUNT(IntegerCol), " +
                    "COUNT(LongCol), " +
                    "COUNT(FloatCol), " +
                    "COUNT(DoubleCol), " +
                    "COUNT(DecimalCol), " +
                    "COUNT(DateCol), " +
                    "COUNT(TimestampCol)]"
                checkKeywordsExistsInExplain(testCount, expected_plan_fragment)
            }

            checkAnswer(testCount, Seq(Row(2, 3, 3, 3, 3, 2, 3, 3, 3, 3, 3, 3)))
          }
        }
      }
    }
  }

  test("column name case sensitivity") {
    Seq("false", "true").foreach { enableVectorizedReader =>
      withSQLConf(aggPushDownEnabledKey -> "true",
        vectorizedReaderEnabledKey -> enableVectorizedReader) {
        withTempPath { dir =>
          spark.range(10).selectExpr("id", "id % 3 as p")
            .write.partitionBy("p").format(format).save(dir.getCanonicalPath)
          withTempView("tmp") {
            spark.read.format(format).load(dir.getCanonicalPath).createOrReplaceTempView("tmp")
            val selectAgg = sql("SELECT max(iD), min(Id) FROM tmp")
            selectAgg.queryExecution.optimizedPlan.collect {
              case _: DataSourceV2ScanRelation =>
                val expected_plan_fragment =
                  "PushedAggregation: [MAX(id), MIN(id)]"
                checkKeywordsExistsInExplain(selectAgg, expected_plan_fragment)
            }
            checkAnswer(selectAgg, Seq(Row(9, 0)))
          }
        }
      }
    }
  }
}

abstract class ParquetAggregatePushDownSuite
  extends FileSourceAggregatePushDownSuite with ParquetTest {

  override def format: String = "parquet"
  override protected val aggPushDownEnabledKey: String =
    SQLConf.PARQUET_AGGREGATE_PUSHDOWN_ENABLED.key
}

class ParquetV1AggregatePushDownSuite extends ParquetAggregatePushDownSuite {

  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.USE_V1_SOURCE_LIST, "parquet")
}

class ParquetV2AggregatePushDownSuite extends ParquetAggregatePushDownSuite {

  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.USE_V1_SOURCE_LIST, "")
}

abstract class OrcAggregatePushDownSuite extends OrcTest with FileSourceAggregatePushDownSuite {

  override def format: String = "orc"
  override protected val aggPushDownEnabledKey: String =
    SQLConf.ORC_AGGREGATE_PUSHDOWN_ENABLED.key
}

class OrcV1AggregatePushDownSuite extends OrcAggregatePushDownSuite {

  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.USE_V1_SOURCE_LIST, "orc")
}

class OrcV2AggregatePushDownSuite extends OrcAggregatePushDownSuite {

  override protected def sparkConf: SparkConf =
    super.sparkConf.set(SQLConf.USE_V1_SOURCE_LIST, "")
}
