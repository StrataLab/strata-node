package xyz.stratalab.genus

import cats.data._
import cats.effect.Async
import cats.effect.implicits._
import cats.effect.kernel.{Outcome, Resource}
import cats.implicits._
import co.topl.genus.services.BlockData
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import xyz.stratalab.algebras.{Stats, SynchronizationTraversalStep, SynchronizationTraversalSteps}
import xyz.stratalab.codecs.bytes.tetra.instances.blockHeaderAsBlockHeaderOps
import xyz.stratalab.interpreters.NodeRpcOps._
import xyz.stratalab.typeclasses.implicits._

object Replicator {

  def background[F[_]: Async: Stats](
    genus: Genus[F, fs2.Stream[F, *]]
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    stream(genus).compile.drain.background

  def stream[F[_]: Async: Stats](genus: Genus[F, fs2.Stream[F, *]]): fs2.Stream[F, Unit] =
    for {
      implicit0(logger: Logger[F]) <- fs2.Stream.eval(Slf4jLogger.fromName("Genus.Replicator"))
      _                            <- fs2.Stream.eval(genus.nodeRpcClient.waitForRpcStartUp)
      nodeLatestHeight <- fs2.Stream.eval(
        OptionT(genus.nodeBlockFetcher.fetchHeight()).getOrRaise(new IllegalStateException("Unknown node height"))
      )
      graphCurrentHeight <- fs2.Stream.eval(
        OptionT(
          genus.blockFetcher
            .fetchCanonicalHead()
            .rethrow
        ).fold(0L)(_.height)
      )
      _ <- fs2.Stream.eval(
        Logger[F].info(s"Historical data start=${graphCurrentHeight + 1}, end=${nodeLatestHeight}")
      )
      // Historical + live data streams
      _ <- fs2.Stream
        .force[F, BlockData](
          genus.nodeBlockFetcher.fetch(startHeight = graphCurrentHeight + 1, endHeight = nodeLatestHeight + 3)
        )
        .evalTap(blockData =>
          Logger[F].info(s"Inserting block ${blockData.header.id.show} height=${blockData.header.height}")
        )
        .evalMap(genus.blockUpdater.insert)
        .rethrow ++
      fs2.Stream
        .force[F, SynchronizationTraversalStep](
          genus.nodeRpcClient.synchronizationTraversal()
        )
        .evalMap {
          case SynchronizationTraversalSteps.Applied(blockId) =>
            EitherT(genus.nodeBlockFetcher.fetch(blockId))
              .semiflatTap(blockData =>
                Logger[F].info(s"Inserting block ${blockData.header.id.show} height=${blockData.header.height}") >>
                Async[F].defer(
                  Stats[F].recordGauge(
                    "bifrost_genus_replications",
                    "Genus replications",
                    Map("block_id" -> blockData.header.id.show),
                    blockData.header.height
                  )
                )
              )
              .flatMapF(genus.blockUpdater.insert)
              .value
          case SynchronizationTraversalSteps.Unapplied(blockId) =>
            EitherT(genus.nodeBlockFetcher.fetch(blockId))
              .semiflatTap(blockData =>
                Logger[F].info(s"Deleting block ${blockData.header.id.show} height=${blockData.header.height}")
              )
              .flatMapF(genus.blockUpdater.remove)
              .value
        }
        .rethrow

    } yield ()
}
