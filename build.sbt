/*
 ***********************
 * Constants *
 ***********************
 */

val scala212 = "2.12.15"
val scala213 = "2.13.10"
val scala32 = "3.2.1"

val defaultScalaVersion = scala213
val allScalaVersions = Seq(scala212, scala213)

ThisBuild / scalaVersion := defaultScalaVersion
ThisBuild / turbo := true

def priorTo213(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _ => false
  }

lazy val javaTargetSettings = Seq(
  scalacOptions ++=
    (if (priorTo213(scalaVersion.value)) Seq("-target:jvm-1.8") else Seq("-release", "8"))
)

lazy val commonSettings = Seq(
  organization := "a.b.c",
  licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      id = "chitralverma",
      name = "Chitral Verma",
      email = "chitral.verma@gmail.com",
      url = url("https://github.com/chitralverma")
    )
  ),
  scalaVersion := defaultScalaVersion,
  crossScalaVersions := allScalaVersions,
  fork := true,
  scalacOptions ++= Seq(
    "-encoding",
    "utf8",
    "-deprecation",
    "-feature",
    "-language:existentials",
    "-language:implicitConversions",
    "-language:reflectiveCalls",
    "-language:higherKinds",
    "-language:postfixOps",
    "-unchecked",
    "-Xfatal-warnings"
  )
)

/*
 ***********************
 * Root Module *
 ***********************
 */

lazy val root = (project in file("."))
  .settings(crossScalaVersions := Nil)
  .aggregate(core, native)

/*
 ***********************
 * Native Module *
 ***********************
 */

lazy val native = project
  .in(file("native"))
  .settings(commonSettings: _*)
  .settings(javaTargetSettings: _*)
  .settings(
    name := "native",
    crossPaths := false,
    nativeCompile / sourceDirectory := baseDirectory.value
  )
  .enablePlugins(JniNative, JniPackage)

/*
 ***********************
 * Core Module *
 ***********************
 */


lazy val core = project
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(javaTargetSettings: _*)
  .settings(name := "core")
  .settings(
    sbtJniCoreScope := Compile,
    classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
  .dependsOn(native % Runtime)
