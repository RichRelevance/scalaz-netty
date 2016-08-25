/*
 * Copyright 2016 RichRelevance
 *
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

import com.typesafe.sbt.SbtGit._
import bintray.Keys._

organization := "org.scalaz.netty"

name := "scalaz-netty"

scalaVersion := "2.11.8"

//crossScalaVersions := Seq(scalaVersion.value, "2.12.0-M4")

resolvers += "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"

libraryDependencies ++= Seq(
  "org.scalaz"        %% "scalaz-core"   % "7.2.5",
  "org.scalaz.stream" %% "scalaz-stream" % "0.8.4a",

  "io.netty"          %  "netty-codec"   % "4.0.40.Final",

  "io.netty"          %  "netty-transport-native-epoll"   % "4.0.40.Final",

  "io.netty"          %  "netty-transport-native-epoll" % "4.0.40.Final" classifier "linux-x86_64",

  "org.scodec"        %% "scodec-bits"   % "1.1.0")

libraryDependencies ++= Seq(
  "org.specs2"     %% "specs2-core" % "3.8.4"  % "test",
  "org.scalacheck" %% "scalacheck"  % "1.13.0" % "test")

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/"))

publishMavenStyle := true

versionWithGit

git.baseVersion := "master"

bintraySettings

bintrayOrganization in bintray := Some("rr")

repository in bintray := (if (version.value startsWith "master") "snapshots" else "releases")
