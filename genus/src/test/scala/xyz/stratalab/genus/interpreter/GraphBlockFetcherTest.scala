package xyz.stratalab.genus.interpreter

import cats.effect.IO
import cats.implicits._
import co.topl.consensus.models.BlockHeader
import co.topl.node.models.BlockBody
import com.tinkerpop.blueprints.Vertex
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalamock.munit.AsyncMockFactory
import xyz.stratalab.codecs.bytes.tetra.instances.blockHeaderAsBlockHeaderOps
import xyz.stratalab.genus.algebras.VertexFetcherAlgebra
import xyz.stratalab.genus.interpreter.GraphBlockFetcher
import xyz.stratalab.genus.model.{GE, GEs}
import xyz.stratalab.genus.orientDb.OrientThread
import xyz.stratalab.models.generators.consensus.ModelGenerators._

class GraphBlockFetcherTest extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {

  type F[A] = IO[A]

  test("On fetchCanonicalHead with throwable response, a FailureMessageWithCause should be returned") {

    withMock {

      val res = for {
        implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
        graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
        expectedTh = new IllegalStateException("boom!")
        _ = (() => graphVertexFetcher.fetchCanonicalHead())
          .expects()
          .once()
          .returning(
            (GEs
              .InternalMessageCause("GraphVertexFetcher:fetchCanonicalHead", expectedTh): GE)
              .asLeft[Option[Vertex]]
              .pure[F]
          )
        graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
        _ <- assertIO(
          graphBlockFetcher.fetchCanonicalHead(),
          (GEs.InternalMessageCause("GraphVertexFetcher:fetchCanonicalHead", expectedTh): GE)
            .asLeft[Option[BlockHeader]]
        ).toResource
      } yield ()

      res.use_
    }

  }

  test("On fetchCanonicalHead if an empty iterator is returned, None BlockHeader should be returned") {

    withMock {
      val res = for {
        implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
        graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
        _ = (() => graphVertexFetcher.fetchCanonicalHead())
          .expects()
          .returning(Option.empty[Vertex].asRight[GE].pure[F])
        graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
        _ <- assertIO(
          graphBlockFetcher.fetchCanonicalHead(),
          Option.empty[BlockHeader].asRight[GE]
        ).toResource
      } yield ()

      res.use_
    }

  }

  test("On fetchHeader with throwable response, a FailureMessageWithCause should be returned") {

    PropF.forAllF { (header: BlockHeader) =>
      withMock {

        val res = for {
          implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
          graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          expectedTh = new IllegalStateException("boom!")
          _ = (graphVertexFetcher.fetchHeader _)
            .expects(header.id)
            .once()
            .returning(
              (GEs
                .InternalMessageCause("GraphVertexFetcher:fetchHeader", expectedTh): GE)
                .asLeft[Option[Vertex]]
                .pure[F]
            )
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeader(header.id),
            (GEs.InternalMessageCause("GraphVertexFetcher:fetchHeader", expectedTh): GE)
              .asLeft[Option[BlockHeader]]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchHeader if an empty iterator is returned, None BlockHeader should be returned") {

    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
          graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          _ = (graphVertexFetcher.fetchHeader _)
            .expects(header.id)
            .returning(Option.empty[Vertex].asRight[GE].pure[F])
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeader(header.id),
            Option.empty[BlockHeader].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchHeaderByHeight with throwable response, a MessageWithCause should be returned") {

    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
          graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          expectedTh = new IllegalStateException("boom!")
          _ = (graphVertexFetcher.fetchHeaderByHeight _)
            .expects(header.height)
            .once()
            .returning(
              (GEs
                .InternalMessageCause("GraphVertexFetcher:fetchHeaderByHeight", expectedTh): GE)
                .asLeft[Option[Vertex]]
                .pure[F]
            )
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeaderByHeight(header.height),
            (GEs.InternalMessageCause("GraphVertexFetcher:fetchHeaderByHeight", expectedTh): GE)
              .asLeft[Option[BlockHeader]]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchHeaderByHeight, if an empty iterator is returned, None BlockHeader should be returned") {
    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
          graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          _ = (graphVertexFetcher.fetchHeaderByHeight _)
            .expects(header.height)
            .once()
            .returning(Option.empty[Vertex].asRight[GE].pure[F])
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeaderByHeight(header.height),
            Option.empty[BlockHeader].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchBody, if empty is returned fetching blockHeader Vertex, None BlockBody should be returned") {
    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
          graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          _ = (graphVertexFetcher.fetchHeader _)
            .expects(header.id)
            .once()
            .returning(Option.empty[Vertex].asRight[GE].pure[F])
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchBody(header.id),
            Option.empty[BlockBody].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchBody, if header Vertex exits, but body vertex does not, None BlockBody should be returned") {
    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          implicit0(orientThread: OrientThread[F]) <- OrientThread.create[F]
          graphVertexFetcher                       <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          headerVertex                             <- mock[Vertex].pure[F].toResource

          _ = (graphVertexFetcher.fetchHeader _)
            .expects(header.id)
            .once()
            .returning(headerVertex.some.asRight[GE].pure[F])

          _ = (graphVertexFetcher.fetchBody _)
            .expects(headerVertex)
            .once()
            .returning(Option.empty[Vertex].asRight[GE].pure[F])

          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)

          _ <- assertIO(
            graphBlockFetcher.fetchBody(header.id),
            Option.empty[BlockBody].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

}
