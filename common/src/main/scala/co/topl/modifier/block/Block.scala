package co.topl.modifier.block

import co.topl.attestation.EvidenceProducer.Syntax._
import co.topl.attestation.{PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.modifier.NodeViewModifier.ModifierTypeId
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.ArbitBox
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.{ModifierId, NodeViewModifier}
import co.topl.utils.IdiomaticScalaTransition.implicits.toEitherOps
import co.topl.utils.TimeProvider

import scala.util.Try

/**
 * A block is an atomic piece of data network participates are agreed on.
 *
 * A block has:
 * - transactional data: a sequence of transactions, where a transaction is an atomic state update.
 * Some metadata is possible as well(transactions Merkle tree root, state Merkle tree root etc).
 *
 * - consensus data to check whether block was generated by a right party in a right way. E.g.
 * "baseTarget" & "generatorSignature" fields in the Nxt block structure, nonce & difficulty in the
 * Bitcoin block structure.
 *
 * - a signature(s) of a block generator(s)
 *
 * - additional data: block structure version no, timestamp etc
 */
case class Block(
  parentId:     ModifierId,
  timestamp:    TimeProvider.Time,
  generatorBox: ArbitBox,
  publicKey:    PublicKeyPropositionCurve25519,
  signature:    SignatureCurve25519,
  height:       Long,
  difficulty:   Long,
  transactions: Seq[Transaction.TX],
  version:      PNVMVersion
) extends TransactionCarryingPersistentNodeViewModifier[Transaction.TX] {

  lazy val modifierTypeId: ModifierTypeId = Block.modifierTypeId

  lazy val id: ModifierId = ModifierId.create(this).getOrThrow()

  def toComponents: (BlockHeader, BlockBody) = Block.toComponents(this)

  def messageToSign: Array[Byte] =
    this.copy(signature = SignatureCurve25519.empty).bytes
}

object Block {

  val modifierTypeId: NodeViewModifier.ModifierTypeId = ModifierTypeId(3: Byte)

  /**
   * Deconstruct a block to its compoennts
   * @param block the block to decompose
   * @return a block header and block body
   */
  def toComponents(block: Block): (BlockHeader, BlockBody) = {
    val header: BlockHeader =
      BlockHeader(
        block.id,
        block.parentId,
        block.timestamp,
        block.generatorBox,
        block.publicKey,
        block.signature,
        block.height,
        block.difficulty,
        block.merkleTree.rootHash,
        block.bloomFilter,
        block.version
      )

    val body: BlockBody =
      BlockBody(
        block.id,
        block.parentId,
        block.transactions,
        block.version
      )

    (header, body)
  }

  /**
   * Creates a full block from the individual components
   * @param header the header information needed by the consensus layer
   * @param body the block body containing transactions
   * @return a full block
   */
  def fromComponents(header: BlockHeader, body: BlockBody): Block = {
    val (_, parentId, timestamp, forgerBox, publicKey, signature, height, difficulty, _, _, version) =
      BlockHeader.unapply(header).get

    val transactions = body.transactions

    Block(parentId, timestamp, forgerBox, publicKey, signature, height, difficulty, transactions, version)
  }

  /**
   * Creates a new block
   * @param parentId the id of the previous block
   * @param timestamp time this block was forged
   * @param txs a seqence of state modifiers
   * @param generatorBox the Arbit box that resulted in the successful hit
   * @param height the new height of the chain with this block
   * @param difficulty the new difficulty of the chain with this block
   * @param version a byte used to signal the serializer version to use for this block
   * @return a block to be sent to the network
   */
  def createAndSign(
    parentId:     ModifierId,
    timestamp:    TimeProvider.Time,
    txs:          Seq[Transaction.TX],
    generatorBox: ArbitBox,
    publicKey:    PublicKeyPropositionCurve25519,
    height:       Long,
    difficulty:   Long,
    version:      PNVMVersion
  )(signFunction: Array[Byte] => Try[SignatureCurve25519]): Try[Block] = {

    // the owner of the generator box must be the key used to sign the block
    require(generatorBox.evidence == publicKey.generateEvidence, "Attempted invalid block generation")

    // generate an unsigned block (block with empty signature) to be signed
    val block =
      Block(
        parentId,
        timestamp,
        generatorBox,
        publicKey,
        SignatureCurve25519.empty,
        height,
        difficulty,
        txs,
        version
      )

    // use the provided signing function to sign the block and return it
    signFunction(block.messageToSign).map(s => block.copy(signature = s))
  }
}
