package co.topl.keyManagement

import co.topl.attestation.{PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.crypto.signatures.{Curve25519, PrivateKey, PublicKey}
import co.topl.utils.AsBytes.implicits._
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}
import cats.implicits._

case class PrivateKeyCurve25519(private val privateKey: PrivateKey, private val publicKey: PublicKey) extends Secret {

  private val privateKeyLength = privateKey.asBytes.length
  private val publicKeyLength = publicKey.asBytes.length

  require(privateKeyLength == Curve25519.KeyLength, s"$privateKeyLength == ${Curve25519.KeyLength}")
  require(publicKeyLength == Curve25519.KeyLength, s"$publicKeyLength == ${Curve25519.KeyLength}")

  override type S = PrivateKeyCurve25519
  override type PK = PublicKeyPropositionCurve25519
  override type PR = SignatureCurve25519
  override type KF = KeyfileCurve25519

  override lazy val serializer: BifrostSerializer[PrivateKeyCurve25519] = PrivateKeyCurve25519

  override lazy val publicImage: PublicKeyPropositionCurve25519 = PublicKeyPropositionCurve25519(publicKey)

  override def sign(message: Array[Byte]): SignatureCurve25519 = SignatureCurve25519(
    Curve25519.sign(privateKey, message)
  )

  override def equals(obj: Any): Boolean = obj match {
    case sk: PrivateKeyCurve25519 => sk.privateKey === privateKey
    case _                        => false
  }
}

object PrivateKeyCurve25519 extends BifrostSerializer[PrivateKeyCurve25519] {

  implicit val secretGenerator: SecretGenerator[PrivateKeyCurve25519] =
    SecretGenerator.instance[PrivateKeyCurve25519] { seed: Array[Byte] =>
      val (sk, pk) = Curve25519.createKeyPair(seed)
      val secret: PrivateKeyCurve25519 = PrivateKeyCurve25519(sk, pk)
      secret -> secret.publicImage
    }

  override def serialize(obj: PrivateKeyCurve25519, w: Writer): Unit = {
    /* : Array[Byte] */
    w.putBytes(obj.privateKey)

    /* publicKeyBytes: Array[Byte] */
    w.putBytes(obj.publicKey)
  }

  override def parse(r: Reader): PrivateKeyCurve25519 =
    PrivateKeyCurve25519(PrivateKey(r.getBytes(Curve25519.KeyLength)), PublicKey(r.getBytes(Curve25519.KeyLength)))

}
