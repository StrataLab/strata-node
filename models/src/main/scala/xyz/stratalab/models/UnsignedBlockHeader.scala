package xyz.stratalab.models

import com.google.protobuf.ByteString
import xyz.stratalab.consensus.models._

case class UnsignedBlockHeader(
  parentHeaderId:                BlockId,
  parentSlot:                    Slot,
  txRoot:                        ByteString,
  bloomFilter:                   ByteString,
  timestamp:                     Timestamp,
  height:                        Long,
  slot:                          Slot,
  eligibilityCertificate:        EligibilityCertificate,
  partialOperationalCertificate: UnsignedBlockHeader.PartialOperationalCertificate,
  metadata:                      ByteString,
  address:                       StakingAddress,
  protocolVersion:               ProtocolVersion
)

object UnsignedBlockHeader {

  case class PartialOperationalCertificate(
    parentVK:        VerificationKeyKesProduct,
    parentSignature: SignatureKesProduct,
    childVK:         ByteString
  )

}
