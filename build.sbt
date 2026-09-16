ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "dev.zam"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "zam",
    Compile / mainClass := Some("zam.Main"),
    scalacOptions ++= Seq("-deprecation", "-feature")
  )