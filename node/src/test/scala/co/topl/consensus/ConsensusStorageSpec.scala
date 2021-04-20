package co.topl.consensus

import co.topl.utils.CoreGenerators
import com.google.common.primitives.Longs
import io.iohk.iodb.{ByteArrayWrapper, Store}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalamock.scalatest.MockFactory
import co.topl.crypto.hash.{Blake2b256, Hash}

class ConsensusStorageSpec extends AnyFlatSpec
  with ScalaCheckPropertyChecks
  with Matchers
  with CoreGenerators
  with MockFactory {

  "totalStake" should "return default total stake after no updates with empty storage" in {
    forAll(positiveMediumIntGen) { defaultTotalStake =>
      val storage = new ConsensusStorage(None, defaultTotalStake)

      storage.totalStake shouldBe defaultTotalStake
    }
  }

  "totalStake" should "load total stake from storage on start with an LSM Store" in {
    import co.topl.crypto.hash.Blake2b256._

    forAll(positiveInt128Gen) { (storageTotalStake) =>
      val store = mock[Store]
      (store.get(_: ByteArrayWrapper))
        .expects(*)
        .onCall { key: ByteArrayWrapper => {
            if (key == ByteArrayWrapper(Hash("totalStake")))
              Some(ByteArrayWrapper(storageTotalStake.toByteArray))
            else Some(ByteArrayWrapper(Longs.toByteArray(0)))
          }
        }
        .anyNumberOfTimes()

      val storage = new ConsensusStorage(Some(store), 100000)

      storage.totalStake shouldBe storageTotalStake
    }
  }

  "totalStake" should "return default total stake when storage does not contain value" in {
    forAll(positiveMediumIntGen) { defaultTotalStake =>
      val store = mock[Store]
      (store.get(_: ByteArrayWrapper))
        .expects(*)
        .returns(None)
        .anyNumberOfTimes()

      val storage = new ConsensusStorage(None, defaultTotalStake)

      storage.totalStake shouldBe defaultTotalStake
    }
  }

}
