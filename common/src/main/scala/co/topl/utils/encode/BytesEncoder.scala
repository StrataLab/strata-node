package co.topl.utils.encode

import co.topl.utils.AsBytes

import scala.util.Try

/* Forked from https://github.com/ScorexFoundation/scorex-util/tree/master/src/main/scala/scorex/util/encode */

trait BytesEncoder {
  // allowed characters
  val Alphabet: String

  // encode Array[Byte] to String
  def encode[V: AsBytes](input: V): String

  // decode string to Array[Byte]. Return Failure on incorrect character in input
  def decode(input: String): Try[Array[Byte]]
}
