package co.topl.ledger.interpreters

import cats.Applicative
import cats.data.NonEmptyChain
import cats.effect._
import cats.implicits._
import co.topl.algebras.ClockAlgebra
import co.topl.brambl.generators.ModelGenerators._
import co.topl.brambl.models.TransactionId
import co.topl.brambl.models.transaction._
import co.topl.brambl.syntax._
import co.topl.brambl.validation.algebras.TransactionCostCalculator
import co.topl.consensus.models.BlockId
import co.topl.eventtree.ParentChildTree
import co.topl.ledger.algebras.TransactionRewardCalculatorAlgebra
import co.topl.ledger.models.RewardQuantities
import co.topl.models.ModelGenerators._
import co.topl.models.generators.consensus.ModelGenerators._
import co.topl.node.models.BlockBody
import co.topl.typeclasses.implicits._
import fs2.Stream
import munit.CatsEffectSuite
import munit.ScalaCheckEffectSuite
import org.scalacheck.Gen
import org.scalacheck.Test
import org.scalacheck.effect.PropF
import org.scalamock.munit.AsyncMockFactory
import co.topl.algebras.Stats.Implicits._

import scala.concurrent.duration._

class MempoolSpec extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {

  type F[A] = IO[A]

  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMaxSize(3)
      .withMinSuccessfulTests(10)

  private val dummyRewardCalc: TransactionRewardCalculatorAlgebra = (_: IoTransaction) => RewardQuantities()
  private val dummyCostCalc: TransactionCostCalculator = (tx: IoTransaction) => tx.inputs.size

  test("expose a Set of Transaction IDs at a specific block") {
    PropF.forAllF(
      nonEmptyChainOf[(BlockId, NonEmptyChain[IoTransaction])](
        Gen.zip(arbitraryBlockId.arbitrary, nonEmptyChainOf(arbitraryIoTransaction.arbitrary.map(_.embedId)))
      ),
      arbitraryIoTransaction.arbitrary.map(_.embedId)
    ) { case (bodies, newTx: IoTransaction) =>
      withMock {
        val bodiesMap = bodies.toList.toMap
        val transactions = bodies.flatMap(_._2).toList.map(t => t.id -> t).toMap.updated(newTx.id, newTx)
        val fetchBody =
          (id: BlockId) => BlockBody(bodiesMap(id).map(_.id).toList).pure[F]
        val fetchTransaction = (id: TransactionId) => transactions(id).pure[F]
        val clock = mock[ClockAlgebra[F]]
        (() => clock.globalSlot)
          .expects()
          .anyNumberOfTimes()
          .returning(0L.pure[F])
        (clock
          .delayedUntilSlot(_: Long))
          .expects(*)
          .anyNumberOfTimes()
          .returning(MonadCancel[F].never[Unit])
        for {
          tree <- ParentChildTree.FromRef.make[F, BlockId]
          _    <- bodies.map(_._1).sliding2.traverse { case (id1, id2) => tree.associate(id2, id1) }
          _ <- Mempool
            .make[F](
              bodies.head._1.pure[F],
              fetchBody,
              fetchTransaction,
              tree,
              _ => Applicative[F].unit,
              clock,
              _ => Applicative[F].unit,
              Long.MaxValue,
              dummyRewardCalc,
              dummyCostCalc
            )
            .map(_._1)
            .use(underTest =>
              for {
                _ <- underTest.read(bodies.last._1).map(_.transactions.keySet).assertEquals(Set.empty[TransactionId])
                _ <- underTest.add(newTx.id)
                _ <- underTest.read(bodies.last._1).map(_.transactions.keySet).assertEquals(Set(newTx.id))
                _ <- underTest.remove(newTx.id)
                _ <- underTest.read(bodies.last._1).map(_.transactions.keySet).assertEquals(Set.empty[TransactionId])
                _ <- underTest
                  .read(bodies.head._1)
                  .map(_.transactions.keySet)
                  .assertEquals(
                    bodies.tail.toList
                      .flatMap(_._2.toList.map(_.id))
                      .toSet
                  )
              } yield ()
            )
        } yield ()
      }
    }
  }

