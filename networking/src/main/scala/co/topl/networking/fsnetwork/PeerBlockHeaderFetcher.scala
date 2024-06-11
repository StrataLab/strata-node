package co.topl.networking.fsnetwork

import cats.MonadThrow
import cats.data.{NonEmptyChain, OptionT}
import cats.effect.kernel.{Async, Fiber}
import cats.effect.{Resource, Spawn}
import cats.implicits._
import co.topl.actor.{Actor, Fsm}
import co.topl.algebras.{ClockAlgebra, Store}
import co.topl.codecs.bytes.tetra.instances._
import co.topl.consensus.algebras.{ChainSelectionAlgebra, LocalChainAlgebra}
import co.topl.consensus.models.{BlockId, SlotData}
import co.topl.models.p2p._
import co.topl.networking.blockchain.BlockchainPeerClient
import co.topl.networking.fsnetwork.BlockDownloadError.BlockHeaderDownloadError
import co.topl.networking.fsnetwork.BlockDownloadError.BlockHeaderDownloadError._
import co.topl.networking.fsnetwork.PeersManager.PeersManagerActor
import co.topl.networking.fsnetwork.P2PShowInstances._
import co.topl.networking.fsnetwork.PeerBlockHeaderFetcher.CompareResult._
import co.topl.networking.fsnetwork.RequestsProxy.RequestsProxyActor
import co.topl.node.models.BlockBody
import co.topl.typeclasses.implicits._
import fs2.Stream
import org.typelevel.log4cats.Logger
import co.topl.catsutils.faAsFAClockOps

object PeerBlockHeaderFetcher {
  sealed trait Message

  object Message {
    case object StartActor extends Message
    case object StopActor extends Message

    /**
     * Request to download block headers from peer, downloaded headers will be sent to block checker directly
     *
     * @param blockIds headers block id to download
     */
    case class DownloadBlockHeaders(blockIds: NonEmptyChain[BlockId]) extends Message

    /**
     * Get current tip from remote peer
     */
    case object GetCurrentTip extends Message
  }

  case class State[F[_]](
    hostId:          HostId,
    hostIdString:    String,
    client:          BlockchainPeerClient[F],
    requestsProxy:   RequestsProxyActor[F],
    peersManager:    PeersManagerActor[F],
    localChain:      LocalChainAlgebra[F],
    chainSelection:  ChainSelectionAlgebra[F, SlotData],
    slotDataStore:   Store[F, BlockId, SlotData],
    bodyStore:       Store[F, BlockId, BlockBody],
    fetchingFiber:   Option[Fiber[F, Throwable, Unit]],
    clock:           ClockAlgebra[F],
    commonAncestorF: CommonAncestorF[F]
  )

  type Response[F[_]] = State[F]
  type PeerBlockHeaderFetcherActor[F[_]] = Actor[F, Message, Response[F]]

  def getFsm[F[_]: Async: Logger]: Fsm[F, State[F], Message, Response[F]] = Fsm {
    case (state, Message.StartActor)               => startActor(state)
    case (state, Message.StopActor)                => stopActor(state)
    case (state, Message.DownloadBlockHeaders(id)) => downloadHeaders(state, id)
    case (state, Message.GetCurrentTip)            => getCurrentTip(state)
  }

  def makeActor[F[_]: Async: Logger](
    hostId:          HostId,
    client:          BlockchainPeerClient[F],
    requestsProxy:   RequestsProxyActor[F],
    peersManager:    PeersManagerActor[F],
    localChain:      LocalChainAlgebra[F],
    chainSelection:  ChainSelectionAlgebra[F, SlotData],
    slotDataStore:   Store[F, BlockId, SlotData],
    bodyStore:       Store[F, BlockId, BlockBody],
    clock:           ClockAlgebra[F],
    commonAncestorF: CommonAncestorF[F]
  ): Resource[F, Actor[F, Message, Response[F]]] = {
    val initialState =
      State(
        hostId,
        show"$hostId",
        client,
        requestsProxy,
        peersManager,
        localChain,
        chainSelection,
        slotDataStore,
        bodyStore,
        None,
        clock,
        commonAncestorF
      )
    val actorName = show"Header fetcher actor for peer $hostId"
    Actor.makeWithFinalize(actorName, initialState, getFsm[F], finalizer[F])
  }

