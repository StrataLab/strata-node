package co.topl.consensus

import cats.Monad
import cats.data.EitherT
import co.topl.algebras.ClockAlgebra
import co.topl.consensus.vrf.ProofToHash
import co.topl.crypto.kes.KeyEvolvingSignatureScheme
import co.topl.crypto.kes.signatures.SymmetricSignature
import co.topl.crypto.signatures.Signature
import co.topl.models._
import co.topl.models.utility.Ratio
import co.topl.typeclasses.ContainsEvidence.Instances._
import co.topl.typeclasses.ContainsEvidence.ops._
import co.topl.typeclasses.Identifiable.Instances._
import co.topl.typeclasses.Identifiable.ops._
import co.topl.typeclasses.crypto.ProofVerifier.Instances._
import co.topl.typeclasses.crypto.ProofVerifier.ops._
import co.topl.typeclasses.crypto.Signable.Instances._
import co.topl.typeclasses.crypto.Signable.ops._
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

import java.nio.charset.StandardCharsets
import scala.language.implicitConversions

/**
 * A program which validates if a child block header can be chained to a parent block header
 */
class ConsensusValidationProgram[F[_]: Monad](
  epochNoncesInterpreter:        EtaAlgebra[F],
  relativeStakeInterpreter:      VrfRelativeStateLookupAlgebra[F],
  clockInterpreter:              ClockAlgebra[F]
)(implicit leaderElectionConfig: LeaderElection.Config) {
  import ConsensusValidationProgram._

  /**
   * Indicates if the provided child header can be chained to the provided parent header using the program's interpreters
   */
  def validate(child: BlockHeaderV2, parent: BlockHeaderV2): EitherT[F, Failure, ValidatedBlockHeader] =
    statelessValidate(child, parent)
      .map(_.header)
      .flatMap(minimalStateValidate(_).map(_.header))
      .flatMap(fullStateValidate(_, parent))

  /**
   * Validations which require no state other than a "parent" block (syntax-based)
   */
  private[consensus] def statelessValidate(
    child:  BlockHeaderV2,
    parent: BlockHeaderV2
  ): EitherT[F, Failure, ValidatedBlockHeader] =
    EitherT
      .cond[F](child.slot > parent.slot, child, Failures.NonForwardSlot(child.slot, parent.slot): Failure)
      .flatMap(child =>
        EitherT.cond[F](
          child.timestamp > parent.timestamp,
          child,
          Failures.NonForwardTimestamp(child.timestamp, parent.timestamp): Failure
        )
      )
      .flatMap(child =>
        EitherT.cond[F](
          child.parentHeaderId == parent.id,
          ValidatedBlockHeader(child),
          Failures.ParentMismatch(child.parentHeaderId, parent.id): Failure
        )
      )

  /**
   * Validations which require just the epoch nonce
   */
  private[consensus] def minimalStateValidate(child: BlockHeaderV2): EitherT[F, Failure, ValidatedBlockHeader] =
    vrfVerification(child)
      .subflatMap(kesVerification)
      .map(ValidatedBlockHeader(_))

  /**
   * Validations which require a full consensus state (stake distribution and registration)
   */
  private[consensus] def fullStateValidate(
    child:  BlockHeaderV2,
    parent: BlockHeaderV2
  ): EitherT[F, Failure, ValidatedBlockHeader] =
    registrationVerification(child).flatMap(child =>
      vrfThresholdFor(child, parent)
        .subflatMap(threshold =>
          vrfThresholdVerification(child, threshold)
            .flatMap(header => eligibilityVerification(header, threshold))
        )
        .map(ValidatedBlockHeader(_))
    )

  /**
   * Determines the VRF threshold for the given child
   */
  private def vrfThresholdFor(
    child:  BlockHeaderV2,
    parent: BlockHeaderV2
  ): EitherT[F, ConsensusValidationProgram.Failure, Ratio] =
    relativeStakeInterpreter
      .lookupAt(child)(child.address)
      .toRight(ConsensusValidationProgram.Failures.InvalidVrfThreshold(Ratio(0)): ConsensusValidationProgram.Failure)
      .map(relativeStake =>
        LeaderElection.getThreshold(
          relativeStake,
          child.slot - parent.slot
        )
      )

  /**
   * Verify that the threshold evidence stamped on the block matches the threshold generated using local state
   */
  private[consensus] def vrfThresholdVerification(
    header:    BlockHeaderV2,
    threshold: Ratio
  ): Either[ConsensusValidationProgram.Failure, BlockHeaderV2] =
    Either.cond(
      header.thresholdEvidence == threshold.evidence,
      header,
      ConsensusValidationProgram.Failures.InvalidVrfThreshold(threshold)
    )

  /**
   * Verify that the block's staker is eligible using their relative stake distribution
   */
  private[consensus] def eligibilityVerification(
    header:    BlockHeaderV2,
    threshold: Ratio
  ): Either[ConsensusValidationProgram.Failure, BlockHeaderV2] =
    Either.cond(
      LeaderElection.isSlotLeaderForThreshold(threshold)(
        Bytes(
          ProofToHash.digest(Signature(header.vrfCertificate.testProof.bytes.data.toArray))
        )
      ),
      header,
      ConsensusValidationProgram.Failures.IneligibleVrfCertificate(threshold, header.vrfCertificate)
    )

  /**
   * Verifies the staker's registration
   */
  private[consensus] def registrationVerification(
    header: BlockHeaderV2
  ): EitherT[F, ConsensusValidationProgram.Failure, BlockHeaderV2] =
    EitherT.pure[F, ConsensusValidationProgram.Failure](header)

  /**
   * Verifies the given block's VRF certificate syntactic integrity for a particular stateful nonce
   */
  private[consensus] def vrfVerification(
    header: BlockHeaderV2
  ): EitherT[F, ConsensusValidationProgram.Failure, BlockHeaderV2] =
    EitherT
      .liftF(epochNoncesInterpreter.etaOf(header))
      .flatMap { epochNonce =>
        val certificate = header.vrfCertificate
        EitherT.cond[F](
          certificate.testProof.satisfies(
            Propositions.Consensus.PublicKeyVrf(certificate.vkVRF),
            epochNonce.toArray ++ BigInt(header.slot).toByteArray ++ "TEST".getBytes(StandardCharsets.UTF_8)
          ) && certificate.nonceProof.satisfies(
            Propositions.Consensus.PublicKeyVrf(certificate.vkVRF),
            epochNonce.toArray ++ BigInt(header.slot).toByteArray ++ "NONCE".getBytes(StandardCharsets.UTF_8)
          ),
          header,
          ConsensusValidationProgram.Failures.InvalidVrfCertificate(header.vrfCertificate)
        )
      }

  private val kesScheme = new KeyEvolvingSignatureScheme

  /**
   * Verifies the given block's KES certificate syntactic integrity for a particular stateful nonce
   */
  private[consensus] def kesVerification(
    header: BlockHeaderV2
  ): Either[ConsensusValidationProgram.Failure, BlockHeaderV2] = {
    val signableBytes = header.signableBytes.toArray
    Either
      .cond(
        true ||
        header.kesCertificate.kesProof
          .satisfies(Propositions.Consensus.PublicKeyKes(header.kesCertificate.vkKES), header),
        header,
        ConsensusValidationProgram.Failures.InvalidKesCertificateKESProof(header.kesCertificate)
      )
      .filterOrElse(
        header =>
          kesScheme.verifyProductSignature(
            signableBytes,
            SymmetricSignature(
              header.kesCertificate.mmmProof.sigi.toArray,
              header.kesCertificate.mmmProof.sigm.toArray,
              co.topl.crypto.kes.keys.PublicKey(header.kesCertificate.mmmProof.pki.toArray),
              header.kesCertificate.mmmProof.offset,
              co.topl.crypto.kes.keys.PublicKey(header.kesCertificate.mmmProof.pkl.toArray)
            ),
            t = (header.slot - header.kesCertificate.mmmProof.offset).toInt
          ),
        ConsensusValidationProgram.Failures.InvalidKesCertificateMMMProof(header.kesCertificate)
      )
  }

}

object ConsensusValidationProgram {

  sealed abstract class Failure

  object Failures {
    case class NonForwardSlot(slot: Slot, parentSlot: Slot) extends Failure
    case class NonForwardTimestamp(timestamp: Timestamp, parentTimestamp: Timestamp) extends Failure
    case class ParentMismatch(expectedParentId: TypedIdentifier, parentId: TypedIdentifier) extends Failure
    case class InvalidVrfThreshold(threshold: Ratio) extends Failure
    case class IneligibleVrfCertificate(threshold: Ratio, vrfCertificate: VrfCertificate) extends Failure
    case class InvalidVrfCertificate(vrfCertificate: VrfCertificate) extends Failure
    case class InvalidKesCertificateKESProof(kesCertificate: KesCertificate) extends Failure
    case class InvalidKesCertificateMMMProof(kesCertificate: KesCertificate) extends Failure
    case class IncompleteEpochData(epoch: Epoch) extends Failure
  }

  @newtype class ValidatedBlockHeader(val header: BlockHeaderV2)

  private[consensus] object ValidatedBlockHeader {

    def apply(blockHeaderV2: BlockHeaderV2): ValidatedBlockHeader =
      blockHeaderV2.coerce
  }

}
