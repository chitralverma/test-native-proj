package a.b.c

import java.nio.file._

class NativeLoader(nativeLibrary: String) {
  NativeLoader.load(nativeLibrary)
}

object NativeLoader {
  def load(nativeLibrary: String): Unit = {
    def loadPackaged(arch: String): Unit = {
      val lib: String = System.mapLibraryName(nativeLibrary)
      val resourcePath: String = Paths.get("/native", arch, lib).toString

      println(("resourcePath", resourcePath))

      import io.github.classgraph.ClassGraph
      import scala.collection.JavaConverters._
      new ClassGraph().enableAllInfo.scan.getAllResources.asScala
        .map(_.getPath)
        .filter(_.contains("native"))
        .foreach(println)

      val resourceStream = Option(
        this.getClass.getResourceAsStream(resourcePath)
      ) match {
        case Some(s) => s
        case None =>
          throw new UnsatisfiedLinkError(
            "Native library " + lib + " (" + resourcePath + ") cannot be found on the classpath."
          )
      }

      val tmp: Path = Files.createTempDirectory("jni-")
      val extractedPath = tmp.resolve(lib)

      try
        Files.copy(resourceStream, extractedPath)
      catch {
        case ex: Exception =>
          throw new UnsatisfiedLinkError(
            "Error while extracting native library: " + ex
          )
      }

      System.load(extractedPath.toAbsolutePath.toString)
    }

    def load(): Unit = try
      System.loadLibrary(nativeLibrary)
    catch {
      case e: Throwable =>
        try
          loadPackaged("aarch64")
        catch {
          case t: Throwable =>
            t.addSuppressed(e)
            try
              loadPackaged("x86_64")
            catch {
              case ex: Throwable =>
                ex.addSuppressed(t)
                throw new IllegalStateException(
                  s"Unable to load the provided native library '$nativeLibrary'.",
                  ex
                )
            }
        }

    }

    load()
  }
}
