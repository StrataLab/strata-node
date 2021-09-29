package co.topl.algebras

import cats.Apply
import cats.implicits._
import cats.tagless._
import co.topl.models.{Epoch, Slot, Timestamp}

import scala.collection.immutable.NumericRange
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

/**
 * Provides global slot, epoch, and timing operations
 */
@autoFunctorK
trait ClockAlgebra[F[_]] {
  def slotLength: F[FiniteDuration]
  def slotsPerEpoch: F[Long]
  def currentEpoch(): F[Epoch]
  def currentSlot(): F[Slot]
  def currentTimestamp(): F[Timestamp]
  def delayedUntilSlot(slot:           Slot): F[Unit]
  def delayedUntilTimestamp(timestamp: Timestamp): F[Unit]
}

object ClockAlgebra {

  trait Implicits {

    implicit final class ClockOps[F[_]: Apply](clock: ClockAlgebra[F]) {

      def epochOf(slot: Slot): F[Epoch] = clock.slotsPerEpoch.map(numberOfSlots => slot / numberOfSlots)

      // TODO: Make sure that boundary calculations are correct
      def epochRange(epoch: Epoch): F[NumericRange.Inclusive[Slot]] =
        clock.slotsPerEpoch.map(slotsPerEpoch => (epoch * slotsPerEpoch) to (((epoch + 1) * slotsPerEpoch) - 1))

    }
  }

  object implicits extends Implicits
}
