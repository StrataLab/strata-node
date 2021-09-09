package co.topl.stakeholder.remote

import co.topl.network.message.Message.MessageCode
import co.topl.network.message.MessageSpecV1
import co.topl.utils.serialization.{Reader, Writer}
import co.topl.stakeholder.components.SerializationMethods
import co.topl.stakeholder.components.Serializer.DeserializeMac
import co.topl.stakeholder.primitives.{ByteStream, Mac}
import co.topl.stakeholder.remote.SpecTypes.requestBlockCode

/**
 * AMS 2020:
 * Holders build tines by requesting individual block ids
 */

object RequestBlockSpec extends MessageSpecV1[(Mac, Array[Byte])] with SerializationMethods {
  override val messageCode: MessageCode = requestBlockCode
  override val messageName: String = "Request Block"

  override def parse(r: Reader): (Mac, Array[Byte]) = {
    val mac = {
      fromBytes(new ByteStream(r.getBytes(mac_length), DeserializeMac)) match {
        case result: Mac @unchecked => result
      }
    }
    (mac, r.getBytes(r.remaining))
  }

  override def serialize(obj: (Mac, Array[Byte]), w: Writer): Unit = {
    w.putBytes(getBytes(obj._1))
    w.putBytes(obj._2)
  }
}
