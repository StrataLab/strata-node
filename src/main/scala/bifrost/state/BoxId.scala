package bifrost.state

import bifrost.modifier.ModifierId
import com.google.common.primitives.Ints
import scorex.crypto.encode.Base58

case class BoxId (hashBytes: Array[Byte]) {
  override def hashCode: Int = Ints.fromByteArray(hashBytes)

  override def equals(o: Any): Boolean = {
    o.isInstanceOf[ModifierId] &&
      java.util.Arrays.equals(hashBytes, o.asInstanceOf[ModifierId].hashBytes)
  }

  override def toString: String = Base58.encode(hashBytes)
}

object BoxId {
  val size = 32 // boxId is a 32 byte identifier
}
