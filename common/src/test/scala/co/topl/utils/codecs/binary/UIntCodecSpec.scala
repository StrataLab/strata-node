package co.topl.utils.codecs.binary

import co.topl.utils.CommonGenerators
import co.topl.utils.IdiomaticScalaTransition.implicits._
import co.topl.utils.serialization.VLQByteStringWriter
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}
import scodec.bits.BitVector

class UIntCodecSpec
    extends AnyFlatSpec
    with CommonGenerators
    with ScalaCheckPropertyChecks
    with ScalaCheckDrivenPropertyChecks
    with Matchers {
  "UIntCodec Decoder" should "be able to decode VLQByteStringWriter output" in {
    forAll(Gen.choose(0, Int.MaxValue)) { intValue =>
      val vlqWriter = new VLQByteStringWriter

      vlqWriter.putUInt(intValue)

      val bytes = vlqWriter.result()

      val decoderResult = UIntCodec.decode(BitVector(bytes)).getOrThrow()

      decoderResult.value shouldBe intValue
      decoderResult.remainder shouldBe empty
    }
  }

  it should "return additional leftover bytes after parsing UInt" in {
    forAll(Gen.choose(0, Int.MaxValue), nonEmptyBytesGen) { (intValue, leftover) =>
      val vlqWriter = new VLQByteStringWriter

      vlqWriter.putUInt(intValue)

      val bytes = vlqWriter.result() ++ leftover

      val decoderResult = UIntCodec.decode(BitVector(bytes)).getOrThrow()

      decoderResult.remainder.toByteArray shouldBe leftover
    }
  }
}
