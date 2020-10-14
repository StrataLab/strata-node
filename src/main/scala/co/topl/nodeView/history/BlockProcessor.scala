package co.topl.nodeView.history

import co.topl.consensus
import co.topl.crypto.FastCryptographicHash
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.nodeView.history.BlockProcessor.ChainCache
import co.topl.nodeView.history.GenericHistory.ProgressInfo
import co.topl.utils.Logging
import io.iohk.iodb.ByteArrayWrapper

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap

class BlockProcessor private (cache: ChainCache, maxDepth: Int) extends Logging {

  import BlockProcessor._

  private var chainCache = cache

  /**
   * Publicly accessible method to check if a block can be applied into the cache
   *
   * @param modifier block that we are attempting to apply into the cache
   * @return 'true' if the parent ID of the given modifier is available in the cache
   */
  def applicableInCache(modifier: Block): Boolean = chainCache.getCacheBlock(modifier.parentId).nonEmpty

  /**
    * Publicly accessible method to check if a block exists in the cache
    *
    * @param id block id that we are checking for
    * @return 'true' is the block ID of the given modifier is available in the cache
    */
  def contains(id: ModifierId): Boolean = chainCache.getCacheBlock(id).nonEmpty

  /**
    * Publicly accessible method to retrieve a block from the cache
    *
    * @param id id of the block to retrieve
    * @return
    */
  def getCacheBlock(id: ModifierId): Option[CacheBlock] = chainCache.getCacheBlock(id)

  /**
    * Process a single block and determine if any of the possible chains in the
    * chain cache are taller than the main chain section
    *
    * @param block - new block to put in cache
    * @return
    */
  def process(history: History, block: Block): ProgressInfo[Block] = {
    // check if the current block is starting a new branch off the main chain
    val pi: ProgressInfo[Block] = if (history.applicable(block)) {
      val parentDifficulty = history.storage.difficultyOf(block.parentId).get
      val prevTimes = history.lastBlocks(4, block).map(prev => prev.timestamp)

      val newHeight = history.storage.heightOf(block.parentId).get + 1
      val newBaseDifficulty = consensus.calcNewBaseDifficulty(parentDifficulty, prevTimes)

      chainCache = chainCache.add( block, newHeight, newBaseDifficulty, prevTimes )

      ProgressInfo(None, Seq.empty, Seq.empty, Seq.empty)

    // if not starting a new branch, can it extend an already known tine?
    } else if (applicableInCache(block)) {
      val cacheParent = chainCache.getCacheBlock(block.parentId).get

      val parentDifficulty = cacheParent.baseDifficulty
      val prevTimes = (cacheParent.prevBlockTimes :+ block.timestamp).takeRight(4)

      val newHeight = cacheParent.height + 1
      val newBaseDifficulty = consensus.calcNewBaseDifficulty(parentDifficulty, prevTimes)

      chainCache = chainCache.add( block, newHeight, newBaseDifficulty, prevTimes )

        // if new chain is longer, calculate and return the ProgressInfo needed to switch tines
        if (newHeight > history.height) {
          val newChain = possibleChain(chainCache.getCacheBlock(block.id).get)
          val commonAncestor = history.modifierById(newChain.head.parentId).get
          val oldChain = history.lastBlocks(
            history.height - history.storage.heightOf(commonAncestor.id).get,
            history.bestBlock )

          ProgressInfo(Some(commonAncestor.id), oldChain, newChain, Seq.empty)

        // otherwise, exit after adding the new block to the chain cache
        } else ProgressInfo(None, Seq.empty, Seq.empty, Seq.empty)

    // if no parent, log and do not add the block to the chain cache
    } else {
      log.warn(s"Received orphan block")
      ProgressInfo(None, Seq.empty, Seq.empty, Seq.empty)
    }

    // following the calculation of progressInfo, clean up the cache below the maxDepth
    chainCache = chainCache.dropUntil(history.height - maxDepth)

    // return ProgressInfo to the append method
    pi
  }

  /**
   * construct a sequence of blocks to be switched to
   *
   * @param from starting point to recurse from at the tip of the chain
   * @return a sequence of blocks from the new highest block to the common ancestor
   */
  private def possibleChain(from: CacheBlock): Seq[Block] = {
    @tailrec
    def loop( currBlock: Option[CacheBlock], height: Long, acc: Seq[Block]): Seq[Block] = {
      currBlock match {
        case Some(b) ⇒ loop(chainCache.getCacheBlock(b.block.parentId), height - 1, b.block +: acc)
        case None ⇒ acc
      }
    }

    loop(Some(from), from.height, Seq.empty)
  }
}




object BlockProcessor extends Logging {

  private implicit val ord: Ordering[CacheBlock] =
    Ordering[(Long, ModifierId)].on(x => (x.height, x.block.id))

  def apply(maxDepth: Int): BlockProcessor = new BlockProcessor(emptyCache, maxDepth)

  def emptyCache: ChainCache = ChainCache(TreeMap.empty)

  def chainStatusKey(id: ModifierId): ByteArrayWrapper =
    ByteArrayWrapper(
      FastCryptographicHash("main_chain".getBytes ++ id.hashBytes)
    )

  /** Wrapper for storing a block and its height in the chain cache */
  case class CacheBlock(block: Block, height: Long, baseDifficulty: Long, prevBlockTimes: Seq[Block.Timestamp])

  /** Stores links mapping ((id, height) -> parentId) of blocks that could possibly be applied. */
  case class ChainCache(cache: TreeMap[CacheBlock, ModifierId]) {

    val nonEmpty: Boolean = cache.nonEmpty

    def getParentId(id: ModifierId): Option[ModifierId] =
      getCacheBlock(id) match {
        case Some(cb) => cache.get(cb)
        case None     => None
      }

    def getCacheBlock(id: ModifierId): Option[CacheBlock] =
      cache.keys.find(k ⇒ k.block.id == id)

    def getHeight(id: ModifierId): Option[Long] =
      cache.keys.find(k ⇒ k.block.id == id).map(_.height)

    def add(block: Block, height: Long, parentDifficulty: Long, prevTimes: Seq[Block.Timestamp]): ChainCache = {
      val cacheBlock = CacheBlock(block, height, parentDifficulty, prevTimes)

      log.debug(s"Added new block to chain cache: $cacheBlock")
      ChainCache(cache.insert(cacheBlock, block.parentId))
    }

    def dropUntil(height: Long): ChainCache =
      ChainCache(cache.dropWhile(_._1.height < height))

  }
}
