import sbt.Keys.libraryDependencies

resolvers in ThisBuild ++= Seq("Apache Development Snapshot Repository" at "https://repository.apache.org/content/repositories/snapshots/",
  Resolver.mavenLocal)

name := "FlinkPlayground"

version := "0.1-SNAPSHOT"

organization := "org.codefeedr"

scalaVersion in ThisBuild := "2.11.0"

val flinkVersion = "1.3.0"

val flinkDependencies = Seq(
  "org.apache.flink" %% "flink-scala" % flinkVersion % "provided",
  "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % "provided")

lazy val root = (project in file(".")).
  settings(
    libraryDependencies ++= flinkDependencies

  )
libraryDependencies += "org.scalatest" % "scalatest_2.11" % "3.0.1" % "test"

mainClass in assembly := Some("org.codefeedr.Job")

// make run command include the provided dependencies
run in Compile <<= Defaults.runTask(fullClasspath in Compile, mainClass in (Compile, run), runner in (Compile, run))

// exclude Scala library from assembly
assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)