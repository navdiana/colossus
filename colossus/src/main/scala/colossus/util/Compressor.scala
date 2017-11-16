package colossus.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip._

import akka.util.{ByteString, ByteStringBuilder}

trait Compressor {
  def compress(bytes: ByteString): ByteString

  def decompress(bytes: ByteString): ByteString
}

object NoCompressor extends Compressor {
  def compress(bytes: ByteString): ByteString = bytes

  def decompress(bytes: ByteString): ByteString = bytes
}

class ZCompressor(bufferKB: Int = 10) extends Compressor {
  val buffer = new Array[Byte](1024 * bufferKB)

  def compress(bytes: ByteString): ByteString = {
    val deflater = new Deflater
    deflater.setInput(bytes.toArray)
    deflater.finish()
    val builder = new ByteStringBuilder
    var numread = 0
    do {
      numread = deflater.deflate(buffer)
      builder.putBytes(buffer, 0, numread)
    } while (numread > 0)
    deflater.end()
    builder.result()
  }

  def decompress(bytes: ByteString): ByteString = {
    val inflater = new Inflater
    inflater.setInput(bytes.toArray)
    val builder = new ByteStringBuilder
    var numread = 0
    do {
      numread = inflater.inflate(buffer)
      builder.putBytes(buffer, 0, numread)
    } while (numread > 0)
    inflater.end()
    builder.result()
  }

}

class GzipCompressor(bufferKB: Int = 10) extends Compressor {
  override def compress(bytes: ByteString): ByteString = {
    val bos = new ByteArrayOutputStream(bytes.length)
    val gzip = new GZIPOutputStream(bos)
    gzip.write(bytes.toArray)
    gzip.close()
    val compressed = bos.toByteArray
    bos.close()
    ByteString(compressed)
  }

  override def decompress(bytes: ByteString): ByteString = {
    val inputStream = new GZIPInputStream(new ByteArrayInputStream(bytes.toArray))
    val buffer: Array[Byte] = new Array[Byte](1024 * bufferKB)
    var read: Int = 0
    val builder = new ByteStringBuilder

    do {
      read = inputStream.read(buffer)
      builder.putBytes(buffer, 0, read)
    } while (read > 0)

    inputStream.close()
    builder.result()
  }
}

