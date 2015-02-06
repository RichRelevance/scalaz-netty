import com.typesafe.sbt.SbtGit._
import bintray.Keys._

organization := "org.scalaz.netty"

name := "scalaz-netty"

version := "master-SNAPSHOT"

scalaVersion := "2.11.5"

crossScalaVersions := Seq("2.10.4", "2.11.5")

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz"        %% "scalaz-core"   % "7.1.0",
  "org.scalaz.stream" %% "scalaz-stream" % "0.6a",

  "io.netty"          %  "netty-codec"   % "4.0.21.Final",

  "org.typelevel"     %% "scodec-core"   % "1.6.0")

libraryDependencies ++= Seq(
  "org.specs2"     %% "specs2"     % "2.4.14" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.1" % "test")

publishMavenStyle := true

bintraySettings

bintrayOrganization in bintray := Some("rr")
