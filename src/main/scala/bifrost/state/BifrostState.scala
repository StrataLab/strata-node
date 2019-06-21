package bifrost.state

import java.io.File
import java.time.Instant

import bifrost.history.BifrostHistory
import bifrost.blocks.BifrostBlock
import bifrost.program.Program
import bifrost.exceptions.TransactionValidationException
import bifrost.scorexMod.{GenericBoxMinimalState, GenericStateChanges}
import bifrost.transaction._
import bifrost.transaction.box._
import bifrost.transaction.box.proposition.MofNProposition
import bifrost.transaction.proof.MultiSignature25519
import com.google.common.primitives.Longs
import io.circe.Json
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import bifrost.crypto.hash.FastCryptographicHash
import bifrost.forging.ForgingSettings
import bifrost.settings.Settings
import bifrost.srb.StateBoxRegistry
import bifrost.transaction.bifrostTransaction.{AssetRedemption, _}
import bifrost.transaction.box.proposition.{ProofOfKnowledgeProposition, PublicKey25519Proposition}
import bifrost.transaction.state.MinimalState.VersionTag
import bifrost.transaction.state.PrivateKey25519
import bifrost.utils.ScorexLogging
import scorex.crypto.encode.Base58

import scala.util.{Failure, Success, Try}

case class BifrostTransactionChanges(toRemove: Set[BifrostBox], toAppend: Set[BifrostBox], minerReward: Long)

case class BifrostStateChanges(override val boxIdsToRemove: Set[Array[Byte]],
                               override val toAppend: Set[BifrostBox], timestamp: Long)
  extends GenericStateChanges[Any, ProofOfKnowledgeProposition[PrivateKey25519], BifrostBox](boxIdsToRemove, toAppend)

/**
  * BifrostState is a data structure which deterministically defines whether an arbitrary transaction is valid and so
  * applicable to it or not. Also has methods to get a closed box, to apply a persistent modifier, and to roll back
  * to a previous version.
  *
  * @param storage           singleton Iodb storage instance
  * @param version           blockId used to identify each block. Also used for rollback
  * @param timestamp         timestamp of the block that results in this state
  * @param history           Main box storage
  * @param stateBoxRegistry  Separate box set for program StateBoxes
  */
