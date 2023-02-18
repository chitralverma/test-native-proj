import java.nio.file._

import scala.collection.JavaConverters._
import scala.sys.process._

ThisBuild / publish / skip := true
ThisBuild / publishArtifact := false

/*
 ***********************
 * Constants *
 ***********************
 */

val scala212 = "2.12.17"
val scala213 = "2.13.10"
val scala32 = "3.2.1"

val defaultScalaVersion = scala213
val supportedScalaVersions = Seq(scala212, scala213)

def priorTo213(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _ => false
  }

val generateNativeLibrary = taskKey[Unit](
  "Generates native library using Cargo which can be added as managed resource to classpath."
)

val managedNativeLibraries = taskKey[Seq[Path]](
  "Maps locally built, platform-dependant libraries to their locations on the classpath."
)

/*
 ***********************
 * Core Module *
 ***********************
 */
lazy val core = project
  .in(file("core"))
  .settings(generalProjectSettings)
  .settings(scalaSettings)
//  .settings(artifactNameSettings)
  .settings(publishSettings)
  .settings(
    inConfig(Compile)(nativeResourceSettings),
    inConfig(Test)(nativeResourceSettings)
  )

/*
 ***********************
 * Core Module Settings *
 ***********************
 */

lazy val generalProjectSettings = Seq(
  name := "core",
  organization := "a.b.c",
  versionScheme := Some("early-semver"),
  licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      id = "chitralverma",
      name = "Chitral Verma",
      email = "chitral.verma@gmail.com",
      url = url("https://github.com/chitralverma")
    )
  ),
  libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.8.1",
    "io.github.classgraph" % "classgraph" % "4.8.154")
)

lazy val scalaSettings = Seq(
  scalaVersion := defaultScalaVersion,
  crossScalaVersions := supportedScalaVersions,
  fork := true,
  turbo := true,
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
  ) ++ (if (priorTo213(scalaVersion.value)) Seq("-target:jvm-1.8")
        else Seq("-release", "8"))
)

lazy val nativeResourceSettings = Seq(
  generateNativeLibrary := Def
    .taskDyn[Unit] {
      Def.task {
        val logger = sLog.value

        sys.env.get("SKIP_NATIVE_GENERATION") match {
          case None =>
            val processLogger = ProcessLogger(
              (o: String) => logger.info(o),
              (e: String) => logger.info(e)
            )

            val targetTriple = sys.env.getOrElse(
              "TARGET_TRIPLE", {
                logger.warn(
                  "Environment variable TARGET_TRIPLE was not set, getting value from `rustc`."
                )

                s"rustc -vV".!!.split("\n")
                  .map(_.trim)
                  .find(_.startsWith("host"))
                  .map(_.split(" ")(1).trim)
                  .getOrElse(throw new IllegalStateException("No target triple found."))
              }
            )

            val arch = targetTriple.toLowerCase(java.util.Locale.ROOT).split("-").head

            val nativeOutputDir = resourceManaged.value.toPath.resolve(s"native/$arch/")
            val cargoTomlPath = s"${baseDirectory.value}/../native/Cargo.toml"

            // Build native project using cargo
            val buildCommand = s"""cargo build
                                  |-Z unstable-options
                                  |--release
                                  |--manifest-path $cargoTomlPath
                                  |--target $targetTriple
                                  |--out-dir $nativeOutputDir
                                  |""".stripMargin

            logger.info(
              s"Building native library with Cargo using command:" +
                s"\n${buildCommand.replaceAll("\n", " ")}"
            )

            buildCommand !! processLogger
            logger.info(s"Successfully built native library at location '$nativeOutputDir'")

            sys.env.get("NATIVE_LIB_LOCATION") match {
              case Some(path) =>
                val dest = Paths.get(path, arch).toAbsolutePath
                logger.info(
                  "Environment variable NATIVE_LIB_LOCATION is set, " +
                    s"copying built native library from location '$nativeOutputDir' to '$dest'."
                )

                IO.copyDirectory(nativeOutputDir.toFile, dest.toFile)

              case None =>
            }

          case Some(_) =>
            logger.info(
              "Environment variable SKIP_NATIVE_GENERATION is set, skipping cargo build."
            )
        }
      }
    }
    .value,
  managedNativeLibraries := Def
    .taskDyn[Seq[Path]] {
      Def.task {
        val managedLibs = sys.env.get("SKIP_NATIVE_GENERATION") match {
          case None =>
            Files
              .find(
                resourceManaged.value.toPath.resolve("native/"),
                Int.MaxValue,
                (filePath, _) => filePath.toFile.isFile
              )
              .iterator()
              .asScala
              .toSeq

          case Some(_) => Seq.empty[Path]
        }

        val externalNativeLibs = sys.env.get("NATIVE_LIB_LOCATION") match {
          case Some(path) =>
            Files
              .find(
                Paths.get(path),
                Int.MaxValue,
                (filePath, _) => filePath.toFile.isFile
              )
              .iterator()
              .asScala
              .toSeq

          case None => Seq.empty[Path]
        }

        // Collect paths of built resources to later include in classpath
        (managedLibs ++ externalNativeLibs).distinct.map(_.toAbsolutePath)
      }
    }
    .dependsOn(generateNativeLibrary)
    .value,
  resourceGenerators += Def.task {
    // Add all generated resources to manage resources' classpath
    managedNativeLibraries.value
      .map { path =>
        val pathStr = path.toString

        // TODO: Remove later if not required
        val arch =
          if (pathStr.contains("aarch64")) "aarch64"
          else if (pathStr.contains("x86_64")) "x86_64"
          else path.getParent.getFileName.toString

        val libraryFile = path.toFile

        // native library as a managed resource file
        val resource = resourceManaged.value / "native" / arch / libraryFile.getName

        // copy native library to a managed resource, so that it is always available
        // on the classpath, even when not packaged as a jar
        IO.copyDirectory(libraryFile, resource)

        sLog.value.info(
          s"Added resource from location '$pathStr' " +
            s"(size: ${libraryFile.length() / (1024 * 1024)} MBs) to classpath."
        )

        resource
      }
  }.taskValue
)

lazy val publishSettings = Seq(
  publish / skip := false,
  publishArtifact := true,
  publishMavenStyle := true,
  externalResolvers += "GitHub Package Registry" at "https://maven.pkg.github.com/chitralverma/test-native-proj",
  publishTo := Some(
    "GitHub Package Registry" at "https://maven.pkg.github.com/chitralverma/test-native-proj"
  ),
  credentials += Credentials(
    realm = "GitHub Package Registry",
    host = "maven.pkg.github.com",
    userName = "chitralverma",
    passwd = sys.env.getOrElse("GITHUB_TOKEN", "")
  )
)
