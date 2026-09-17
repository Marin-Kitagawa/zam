ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.zam"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "zam",
    Compile / mainClass := Some("zam.Main"),
    scalacOptions ++= Seq("-deprecation", "-feature"),
    libraryDependencies ++= Seq(
      "net.java.dev.jna" % "jna" % "5.15.0",
      "net.java.dev.jna" % "jna-platform" % "5.15.0"
    ),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", _*) => MergeStrategy.first
      case _ => MergeStrategy.first
    }
  )