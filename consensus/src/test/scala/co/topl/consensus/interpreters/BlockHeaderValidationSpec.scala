package co.topl.consensus.interpreters

import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.implicits._
import co.topl.algebras.{ClockAlgebra, UnsafeResource}
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.consensus.BlockHeaderValidationFailures
import co.topl.consensus.algebras._
import co.topl.consensus.interpreters.LeaderElectionValidation.VrfConfig
import co.topl.crypto.signing._
import co.topl.crypto.generation.mnemonic.Entropy
import co.topl.crypto.hash.{blake2b256, Blake2b256, Blake2b512}
import co.topl.models.Box.Values.Registrations.Operator
import co.topl.models.ModelGenerators._
import co.topl.models._
import co.topl.models.utility.HasLength.instances._
import co.topl.models.utility.Lengths._
import co.topl.models.utility.{Lengths, Ratio, Sized}
import co.topl.numerics.{ExpInterpreter, Log1pInterpreter}
import co.topl.typeclasses.implicits._
import com.google.common.primitives.Longs
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import java.util.UUID
import scala.util.Random

class BlockHeaderValidationSpec
    extends AnyFlatSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with MockFactory
    with EitherValues {

  behavior of "ConsensusValidation"

  type F[A] = IO[A]

  import BlockHeaderValidationSpec._

  implicit private val ed25519Vrf: Ed25519VRF =
    Ed25519VRF.precomputed()

  implicit private val ed25519: Ed25519 =
    new Ed25519

  implicit private val kesProduct: KesProduct =
    new KesProduct

  private val blake2b256: Blake2b256 =
    new Blake2b256

  private val blake2b512: Blake2b512 =
    new Blake2b512

  private val expInterpreter = ExpInterpreter.make[F](10000, 38).unsafeRunSync()

  private val log1pInterpreter = Log1pInterpreter.make[F](10000, 16).unsafeRunSync()

  private val log1pCached = Log1pInterpreter.makeCached[F](log1pInterpreter).unsafeRunSync()

  private val leaderElectionInterpreter =
    LeaderElectionValidation.Eval
      .makeCached[F](
        LeaderElectionValidation.Eval.make[F](
          VrfConfig(lddCutoff = 0, precision = 16, baselineDifficulty = Ratio(1, 15), amplitude = Ratio(2, 5)),
          new UnsafeResource[F, Blake2b512] {
            def use[Res](f: Blake2b512 => F[Res]): F[Res] = f(blake2b512)
          },
          expInterpreter,
          log1pCached
        )
      )
      .unsafeRunSync()

  private def createDummyClockAlgebra(child: BlockHeader) = {
    val clock: ClockAlgebra[F] = mock[ClockAlgebra[F]]
    (() => clock.globalSlot)
      .expects()
      .once()
      .returning(child.slot.pure[F])

    (clock
      .timestampToSlot(_: Timestamp))
      .expects(*)
      .once()
      .returning(child.slot.pure[F])

    (() => clock.forwardBiasedSlotWindow)
      .expects()
      .once()
      .returning(1L.pure[F])

    clock
  }

  it should "invalidate blocks with non-forward slot" in {
    forAll(genValid(u => u.copy(slot = 0L))) { case (parent, child, _: Operator, _: Eta, _: Ratio) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = mock[ClockAlgebra[F]]
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]

      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      underTest.validate(child, parent).unsafeRunSync().left.value shouldBe BlockHeaderValidationFailures
        .NonForwardSlot(child.slot, parent.slot)
    }
  }

  it should "invalidate blocks with non-forward timestamp" in {
    forAll(genValid(u => u.copy(timestamp = 0L))) { case (parent, child, _: Operator, _: Eta, _: Ratio) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = mock[ClockAlgebra[F]]
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      underTest.validate(child, parent).unsafeRunSync().left.value shouldBe BlockHeaderValidationFailures
        .NonForwardTimestamp(child.timestamp, parent.timestamp)
    }
  }

  it should "invalidate blocks with parent-header mismatch" in {
    forAll(genValid(u => u.copy(parentHeaderId = TypedBytes(1: Byte, Bytes.fill(32)(0: Byte))))) {
      case (parent, child, _: Operator, _: Eta, _: Ratio) =>
        val etaInterpreter = mock[EtaCalculationAlgebra[F]]
        val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
        val clockAlgebra = mock[ClockAlgebra[F]]
        val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
        val kesProductResource = mock[UnsafeResource[F, KesProduct]]
        val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
        val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
        val underTest =
          BlockHeaderValidation.Eval
            .make[F](
              etaInterpreter,
              consensusValidationState,
              leaderElectionInterpreter,
              clockAlgebra,
              ed25519VRFResource,
              kesProductResource,
              ed25519Resource,
              blake2b256Resource
            )
            .unsafeRunSync()

        underTest.validate(child, parent).unsafeRunSync().left.value shouldBe BlockHeaderValidationFailures
          .ParentMismatch(child.parentHeaderId, parent.id)
    }
  }

  it should "invalidate blocks with incorrect timestamp for slot" in {
    val generator =
      for {
        slotError <- Gen.chooseNum[Long](Int.MinValue, Int.MaxValue).suchThat(_ != 0)
        (parent, child, _: Operator, _: Eta, _: Ratio) <- genValid()
      } yield (parent, child, slotError)

    forAll(generator) { case (parent, child, slotError) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]

      val clockAlgebra = mock[ClockAlgebra[F]]
      (() => clockAlgebra.globalSlot)
        .expects()
        .once()
        .returning(child.slot.pure[F])

      (clockAlgebra
        .timestampToSlot(_: Timestamp))
        .expects(child.timestamp)
        .once()
        .returning((child.slot + slotError).pure[F]) // return incorrect slot for child block

      (() => clockAlgebra.forwardBiasedSlotWindow)
        .expects()
        .once()
        .returning(1L.pure[F])

      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      underTest.validate(child, parent).unsafeRunSync().left.value shouldBe BlockHeaderValidationFailures
        .TimestampSlotMismatch(child.slot, child.timestamp)
    }
  }

  // TODO do we need to care about long overflow if (forwardWindowBias + global slot) > Long.MaxValue?
  it should "invalidate block if slot is greater than current global slot + forward biased slot window" in {
    val generator =
      for {
        globalSlot        <- Gen.chooseNum[Long](1, Int.MaxValue)
        parentSlot        <- Gen.const[Long](globalSlot - 1)
        forwardSlotWindow <- Gen.chooseNum[Long](1, Int.MaxValue)
        biasValue         <- Gen.chooseNum[Long](1, Int.MaxValue)
        slotFromFuture    <- Gen.chooseNum[Long](globalSlot + forwardSlotWindow + biasValue, Long.MaxValue)
        (parent, child, _: Operator, _: Eta, _: Ratio) <- genValid(
          childHeader => childHeader.copy(slot = slotFromFuture),
          parentSlot = parentSlot
        )
      } yield (parent, child, forwardSlotWindow, globalSlot)

    forAll(generator) { case (parent, child, forwardWindowBias, globalSlot) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]

      val clockAlgebra = mock[ClockAlgebra[F]]
      (() => clockAlgebra.globalSlot)
        .expects()
        .once()
        .returning(globalSlot.pure[F])

      (clockAlgebra
        .timestampToSlot(_: Timestamp))
        .expects(child.timestamp)
        .once()
        .returning(child.slot.pure[F])

      (() => clockAlgebra.forwardBiasedSlotWindow)
        .expects()
        .once()
        .returning(forwardWindowBias.pure[F])

      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      underTest.validate(child, parent).unsafeRunSync().left.value shouldBe BlockHeaderValidationFailures
        .SlotBeyondForwardBiasedSlotWindow(globalSlot, child.slot)
    }
  }

  it should "invalidate blocks with syntactically incorrect VRF certificate for a particular nonce" in {
    forAll(
      headerGen(
        slotGen = Gen.chooseNum(0L, 50L),
        timestampGen = Gen.chooseNum(0L, 50L),
        heightGen = Gen.const(1L)
      ).flatMap(parent =>
        // The child block has a generated VRF Certificate (generated test/nonce proofs), meaning the proofs will not
        // match the epoch nonce `[1]` used in the test body
        headerGen(
          slotGen = Gen.chooseNum(51L, 100L),
          timestampGen = Gen.chooseNum(51L, 100L),
          parentSlotGen = Gen.const(parent.slot),
          parentHeaderIdGen = Gen.const(parent.id),
          heightGen = Gen.const(2L)
        )
          .map(parent -> _)
      ),
      etaGen
    ) { case ((parent, child), eta) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = createDummyClockAlgebra(child)
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      (etaInterpreter
        .etaToBe(_: SlotId, _: Slot))
        .expects(SlotId(parent.slot, parent.id), child.slot)
        .anyNumberOfTimes()
        // This epoch nonce does not satisfy the generated VRF certificate
        .returning(eta.pure[F])

      (ed25519VRFResource
        .use[Boolean](_: Function1[Ed25519VRF, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Boolean]] => f(ed25519Vrf) }

      (ed25519VRFResource
        .use[Rho](_: Function1[Ed25519VRF, F[Rho]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Rho]] => f(ed25519Vrf) }

      underTest
        .validate(child, parent)
        .unsafeRunSync()
        .left
        .value shouldBe a[BlockHeaderValidationFailures.InvalidEligibilityCertificateEta]
    }
  }

  it should "invalidate blocks with a syntactically incorrect KES certificate" in {
    forAll(genValid()) { case (parent, child, _: Operator, eta, _: Ratio) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = createDummyClockAlgebra(child)
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      // Changing any bytes of the block will result in a bad block signature
      val badBlock = child.copy(timestamp = child.timestamp + 1)

      (etaInterpreter
        .etaToBe(_: SlotId, _: Slot))
        .expects(SlotId(parent.slot, parent.id), badBlock.slot)
        .anyNumberOfTimes()
        .returning(eta.pure[F])

      (ed25519VRFResource
        .use[Boolean](_: Function1[Ed25519VRF, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Boolean]] => f(ed25519Vrf) }

      (ed25519VRFResource
        .use[Rho](_: Function1[Ed25519VRF, F[Rho]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Rho]] => f(ed25519Vrf) }

      (kesProductResource
        .use[Boolean](_: Function1[KesProduct, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[KesProduct, F[Boolean]] => f(kesProduct) }

      (ed25519Resource
        .use[Boolean](_: Function1[Ed25519, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519, F[Boolean]] => f(ed25519) }

      underTest
        .validate(badBlock, parent)
        .unsafeRunSync()
        .left
        .value shouldBe a[BlockHeaderValidationFailures.InvalidBlockProof]
    }
  }

  it should "invalidate blocks with a semantically incorrect registration verification" in {
    forAll(
      genValid(u =>
        u.copy(address = u.address.copy(vk = VerificationKeys.Ed25519(Sized.strictUnsafe(Bytes.fill(32)(0: Byte)))))
      )
    ) { case (parent, child, registration, eta, _: Ratio) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = createDummyClockAlgebra(child)
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      (consensusValidationState
        .operatorRegistration(_: TypedIdentifier, _: Slot)(_: StakingAddresses.Operator))
        .expects(*, *, *)
        .once()
        .returning(registration.some.pure[F])

      (etaInterpreter
        .etaToBe(_: SlotId, _: Slot))
        .expects(SlotId(parent.slot, parent.id), child.slot)
        .anyNumberOfTimes()
        .returning(eta.pure[F])

      (ed25519VRFResource
        .use[Boolean](_: Function1[Ed25519VRF, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Boolean]] => f(ed25519Vrf) }

      (ed25519VRFResource
        .use[Rho](_: Function1[Ed25519VRF, F[Rho]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Rho]] => f(ed25519Vrf) }

      (kesProductResource
        .use[Boolean](_: Function1[KesProduct, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[KesProduct, F[Boolean]] => f(kesProduct) }

      (ed25519Resource
        .use[Boolean](_: Function1[Ed25519, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519, F[Boolean]] => f(ed25519) }

      (blake2b256Resource
        .use[Evidence](_: Function1[Blake2b256, F[Evidence]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Blake2b256, F[Evidence]] => f(blake2b256) }

      underTest
        .validate(child, parent)
        .unsafeRunSync()
        .left
        .value shouldBe a[BlockHeaderValidationFailures.RegistrationCommitmentMismatch]
    }
  }

  it should "invalidate blocks with an insufficient VRF threshold" in {
    forAll(genValid()) { case (parent, child, registration, eta, _: Ratio) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = createDummyClockAlgebra(child)
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      (consensusValidationState
        .operatorRegistration(_: TypedIdentifier, _: Slot)(_: StakingAddresses.Operator))
        .expects(*, *, *)
        .once()
        .returning(registration.some.pure[F])

      (etaInterpreter
        .etaToBe(_: SlotId, _: Slot))
        .expects(SlotId(parent.slot, parent.id), child.slot)
        .anyNumberOfTimes()
        .returning(eta.pure[F])

      (consensusValidationState
        .operatorRelativeStake(_: TypedIdentifier, _: Slot)(_: StakingAddresses.Operator))
        .expects(*, *, *)
        .once()
        .returning(Ratio.Zero.some.pure[F])

      (ed25519VRFResource
        .use[Boolean](_: Function1[Ed25519VRF, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Boolean]] => f(ed25519Vrf) }

      (ed25519VRFResource
        .use[Rho](_: Function1[Ed25519VRF, F[Rho]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Rho]] => f(ed25519Vrf) }

      (kesProductResource
        .use[Boolean](_: Function1[KesProduct, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[KesProduct, F[Boolean]] => f(kesProduct) }

      (ed25519Resource
        .use[Boolean](_: Function1[Ed25519, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519, F[Boolean]] => f(ed25519) }

      (blake2b256Resource
        .use[Evidence](_: Function1[Blake2b256, F[Evidence]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Blake2b256, F[Evidence]] => f(blake2b256) }

      underTest
        .validate(child, parent)
        .unsafeRunSync()
        .left
        .value shouldBe a[BlockHeaderValidationFailures.InvalidVrfThreshold]
    }
  }

  it should "validate valid blocks" in {
    forAll(genValid()) { case (parent, child, registration, eta, relativeStake) =>
      val etaInterpreter = mock[EtaCalculationAlgebra[F]]
      val consensusValidationState = mock[ConsensusValidationStateAlgebra[F]]
      val clockAlgebra = createDummyClockAlgebra(child)
      val ed25519VRFResource = mock[UnsafeResource[F, Ed25519VRF]]
      val kesProductResource = mock[UnsafeResource[F, KesProduct]]
      val ed25519Resource = mock[UnsafeResource[F, Ed25519]]
      val blake2b256Resource = mock[UnsafeResource[F, Blake2b256]]
      val underTest =
        BlockHeaderValidation.Eval
          .make[F](
            etaInterpreter,
            consensusValidationState,
            leaderElectionInterpreter,
            clockAlgebra,
            ed25519VRFResource,
            kesProductResource,
            ed25519Resource,
            blake2b256Resource
          )
          .unsafeRunSync()

      (consensusValidationState
        .operatorRegistration(_: TypedIdentifier, _: Slot)(_: StakingAddresses.Operator))
        .expects(*, *, *)
        .once()
        .returning(registration.some.pure[F])

      (etaInterpreter
        .etaToBe(_: SlotId, _: Slot))
        .expects(SlotId(parent.slot, parent.id), child.slot)
        .anyNumberOfTimes()
        .returning(eta.pure[F])

      (consensusValidationState
        .operatorRelativeStake(_: TypedIdentifier, _: Slot)(_: StakingAddresses.Operator))
        .expects(*, *, *)
        .once()
        .returning(relativeStake.some.pure[F])

      (ed25519VRFResource
        .use[Boolean](_: Function1[Ed25519VRF, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Boolean]] => f(ed25519Vrf) }

      (ed25519VRFResource
        .use[Rho](_: Function1[Ed25519VRF, F[Rho]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519VRF, F[Rho]] => f(ed25519Vrf) }

      (kesProductResource
        .use[Boolean](_: Function1[KesProduct, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[KesProduct, F[Boolean]] => f(kesProduct) }

      (ed25519Resource
        .use[Boolean](_: Function1[Ed25519, F[Boolean]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Ed25519, F[Boolean]] => f(ed25519) }

      (blake2b256Resource
        .use[Evidence](_: Function1[Blake2b256, F[Evidence]]))
        .expects(*)
        .anyNumberOfTimes()
        .onCall { f: Function1[Blake2b256, F[Evidence]] => f(blake2b256) }

      underTest.validate(child, parent).unsafeRunSync().value shouldBe child
    }
  }

  private def validEligibilityCertificate(
    skVrf:                SecretKeys.VrfEd25519,
    thresholdInterpreter: LeaderElectionValidationAlgebra[F],
    eta:                  Eta,
    relativeStake:        Ratio,
    parentSlot:           Slot
  ): (EligibilityCertificate, Slot) = {
    def proof(slot: Slot) =
      Proofs.Knowledge.VrfEd25519(
        Sized.strictUnsafe(
          ed25519Vrf.sign(
            skVrf.bytes.data,
            LeaderElectionValidation
              .VrfArgument(eta, slot)
              .signableBytes
          )
        )
      )

    def isLeader(threshold: Ratio, testProof: Proofs.Knowledge.VrfEd25519) =
      thresholdInterpreter
        .isSlotLeaderForThreshold(threshold)(Rho(Sized.strictUnsafe(ed25519Vrf.proofToHash(testProof.bytes.data))))
        .unsafeRunSync()
    var slot = parentSlot + 1
    var testProof = proof(slot)
    var threshold = thresholdInterpreter.getThreshold(relativeStake, slot).unsafeRunSync()
    while (!isLeader(threshold, testProof)) {
      slot += 1
      testProof = proof(slot)
      threshold = thresholdInterpreter.getThreshold(relativeStake, slot).unsafeRunSync()
    }
    val cert = EligibilityCertificate(
      testProof,
      VerificationKeys.VrfEd25519(Sized.strictUnsafe(ed25519Vrf.getVerificationKey(skVrf.bytes.data))),
      threshold.typedEvidence.evidence,
      eta
    )

    cert -> slot
  }

  private def withPartialOperationalCertificate(
    slot:      Slot,
    unsignedF: BlockHeader.Unsigned.PartialOperationalCertificate => BlockHeader.Unsigned,
    parentSK:  SecretKeys.KesProduct
  ): (BlockHeader.Unsigned, SecretKeys.Ed25519) = {
    val (linearSKBytes, linearVKBytes) =
      Ed25519.instance.deriveKeyPairFromEntropy(Entropy.fromUuid(UUID.randomUUID()), None)

    val message = linearVKBytes ++ Bytes(Longs.toByteArray(slot))
    val parentSignature = kesProduct.sign(parentSK, message)
    val partialCertificate = BlockHeader.Unsigned.PartialOperationalCertificate(
      kesProduct.getVerificationKey(parentSK),
      parentSignature,
      VerificationKeys.Ed25519(Sized.strictUnsafe(linearVKBytes))
    )
    unsignedF(partialCertificate) -> SecretKeys.Ed25519(Sized.strictUnsafe(linearSKBytes))
  }

  private def genValid(
    preSign:    BlockHeader.Unsigned => BlockHeader.Unsigned = identity,
    parentSlot: Slot = 5000L
  ): Gen[(BlockHeader, BlockHeader, Box.Values.Registrations.Operator, Eta, Ratio)] =
    for {
      parent <- headerGen(slotGen = Gen.const[Long](parentSlot))
      (txRoot, bloomFilter, eta) <- genSizedStrictBytes[Lengths.`32`.type]().flatMap(txRoot =>
        genSizedStrictBytes[Lengths.`256`.type]()
          .flatMap(bloomFilter => etaGen.map(nonce => (txRoot, bloomFilter, nonce)))
      )
      relativeStake       <- relativeStakeGen
      (vrfSecretBytes, _) <- Gen.const(Ed25519VRF.precomputed().generateRandom)
    } yield {

      val (kesSK0, _) = kesProduct.createKeyPair(Bytes(Random.nextBytes(32)), (9, 9), 0L)

      val poolVK = arbitraryEd25519VK.arbitrary.first

      val vrfSecret = SecretKeys.VrfEd25519(Sized.strictUnsafe(vrfSecretBytes))

      val registration = validRegistration(
        VerificationKeys.VrfEd25519(Sized.strictUnsafe(ed25519Vrf.getVerificationKey(vrfSecretBytes))),
        poolVK,
        kesSK0
      )

      val address = StakingAddresses.Operator(poolVK)

      val (eligibilityCert, slot) =
        validEligibilityCertificate(vrfSecret, leaderElectionInterpreter, eta, relativeStake, parent.slot)

      val (unsignedOriginal, linearSK) =
        withPartialOperationalCertificate(
          slot,
          partial =>
            BlockHeader.Unsigned(
              parentHeaderId = parent.id,
              parentSlot = parent.slot,
              txRoot = txRoot,
              bloomFilter = bloomFilter,
              timestamp = System.currentTimeMillis(),
              height = parent.height + 1,
              slot = slot,
              eligibilityCertificate = eligibilityCert,
              partialOperationalCertificate = partial,
              metadata = None,
              address = address
            ),
          kesSK0
        )

      val unsigned =
        preSign(unsignedOriginal)

      val operationalCertificate =
        OperationalCertificate(
          unsigned.partialOperationalCertificate.parentVK,
          unsigned.partialOperationalCertificate.parentSignature,
          unsigned.partialOperationalCertificate.childVK,
          Proofs.Knowledge.Ed25519(Sized.strictUnsafe(ed25519.sign(linearSK.bytes.data, unsigned.signableBytes)))
        )

      val child =
        BlockHeader(
          parentHeaderId = unsigned.parentHeaderId,
          parentSlot = unsigned.parentSlot,
          txRoot = unsigned.txRoot,
          bloomFilter = unsigned.bloomFilter,
          timestamp = unsigned.timestamp,
          height = unsigned.height,
          slot = unsigned.slot,
          eligibilityCertificate = unsigned.eligibilityCertificate,
          operationalCertificate = operationalCertificate,
          metadata = unsigned.metadata,
          address = unsigned.address
        )
      (parent, child, registration, eta, relativeStake)
    }

}

object BlockHeaderValidationSpec {

  // Note: These methods are in the companion object because `digest.Digest32#value` conflicts with a ScalaTest member

  def validRegistration(
    vkVrf:               VerificationKeys.VrfEd25519,
    poolVK:              VerificationKeys.Ed25519,
    skKes:               SecretKeys.KesProduct
  )(implicit kesProduct: KesProduct): Box.Values.Registrations.Operator = {
    val commitmentMessage = Bytes(blake2b256.hash((vkVrf.bytes.data ++ poolVK.bytes.data).toArray).value)
    Box.Values.Registrations.Operator(kesProduct.sign(skKes, commitmentMessage))
  }
}
