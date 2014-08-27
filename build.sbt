organization := "org.scalaz"

name := "scalaz-netty"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.2"

crossScalaVersions := Seq("2.10.4", "2.11.2")

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz"        %% "scalaz-core"   % "7.0.6",
  "org.scalaz.stream" %% "scalaz-stream" % "0.4.1",
  //
  "io.netty"          %  "netty-codec"   % "4.0.21.Final",
  //
  "org.typelevel"     %% "scodec-core"   % "1.1.0")

libraryDependencies ++= Seq(
  "org.specs2"     %% "specs2"     % "2.3.13" % "test",
  "org.scalacheck" %% "scalacheck" % "1.11.3" % "test")