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

package org.apache.spark.sql.jdbc.v2

import java.sql.Connection

import org.apache.spark.{SparkConf, SparkSQLFeatureNotSupportedException}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.{RowDataSourceScanExec, SparkPlan}
import org.apache.spark.sql.execution.datasources.jdbc.JDBCRDD
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog
import org.apache.spark.sql.jdbc.MsSQLServerDatabaseOnDocker
import org.apache.spark.sql.types._
import org.apache.spark.tags.DockerTest

/**
 * To run this test suite for a specific version (e.g., 2022-CU15-ubuntu-22.04):
 * {{{
 *   ENABLE_DOCKER_INTEGRATION_TESTS=1
 *   MSSQLSERVER_DOCKER_IMAGE_NAME=mcr.microsoft.com/mssql/server:2022-CU15-ubuntu-22.04
 *     ./build/sbt -Pdocker-integration-tests "testOnly *v2*MsSqlServerIntegrationSuite"
 * }}}
 */
@DockerTest
class MsSqlServerIntegrationSuite extends DockerJDBCIntegrationV2Suite with V2JDBCTest {

  object JdbcClientTypes {
    val INTEGER = "int"
    val STRING = "nvarchar"
  }

  def getExternalEngineQuery(executedPlan: SparkPlan): String = {
    getExternalEngineRdd(executedPlan).asInstanceOf[JDBCRDD].getExternalEngineQuery
  }

  def getExternalEngineRdd(executedPlan: SparkPlan): RDD[InternalRow] = {
    val queryNode = executedPlan.collect { case r: RowDataSourceScanExec =>
      r
    }.head
    queryNode.rdd
  }

  // Following tests are disabled for both single and multiple partition read
  override def excluded: Seq[String] = Seq(
    "simple scan with OFFSET (false)",
    "simple scan with OFFSET (true)",
    "simple scan with LIMIT and OFFSET (false)",
    "simple scan with LIMIT and OFFSET (true)",
    "simple scan with paging: top N and OFFSET (false)",
    "simple scan with paging: top N and OFFSET (true)",
    "scan with aggregate push-down: VAR_POP with DISTINCT (false)",
    "scan with aggregate push-down: VAR_POP with DISTINCT (true)",
    "scan with aggregate push-down: COVAR_POP with DISTINCT (false)",
    "scan with aggregate push-down: COVAR_POP with DISTINCT (true)",
    "scan with aggregate push-down: COVAR_POP without DISTINCT (false)",
    "scan with aggregate push-down: COVAR_POP without DISTINCT (true)",
    "scan with aggregate push-down: COVAR_SAMP with DISTINCT (false)",
    "scan with aggregate push-down: COVAR_SAMP with DISTINCT (true)",
    "scan with aggregate push-down: COVAR_SAMP without DISTINCT (false)",
    "scan with aggregate push-down: COVAR_SAMP without DISTINCT (true)",
    "scan with aggregate push-down: CORR with DISTINCT (false)",
    "scan with aggregate push-down: CORR with DISTINCT (true)",
    "scan with aggregate push-down: CORR without DISTINCT (false)",
    "scan with aggregate push-down: CORR without DISTINCT (true)",
    "scan with aggregate push-down: REGR_INTERCEPT with DISTINCT (false)",
    "scan with aggregate push-down: REGR_INTERCEPT with DISTINCT (true)",
    "scan with aggregate push-down: REGR_INTERCEPT without DISTINCT (false)",
    "scan with aggregate push-down: REGR_INTERCEPT without DISTINCT (true)",
    "scan with aggregate push-down: REGR_SLOPE with DISTINCT (false)",
    "scan with aggregate push-down: REGR_SLOPE with DISTINCT (true)",
    "scan with aggregate push-down: REGR_SLOPE without DISTINCT (false)",
    "scan with aggregate push-down: REGR_SLOPE without DISTINCT (true)",
    "scan with aggregate push-down: REGR_R2 with DISTINCT (false)",
    "scan with aggregate push-down: REGR_R2 with DISTINCT (true)",
    "scan with aggregate push-down: REGR_R2 without DISTINCT (false)",
    "scan with aggregate push-down: REGR_R2 without DISTINCT (true)",
    "scan with aggregate push-down: REGR_SXY with DISTINCT (false)",
    "scan with aggregate push-down: REGR_SXY with DISTINCT (true)",
    "scan with aggregate push-down: REGR_SXY without DISTINCT (false)",
    "scan with aggregate push-down: REGR_SXY without DISTINCT (true)")

