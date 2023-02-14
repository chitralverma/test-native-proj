import scala.sys.process._

ThisBuild / versionScheme := Some("early-semver")

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
  .settings(publishArtifact := false, publish / skip := true)
  .aggregate(core)

/*
 ***********************
 * Core Module *
 ***********************
 */

def distinctBy[A, B](xs: List[A])(f: A => B): List[A] =
  scala.reflect.internal.util.Collections.distinctBy(xs)(f)

val targetTriple = s"rustc -vV".!!.split("\n")
  .map(_.trim)
  .find(_.startsWith("host"))
  .map(_.split(" ")(1).trim)
  .getOrElse(new IllegalStateException("No target triple found."))

val generateNativeLibrary = taskKey[Seq[(File, String)]](
  "Generates Native library using Cargo and adds it as managed resource to classpath."
)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(javaTargetSettings: _*)
  .settings(name := "core")
  .settings(
    libraryDependencies += "io.github.classgraph" % "classgraph" % "4.8.154"
  )
  .settings(
    inConfig(Compile)(settings),
    inConfig(Test)(settings)
  )

lazy val settings = Seq(
  generateNativeLibrary := Def
    .taskDyn[Seq[(File, String)]] {
      Def.task {
        val logger = streams.value.log

        val processLogger = ProcessLogger(
          (o: String) => logger.info(o),
          (e: String) => logger.info(e)
        )

        val nativeOutputDir = resourceManaged.value.toPath.resolve(s"native/$targetTriple/")
        val cargoTomlPath = s"${baseDirectory.value}/../native/Cargo.toml"

        val buildCommand = s"""cargo build
                              |--release
                              |--manifest-path $cargoTomlPath
                              |-Z unstable-options
                              |--out-dir $nativeOutputDir
                              |""".stripMargin

        logger.info(
          s"Building library with Cargo using command:\n${buildCommand.replaceAll("\n", " ")}"
        )
        buildCommand !! processLogger

        resourceManaged.value.toPath
          .resolve(s"native/$targetTriple/")
          .toFile
          .listFiles()
          .map(library => s"/native/${library.name}" -> library)
          .toMap
          .map { case (resourcePath, file) =>
            logger.info(
              s"Adding resource from location $file to class path at location $resourcePath"
            )
            (file, resourcePath)
          }
          .toSeq
      }
    }
    .value,
  resourceGenerators += Def.task {
    val libraries: Seq[(File, String)] = generateNativeLibrary.value
    val resources: Seq[File] = for ((file, path) <- libraries) yield {

      // native library as a managed resource file
      val resource = resourceManaged.value / path

      // copy native library to a managed resource, so that it is always available
      // on the classpath, even when not packaged as a jar
      IO.copyFile(file, resource)
      resource
    }
    resources
  }.taskValue
)