case class BifrostState(storage: LSMStore, override val version: VersionTag, timestamp: Long, var history: BifrostHistory, stateBoxRegistry: StateBoxRegistry)
  extends GenericBoxMinimalState[Any, ProofOfKnowledgeProposition[PrivateKey25519],
    BifrostBox, BifrostTransaction, BifrostBlock, BifrostState] with ScorexLogging {

  override type NVCT = BifrostState
  type P = BifrostState.P
  type T = BifrostState.T
  type TX = BifrostState.TX
  type BX = BifrostState.BX
  type BPMOD = BifrostState.BPMOD
  type GSC = BifrostState.GSC
  type BSC = BifrostState.BSC



  override def semanticValidity(tx: BifrostTransaction): Try[Unit] = BifrostState.semanticValidity(tx)

  private def lastVersionString = storage.lastVersionID.map(v => Base58.encode(v.data)).getOrElse("None")

  private def getProfileBox(prop: PublicKey25519Proposition, field: String): Try[ProfileBox] = {
    storage.get(ByteArrayWrapper(ProfileBox.idFromBox(prop, field))) match {
      case Some(bytes) => ProfileBoxSerializer.parseBytes(bytes.data)
      case None => Failure(new Exception(s"Couldn't find profile box for ${
        Base58.encode(prop
          .pubKeyBytes)
      } with field <$field>"))
    }
  }

  override def closedBox(boxId: Array[Byte]): Option[BX] =
    storage.get(ByteArrayWrapper(boxId))
      .map(_.data)
      .map(BifrostBoxSerializer.parseBytes)
      .flatMap(_.toOption)

  override def rollbackTo(version: VersionTag): Try[NVCT] = Try {
    if (storage.lastVersionID.exists(_.data sameElements version)) {
      this
    } else {
      log.debug(s"Rollback BifrostState to ${Base58.encode(version)} from version $lastVersionString")
      storage.rollback(ByteArrayWrapper(version))
      val timestamp: Long = Longs.fromByteArray(storage.get(ByteArrayWrapper(FastCryptographicHash("timestamp"
        .getBytes))).get
        .data)
      BifrostState(storage, version, timestamp, history, stateBoxRegistry)
    }
  }

  override def changes(mod: BPMOD): Try[GSC] = BifrostState.changes(mod)

  override def applyChanges(changes: GSC, newVersion: VersionTag): Try[NVCT] = Try {



    /*val stateBoxesToAdd = changes.toAppend.map { sb => sb.typeOfBox match
      {
        case "StateBox" => ByteArrayWrapper(sb.)
      }
    }*/

    val boxesToAdd = changes.toAppend.map(b => ByteArrayWrapper(b.id) -> ByteArrayWrapper(b.bytes))

    /* This seeks to avoid the scenario where there is remove and then update of the same keys */
    val boxIdsToRemove = (changes.boxIdsToRemove -- boxesToAdd.map(_._1.data)).map(ByteArrayWrapper.apply)

    log.debug(s"Update BifrostState from version $lastVersionString to version ${Base58.encode(newVersion)}. " +
      s"Removing boxes with ids ${boxIdsToRemove.map(b => Base58.encode(b.data))}, " +
      s"adding boxes ${boxesToAdd.map(b => Base58.encode(b._1.data))}")

    val timestamp: Long = changes.asInstanceOf[BifrostStateChanges].timestamp

    if (storage.lastVersionID.isDefined) boxIdsToRemove.foreach(i => require(closedBox(i.data).isDefined))

    storage.update(
      ByteArrayWrapper(newVersion),
      boxIdsToRemove,
      boxesToAdd + (ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes)) -> ByteArrayWrapper(Longs.toByteArray(
        timestamp)))
    )

    val newSt = BifrostState(storage, newVersion, timestamp, history, stateBoxRegistry)

    boxIdsToRemove.foreach(box => require(newSt.closedBox(box.data).isEmpty, s"Box $box is still in state"))
    newSt

  }

  def applyChanges(changes: GSC, newVersion: VersionTag, executionBox: ExecutionBox): Try[NVCT] = Try {



    val stateBoxesToAdd = changes.toAppend.map { sb => sb.typeOfBox match
      {
        case "StateBox" => sb.asInstanceOf[StateBox]
      }
    }

    val stateBoxId = executionBox.value.zip(stateBoxesToAdd).map(usb => usb._1 -> usb._2.bytes)

    val boxesToAdd = changes.toAppend.map(b => ByteArrayWrapper(b.id) -> ByteArrayWrapper(b.bytes))

    /* This seeks to avoid the scenario where there is remove and then update of the same keys */
    val boxIdsToRemove = (changes.boxIdsToRemove -- boxesToAdd.map(_._1.data)).map(ByteArrayWrapper.apply)

    log.debug(s"Update BifrostState from version $lastVersionString to version ${Base58.encode(newVersion)}. " +
      s"Removing boxes with ids ${boxIdsToRemove.map(b => Base58.encode(b.data))}, " +
      s"adding boxes ${boxesToAdd.map(b => Base58.encode(b._1.data))}")

    val timestamp: Long = changes.asInstanceOf[BifrostStateChanges].timestamp

    if (storage.lastVersionID.isDefined) boxIdsToRemove.foreach(i => require(closedBox(i.data).isDefined))

    storage.update(
      ByteArrayWrapper(newVersion),
      boxIdsToRemove,
      boxesToAdd + (ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes)) -> ByteArrayWrapper(Longs.toByteArray(
        timestamp)))
    )

    stateBoxId.foreach(sb => stateBoxRegistry.update(
      newVersion,
      sb._1,
      sb._2
    ))

    val newSt = BifrostState(storage, newVersion, timestamp, history, stateBoxRegistry)

    boxIdsToRemove.foreach(box => require(newSt.closedBox(box.data).isEmpty, s"Box $box is still in state"))
    newSt

  }


  //noinspection ScalaStyle
  override def validate(transaction: TX): Try[Unit] = {
    transaction match {
      case poT: PolyTransfer => validatePolyTransfer(poT)
      case arT: ArbitTransfer => validateArbitTransfer(arT)
      case asT: AssetTransfer => validateAssetTransfer(asT)
      case cc: ProgramCreation => validateProgramCreation(cc)
      case prT: ProfileTransaction => validateProfileTransaction(prT)
      case cme: ProgramMethodExecution => validateProgramMethodExecution(cme)
      case ar: AssetRedemption => validateAssetRedemption(ar)
      case ac: AssetCreation => validateAssetCreation(ac)
      case cb: CoinbaseTransaction => validateCoinbaseTransaction(cb)
      case _ => throw new Exception("State validity not implemented for " + transaction.getClass.toGenericString)
    }
  }

  /**
    *
    * @param poT : the PolyTransfer to validate
    * @return
    */
  def validatePolyTransfer(poT: PolyTransfer): Try[Unit] = {

    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        poT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: PolyBox) =>
                if (unlocker.boxKey.isValid(box
                  .proposition,
                  poT
                    .messageToSign)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception(
                    "Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )
        )
      }
      determineEnoughPolys(boxesSumTry: Try[Long], poT)
    }

    statefulValid.flatMap(_ => semanticValidity(poT))
  }

  private def determineEnoughPolys(boxesSumTry: Try[Long], tx: BifrostTransaction): Try[Unit] = {
    boxesSumTry flatMap { openSum =>
      if (tx.newBoxes.map {
        case p: PolyBox => p.value
        case _ => 0L
      }.sum == openSum - tx.fee) {
        Success[Unit](Unit)
      } else {
        Failure(new Exception("Negative fee"))
      }
    }
  }

  def validateArbitTransfer(arT: ArbitTransfer): Try[Unit] = {

    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        arT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: ArbitBox) =>
                if (unlocker.boxKey.isValid(box
                  .proposition,
                  arT
                    .messageToSign)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception(
                    "Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )

        )
      }
      //Determine enough arbits
      boxesSumTry flatMap { openSum =>
        if (arT.newBoxes.map {
          case p: ArbitBox => p.value
          case _ => 0L
        }.sum == openSum - arT.fee) {
          Success[Unit](Unit)
        } else {
          Failure(new Exception("Negative fee"))
        }
      }

    }

    statefulValid.flatMap(_ => semanticValidity(arT))
  }

  def validateAssetTransfer(asT: AssetTransfer): Try[Unit] = {
    val statefulValid: Try[Unit] = {

      val boxesSumTry: Try[Long] = {
        asT.unlockers.foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) =>

          partialRes.flatMap(partialSum =>
            /* Checks if unlocker is valid and if so adds to current running total */
            closedBox(unlocker.closedBoxId) match {
              case Some(box: AssetBox) =>
                if (unlocker.boxKey.isValid(box
                  .proposition,
                  asT
                    .messageToSign) && (box
                  .issuer equals asT.issuer) && (box
                  .assetCode equals asT.assetCode)) {
                  Success(partialSum + box.value)
                } else {
                  Failure(new Exception(
                    "Incorrect unlocker"))
                }
              case None => Failure(new Exception(s"Box for unlocker $unlocker is not in the state"))
            }
          )
        )
      }
      determineEnoughAssets(boxesSumTry, asT)
    }

    statefulValid.flatMap(_ => semanticValidity(asT))
  }

  private def determineEnoughAssets(boxesSumTry: Try[Long], tx: BifrostTransaction): Try[Unit] = {
    boxesSumTry flatMap { openSum =>
      if (tx.newBoxes.map {
        case a: AssetBox => a.value
        case _ => 0L
      }.sum <= openSum) {
        Success[Unit](Unit)
      } else {
        Failure(new Exception("Not enough assets"))
      }
    }
  }

  def validateAssetCreation(ac: AssetCreation): Try[Unit] = {
    val statefulValid: Try[Unit] = {
      ac.newBoxes.size match {
          //only one box should be created
        case 1 => if (ac.newBoxes.head.isInstanceOf[AssetBox]) // the new box is an asset box
          {
            Success[Unit](Unit)
          }
          else {
            Failure(new Exception("Incorrect box type"))
          }
        case _ => Failure(new Exception("Incorrect number of boxes created"))
      }
    }
    statefulValid.flatMap(_ => semanticValidity(ac))
  }

  /**
    *
    * @param pt : ProfileTransaction
    * @return success or failure
    */
  def validateProfileTransaction(pt: ProfileTransaction): Try[Unit] = Try {
    /* Make sure there are no existing boxes of the all fields in tx
    *  If there is one box that exists then the tx is invalid
    * */
    val boxesExist: Boolean = pt.newBoxes.forall(curBox => {
      if (curBox.isInstanceOf[ProfileBox]) {
        val pBox = curBox.asInstanceOf[ProfileBox]
        val boxBytes = storage.get(ByteArrayWrapper(ProfileBox.idFromBox(pBox.proposition, pBox.key)))
        boxBytes match {
          case None => false
          case _ => ProfileBoxSerializer.parseBytes(boxBytes.get.data).isSuccess
        }
      } else {
        false
      }
    })
    require(!boxesExist)

    semanticValidity(pt)
  }

  /**
    * Validates ProgramCreation instance on its unlockers && timestamp of the program
    *
    * @param cc : ProgramCreation object
    * @return
    */
  //noinspection ScalaStyle
  def validateProgramCreation(cc: ProgramCreation): Try[Unit] = {

    /* First check to see all roles are present */
    val roleBoxAttempts: Map[PublicKey25519Proposition, Try[ProfileBox]] = cc.signatures.filter { case (prop, sig) =>
      // Verify that this is being sent by this party because we rely on that during ProgramMethodExecution
      sig.isValid(prop, cc.messageToSign)

    }.map { case (prop, _) => (prop, getProfileBox(prop, "role")) }

    val roleBoxes: Iterable[String] = roleBoxAttempts collect { case s: (PublicKey25519Proposition, Try[ProfileBox]) if s
      ._2.isSuccess => s._2.get.value
    }

    if (!Set(Role.Producer.toString, Role.Hub.toString, Role.Investor.toString).equals(roleBoxes.toSet)) {
      log.debug(
        "Not all roles were fulfilled for this transaction. Either they weren't provided or the signatures were not valid.")
      return Failure(new Exception(
        "Not all roles were fulfilled for this transaction. Either they weren't provided or the signatures were not valid."))
    } else if (roleBoxes.size > 3) {
      log.debug("Too many signatures for the parties of this transaction")
      return Failure(new TransactionValidationException("Too many signatures for the parties of this transaction"))
    }

    /* Verifies that the role boxes match the roles stated in the program creation */
    if (!roleBoxes.zip(cc.parties).forall { case (boxRole, propToRole) => boxRole.equals(propToRole._2.toString) }) {
      log.debug("role boxes does not match the roles stated in the program creation")
      return Failure(
        new TransactionValidationException("role boxes does not match the roles stated in the program creation"))
    }

    val unlockersValid: Try[Unit] = cc.unlockers
      .foldLeft[Try[Unit]](Success())((unlockersValid, unlocker) =>
      unlockersValid
        .flatMap { (unlockerValidity) =>
          closedBox(unlocker.closedBoxId) match {
            case Some(box) =>
              if (unlocker.boxKey.isValid(
                box.proposition,
                cc.messageToSign)) {
                Success()
              } else {
                Failure(new TransactionValidationException("Incorrect unlocker"))
              }
            case None => Failure(new TransactionValidationException(s"Box for unlocker $unlocker is not in the state"))
          }
        }
    )

    val statefulValid = unlockersValid flatMap { _ =>

      val boxesAreNew = cc.newBoxes.forall(curBox => storage.get(ByteArrayWrapper(curBox.id)) match {
        case Some(_) => false
        case None => true
      })

      val inPast = cc.timestamp <= timestamp
      val inFuture = cc.timestamp >= Instant.now().toEpochMilli
      val txTimestampIsAcceptable = !(inPast || inFuture)


      if (boxesAreNew && txTimestampIsAcceptable) {
        Success[Unit](Unit)
      } else if (!boxesAreNew) {
        Failure(new TransactionValidationException("ProgramCreation attempts to overwrite existing program"))
      } else if (inPast) {
        Failure(new TransactionValidationException("ProgramCreation attempts to write into the past"))
      } else {
        Failure(new TransactionValidationException("ProgramCreation timestamp is too far into the future"))
      }
    }

    statefulValid.flatMap(_ => semanticValidity(cc))
  }

  /**
    *
    * @param cme : the ProgramMethodExecution to validate
    * @return
    */
  //noinspection ScalaStyle
  def validateProgramMethodExecution(cme: ProgramMethodExecution): Try[Unit] = Try {

    val executionBoxBytes = storage.get(ByteArrayWrapper(cme.executionBox.id))

    /* Program exists */
    if (executionBoxBytes.isEmpty) {
      throw new TransactionValidationException(s"Program ${Base58.encode(cme.executionBox.id)} does not exist")
    }

    val executionBox: ExecutionBox = ExecutionBoxSerializer.parseBytes(executionBoxBytes.get.data).get
    val programProposition: MofNProposition =  executionBox.proposition

    /* First check to see all roles are present */
    val roleBoxAttempts: Map[PublicKey25519Proposition, Try[ProfileBox]] = cme.signatures.filter { case (prop, sig) =>
      /* Verify that this is being sent by this party because we rely on that during ProgramMethodExecution */
      sig.isValid(prop, cme.messageToSign)
    }.map { case (prop, _) => (prop, getProfileBox(prop, "role")) }

    val roleBoxes: Iterable[ProfileBox] = roleBoxAttempts collect { case s: (PublicKey25519Proposition, Try[ProfileBox]) if s
      ._2.isSuccess => s._2.get
    }

    /* This person belongs to program */
    if (!MultiSignature25519(cme.signatures.values.toSet).isValid(programProposition, cme.messageToSign)) {

      println(s">>>>> programProposition: ${programProposition.toString}")
      println(s">>>>> cme.signatures.keySet: ${cme.signatures.keySet}")
      println(s">>>>> cme.parties.keySet ${cme.parties.keySet}")
      throw new TransactionValidationException(s"Signature is invalid for ExecutionBox")
    }

    /* ProfileBox exists for all attempted signers */
    if (roleBoxes.size != cme.signatures.size) {
      throw new TransactionValidationException(
        s"${Base58.encode(cme.parties.head._1.pubKeyBytes)} claimed ${cme.parties.head._1} role but didn't exist.")
    }

    /* Signatures match each profilebox owner */
    if (!cme.signatures.values.zip(roleBoxes).forall { case (sig, roleBox) => sig.isValid(roleBox.proposition,
      cme.messageToSign)
    }) {
      throw new TransactionValidationException(s"Not all signatures are valid for provided role boxes")
    }

    /* Roles provided by CME matches profileboxes */
    /* TODO check roles
    if (!roleBoxes.forall(rb => rb.value match {
      case "producer" => cme.parties.get(Role.Producer).isDefined && (cme.parties(Role.Producer).pubKeyBytes sameElements rb.proposition.pubKeyBytes)
      case "investor" => cme.parties.get(Role.Investor).isDefined && (cme.parties(Role.Investor).pubKeyBytes sameElements rb.proposition.pubKeyBytes)
      case "hub" => cme.parties.get(Role.Hub).isDefined && (cme.parties(Role.Hub).pubKeyBytes sameElements rb.proposition.pubKeyBytes)
      case _ => false
    }))
      throw new IllegalAccessException(s"Not all roles are valid for signers")
    */
    /* Handles fees */
    val boxesSumMapTry: Try[Map[PublicKey25519Proposition, Long]] = {
      cme.unlockers
        .tail
        .foldLeft[Try[Map[PublicKey25519Proposition, Long]]](Success(Map()))((partialRes, unlocker) => {
        partialRes
          .flatMap(_ => closedBox(unlocker.closedBoxId) match {
            case Some(box: PolyBox) =>
              if (unlocker.boxKey.isValid(box.proposition, cme.messageToSign)) {
                partialRes.get.get(box.proposition) match {
                  case Some(total) => Success(partialRes.get + (box.proposition -> (total + box.value)))
                  case None => Success(partialRes.get + (box.proposition -> box.value))
                }
              } else {
                Failure(new TransactionValidationException("Incorrect unlocker"))
              }
            case None => Failure(new TransactionValidationException(s"Box for unlocker $unlocker is not in the state"))
          })
      })
    }

    /* Incorrect unlocker or box provided, or not enough to cover declared fees */
    if (boxesSumMapTry.isFailure || !boxesSumMapTry.get.forall { case (prop, amount) => cme.fees.get(prop) match {
      case Some(fee) => amount >= fee
      case None => true
    }
    }) {
      throw new TransactionValidationException("Insufficient balances provided for fees")
    }

    /* Timestamp is after most recent block, not in future */
    if (cme.timestamp <= timestamp) {
      throw new TransactionValidationException("ProgramMethodExecution attempts to write into the past")
    }
    if (cme.timestamp > Instant.now.toEpochMilli) {
      throw new TransactionValidationException("ProgramMethodExecution timestamp is too far into the future")
    }
  }.flatMap(_ => semanticValidity(cme))

  /**
    *
    * @param ar :  the AssetRedemption to validate
    * @return
    */
  //noinspection ScalaStyle
  def validateAssetRedemption(ar: AssetRedemption): Try[Unit] = {
    val statefulValid: Try[Unit] = {

      /* First check that all the proposed boxes exist */
      val availableAssetsTry: Try[Map[String, Long]] = ar.unlockers
        .foldLeft[Try[Map[String, Long]]](Success(Map[String, Long]()))((partialRes, unlocker) =>

        partialRes.flatMap(partialMap =>
          /* Checks if unlocker is valid and if so adds to current running total */
          closedBox(unlocker.closedBoxId) match {
            case Some(box: AssetBox) =>
              if (unlocker.boxKey.isValid(box.proposition, ar.messageToSign) && (box.issuer equals ar.issuer)) {
                Success(partialMap
                  .get(box.assetCode) match {
                  case Some(amount) => partialMap + (box.assetCode -> (amount + box.value))
                  case None => partialMap + (box.assetCode -> box.value)
                })
              } else {
                Failure(new TransactionValidationException("Incorrect unlocker"))
              }
            case None => Failure(
              new TransactionValidationException(s"Box for unlocker $unlocker is not in the state"))
          }
        )
      )

      /* Make sure that there's enough to cover the remainders */
      val enoughAssets = availableAssetsTry.flatMap(availableAssets =>
        ar.remainderAllocations
          .foldLeft[Try[Unit]](Success()) { case (partialRes, (assetCode, remainders)) =>
          partialRes.flatMap(_ => availableAssets.get(assetCode) match {
            case Some(amount) => if (amount > remainders.map(_._2).sum) {
              Success()
            } else {
              Failure(new TransactionValidationException("Not enough assets"))
            }
            case None => Failure(new TransactionValidationException("Asset not included in inputs"))
          })
        }
      )

      /* Handles fees */
      val boxesSumTry: Try[Long] = {
        ar.unlockers
          .tail
          .foldLeft[Try[Long]](Success(0L))((partialRes, unlocker) => {
          partialRes
            .flatMap(total => closedBox(unlocker.closedBoxId) match {
              case Some(box: PolyBox) =>
                if (unlocker.boxKey.isValid(box.proposition, ar.messageToSign)) {
                  Success(total + box.value)
                } else {
                  Failure(new TransactionValidationException("Incorrect unlocker"))
                }
              case None => Failure(new TransactionValidationException(s"Box for unlocker $unlocker is not in the state"))
            })
        })
      }

      /* Incorrect unlocker or box provided, or not enough to cover declared fees */
      val enoughToCoverFees = Try {
        if (boxesSumTry.isFailure || boxesSumTry.get < ar.fee) {
          throw new TransactionValidationException("Insufficient balances provided for fees")
        }
      }

      enoughAssets.flatMap(_ => enoughToCoverFees)
    }

    statefulValid.flatMap(_ => semanticValidity(ar))
  }

  def validateCoinbaseTransaction(cb: CoinbaseTransaction): Try[Unit] = {
    val t = history.modifierById(cb.blockID).get
    def helper(m: BifrostBlock): Boolean = { m.id sameElements t.id }
    val validConstruction: Try[Unit] = {
      assert(cb.fee == 0L) // no fee for a coinbase tx
      //assert(cb.newBoxes.size == 1) // one one new box
      //assert(cb.newBoxes.head.isInstanceOf[ArbitBox]) // the new box is an arbit box
      // This will be implemented at a consensus level
      Try {
        // assert(cb.newBoxes.head.asInstanceOf[ArbitBox].value == history.modifierById(history.chainBack(history.bestBlock, helper).get.reverse(1)._2).get.inflation)
      }
    }
    validConstruction
  }
}

