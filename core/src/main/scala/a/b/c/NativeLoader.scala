package a.b.c

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters

class NativeLoader(nativeLibrary: String) {
  NativeLoader.load(nativeLibrary)
}

object NativeLoader {
  def load(nativeLibrary: String): Unit = {
    def loadPackaged(): Unit = {

      val lib: String = System.mapLibraryName(nativeLibrary)

      val tmp: Path = Files.createTempDirectory("jni-")

      val resourcePath: String = "/native/" + lib

      val resourceStream = Option(
        this.getClass.getResourceAsStream(resourcePath)
      ) match {
        case Some(s) => s
        case None =>
          throw new UnsatisfiedLinkError(
            "Native library " + lib + " (" + resourcePath + ") cannot be found on the classpath."
          )
      }

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
      case _: UnsatisfiedLinkError => loadPackaged()
    }

    load()
  }
}
