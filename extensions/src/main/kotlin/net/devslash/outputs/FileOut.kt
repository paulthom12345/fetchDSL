package net.devslash.outputs

import net.devslash.*
import java.io.File


class FileOut(private val fileName: String, val out: OutputFormat = DefaultOutput()) : BasicOutput {
  val lock = Object()

  override fun accept(resp: HttpResponse, data: RequestData) {
    synchronized(lock) {
      val f = File(fileName.asReplaceableValue().get(data))
      val output = out.accept(resp, data)
      if (output != null) {
        f.writeBytes(output)
      }
    }
  }
}
