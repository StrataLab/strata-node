package xyz.stratalab.consensus

import co.topl.consensus.models.BlockHeader
import xyz.stratalab.models._

package object interpreters {

  implicit class BlockHeaderVersionsOps(header: BlockHeader) {

    def getProposalVote: Option[ProposalId] =
      Option.when(header.version.thirdDigit != emptyProposal)(header.version.thirdDigit)

    def getVersionVote: Option[VersionId] =
      Option.when(header.version.secondDigit != emptyVersion)(header.version.secondDigit)

    def versionId: VersionId = header.version.firstDigit
  }

}
