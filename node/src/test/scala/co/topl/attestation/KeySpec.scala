package co.topl.attestation

import co.topl.utils.codecs.implicits._
import co.topl.attestation.AddressCodec.implicits._
import co.topl.utils.StringDataTypes.Latin1Data
import co.topl.utils.{KeyFileTestHelper, NodeGenerators}
import co.topl.utils.NodeGenerators
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class KeySpec extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with NodeGenerators with Matchers {

  var password: Latin1Data = _
  var messageByte: Array[Byte] = _

  var addressCurve25519: Address = _
  var addressEd25519: Address = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    password = Latin1Data.unsafe(sampleUntilNonEmpty(stringGen))
    messageByte = sampleUntilNonEmpty(nonEmptyBytesGen)

    import org.scalatest.TryValues._

    addressCurve25519 = keyRingCurve25519.DiskOps.generateKeyFile(password).success.value
    addressEd25519 = keyRingEd25519.DiskOps.generateKeyFile(password).success.value
  }

  property("The randomly generated address from generateKeyFile should exist in keyRing") {
    keyRingCurve25519.addresses.contains(addressCurve25519) shouldBe true

    keyRingEd25519.addresses.contains(addressEd25519) shouldBe true
  }

  property("Once we lock the generated address, it will be removed from the secrets set in the keyRing") {
    keyRingCurve25519.removeFromKeyring(addressCurve25519)

    /** There will be a warning for locking again if a key is already locked */
    keyRingCurve25519.removeFromKeyring(addressCurve25519)
    keyRingCurve25519.addresses.contains(addressCurve25519) shouldBe false

    keyRingEd25519.removeFromKeyring(addressEd25519)

    /** There will be a warning for locking again if a key is already locked */
    keyRingEd25519.removeFromKeyring(addressEd25519)
    keyRingEd25519.addresses.contains(addressEd25519) shouldBe false
  }

  property("Once unlocked, the address will be accessible from the keyRing again") {
    keyRing.DiskOps.unlockKeyFile(address.encodeAsBase58, password)

    /** There will be a warning for unlocking again if a key is already unlocked */
    keyRing.DiskOps.unlockKeyFile(address.encodeAsBase58, password)

    keyRingEd25519.DiskOps.unlockKeyFile(addressEd25519.toString, password)

    /** There will be a warning for unlocking again if a key is already unlocked */
    keyRingEd25519.DiskOps.unlockKeyFile(addressEd25519.toString, password)
    keyRingEd25519.addresses.contains(addressEd25519) shouldBe true
  }

  property("LookupPublickKey should return the correct public key to the address") {
    keyRingCurve25519.lookupPublicKey(addressCurve25519).get.address shouldEqual addressCurve25519

    keyRingEd25519.lookupPublicKey(addressEd25519).get.address shouldEqual addressEd25519
  }

  property("The proof generated by signing the message Bytes with address should be valid") {
    val proofCurve25519 = keyRingCurve25519.signWithAddress(addressCurve25519)(messageByte).get
    val propCurve25519 = keyRingCurve25519.lookupPublicKey(addressCurve25519).get
    proofCurve25519.isValid(propCurve25519, messageByte) shouldBe true

    val proofEd25519 = keyRingEd25519.signWithAddress(addressEd25519)(messageByte).get
    val propEd25519 = keyRingEd25519.lookupPublicKey(addressEd25519).get
    proofEd25519.isValid(propEd25519, messageByte) shouldBe true
  }

  property("Trying to sign a message with an address not on the keyRing will fail") {
    val randAddrCurve25519: Address = addressCurve25519Gen.sample.get
    val errorCurve25519 = intercept[Exception](keyRingCurve25519.signWithAddress(randAddrCurve25519)(messageByte))
    errorCurve25519.getMessage shouldEqual "Unable to find secret for the given address"

    val randAddrEd25519: Address = addressEd25519Gen.sample.get
    val errorEd25519 = intercept[Exception](keyRingEd25519.signWithAddress(randAddrEd25519)(messageByte))
    errorEd25519.getMessage shouldEqual "Unable to find secret for the given address"
  }

  property("The proof from signing with an address should only be valid for the corresponding proposition") {
    val propCurve25519 = keyRingCurve25519.lookupPublicKey(addressCurve25519).get
    val newAddrCurve25519: Address = keyRingCurve25519.DiskOps.generateKeyFile(stringGen.sample.get).get
    val newPropCurve25519 = keyRingCurve25519.lookupPublicKey(newAddrCurve25519).get
    val newProofCurve25519 = keyRingCurve25519.signWithAddress(newAddrCurve25519)(messageByte).get
    newProofCurve25519.isValid(propCurve25519, messageByte) shouldBe false
    newProofCurve25519.isValid(newPropCurve25519, messageByte) shouldBe true

    val newAddr: Address = keyRing.DiskOps.generateKeyFile(Latin1Data.unsafe(stringGen.sample.get)).get
    val newProp = keyRing.lookupPublicKey(newAddr).get
    val newProof = keyRing.signWithAddress(newAddr)(messageByte).get

    newProof.isValid(prop, messageByte) shouldBe false
    newProof.isValid(newProp, messageByte) shouldBe true
  }

  //TODO: Jing - test importPhrase
}
