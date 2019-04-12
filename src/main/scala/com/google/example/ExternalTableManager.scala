package com.google.example

import com.google.cloud.bigquery.JobInfo.{CreateDisposition, WriteDisposition}
import com.google.cloud.bigquery.QueryJobConfiguration.Priority
import com.google.cloud.bigquery._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.catalog.{CatalogTable, CatalogTablePartition}

object ExternalTableManager {
  def defaultExpiration: Long = System.currentTimeMillis() + 1000*60*60*24*2 // 2 days

  def createExternalTable(project: String,
                          dataset: String,
                          table: String,
                          schema: Schema,
                          sources: java.util.List[String],
                          bigquery: BigQuery): TableInfo = {

    val tableId = TableId.of(project, dataset, table)

    val tableDefinition = ExternalTableDefinition
      .newBuilder(sources, schema, FormatOptions.orc()).build()

    val tableInfo = TableInfo
      .newBuilder(tableId, tableDefinition)
      .setExpirationTime(defaultExpiration)
      .build()

    bigquery.create(tableInfo)

    tableInfo
  }

  def registerParts(project: String,
                    dataset: String,
                    tableId: String,
                    table: CatalogTable,
                    parts: Seq[CatalogTablePartition],
                    bigquery: BigQuery): Seq[TableResult] = {
    parts.map(part =>
      registerPart(project, dataset, tableId, table, part, bigquery)
    )
  }

  def registerPart(project: String,
                   dataset: String,
                   tableId: String,
                   table: CatalogTable,
                   part: CatalogTablePartition,
                   bigquery: BigQuery): TableResult = {

    val extTable = Mapping.createExternalTable(project, dataset, tableId,
      table, part, bigquery)

    val query = QueryJobConfiguration
      .newBuilder(genSql(extTable.getTableId, table, part))
      .setCreateDisposition(CreateDisposition.CREATE_NEVER)
      .setWriteDisposition(WriteDisposition.WRITE_APPEND)
      .setDestinationTable(TableId.of(project,dataset,tableId))
      .setPriority(Priority.BATCH)
      .setUseLegacySql(false)
      .setUseQueryCache(false)
      .build()

    val jobId = JobId.newBuilder()
      .setProject(project)
      .setLocation("US")
      .setJob(s"load_${tableId}_${part.spec.values.mkString("_")}")
      .build()

    bigquery.query(query, jobId)
  }

  def genSql(extTable: TableId, table: CatalogTable, part: CatalogTablePartition): String = {
    val partCols = table.partitionColumnNames.toSet

    val renamedCols = table.schema
      .filterNot(field => partCols.contains(field.name))
      .zipWithIndex
      .map{x =>
        s"_col${x._2+1} as ${x._1.name}"
      }

    val partVals = table.partitionColumnNames
      .zip(part.spec.values)
      .map{x => s"${x._1} as '${x._2}'"}

    val tableSpec = extTable.getDataset + "." + extTable.getTable
    s"""select
       |  ${partVals.mkString("", ",\n  ",",")}
       |  ${renamedCols.mkString(",\n  ")}
       |from $tableSpec""".stripMargin
  }

  def findParts(db: String, table: String, partCol: String, target: String, spark: SparkSession): Seq[CatalogTablePartition] = {
    val cat = spark.sessionState.catalog.externalCatalog

    cat.listPartitions(db, table)
      .filter{x =>
        cat.getTable(db, table)
          .partitionColumnNames
          .zip(x.spec.values)
          .filter(_._1 == partCol)
          .exists(_._2 == target)
      }
  }
}
