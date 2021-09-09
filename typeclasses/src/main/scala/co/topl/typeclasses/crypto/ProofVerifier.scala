package co.topl.typeclasses.crypto

import co.topl.crypto.PublicKey
import co.topl.crypto.signatures.{Curve25519, Ed25519, Ed25519VRF, Signature}
import co.topl.models._
import co.topl.typeclasses.crypto.Signable.ops._

import scala.language.implicitConversions
import scala.util.Try

trait ProofVerifier[Proof, Proposition] {

  /**
   * Does the given `proof` satisfy the given `proposition` using the given `data`?
   */
  def verifyWith[Data: Signable](proof: Proof, proposition: Proposition, data: Data): Boolean
}

object ProofVerifier {

  trait Ops[Proof, Proposition] {

    def proof: Proof
    def typeclassInstance: ProofVerifier[Proof, Proposition]

    def satisfies[Data: Signable](proposition: Proposition, data: Data): Boolean =
      typeclassInstance.verifyWith(proof, proposition, data)
  }

  trait Implicits {

    implicit def asVerifierOps[Proof, Proposition](
      p:                 Proof
    )(implicit verifier: ProofVerifier[Proof, Proposition]): Ops[Proof, Proposition] =
      new Ops[Proof, Proposition] {
        override def proof: Proof = p

        override def typeclassInstance: ProofVerifier[Proof, Proposition] = verifier
      }
  }

  object ops extends Implicits

  trait Instances {

    implicit val publicKeyCurve25519: ProofVerifier[Proofs.SignatureCurve25519, Propositions.PublicKeyCurve25519] =
      new ProofVerifier[Proofs.SignatureCurve25519, Propositions.PublicKeyCurve25519] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.SignatureCurve25519,
          proposition: Propositions.PublicKeyCurve25519,
          data:        Data
        ): Boolean = Curve25519.verify(
          Signature(proof.bytes.fold(Array.emptyByteArray)(_.data.toArray)),
          data.signableBytes.toArray,
          PublicKey(proposition.key.bytes.data.toArray)
        )
      }

    implicit val publicKeyEd25519: ProofVerifier[Proofs.SignatureEd25519, Propositions.PublicKeyEd25519] =
      new ProofVerifier[Proofs.SignatureEd25519, Propositions.PublicKeyEd25519] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.SignatureEd25519,
          proposition: Propositions.PublicKeyEd25519,
          data:        Data
        ): Boolean = new Ed25519().verify(
          Signature(proof.bytes.fold(Array.emptyByteArray)(_.data.toArray)),
          data.signableBytes.toArray,
          PublicKey(proposition.key.bytes.data.toArray)
        )
      }

    implicit val thresholdCurve25519
      : ProofVerifier[Proofs.ThresholdSignatureCurve25519, Propositions.ThresholdCurve25519] =
      new ProofVerifier[Proofs.ThresholdSignatureCurve25519, Propositions.ThresholdCurve25519] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.ThresholdSignatureCurve25519,
          proposition: Propositions.ThresholdCurve25519,
          data:        Data
        ): Boolean = {
          val dataBytes = data.signableBytes.toArray
          proposition.propositions.size >= proposition.threshold && {
            val (validSignatureCount, _) =
              proof.signatures
                .foldLeft((0, proposition.propositions)) { case ((acc, unusedProps), sig) =>
                  if (acc < proposition.threshold) {
                    unusedProps
                      .find(prop =>
                        unusedProps(prop) && Curve25519.verify(
                          Signature(sig.bytes.fold(Array.emptyByteArray)(_.data.toArray)),
                          dataBytes,
                          PublicKey(prop.bytes.data.toArray)
                        )
                      ) match {
                      case Some(prop) =>
                        (acc + 1, unusedProps.diff(Set(prop)))
                      case None =>
                        (acc, unusedProps)
                    }
                  } else (acc, unusedProps)
                }
            validSignatureCount >= proposition.threshold
          }
        }
      }

    implicit val thresholdEd25519: ProofVerifier[Proofs.ThresholdSignatureEd25519, Propositions.ThresholdEd25519] =
      new ProofVerifier[Proofs.ThresholdSignatureEd25519, Propositions.ThresholdEd25519] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.ThresholdSignatureEd25519,
          proposition: Propositions.ThresholdEd25519,
          data:        Data
        ): Boolean = {
          val dataBytes = data.signableBytes.toArray
          proposition.propositions.size >= proposition.threshold && {
            val (validSignatureCount, _) =
              proof.signatures
                .foldLeft((0, proposition.propositions)) { case ((acc, unusedProps), sig) =>
                  if (acc < proposition.threshold) {
                    unusedProps
                      .find(prop =>
                        unusedProps(prop) && new Ed25519().verify(
                          Signature(sig.bytes.fold(Array.emptyByteArray)(_.data.toArray)),
                          dataBytes,
                          PublicKey(prop.bytes.data.toArray)
                        )
                      ) match {
                      case Some(prop) =>
                        (acc + 1, unusedProps.diff(Set(prop)))
                      case None =>
                        (acc, unusedProps)
                    }
                  } else (acc, unusedProps)
                }
            validSignatureCount >= proposition.threshold
          }
        }
      }

    implicit val existence: ProofVerifier[Proofs.Existence, Propositions.Existence] =
      new ProofVerifier[Proofs.Existence, Propositions.Existence] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.Existence,
          proposition: Propositions.Existence,
          data:        Data
        ): Boolean = true // TODO
      }

    implicit val consensusVrfTest: ProofVerifier[Proofs.Consensus.VrfTest, Propositions.Consensus.PublicKeyVrf] =
      new ProofVerifier[Proofs.Consensus.VrfTest, Propositions.Consensus.PublicKeyVrf] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.Consensus.VrfTest,
          proposition: Propositions.Consensus.PublicKeyVrf,
          data:        Data
        ): Boolean =
          Try(
            Ed25519VRF.instance.vrfVerify(
              proposition.key.ed25519.bytes.data.toArray,
              data.signableBytes.toArray,
              proof.bytes.data.toArray
            )
          ).getOrElse(false)
      }

    implicit val consensusVrfNonce: ProofVerifier[Proofs.Consensus.Nonce, Propositions.Consensus.PublicKeyVrf] =
      new ProofVerifier[Proofs.Consensus.Nonce, Propositions.Consensus.PublicKeyVrf] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.Consensus.Nonce,
          proposition: Propositions.Consensus.PublicKeyVrf,
          data:        Data
        ): Boolean = Try(
          Ed25519VRF.instance.vrfVerify(
            proposition.key.ed25519.bytes.data.toArray,
            data.signableBytes.toArray,
            proof.bytes.data.toArray
          )
        ).getOrElse(false)
      }

    implicit val consensusKesCertificate
      : ProofVerifier[Proofs.Consensus.KesCertificate, Propositions.Consensus.PublicKeyKes] =
      new ProofVerifier[Proofs.Consensus.KesCertificate, Propositions.Consensus.PublicKeyKes] {

        override def verifyWith[Data: Signable](
          proof:       Proofs.Consensus.KesCertificate,
          proposition: Propositions.Consensus.PublicKeyKes,
          data:        Data
        ): Boolean = publicKeyEd25519.verifyWith(
          Proofs.SignatureEd25519(Some(proof.bytes)),
          Propositions.PublicKeyEd25519(PublicKeys.Ed25519(proposition.key.bytes)),
          data
        )
      }
  }

  object Instances extends Instances
}
