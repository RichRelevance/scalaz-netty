organization := "org.scalaz"

name := "scalaz-netty"

version := "master-SNAPSHOT"

scalaVersion := "2.11.2"

crossScalaVersions := Seq("2.10.4", "2.11.2")

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz"        %% "scalaz-core"   % "7.1.0",
  "org.scalaz.stream" %% "scalaz-stream" % "0.6a",
  //
  "io.netty"          %  "netty-codec"   % "4.0.21.Final",
  //
  "org.typelevel"     %% "scodec-core"   % "1.3.0")

libraryDependencies ++= Seq(
  "org.specs2"     %% "specs2"     % "2.4.2" % "test",
  "org.scalacheck" %% "scalacheck" % "1.11.5" % "test")

publishTo := Some(
  if (version.value.trim.endsWith("SNAPSHOT"))
    "RR Snapshots Nexus" at "https://repo.richrelevance.com/content/repositories/inhouse.snapshots/"
  else
    "RR Release Nexus" at "https://repo.richrelevance.com/content/repositories/inhouse/")
