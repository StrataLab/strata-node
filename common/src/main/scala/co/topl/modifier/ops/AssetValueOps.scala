package co.topl.modifier.ops

import cats.implicits._
import co.topl.attestation.Address
import co.topl.attestation.ops.AddressOps.ToDionAddressFailure
import co.topl.models.{Box, Bytes, DionAddress, Transaction}
import co.topl.models.utility.HasLength.instances.{bigIntLength, latin1DataLength}
import co.topl.models.utility.{Lengths, Sized}
import co.topl.modifier.box.AssetValue
import co.topl.utils.{Int128 => DionInt128}
import co.topl.models.utility.StringDataTypes.Latin1Data
import co.topl.utils.StringDataTypes.{Latin1Data => DionLatin1Data}

import scala.language.implicitConversions

class AssetValueOps(private val assetValue: AssetValue) extends AnyVal {

  import AssetValueOps._
  import AssetCodeOps._
  import AssetCodeOps.implicits._

  /**
   * Attempts to convert a Dion [[AssetValue]] into a Tetra [[Transaction.AssetOutput]] with a given [[DionAddress]].
   *
   * @param address the address to use in the asset output value
   * @return a [[Transaction.AssetOutput]] if successful, otherwise a [[ToAssetOutputFailure]]
   */
  def toAssetOutput(address: DionAddress): Either[ToAssetOutputFailure, Transaction.AssetOutput] =
    for {
      quantity <-
        Sized
          .max[BigInt, Lengths.`128`.type](assetValue.quantity.toLong)
          .leftMap(error => ToAssetOutputFailures.InvalidQuantity(assetValue.quantity, error))

      assetCode <-
        assetValue.assetCode.toTetraAssetCode
          .leftMap {
            case ToTetraAssetCodeFailures.InvalidAddress(address) => ToAssetOutputFailures.InvalidIssuerAddress(address)
            case ToTetraAssetCodeFailures.InvalidShortName(shortName) =>
              ToAssetOutputFailures.InvalidShortName(shortName)
          }
      securityRoot = Bytes(assetValue.securityRoot.root)
      metadata <-
        assetValue.metadata.traverse(data =>
          Sized
            .max[Latin1Data, Lengths.`127`.type](Latin1Data.fromData(data.value))
            .leftMap(error => ToAssetOutputFailures.InvalidMetadata(data, error))
        )
      asset = Box.Values.Asset(quantity, assetCode, securityRoot, metadata)
    } yield Transaction.AssetOutput(address, asset)
}

object AssetValueOps {
  sealed trait ToAssetOutputFailure

  object ToAssetOutputFailures {
    case class InvalidQuantity(quantity: DionInt128, inner: Sized.InvalidLength) extends ToAssetOutputFailure
    case class InvalidIssuerAddress(isser: Address) extends ToAssetOutputFailure
    case class InvalidShortName(shortName: DionLatin1Data) extends ToAssetOutputFailure
    case class InvalidMetadata(metadata: DionLatin1Data, inner: Sized.InvalidLength) extends ToAssetOutputFailure
  }

  trait ToAssetValueOps {
    implicit def assetValueOpsFromAssetValue(assetValue: AssetValue): AssetValueOps = new AssetValueOps(assetValue)
  }

  trait Implicits extends ToAssetValueOps

  object implicits extends Implicits
}
