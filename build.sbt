/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

name := "bigquery-hive-orc-loader"

version := "0.1.0"

scalaVersion := "2.11.11"

val hiveVersion = "1.2.1"
val sparkVersion = "2.3.0"

val exHadoop = ExclusionRule("org.apache.hadoop")
val exGuava = ExclusionRule("com.google.guava")

libraryDependencies ++= Seq(
  "com.google.guava" % "guava" % "27.1-jre"
)

libraryDependencies ++= Seq(
	"org.apache.hive" % "hive-metastore" % hiveVersion,
  "org.apache.spark" %% "spark-core" % sparkVersion,
  "org.apache.spark" %% "spark-sql" % sparkVersion,
  "org.apache.spark" %% "spark-hive" % sparkVersion,
  "org.apache.hadoop" % "hadoop-common" % "2.7.7",
	"com.google.cloud" % "google-cloud-bigquery" % "1.69.0",
	"com.google.apis" % "google-api-services-bigquery" % "v2-rev431-1.25.0",
	"com.google.code.gson" % "gson" % "2.8.5",
  "com.google.protobuf" % "protobuf-java" % "3.6.1",
  "com.github.scopt" %% "scopt" % "3.7.1"
).map(_ excludeAll exGuava)

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.4" % "test"

mainClass in assembly := Some("com.google.cloud.example.BQHiveLoader")

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", _) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assemblyShadeRules in assembly := Seq(
  ShadeRule.rename("com.google.common.**" -> "s.guava.@1").inAll,
  ShadeRule.rename("com.google.protobuf.*" -> "s.proto.@1").inAll
)
