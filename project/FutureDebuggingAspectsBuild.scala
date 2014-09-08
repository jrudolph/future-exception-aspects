import sbt._
import Keys._

import com.typesafe.sbt.SbtAspectj.{ Aspectj, aspectjSettings, compiledClasses, useInstrumentedClasses }
import com.typesafe.sbt.SbtAspectj.AspectjKeys.{ inputs, binaries }

import sbtassembly.Plugin._
import AssemblyKeys._

object FutureDebuggingAspectsBuild extends Build {
  import Dependencies._

  lazy val root =
    Project("root", file("."))
      .aggregate(futureDebuggingAspects, example, tests)

  lazy val futureDebuggingAspects =
    Project("future-debugging-aspects", file("future-debugging-aspects"))
      .settings(basicSettings: _*)
      .settings(aspectjSettings: _*)
      .settings(
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-actor" % "2.3.5"
        ),
        inputs in Aspectj <+= compiledClasses,
        products in Compile <<= products in Aspectj
      )

  lazy val tests =
    Project("tests", file("tests"))
      .settings(basicSettings: _*)
      .settings(aspectjSettings: _*)
      .settings(
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-actor" % "2.3.5",
          "com.typesafe.akka" %% "akka-testkit" % "2.3.5" % "test",
          "org.specs2" %% "specs2" % "2.3.12" % "test"
        ),
        fork in run := true,

        // we only need that if we are calling weaved classes, if I understand it correctly
        inputs in Aspectj <+= compiledClasses,

        /*inputs in Aspectj <++= (externalDependencyClasspath in Compile).map { cp =>
          val whitelist = Seq("scala-reflect")
          cp.map(_.data).filter(f => whitelist.exists(f.getName.contains))
        },*/

        /* an alternative way to selecting dependency jars to weave
        inputs in Aspectj <++= update map { report =>
          report.matching(moduleFilter(organization = "io.spray", name = "spray-io*"))
        },*/

        // configure our aspects
        binaries in Aspectj <<= products in Compile in futureDebuggingAspects,
        // reconfigure our products to use the output of aspectj
        products in Compile <<= products in Aspectj,

        // use instrumented classes when running
        fullClasspath in Runtime <<= useInstrumentedClasses(Runtime)
      )
      .dependsOn(futureDebuggingAspects)

  lazy val example =
    Project("example", file("example"))
      .settings(basicSettings: _*)
      .settings(aspectjSettings: _*)
      .settings(assemblySettings: _*)
      .settings(
        libraryDependencies ++= Seq(
          "com.typesafe.akka" %% "akka-actor" % "2.3.5",
          "org.specs2" %% "specs2" % "2.3.12" % "test"
        ),
        //addReflect,
        fork in run := true,

        // we only need that if we are calling weaved classes, if I understand it correctly
        inputs in Aspectj <+= compiledClasses,

        /*inputs in Aspectj <++= (externalDependencyClasspath in Compile).map { cp =>
          val whitelist = Seq("scala-reflect")
          cp.map(_.data).filter(f => whitelist.exists(f.getName.contains))
        },*/

        /* an alternative way to selecting dependency jars to weave
        inputs in Aspectj <++= update map { report =>
          report.matching(moduleFilter(organization = "io.spray", name = "spray-io*"))
        },*/

        // configure our aspects
        binaries in Aspectj <<= products in Compile in futureDebuggingAspects,
        // reconfigure our products to use the output of aspectj
        products in Compile <<= products in Aspectj,

        // use instrumented classes when running
        fullClasspath in Runtime <<= useInstrumentedClasses(Runtime)
      )
      .dependsOn(futureDebuggingAspects)

  def basicSettings = seq(
    scalaVersion := "2.11.2"
  ) ++ ScalariformSupport.formatSettings

  object Dependencies {
    val addReflect = libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _ % "provided")
  }
}
