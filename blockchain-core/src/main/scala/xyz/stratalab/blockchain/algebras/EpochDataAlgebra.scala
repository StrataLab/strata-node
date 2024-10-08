package xyz.stratalab.blockchain.algebras

import co.topl.proto.node.EpochData
import xyz.stratalab.models.Epoch

/**
 * Provides epoch-level statistics
 */
trait EpochDataAlgebra[F[_]] {

  /**
   * Constructs the EpochData for the requested epoch.  The "current" epoch is updated as blocks are adopted.
   * @param epoch the epoch number to request
   * @return EpochData
   */
  def dataOf(epoch: Epoch): F[Option[EpochData]]

}