  private def finalizer[F[_]: Async: Logger](state: State[F]): F[Unit] =
    stopActor(state).void

  private def startActor[F[_]: Async: Logger](state: State[F]): F[(State[F], Response[F])] =
    if (state.fetchingFiber.isEmpty) {
      for {
        _                 <- Logger[F].info(show"Start block header actor for peer ${state.hostIdString}")
        newBlockIdsStream <- state.client.remotePeerAdoptions
        fiber             <- Spawn[F].start(slotDataFetcher(state, newBlockIdsStream).compile.drain)
        newState = state.copy(fetchingFiber = Option(fiber))
      } yield (newState, newState)
    } else {
      Logger[F].info(show"Ignore starting block header actor for peer ${state.hostIdString}") >>
      (state, state).pure[F]
    }

  private def slotDataFetcher[F[_]: Async: Logger](
    state:             State[F],
    newBlockIdsStream: Stream[F, BlockId]
  ): Stream[F, Option[Long]] =
    // TODO close connection to remote peer in case of error
    newBlockIdsStream
      .evalScan(Option.empty[Long]) { (lastProcessedHeight, newBlockId) =>
        processRemoteBlockId(state, newBlockId, lastProcessedHeight)
          .handleErrorWith(
            Logger[F].error(_)(show"Fetching slot data $newBlockId from remote ${state.hostIdString} returns error") >>
            state.peersManager.sendNoWait(PeersManager.Message.NonCriticalErrorForHost(state.hostId)) >>
            Option.empty[Long].pure[F]
          )
      }

  private def processRemoteBlockId[F[_]: Async: Logger](
    state:      State[F],
    blockId:    BlockId,
    lastHeight: Option[Long]
  ): F[Option[Long]] =
    for {
      remoteSlotData <- getSlotDataFromStorageOrRemote(state)(blockId)
      remoteHeight   <- remoteSlotData.height.pure[F]
      currentHeight  <- state.localChain.head.map(_.height)
      res <- remoteSlotShallBeProcessed(remoteHeight, currentHeight, lastHeight).ifM(
        ifTrue = processRemoteSlotData(state, remoteSlotData),
        ifFalse = Logger[F].info(
          show"Skip slot $blockId from ${state.hostIdString}: rh=$remoteHeight, ch=$currentHeight, lh=$lastHeight"
        ) >>
          lastHeight.pure[F]
      )
    } yield res

  private def remoteSlotShallBeProcessed[F[_]: Async](
    remoteHeight:           Long,
    currentHeight:          Long,
    lastProcessedHeightOpt: Option[Long]
  ): F[Boolean] = {
    val lastProcessedHeight = lastProcessedHeightOpt.getOrElse(Long.MaxValue)
    (lastProcessedHeight >= remoteHeight || currentHeight >= lastProcessedHeight).pure[F]
  }

  private def processRemoteSlotData[F[_]: Async: Logger](
    state:       State[F],
    endSlotData: SlotData
  ): F[Option[Long]] =
    for {
      blockId    <- endSlotData.slotId.blockId.pure[F]
      hostId     <- state.hostIdString.pure[F]
      _          <- Logger[F].info(show"Got blockId: $blockId from peer $hostId")
      (from, to) <- slotDataToSync(state, endSlotData)
      _ <- Logger[F].info(show"Sync ${from.slotId.blockId}:${to.slotId.blockId} from peer $hostId for $blockId")

      downloadedSlotData <- downloadSlotDataChain(state, to)
      _                  <- saveSlotDataChain(state, downloadedSlotData)
      _ <- Logger[F].info(show"Save tine length=${downloadedSlotData.length} from peer $hostId for $blockId")

      _            <- Async[F].cede
      chainToCheck <- buildSlotDataChain(state, from, to)
      _            <- Async[F].cede

      chainToCheckHead = chainToCheck.headOption.map(_.slotId.blockId)
      chainToCheckLast = chainToCheck.lastOption.map(_.slotId.blockId)
      _ <- Logger[F]
        .debug(
          show"CheckChain $chainToCheckHead:$chainToCheckLast len=${chainToCheck.length} from $hostId for $blockId"
        )

      compareResult <- compareSlotDataWithLocal(chainToCheck, state)
        .logDuration(show"Compare slot data end=$chainToCheckLast chain from $hostId for $blockId with local chain")
      betterChain <- compareResult match {
        case CompareResult.NoRemote =>
          Logger[F].info(show"Already adopted $blockId from peer $hostId") >>
          None.pure[F]
        case CompareResult.RemoteIsBetter(betterChain) =>
          Logger[F].debug(show"Received tip $blockId is better than current block from peer $hostId") >>
          state.requestsProxy.sendNoWait(RequestsProxy.Message.RemoteSlotData(state.hostId, betterChain)) >>
          betterChain.some.pure[F]
        case CompareResult.RemoteIsWorseByDensity =>
          Logger[F].info(show"Ignoring tip $blockId from peer $hostId because of the density rule") >>
          state.requestsProxy.sendNoWait(RequestsProxy.Message.BadKLookbackSlotData(state.hostId)) >>
          None.pure[F]
        case CompareResult.RemoteIsWorseByHeight =>
          Logger[F].info(show"Ignoring tip $blockId because other better or equal block had been adopted") >>
          None.pure[F]
      }

      blockSourceOpt <- buildBlockSource(state, to, betterChain)
      _              <- Logger[F].debug(show"Built block source=$blockSourceOpt from peer $hostId for $blockId")
      _ <- blockSourceOpt.traverse_(s => state.peersManager.sendNoWait(PeersManager.Message.BlocksSource(s)))
    } yield to.height.some

