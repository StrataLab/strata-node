package xyz.stratalab.algebras

import co.topl.brambl.models.TransactionId
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.consensus.models.{BlockHeader, BlockId}
import co.topl.node.models.BlockBody
import co.topl.proto.node.{EpochData, NodeConfig}
import xyz.stratalab.models.Epoch

/**
 * Node Rpc
 * An interaction layer intended for users/clients of a blockchain node.
 * @tparam F Effect type
 * @tparam S Canonical head changes Synchronization Traversal Container, Ex: Stream, Seq
 */
trait NodeRpc[F[_], S[_]] {
  def broadcastTransaction(transaction: IoTransaction): F[Unit]

  def currentMempool(): F[Set[TransactionId]]
  def currentMempoolContains(transactionId: TransactionId): F[Boolean]

  def fetchBlockHeader(blockId: BlockId): F[Option[BlockHeader]]

  def fetchBlockBody(blockId: BlockId): F[Option[BlockBody]]

  def fetchTransaction(transactionId: TransactionId): F[Option[IoTransaction]]

  def blockIdAtHeight(height: Long): F[Option[BlockId]]

  def blockIdAtDepth(depth: Long): F[Option[BlockId]]

  def synchronizationTraversal(): F[S[SynchronizationTraversalStep]]
  def fetchProtocolConfigs(): F[S[NodeConfig]]

  def fetchEpochData(epoch: Option[Epoch]): F[Option[EpochData]]
}
