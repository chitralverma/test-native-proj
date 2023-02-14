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
lazy val targetTriple = sys.env.getOrElse(
  "TARGET_TRIPLE", {
    println("Environment Variable TARGET_TRIPLE was not set, getting value from `rustc`.")

    s"rustc -vV".!!.split("\n")
      .map(_.trim)
      .find(_.startsWith("host"))
      .map(_.split(" ")(1).trim)
      .getOrElse(throw new IllegalStateException("No target triple found."))
  }
)

lazy val targetClassifier = {
  val tgt = targetTriple.toLowerCase(java.util.Locale.ROOT)
  val arch = tgt.split("-").head

  val host =
    if (tgt.contains("linux")) "linux"
    else if (tgt.contains("windows")) if (tgt.contains("msvc")) "win-msvc" else "win-gnu"
    else if (tgt.contains("apple") || tgt.contains("darwin")) "darwin"

  s"$host-$arch"
}

val generateNativeLibrary = taskKey[Seq[(File, String)]](
  "Generates Native library using Cargo and adds it as managed resource to classpath."
)

lazy val core = project
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(javaTargetSettings: _*)
  .settings(name := "core")
  .settings(
    inConfig(Compile)(settings),
    inConfig(Test)(settings)
  )
  .settings(
    Compile / packageBin / artifact := {
      val prev: Artifact = (Compile / packageBin / artifact).value
      sLog.value.info(
        s"Building jar with classifier `$targetClassifier`."
      )

      prev.withClassifier(Some(targetClassifier))
    }
  )

lazy val settings = Seq(
  generateNativeLibrary := Def
    .taskDyn[Seq[(File, String)]] {
      Def.task {
        val logger = sLog.value

        val processLogger = ProcessLogger(
          (o: String) => logger.info(o),
          (e: String) => logger.info(e)
        )

        val nativeOutputDir = resourceManaged.value.toPath.resolve(s"native/$targetTriple/")
        val cargoTomlPath = s"${baseDirectory.value}/../native/Cargo.toml"

        val buildCommand = s"""cargo build
                              |-Z unstable-options
                              |--release
                              |--manifest-path $cargoTomlPath
                              |--target $targetTriple
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
              s"Adding resource from location $file " +
                s"(size: ${file.length() / (1024 * 1024)} MBs) " +
                s"to classpath at location $resourcePath"
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