  override val catalogName: String = "mssql"
  override val db = new MsSQLServerDatabaseOnDocker

  override def sparkConf: SparkConf = super.sparkConf
    .set("spark.sql.catalog.mssql", classOf[JDBCTableCatalog].getName)
    .set("spark.sql.catalog.mssql.url", db.getJdbcUrl(dockerIp, externalPort))
    .set("spark.sql.catalog.mssql.pushDownAggregate", "true")
    .set("spark.sql.catalog.mssql.pushDownLimit", "true")

  override def tablePreparation(connection: Connection): Unit = {
    connection.prepareStatement(
      "CREATE TABLE employee (dept INT, name VARCHAR(32), salary NUMERIC(20, 2), bonus FLOAT)")
      .executeUpdate()
    connection.prepareStatement(
      s"""CREATE TABLE pattern_testing_table (
         |pattern_testing_col VARCHAR(50)
         |)
                   """.stripMargin
    ).executeUpdate()
  }

  override def testRenameColumn(tbl: String): Unit = {
    sql(s"ALTER TABLE $tbl RENAME COLUMN ID TO RENAMED")
    val t = spark.table(s"$tbl")
    val expectedSchema = new StructType()
      .add("RENAMED", StringType, true, defaultMetadata(StringType, JdbcClientTypes.STRING))
      .add("ID1", StringType, true, defaultMetadata(StringType, JdbcClientTypes.STRING))
      .add("ID2", StringType, true, defaultMetadata(StringType, JdbcClientTypes.STRING))
    assert(t.schema === expectedSchema)
  }

  override def notSupportsTableComment: Boolean = true