  // return slot data for sync where "from" common accepted ancestor "to" top slot data to sync,
  // "from" could be equal to "to"
  private def slotDataToSync[F[_]: Async: Logger](
    state:       State[F],
    endSlotData: SlotData
  ): F[(SlotData, SlotData)] =
    for {
      endBlockId <- endSlotData.slotId.blockId.pure[F]
      (from, to) <- state.bodyStore.contains(endBlockId).flatMap {
        case true =>
          Logger[F].debug(show"Build sync data for $endBlockId: no sync is required") >>
          (endSlotData, endSlotData).pure[F] // no sync is required at all
        case false =>
          state.bodyStore.contains(endSlotData.parentSlotId.blockId).flatMap {
            case true =>
              Logger[F].debug(show"Build sync data for $endBlockId: sync for $endBlockId only") >>
              state.slotDataStore.getOrRaise(endSlotData.parentSlotId.blockId).map((_, endSlotData))
            case false =>
              Logger[F].debug(show"Build sync data for $endBlockId: building long slot data chain") >>
              buildLongSlotDataToSync(state, endSlotData)
          }
      }
    } yield (from, to)

  private def buildLongSlotDataToSync[F[_]: Async: Logger](
    state:   State[F],
    endSlot: SlotData
  ): F[(SlotData, SlotData)] =
    for {
      commonBlockId    <- state.commonAncestorF(state.client, state.localChain)
      commonSlotData   <- getSlotDataFromStorageOrRemote(state)(commonBlockId)
      commonSlotHeight <- commonSlotData.height.pure[F]
      currentHeight    <- state.localChain.head.map(_.height)
      endSlotHeight    <- endSlot.height.pure[F]
      requestedHeight  <- state.chainSelection.enoughHeightToCompare(currentHeight, commonSlotHeight, endSlotHeight)
      message =
        show"For slot ${endSlot.slotId.blockId} with height $endSlotHeight from peer ${state.hostIdString} :" ++
          show" commonSlotHeight=$commonSlotHeight, requestedHeight=$requestedHeight"
      _          <- Logger[F].info(message)
      blockIdTo  <- state.client.getRemoteBlockIdAtHeight(requestedHeight).map(_.get)
      slotDataTo <- state.client.getSlotDataOrError(blockIdTo, new NoSuchElementException(blockIdTo.toString))
    } yield (commonSlotData, slotDataTo)

  // if newSlotDataOpt is defined then it will include blockId as well
  private def buildBlockSource[F[_]: Async](
    state:                State[F],
    blockSlotData:        SlotData,
    newBetterSlotDataOpt: Option[NonEmptyChain[SlotData]]
  ) =
    OptionT
      .fromOption[F](newBetterSlotDataOpt)
      .map(newSlotData => newSlotData.map(sd => (state.hostId, sd.slotId.blockId)))
      .orElseF {
        sourceOfAlreadyAdoptedBlockIsUseful(state, blockSlotData).ifM(
          ifTrue = Option(NonEmptyChain.one(state.hostId -> blockSlotData.slotId.blockId)).pure[F],
          ifFalse = Option.empty[NonEmptyChain[(HostId, BlockId)]].pure[F]
        )
      }
      .value