  test("allow transactions to be added externally") {
    PropF.forAllF { (currentBlockId: BlockId, _transaction: IoTransaction) =>
      val transaction = _transaction.embedId
      withMock {
        val fetchTransaction = mockFunction[TransactionId, F[IoTransaction]]
        fetchTransaction
          .expects(transaction.id)
          .once()
          .returning(transaction.pure[F])
        val clock = mock[ClockAlgebra[F]]
        (() => clock.globalSlot)
          .expects()
          .once()
          .returning(0L.pure[F])
        (clock
          .delayedUntilSlot(_: Long))
          .expects(*)
          .once()
          .returning(MonadCancel[F].never[Unit])
        Mempool
          .make[F](
            currentBlockId.pure[F],
            mockFunction[BlockId, F[BlockBody]],
            fetchTransaction,
            mock[ParentChildTree[F, BlockId]],
            _ => Applicative[F].unit,
            clock,
            _ => Applicative[F].unit,
            Long.MaxValue,
            dummyRewardCalc,
            dummyCostCalc
          )
          .map(_._1)
          .use(underTest =>
            underTest.adoptions
              .subscribeAwait(Int.MaxValue)
              .map(_.concurrently(Stream.eval(underTest.add(transaction.id).assert)))
              .use(_.head.interruptAfter(3.seconds).compile.lastOrError)
              .assertEquals(transaction.id)
          )
      }
    }
  }

  test("expire transactions at the user-defined slot") {
    PropF.forAllF { (currentBlockId: BlockId, transactionWithRandomTime: IoTransaction) =>
      withMock {
        val transaction = transactionWithRandomTime.update(_.datum.event.schedule.max.set(2)).embedId
        val fetchTransaction = mockFunction[TransactionId, F[IoTransaction]]
        fetchTransaction
          .expects(transaction.id)
          .once()
          .returning(transaction.pure[F])
        val clock = mock[ClockAlgebra[F]]
        (() => clock.globalSlot)
          .expects()
          .once()
          .returning(0L.pure[F])
        val testResource =
          for {
            clockDeferred <- Deferred[F, Unit].toResource
            _ =
              (clock
                .delayedUntilSlot(_: Long))
                // This is the real thing being tested here
                .expects(transaction.datum.event.schedule.max)
                .once()
                .returning(clockDeferred.get)
            expirationDeferred <- Deferred[F, Unit].toResource
            (underTest, _) <-
              Mempool
                .make[F](
                  currentBlockId.pure[F],
                  mockFunction[BlockId, F[BlockBody]],
                  fetchTransaction,
                  mock[ParentChildTree[F, BlockId]],
                  _ => Applicative[F].unit,
                  clock,
                  _ => expirationDeferred.complete(()).void,
                  Long.MaxValue,
                  dummyRewardCalc,
                  dummyCostCalc
                )
            _ <- underTest.add(transaction.id).toResource
            _ <- underTest.read(currentBlockId).map(_.transactions.keySet).assertEquals(Set(transaction.id)).toResource
            _ <- clockDeferred.complete(()).toResource
            _ <- expirationDeferred.get.toResource
            _ <- underTest
              .read(currentBlockId)
              .map(_.transactions.keySet)
              .assertEquals(Set.empty[TransactionId])
              .toResource
          } yield ()
        testResource.use_
      }
    }
  }

  test("expire transactions at the node-configured slot if the user-defined slot is too high") {
    PropF.forAllF { (currentBlockId: BlockId, transactionWithRandomTime: IoTransaction) =>
      withMock {
        val transaction =
          transactionWithRandomTime.update(_.datum.event.schedule.max.set(Long.MaxValue)).embedId
        val fetchTransaction = mockFunction[TransactionId, F[IoTransaction]]
        fetchTransaction
          .expects(transaction.id)
          .once()
          .returning(transaction.pure[F])
        val clock = mock[ClockAlgebra[F]]
        (() => clock.globalSlot)
          .expects()
          .once()
          .returning(0L.pure[F])
        val testResource =
          for {
            clockDeferred <- Deferred[F, Unit].toResource
            _ =
              (clock
                .delayedUntilSlot(_: Long))
                // This is the real thing being tested here
                .expects(100L)
                .once()
                .returning(clockDeferred.get)
            expirationDeferred <- Deferred[F, Unit].toResource
            (underTest, _) <-
              Mempool
                .make[F](
                  currentBlockId.pure[F],
                  mockFunction[BlockId, F[BlockBody]],
                  fetchTransaction,
                  mock[ParentChildTree[F, BlockId]],
                  _ => Applicative[F].unit,
                  clock,
                  _ => expirationDeferred.complete(()).void,
                  100L,
                  dummyRewardCalc,
                  dummyCostCalc
                )
            _ <- underTest.add(transaction.id).toResource
            _ <- underTest.read(currentBlockId).map(_.transactions.keySet).assertEquals(Set(transaction.id)).toResource
            _ <- clockDeferred.complete(()).toResource
            _ <- expirationDeferred.get.toResource

            _ <- underTest
              .read(currentBlockId)
              .map(_.transactions.keySet)
              .assertEquals(Set.empty[TransactionId])
              .toResource
          } yield ()
        testResource.use_
      }
    }
  }

}
