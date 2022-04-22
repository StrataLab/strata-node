package co.topl.codecs.json.modifier.block

import co.topl.attestation.{PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.codecs.binary._
import co.topl.codecs.json.crypto._
import co.topl.codecs.json.modifier.box._
import co.topl.codecs.json.modifier.transaction._
import co.topl.codecs.json.{
  deriveDecoderFromScodec,
  deriveEncoderFromScodec,
  deriveKeyDecoderFromScodec,
  deriveKeyEncoderFromScodec
}
import co.topl.codecs.{
  base58JsonDecoder,
  proofJsonDecoder,
  proofJsonEncoder,
  propositionJsonDecoder,
  propositionJsonEncoder
}
import co.topl.crypto.hash.digest.Digest32
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.block.{Block, BlockBody, BlockHeader, BloomFilter}
import co.topl.modifier.box.ArbitBox
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.{ModifierId, NodeViewModifier}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.StringDataTypes.Base58Data
import co.topl.utils.TimeProvider
import co.topl.utils.encode.Base58
import com.google.common.primitives.Longs
import io.circe._
import io.circe.syntax._

trait BlockJsonCodecs {

  private val modifierIdTypeName = "Modifier ID"
  private val bloomFilterTypeName = "Bloom Filter"

  implicit val modifierIdJsonEncoder: Encoder[ModifierId] = deriveEncoderFromScodec(modifierIdTypeName)

  implicit val modifierIdJsonKeyEncoder: KeyEncoder[ModifierId] = deriveKeyEncoderFromScodec(modifierIdTypeName)

  implicit val modifierIdJsonDecoder: Decoder[ModifierId] = deriveDecoderFromScodec(modifierIdTypeName)

  implicit val modifierIdJsonKeyDecoder: KeyDecoder[ModifierId] = deriveKeyDecoderFromScodec(modifierIdTypeName)

  implicit val blockJsonEncoder: Encoder[Block] = { b: Block =>
    val (header, body) = b.toComponents
    Map(
      "header"    -> header.asJson,
      "body"      -> body.asJson,
      "blockSize" -> (b: NodeViewModifier).persistedBytes.length.asJson
    ).asJson
  }

  implicit def blockJsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[Block] = (c: HCursor) =>
    for {
      header <- c.downField("header").as[BlockHeader]
      body   <- c.downField("body").as[BlockBody]
    } yield Block.fromComponents(header, body)

  implicit val blockBodyJsonEncoder: Encoder[BlockBody] = { b: BlockBody =>
    Map(
      "id"       -> b.id.asJson,
      "parentId" -> b.parentId.asJson,
      "txs"      -> b.transactions.asJson,
      "version"  -> b.version.asJson
    ).asJson
  }

  implicit def blockBodyJsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[BlockBody] = (c: HCursor) =>
    for {
      id       <- c.downField("id").as[ModifierId]
      parentId <- c.downField("parentId").as[ModifierId]
      txsSeq   <- c.downField("txs").as[Seq[Transaction.TX]]
      version  <- c.downField("version").as[PNVMVersion]
    } yield BlockBody(id, parentId, txsSeq, version)

  implicit val blockHeaderJsonEncoder: Encoder[BlockHeader] = { bh: BlockHeader =>
    Map(
      "id"           -> bh.id.asJson,
      "parentId"     -> bh.parentId.asJson,
      "timestamp"    -> bh.timestamp.asJson,
      "generatorBox" -> bh.generatorBox.asJson,
      "publicKey"    -> bh.publicKey.asJson(propositionJsonEncoder),
      "signature"    -> proofJsonEncoder(bh.signature).asJson,
      "height"       -> bh.height.asJson,
      "difficulty"   -> bh.difficulty.asJson,
      "txRoot"       -> bh.txRoot.asJson,
      "bloomFilter"  -> bh.bloomFilter.asJson,
      "version"      -> bh.version.asJson
    ).asJson
  }

  implicit val blockHeaderDecoder: Decoder[BlockHeader] = (c: HCursor) =>
    for {
      id           <- c.downField("id").as[ModifierId]
      parentId     <- c.downField("parentId").as[ModifierId]
      timestamp    <- c.downField("timestamp").as[TimeProvider.Time]
      generatorBox <- c.downField("generatorBox").as[ArbitBox]
      publicKey <- c.downField("publicKey").as(propositionJsonDecoder).flatMap {
        case pubKey: PublicKeyPropositionCurve25519 => Right(pubKey)
        case _                                      => Left(DecodingFailure("Not a Curve25519 publicKey", Nil))
      }
      signature <- c.downField("signature").as(proofJsonDecoder).flatMap {
        case sig: SignatureCurve25519 => Right(sig)
        case _                        => Left(DecodingFailure("not a Curve25519 signature", Nil))
      }
      height      <- c.downField("height").as[Long]
      difficulty  <- c.downField("difficulty").as[Long]
      txRoot      <- c.downField("txRoot").as[Digest32]
      bloomFilter <- c.downField("bloomFilter").as[BloomFilter]
      version     <- c.downField("version").as[Byte]
    } yield BlockHeader(
      id,
      parentId,
      timestamp,
      generatorBox,
      publicKey,
      signature,
      height,
      difficulty,
      txRoot,
      bloomFilter,
      version
    )

  implicit val bloomFilterJsonEncoder: Encoder[BloomFilter] = (b: BloomFilter) =>
    Base58.encode(b.value.flatMap(Longs.toByteArray)).asJson

  implicit val bloomFilterJsonDecoder: Decoder[BloomFilter] = Decoder[Base58Data].map(fromBase58)

  private def fromBase58(data: Base58Data): BloomFilter =
    new BloomFilter(data.value.grouped(Longs.BYTES).map(Longs.fromByteArray).toArray)
}