  // we still interesting in source if we receive current best block or short fork
  private def sourceOfAlreadyAdoptedBlockIsUseful[F[_]: Async](state: State[F], blockSlotData: SlotData): F[Boolean] =
    state.localChain.head.map(sd => sd.height == blockSlotData.height && sd.parentSlotId == blockSlotData.parentSlotId)

  private def saveSlotDataChain[F[_]: Async: Logger](
    state: State[F],
    tine:  List[SlotData]
  ): F[List[SlotData]] = {
    def adoptSlotData(slotData: SlotData) = {
      val slotBlockId = slotData.slotId.blockId
      Logger[F].info(show"Storing SlotData id=$slotBlockId from peer ${state.hostIdString}") >>
      state.slotDataStore.put(slotBlockId, slotData)
    }

    tine.traverse(adoptSlotData) >> tine.pure[F]
  }

  private def buildSlotDataChain[F[_]: Async](
    state: State[F],
    from:  SlotData,
    to:    SlotData
  ): F[List[SlotData]] =
    prependOnChainUntil[F, SlotData](
      s => s.pure[F],
      state.slotDataStore.getOrRaise,
      s => (s.slotId == from.slotId).pure[F]
    )(
      to.slotId.blockId
    )

  private def getSlotDataFromStorageOrRemote[F[_]: Async: Logger](state: State[F])(blockId: BlockId): F[SlotData] =
    state.slotDataStore.get(blockId).flatMap {
      case Some(sd) => sd.pure[F]
      case None =>
        Logger[F].info(show"Fetching SlotData id=$blockId from peer ${state.hostIdString}") >>
        Async[F].cede >>
        state.client
          .getSlotDataOrError(blockId, new NoSuchElementException(blockId.toString))
          // If the node is in a pre-genesis state, verify that the remote peer only notified about the genesis block.
          // It would be adversarial to send any new blocks during this time.
          .flatTap(slotData =>
            Async[F].whenA(slotData.slotId.slot >= 0)(
              Async[F].defer(
                state.clock.globalSlot.flatMap(globalSlot =>
                  Async[F]
                    .raiseWhen(globalSlot < 0)(new IllegalStateException("Peer provided new data prior to genesis"))
                )
              )
            )
          )
    }

  // return: recent (current) block is the last
  private def downloadSlotDataChain[F[_]: Async: Logger](
    state: State[F],
    from:  SlotData
  ): F[List[SlotData]] = {
    val getSlotData: BlockId => F[SlotData] = id =>
      if (id == from.slotId.blockId) {
        from.pure[F]
      } else {
        getSlotDataFromStorageOrRemote(state)(id)
      }

    prependOnChainUntil(
      (s: SlotData) => s.pure[F],
      getSlotData,
      (sd: SlotData) => state.slotDataStore.contains(sd.slotId.blockId)
    )(from.slotId.blockId)
      .handleErrorWith { error =>
        Logger[F].error(show"Failed to get slot data from ${state.hostIdString} due to ${error.toString}") >>
        List.empty[SlotData].pure[F] // TODO send information about error
      }
  }

  sealed trait CompareResult

  object CompareResult {
    case class RemoteIsBetter(remote: NonEmptyChain[SlotData]) extends CompareResult
    object RemoteIsWorseByHeight extends CompareResult
    object RemoteIsWorseByDensity extends CompareResult
    object NoRemote extends CompareResult
  }

  private def compareSlotDataWithLocal[F[_]: Async](
    slotData: List[SlotData],
    state:    State[F]
  ): F[CompareResult] =
    NonEmptyChain.fromSeq(slotData) match {
      case Some(nonEmptySlotDataChain) =>
        val bestSlotData = nonEmptySlotDataChain.last
        state.localChain.isWorseThan(bestSlotData).flatMap {
          case true => Async[F].pure(RemoteIsBetter(nonEmptySlotDataChain))
          case false =>
            state.localChain.head.map(localHead =>
              if (localHead.height < bestSlotData.height) RemoteIsWorseByDensity else RemoteIsWorseByHeight
            )
        }
      case None => Async[F].pure(NoRemote)
    }

