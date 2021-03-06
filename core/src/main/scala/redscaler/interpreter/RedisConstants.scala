package redscaler.interpreter

object RedisConstants {
  val DOLLAR_BYTE: Byte   = '$'
  val ASTERISK_BYTE: Byte = '*'
  val PLUS_BYTE: Byte     = '+'
  val MINUS_BYTE: Byte    = '-'
  val COLON_BYTE: Byte    = ':'
  val CR: Byte            = '\r'
  val LF: Byte            = '\n'
  val CRLF: Vector[Byte]  = Vector[Byte](CR, LF)

  def intCrlf(i: Int): Vector[Byte] =
    i.toString.toVector.map(_.toByte) ++ CRLF
}
