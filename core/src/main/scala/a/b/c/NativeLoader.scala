package a.b.c

import scala.sys.process._

import java.nio.file.{Files, Path}

class NativeLoader(nativeLibrary: String) {
  NativeLoader.load(nativeLibrary)
}

object NativeLoader {
  def load(nativeLibrary: String): Unit = {
    def loadPackaged(): Unit = {

      val lib: String = System.mapLibraryName(nativeLibrary)

      val tmp: Path = Files.createTempDirectory("jni-")
      val plat: String = {
        s"rustc -vV".!!
          .split("\n")
          .find(_.startsWith("host: "))
          .map(_.split(" ")(1).trim)
          .get
      }

      val resourcePath: String = "/native/" + plat + "/" + lib
      val resourceStream = Option(this.getClass.getResourceAsStream(resourcePath)) match {
        case Some(s) => s
        case None =>
          throw new UnsatisfiedLinkError(
            "Native library " + lib + " (" + resourcePath + ") cannot be found on the classpath."
          )
      }

      val extractedPath = tmp.resolve(lib)

      try {
        Files.copy(resourceStream, extractedPath)
      } catch {
        case ex: Exception => throw new UnsatisfiedLinkError("Error while extracting native library: " + ex)
      }

      System.load(extractedPath.toAbsolutePath.toString)
    }

    def load(): Unit = try {
      System.loadLibrary(nativeLibrary)
    } catch {
      case _: UnsatisfiedLinkError => loadPackaged()
    }

    load()
  }
}