object BifrostState extends ScorexLogging {

  type T = Any
  type TX = BifrostTransaction
  type P = ProofOfKnowledgeProposition[PrivateKey25519]
  type BX = BifrostBox
  type BPMOD = BifrostBlock
  type GSC = GenericStateChanges[T, P, BX]
  type BSC = BifrostStateChanges

  //noinspection ScalaStyle
  def semanticValidity(tx: TX): Try[Unit] = {
    tx match {
      case poT: PolyTransfer => PolyTransfer.validate(poT)
      case arT: ArbitTransfer => ArbitTransfer.validate(arT)
      case asT: AssetTransfer => AssetTransfer.validate(asT)
      case ac: AssetCreation => AssetCreation.validate(ac)
      case cc: ProgramCreation => ProgramCreation.validate(cc)
      case prT: ProfileTransaction => ProfileTransaction.validate(prT)
      case cme: ProgramMethodExecution => ProgramMethodExecution.validate(cme)
      case ar: AssetRedemption => AssetRedemption.validate(ar)
      case cb: CoinbaseTransaction => CoinbaseTransaction.validate(cb)
      case _ => throw new UnsupportedOperationException(
        "Semantic validity not implemented for " + tx.getClass.toGenericString)
    }
  }

  def changes(mod: BPMOD) : Try[GSC] = Try {
    val initial = (Set(): Set[Array[Byte]], Set(): Set[BX], 0L)

    val gen = mod.forgerBox.proposition

    val boxDeltas: Seq[(Set[Array[Byte]], Set[BX], Long)] = mod.transactions match {
      case Some(txSeq) => txSeq.map(tx => (tx.boxIdsToOpen.toSet, tx.newBoxes.toSet, tx.fee))
    }

    val (toRemove: Set[Array[Byte]], toAdd: Set[BX], reward: Long) =
      boxDeltas.foldLeft((Set[Array[Byte]](), Set[BX](), 0L))((aggregate, boxDelta) => {
        (aggregate._1 ++ boxDelta._1, aggregate._2 ++ boxDelta._2, aggregate._3 + boxDelta._3)
      })

    val rewardNonce = Longs.fromByteArray(mod.id.take(Longs.BYTES))

    var finalToAdd = toAdd
    if (reward != 0) finalToAdd += PolyBox(gen, rewardNonce, reward)

    //no reward additional to tx fees
    BifrostStateChanges(toRemove, finalToAdd, mod.timestamp)
  }

