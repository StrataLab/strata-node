package co.topl.models

import co.topl.models.utility.HasLength.implicits._
import co.topl.models.utility.Lengths._
import co.topl.models.utility.StringDataTypes.Latin1Data
import co.topl.models.utility.{Length, Lengths, Ratio, Sized}
import org.scalacheck.Gen
import org.scalacheck.Gen.posNum

trait ModelGenerators {

  def epochNonceGen: Gen[Eta] =
    Gen.long.map(BigInt(_).toByteArray).map(Bytes(_))

  def relativeStakeGen: Gen[Ratio] =
    Gen.chooseNum(1L, 5L).flatMap(denominator => Ratio(1L, denominator))

  def vrfSecretGen: Gen[KeyPairs.Vrf] =
    for {
      publicKey  <- genSizedStrictBytes[Lengths.`32`.type]().map(PublicKeys.Ed25519(_)).map(PublicKeys.Vrf)
      privateKey <- genSizedStrictBytes[Lengths.`32`.type]().map(PrivateKeys.Ed25519(_)).map(PrivateKeys.Vrf)
    } yield KeyPairs.Vrf(privateKey, publicKey)

  def mmmProofGen: Gen[Proofs.Consensus.MMM] =
    for {
      sigi   <- genSizedStrictBytes[Lengths.`704`.type]().map(_.data)
      sigm   <- genSizedStrictBytes[Lengths.`704`.type]().map(_.data)
      pki    <- genSizedStrictBytes[Lengths.`32`.type]().map(_.data)
      offset <- posNum[Long]
      pkl    <- genSizedStrictBytes[Lengths.`32`.type]().map(_.data)
    } yield Proofs.Consensus.MMM(sigi, sigm, pki, offset, pkl)

  def vrfCertificateGen: Gen[VrfCertificate] =
    for {
      publicKey  <- genSizedStrictBytes[Lengths.`32`.type]().map(PublicKeys.Ed25519(_)).map(PublicKeys.Vrf)
      nonceProof <- genSizedStrictBytes[Lengths.`80`.type]().map(Proofs.Consensus.Nonce(_))
      testProof  <- genSizedStrictBytes[Lengths.`80`.type]().map(Proofs.Consensus.VrfTest(_))
    } yield VrfCertificate(publicKey, nonceProof, testProof)

  def kesCertificateGen: Gen[KesCertificate] =
    for {
      publicKey <- genSizedStrictBytes[Lengths.`32`.type]().map(PublicKeys.Kes(_, 0))
      kesProof  <- genSizedStrictBytes[Lengths.`64`.type]().map(Proofs.Consensus.KesCertificate(_))
      mmmProof  <- mmmProofGen
    } yield KesCertificate(publicKey, kesProof, mmmProof)

  def taktikosAddressGen: Gen[TaktikosAddress] =
    for {
      paymentVerificationKeyHash <- genSizedStrictBytes[Lengths.`32`.type]()
      stakingVerificationKey     <- genSizedStrictBytes[Lengths.`32`.type]()
      signature                  <- genSizedStrictBytes[Lengths.`64`.type]()
    } yield TaktikosAddress(paymentVerificationKeyHash, stakingVerificationKey, signature)

  def headerGen(
    parentHeaderIdGen: Gen[TypedIdentifier] =
      genSizedStrictBytes[Lengths.`32`.type]().map(sized => TypedBytes(IdentifierTypes.Block.HeaderV2, sized.data)),
    txRootGen:         Gen[TxRoot] = genSizedStrictBytes[Lengths.`32`.type](),
    bloomFilterGen:    Gen[BloomFilter] = genSizedStrictBytes[Lengths.`256`.type](),
    timestampGen:      Gen[Timestamp] = Gen.chooseNum(0L, 500L),
    heightGen:         Gen[Long] = Gen.chooseNum(0L, 20L),
    slotGen:           Gen[Slot] = Gen.chooseNum(0L, 50L),
    vrfCertificateGen: Gen[VrfCertificate] = vrfCertificateGen,
    kesCertificateGen: Gen[KesCertificate] = kesCertificateGen,
    thresholdEvidenceGen: Gen[Evidence] = genSizedStrictBytes[Lengths.`32`.type]().map(b =>
      Sized.strict[TypedBytes, Lengths.`33`.type](TypedBytes(1: Byte, b.data)).toOption.get
    ),
    metadataGen: Gen[Option[Sized.Max[Latin1Data, Lengths.`32`.type]]] = Gen.option(
      Gen
        .containerOfN[Array, Byte](32, Gen.choose[Byte](0, 32))
        .map(Latin1Data(_))
        .map(Sized.max[Latin1Data, Lengths.`32`.type](_).toOption.get)
    ),
    addressGen: Gen[TaktikosAddress] = taktikosAddressGen
  ): Gen[BlockHeaderV2] =
    for {
      parentHeaderID <- parentHeaderIdGen
      txRoot         <- txRootGen
      bloomFilter    <- bloomFilterGen
      timestamp      <- timestampGen
      height         <- heightGen
      slot           <- slotGen
      vrfCertificate <- vrfCertificateGen
      kesCertificate <- kesCertificateGen
      threshold      <- thresholdEvidenceGen
      metadata       <- metadataGen
      address        <- addressGen
    } yield BlockHeaderV2(
      parentHeaderID,
      txRoot,
      bloomFilter,
      timestamp,
      height,
      slot,
      vrfCertificate,
      kesCertificate,
      threshold,
      metadata,
      address
    )

  def genSizedMaxBytes[L <: Length](
    byteGen:    Gen[Byte] = Gen.choose[Byte](0, 32)
  )(implicit l: L): Gen[Sized.Max[Bytes, L]] =
    Gen
      .containerOfN[Array, Byte](l.value, byteGen)
      .map(Bytes(_))
      .map(Sized.max[Bytes, L](_).toOption.get)

  def genSizedStrictBytes[L <: Length](
    byteGen:    Gen[Byte] = Gen.choose[Byte](0, 32)
  )(implicit l: L): Gen[Sized.Strict[Bytes, L]] =
    Gen
      .containerOfN[Array, Byte](l.value, byteGen)
      .map(Bytes(_))
      .map(Sized.strict[Bytes, L](_).toOption.get)
}

object ModelGenerators extends ModelGenerators
