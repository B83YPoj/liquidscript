organization := "hack"

name := "hack"

version := "0.1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.+",
  "com.wavesplatform" %% "scorex-transaction" % "1.4.6",
  "org.slf4j" % "slf4j-api" % "1.+",
  "com.typesafe.play" %% "play-json" % "2.+",
  "org.scalaj" %% "scalaj-http" % "2.3.0"
)
scalacOptions ++= Seq("-Xdisable-assertions")

licenses := Seq("CC0" -> url("https://creativecommons.org/publicdomain/zero/1.0/legalcode"))

assemblyMergeStrategy in assembly := {
  case PathList("javax", "servlet", xs@_*) => MergeStrategy.first
  case PathList(ps@_*) if ps.last endsWith ".html" => MergeStrategy.first
  case "application.conf" => MergeStrategy.concat
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

mainClass in assembly := Some("Main")


