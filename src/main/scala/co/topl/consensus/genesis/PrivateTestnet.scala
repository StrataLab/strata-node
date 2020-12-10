package co.topl.consensus.genesis

import co.topl.attestation.AddressEncoder.NetworkPrefix
import co.topl.attestation.EvidenceProducer.Syntax._
import co.topl.attestation.{PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.consensus.Forger.ChainParams
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer}
import co.topl.nodeView.history.History
import co.topl.nodeView.state.box.{ArbitBox, SimpleValue}
import co.topl.settings.{AppSettings, RuntimeOpts, Version}
import co.topl.utils.encode.encodeBase16

import scala.util.Try

case class PrivateTestnet ( keyGen  : (Int, Option[String]) => Set[PublicKeyPropositionCurve25519],
                            settings: AppSettings,
                            opts    : RuntimeOpts
                          )(implicit val networkPrefix: NetworkPrefix) extends GenesisProvider {

  override protected val blockChecksum: ModifierId = ModifierId.empty

  override protected val blockVersion: Version = settings.application.version

  override protected val members: Map[String, Long] = Map("Not implemented here" -> 0L)

  override def getGenesisBlock: Try[(Block, ChainParams)] = Try(formNewBlock)

  /**
   * We want a private network to have a brand new genesis block that is created at runtime. This is
   * done to allow the user to forge on their private network. Therefore, we need to generate a new set of keys
   * by making a call to the key manager holder to create a the set of forging keys. Once these keys are created,
   * we can use the public images to pre-fund the accounts from genesis.
   */
  val (numberOfKeys, balance, initialDifficulty) = settings.forging.privateTestnet.map { settings =>
      (settings.numTestnetAccts, settings.testnetBalance, settings.initialDifficulty)
  }.getOrElse(10, 1000000L, 1000000000000000000L)

  def formNewBlock: (Block, ChainParams) = {
    // map the members to their balances then continue as normal
    val privateTotalStake = numberOfKeys * balance

    val accts = keyGen(numberOfKeys, opts.seed)

    val txInput = (
      IndexedSeq(),
      (genesisAcct.publicImage.address -> SimpleValue(0L)) +:
        accts.map(_.address -> SimpleValue(balance)).toIndexedSeq,
      Map(genesisAcct.publicImage -> SignatureCurve25519.genesis),
      0L,
      0L,
      "",
      true)

    val txs = Seq(
      ArbitTransfer[PublicKeyPropositionCurve25519]
        (txInput._1,txInput._2,txInput._3,txInput._4,txInput._5,txInput._6,txInput._7),
      PolyTransfer[PublicKeyPropositionCurve25519]
        (txInput._1,txInput._2,txInput._3,txInput._4,txInput._5,txInput._6,txInput._7)
    )

    val generatorBox = ArbitBox(genesisAcct.publicImage.generateEvidence, 0, SimpleValue(privateTotalStake))

    val signature = SignatureCurve25519.genesis

    val block =
      Block(
        ModifierId.genesisParentId,
        0L,
        generatorBox,
        genesisAcct.publicImage,
        signature,
        1L,
        initialDifficulty,
        txs,
        blockVersion.blockByte
      )

    log.debug(s"Initialize state with block $block")

    (block, ChainParams(privateTotalStake, initialDifficulty))
  }
}