  private def getCurrentTip[F[_]: Async: Logger](state: State[F]): F[(State[F], Response[F])] = {
    for {
      _   <- OptionT.liftF(Logger[F].info(show"Requested current tip from peer ${state.hostIdString}"))
      tip <- OptionT(state.client.remoteCurrentTip())
      _   <- OptionT.liftF(processRemoteBlockId(state, tip, None))
      _   <- OptionT.liftF(Logger[F].info(show"Processed current tip $tip from peer ${state.hostIdString}"))
    } yield (state, state)
  }.getOrElse((state, state))
    .handleErrorWith(Logger[F].error(_)("Get tip from remote host return error") >> (state, state).pure[F])

  private def downloadHeaders[F[_]: Async: Logger](
    state:    State[F],
    blockIds: NonEmptyChain[BlockId]
  ): F[(State[F], Response[F])] =
    for {
      remoteHeaders <- getHeadersFromRemotePeer(state.client, state.hostId, blockIds)
      _             <- sendHeadersToProxy(state, NonEmptyChain.fromSeq(remoteHeaders).get)
    } yield (state, state)

  private def getHeadersFromRemotePeer[F[_]: Async: Logger](
    client:   BlockchainPeerClient[F],
    hostId:   HostId,
    blockIds: NonEmptyChain[BlockId]
  ): F[List[(BlockId, Either[BlockHeaderDownloadError, UnverifiedBlockHeader])]] =
    Stream
      .foldable[F, NonEmptyChain, BlockId](blockIds)
      .evalMap(downloadHeader(client, hostId, _))
      .compile
      .toList

  private def downloadHeader[F[_]: Async: Logger](
    client:  BlockchainPeerClient[F],
    hostId:  HostId,
    blockId: BlockId
  ): F[(BlockId, Either[BlockHeaderDownloadError, UnverifiedBlockHeader])] = {
    val headerEither =
      for {
        _                              <- Logger[F].debug(show"Fetching remote header id=$blockId from peer $hostId")
        (downloadTime, headerWithNoId) <- Async[F].timed(client.getHeaderOrError(blockId, HeaderNotFoundInPeer))
        header                         <- headerWithNoId.embedId.pure[F]
        _ <- Logger[F].info(show"Fetched header $blockId: $header from $hostId for ${downloadTime.toMillis} ms")
        _ <- MonadThrow[F].raiseWhen(header.id =!= blockId)(HeaderHaveIncorrectId(blockId, header.id))
      } yield UnverifiedBlockHeader(hostId, header, downloadTime.toMillis)

    headerEither
      .map(blockHeader => Either.right[BlockHeaderDownloadError, UnverifiedBlockHeader](blockHeader))
      .handleError {
        case e: BlockHeaderDownloadError => Either.left[BlockHeaderDownloadError, UnverifiedBlockHeader](e)
        case unknownError                => Either.left(UnknownError(unknownError))
      }
      .flatTap {
        case Right(_) =>
          Logger[F].debug(show"Successfully download block header $blockId from peer $hostId")
        case Left(error) =>
          Logger[F].error(show"Failed download block $blockId from peer $hostId because of: ${error.toString}")
      }
      .map((blockId, _))
  }

  private def sendHeadersToProxy[F[_]](
    state:         State[F],
    headersEither: NonEmptyChain[(BlockId, Either[BlockHeaderDownloadError, UnverifiedBlockHeader])]
  ): F[Unit] = {
    val message: RequestsProxy.Message = RequestsProxy.Message.DownloadHeadersResponse(state.hostId, headersEither)
    state.requestsProxy.sendNoWait(message)
  }

  private def stopActor[F[_]: Async: Logger](state: State[F]): F[(State[F], Response[F])] =
    state.fetchingFiber
      .map { fiber =>
        val newState = state.copy(fetchingFiber = None)
        Logger[F].info(show"Stop block header fetcher fiber for peer ${state.hostIdString}") >>
        fiber.cancel >>
        (newState, newState).pure[F]
      }
      .getOrElse {
        Logger[F].info(show"Ignoring stopping block header fetcher fiber for peer ${state.hostIdString}") >>
        (state, state).pure[F]
      }

}
