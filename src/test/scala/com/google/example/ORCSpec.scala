package com.google.example

import org.apache.spark.SparkConf
import org.apache.spark.sql.types.{DoubleType, IntegerType, LongType, StringType, StructField, StructType}
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

import scala.util.Random

class ORCSpec extends FlatSpec with BeforeAndAfterAll {
  private var sparkSession: Option[SparkSession] = None
  def getSpark: SparkSession = sparkSession.get
  val testDir = "/tmp/test"

  override def beforeAll(): Unit = {
    val spark = SparkSession.builder()
      .master("local[2]")
      .config(new SparkConf().setAll(Map(
        "spark.sql.hive.convertMetastoreOrc" -> "true",
        "spark.sql.orc.enabled" -> "true",
        "spark.sql.orc.filterPushdown" -> "true",
        "spark.sql.orc.char.enabled" -> "true"
      )))
      .enableHiveSupport
      .getOrCreate()

    spark.sql("DROP DATABASE IF EXISTS testdb CASCADE").show()
    //spark.sql("DROP TABLE IF EXISTS testdb.test").show()
    sparkSession = Option(spark)
  }

  "BQHiveLoader" should "write ORC" in {
    val spark = getSpark
    import spark.implicits._

    val rand = new Random()
    spark.sql("create database if not exists testdb").show()
    spark.sql("use testdb").show()
    spark.sql(
      s"""CREATE EXTERNAL TABLE testdb.test (id STRING, x BIGINT, y INT, z DOUBLE)
        |PARTITIONED BY (region STRING, date STRING)
        |STORED AS ORC
        |LOCATION 'file://$testDir/warehouse/test/'""".stripMargin).show()

    for (x <- 11 to 12) {
      for (region <- Seq("US", "EU")) {
        val date = s"2019-04-$x"
        val data = (0 until 10000).map{_ =>
          val id: String = (0 until 8).map(_ => rand.nextPrintableChar()).mkString
          (
            id, // id
            rand.nextLong(),    // x
            rand.nextInt(),     // y
            rand.nextDouble()   // z
          )
        }

        val df = data.toDF()

        System.out.println("columns: " + df.columns.mkString(","))

        val colnames = Seq("id", "x", "y", "z")

        val dfWithColumnsRenamed = colnames.zipWithIndex.foldLeft(df){(a,b) =>
          a.withColumnRenamed(s"_${b._2+1}", b._1)
        }

        System.out.println("columns: " + dfWithColumnsRenamed.columns.mkString(","))

        dfWithColumnsRenamed
          .write
          .mode(SaveMode.Overwrite)
          .format("orc")
          .option("orc.compress", "snappy")
          .save(s"$testDir/${region}_${date}_part_$x.snappy.orc")

        spark.sql(
          s"""ALTER TABLE testdb.test
             |ADD PARTITION (
             |  region = '$region',
             |  date = '$date'
             |)
             |LOCATION 'file://$testDir/${region}_${date}_part_$x.snappy.orc'""".stripMargin).show()
      }
    }

    spark.sql("select count(1) from testdb.test").show()

    val parts0 = spark.sessionState.catalog.externalCatalog
      .listPartitions("testdb", "test")
    System.out.println(parts0.map(_.toString()).mkString("\n"))

    spark.sql("msck repair table testdb.test")

    val parts = spark.sessionState.catalog.externalCatalog
      .listPartitions("testdb", "test")
    System.out.println(parts.map(_.toString()).mkString("\n"))

    spark.sql("select count(1) from testdb.test").show()
  }

  it should "register ORC" in {
    val spark = getSpark
    val table = spark.sessionState.catalog.externalCatalog.getTable("testDb", "test")
    val partCols = table.partitionColumnNames.toSet

    val renames = table.schema
      .filterNot(field => partCols.contains(field.name))
      .zipWithIndex
      .map{x =>
        s"_col${x._2+1} as ${x._1.name}"
      }

    val partVals = Seq(
      ("date", "2019-04-11"),
      ("region", "US")
    ).map{x =>
      s"${x._1} as '${x._2}'"
    }

    val sql =
      s"""select
         |  ${partVals.mkString("", ",\n  ",",")}
         |  ${renames.mkString(",\n  ")}
         |from testdb.test""".stripMargin

    System.out.println(sql)
    assert(sql.nonEmpty)
  }

  it should "read partition" in {
    val spark = getSpark
    spark.sql(
      """select
        |  id,
        |  x,
        |  y,
        |  z,
        |  date,
        |  region
        |from testdb.test
        |where date == '2019-04-11'
        |  and region = 'US'""".stripMargin).show(3)
  }

  it should "read orc" in {
    val spark = getSpark
    for (region <- Seq("US")) {
      for (x <- Seq(11)) {
        val date = s"2019-04-$x"
        val in = spark.read
          .schema(StructType(Array(
            StructField("id", StringType),
            StructField("x", LongType),
            StructField("y", IntegerType),
            StructField("z", DoubleType)
          )))
          .orc(s"file://$testDir/${region}_${date}_part_$x.snappy.orc")
        System.out.println(in.columns.mkString(","))
        in.show(3)
      }
    }

  }
}
