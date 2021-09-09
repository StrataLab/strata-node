package co.topl.fullnode

import cats.Id
import cats.data.{OptionT, StateT}
import cats.implicits._
import co.topl.algebras.ClockAlgebra
import co.topl.algebras.ClockAlgebra.implicits._
import co.topl.consensus.{ConsensusValidationProgram, LeaderElection, VrfRelativeStateLookupAlgebra}
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.signatures.Ed25519VRF
import co.topl.models._
import co.topl.models.utility.HasLength.implicits._
import co.topl.models.utility.Lengths._
import co.topl.models.utility._
import co.topl.typeclasses.BlockGenesis
import co.topl.typeclasses.Identifiable.Instances._
import co.topl.typeclasses.Identifiable.ops._
import co.topl.typeclasses.crypto.KeyInitializer
import co.topl.typeclasses.crypto.KeyInitializer.Instances._

object FullNode extends App {

  val stakerRelativeStake =
    Ratio(1, 5)

  implicit val leaderElectionConfig: LeaderElection.Config =
    LeaderElection
      .Config(lddCutoff = 0, precision = 16, baselineDifficulty = Ratio(1, 15), amplitude = Ratio(2, 5))

  implicit val vrf: Ed25519VRF = new Ed25519VRF
  vrf.precompute()

  implicit val clock: ClockAlgebra[Id] = new SyncClockInterpreter

  private val Right(stakerAddress: TaktikosAddress) = {
    val stakingVerificationKey = KeyInitializer[KeyPairs.Ed25519].random().publicKey.bytes
    for {
      paymentVerificationKeyHash <- Sized.strict[Bytes, Lengths.`32`.type](
        Bytes(blake2b256.hash(KeyInitializer[KeyPairs.Ed25519].random().publicKey.bytes.data.toArray).value)
      )
      signature <- Sized.strict[Bytes, Lengths.`64`.type](Bytes(Array.fill[Byte](64)(1)))
    } yield TaktikosAddress(paymentVerificationKeyHash, stakingVerificationKey, signature)
  }

  val staker = Staker(stakerAddress)

  val initialState =
    InMemoryState(
      BlockGenesis(Nil).value,
      Map.empty,
      Map.empty,
      Map(0L -> Map(stakerAddress -> stakerRelativeStake)),
      Map(0L -> Bytes(Array.fill[Byte](4)(0)))
    )

  val stateT =
    StateT[Id, InMemoryState, Option[BlockV2]] { implicit state =>
      staker
        .mintBlock(
          state.canonicalHead,
          transactions = Nil,
          header => address => state.relativeStakes.get(clock.epochOf(header.slot)).flatMap(_.get(address)),
          header => state.epochNonce(clock.epochOf(header.slot))
        )
        .value match {
        case Some(newBlock) =>
          new ConsensusValidationProgram[Id](
            header => state.epochNonce(clock.epochOf(header.slot)),
            new VrfRelativeStateLookupAlgebra[Id] {

              def lookupAt(block: BlockHeaderV2)(address: TaktikosAddress): OptionT[Id, Ratio] =
                OptionT.fromOption(state.relativeStakes(clock.epochOf(block.slot)).get(address))
            },
            clock
          ).validate(newBlock.headerV2, state.canonicalHead.headerV2)
            .valueOr(f => throw new Exception(f.toString))

          state.append(newBlock) -> Some(newBlock)
        case _ =>
          state -> None
      }
    }

  LazyList
    .unfold(initialState)(stateT.run(_).swap.some)
    .takeWhile(_.nonEmpty)
    .collect { case Some(newBlock) => newBlock }
    .foreach { head =>
      println(
        s"Applied headerId=${new String(head.headerV2.id.dataBytes.toArray)}" +
        s" to parentHeaderId=${new String(head.headerV2.parentHeaderId.dataBytes.toArray)}" +
        s" at height=${head.headerV2.height}" +
        s" at slot=${head.headerV2.slot}" +
        s" at timestamp=${head.headerV2.timestamp}"
      )
    }

  println("Completed epoch")

}