  def readOrGenerate(settings: ForgingSettings, callFromGenesis: Boolean = false, history: BifrostHistory): BifrostState = {
    val dataDirOpt = settings.dataDirOpt.ensuring(_.isDefined, "data dir must be specified")
    val dataDir = dataDirOpt.get

    new File(dataDir).mkdirs()

    val iFile = new File(s"$dataDir/state")
    iFile.mkdirs()
    val stateStorage = new LSMStore(iFile)

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        stateStorage.close()
      }
    })
    val version = stateStorage
      .lastVersionID
      .fold(Array.emptyByteArray)(_.data)

    var timestamp: Long = 0L
    if (callFromGenesis) {
      timestamp = System.currentTimeMillis()
    } else {
      timestamp = Longs.fromByteArray(stateStorage
        .get(ByteArrayWrapper(FastCryptographicHash("timestamp".getBytes)))
        .get
        .data)
    }

    val stateBoxRegistry = StateBoxRegistry.readOrGenerate(settings)

    BifrostState(stateStorage, version, timestamp, history, stateBoxRegistry)
  }

  def genesisState(settings: ForgingSettings, initialBlocks: Seq[BPMOD], history: BifrostHistory): BifrostState = {
    initialBlocks
      .foldLeft(readOrGenerate(settings, callFromGenesis = true, history)) {
        (state, mod) => state
          .changes(mod)
          .flatMap(cs => state.applyChanges(cs, mod.id))
          .get
    }
  }
}
