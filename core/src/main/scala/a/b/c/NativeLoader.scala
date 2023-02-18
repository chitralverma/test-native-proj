package a.b.c

import java.nio.file._

class NativeLoader(nativeLibrary: String) {
  NativeLoader.load(nativeLibrary)
}

object NativeLoader {
  def load(nativeLibrary: String): Unit = {
    def loadPackaged(arch: String): Unit = {
      val lib: String = System.mapLibraryName(nativeLibrary)
//      val resourcePath: String = Paths.get("/native", arch, lib).toString
      val resourcePath: String = s"/native/$arch/$lib"

      println(("resourcePath", resourcePath))

      import io.github.classgraph.ClassGraph

      import scala.jdk.CollectionConverters._
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
            s"Native library $lib ($resourcePath) cannot be found on the classpath."
          )
      }

      val tmp: Path = Files.createTempDirectory("jni-")
      val extractedPath = tmp.resolve(lib)

      try
        Files.copy(resourceStream, extractedPath)
      catch {
        case ex: Exception =>
          throw new UnsatisfiedLinkError(
            s"Error while extracting native library:\n$ex"
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
