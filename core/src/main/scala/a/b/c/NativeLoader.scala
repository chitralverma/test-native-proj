package a.b.c

import java.nio.file.{Files, Path}

class NativeLoader(nativeLibrary: String) {
  NativeLoader.load(nativeLibrary)
}

object NativeLoader {
  def load(nativeLibrary: String): Unit = {
    def loadPackaged(arch: String): Unit = {
      val lib: String = System.mapLibraryName(nativeLibrary)

      val tmp: Path = Files.createTempDirectory("jni-")

      val resourcePath: String = s"/native/$arch/$lib"

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
      case _: Throwable =>
        try
          loadPackaged("aarch64")
        catch {
          case _: Throwable =>
            try
              loadPackaged("x86_64")
            catch {
              case ex: Throwable =>
                throw new IllegalStateException(
                  s"Unable to load the provided native library '$nativeLibrary'."
                )
            }
        }

    }

    load()
  }
}