  override def testUpdateColumnType(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INTEGER)")
    var t = spark.table(tbl)
    var expectedSchema = new StructType()
      .add("ID", IntegerType, true, defaultMetadata(IntegerType, JdbcClientTypes.INTEGER))
    assert(t.schema === expectedSchema)
    sql(s"ALTER TABLE $tbl ALTER COLUMN id TYPE STRING")
    t = spark.table(tbl)
    expectedSchema = new StructType()
      .add("ID", StringType, true, defaultMetadata(StringType, JdbcClientTypes.STRING))
    assert(t.schema === expectedSchema)
    // Update column type from STRING to INTEGER
    val sql1 = s"ALTER TABLE $tbl ALTER COLUMN id TYPE INTEGER"
    checkError(
      exception = intercept[AnalysisException] {
        sql(sql1)
      },
      condition = "NOT_SUPPORTED_CHANGE_COLUMN",
      parameters = Map(
        "originType" -> "\"STRING\"",
        "newType" -> "\"INT\"",
        "newName" -> "`ID`",
        "originName" -> "`ID`",
        "table" -> s"`$catalogName`.`alt_table`"),
      context = ExpectedContext(fragment = sql1, start = 0, stop = 55)
    )
  }

  override def testUpdateColumnNullability(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID STRING NOT NULL)")
    // Update nullability is unsupported for mssql db.
    checkError(
      exception = intercept[SparkSQLFeatureNotSupportedException] {
        sql(s"ALTER TABLE $tbl ALTER COLUMN ID DROP NOT NULL")
      },
      condition = "UNSUPPORTED_FEATURE.UPDATE_COLUMN_NULLABILITY")
  }

  test("SPARK-47440: SQLServer does not support boolean expression in binary comparison") {
    val df1 = sql("SELECT name FROM " +
      s"$catalogName.employee WHERE ((name LIKE 'am%') = (name LIKE '%y'))")
    checkFilterPushed(df1)
    assert(df1.collect().length == 4)

    val df2 = sql("SELECT name FROM " +
      s"$catalogName.employee " +
      "WHERE ((name NOT LIKE 'am%') = (name NOT LIKE '%y'))")
    checkFilterPushed(df2)
    assert(df2.collect().length == 4)

    val df3 = sql("SELECT name FROM " +
      s"$catalogName.employee " +
      "WHERE (dept > 1 AND ((name LIKE 'am%') = (name LIKE '%y')))")
    checkFilterPushed(df3)
    assert(df3.collect().length == 3)
  }

  test("SPARK-47994: SQLServer does not support 1 or 0 as boolean type in CASE WHEN filter") {
    val df = sql(
      s"""
        |WITH tbl AS (
        |SELECT CASE
        |WHEN e.dept = 1 THEN 'first' WHEN e.dept = 2 THEN 'second' ELSE 'third' END
        |AS deptString FROM $catalogName.employee as e)
        |SELECT * FROM tbl
        |WHERE deptString = 'first'
        |""".stripMargin)
    checkFilterPushed(df)
    assert(df.collect().length == 2)
  }

  test("SPARK-50087: SqlServer handle booleans in CASE WHEN test") {
    val df = sql(
      s"""|SELECT * FROM $catalogName.employee
          |WHERE CASE WHEN name = 'Legolas' THEN name = 'Elf' ELSE NOT (name = 'Wizard') END
          |""".stripMargin
    )

    checkFilterPushed(df)
    // scalastyle:off
    assert(getExternalEngineQuery(df.queryExecution.executedPlan) ==
      """SELECT  "dept","name","salary","bonus" FROM "employee" WHERE (CASE WHEN ("name" = 'Legolas') THEN IIF(("name" = 'Elf'), 1, 0) ELSE IIF(("name" <> 'Wizard'), 1, 0) END = 1)  """
    )
    // scalastyle:on
    df.collect()
  }

  test("SPARK-50087: SqlServer handle booleans in CASE WHEN with always true test") {
    val df = sql(
      s"""|SELECT * FROM $catalogName.employee
          |WHERE CASE WHEN (name = 'Legolas') THEN (name = 'Elf') ELSE (1=1) END
          |""".stripMargin
    )

    checkFilterPushed(df)
    // scalastyle:off
    assert(getExternalEngineQuery(df.queryExecution.executedPlan) ==
      """SELECT  "dept","name","salary","bonus" FROM "employee" WHERE (CASE WHEN ("name" = 'Legolas') THEN IIF(("name" = 'Elf'), 1, 0) ELSE 1 END = 1)  """
    )
    // scalastyle:on
    df.collect()
  }

  test("SPARK-50087: SqlServer handle booleans in nested CASE WHEN test") {
    val df = sql(
      s"""|SELECT * FROM $catalogName.employee
          |WHERE CASE WHEN (name = 'Legolas') THEN
          | CASE WHEN (name = 'Elf') THEN (name = 'Elrond') ELSE (name = 'Gandalf') END
          | ELSE (name = 'Sauron') END
          |""".stripMargin
    )

    checkFilterPushed(df)
    // scalastyle:off
    assert(getExternalEngineQuery(df.queryExecution.executedPlan) ==
      """SELECT  "dept","name","salary","bonus" FROM "employee" WHERE (CASE WHEN ("name" = 'Legolas') THEN IIF((CASE WHEN ("name" = 'Elf') THEN IIF(("name" = 'Elrond'), 1, 0) ELSE IIF(("name" = 'Gandalf'), 1, 0) END = 1), 1, 0) ELSE IIF(("name" = 'Sauron'), 1, 0) END = 1)  """
    )
    // scalastyle:on
    df.collect()
  }

  test("SPARK-50087: SqlServer handle non-booleans in nested CASE WHEN test") {
    val df = sql(
      s"""|SELECT * FROM $catalogName.employee
          |WHERE CASE WHEN (name = 'Legolas') THEN
          | CASE WHEN (name = 'Elf') THEN 'Elf' ELSE 'Wizard' END
          | ELSE 'Sauron' END = name
          |""".stripMargin
    )

    checkFilterPushed(df)
    // scalastyle:off
    assert(getExternalEngineQuery(df.queryExecution.executedPlan) ==
      """SELECT  "dept","name","salary","bonus" FROM "employee" WHERE ("name" IS NOT NULL) AND ((CASE WHEN "name" = 'Legolas' THEN CASE WHEN "name" = 'Elf' THEN 'Elf' ELSE 'Wizard' END ELSE 'Sauron' END) = "name")  """
    )
    // scalastyle:on
    df.collect()
  }

  test("SPARK-51321: SQLServer pushdown for RPAD expression on string column") {
    val df = sql(
      s"""|SELECT name FROM $catalogName.employee
          |WHERE rpad(name, 10, 'x') = 'amyxxxxxxx'
          |""".stripMargin
    )
    checkFilterPushed(df)
    val rows = df.collect()
    assert(rows.length == 1)
    assert(rows(0).getString(0) === "amy")
  }

  test("SPARK-51321: SQLServer pushdown for LPAD expression on string column") {
    val df = sql(
      s"""|SELECT name FROM $catalogName.employee
          |WHERE lpad(name, 10, 'x') = 'xxxxxxxamy'
          |""".stripMargin
    )
    checkFilterPushed(df)
    val rows = df.collect()
    assert(rows.length == 1)
    assert(rows(0).getString(0) === "amy")
  }
}
