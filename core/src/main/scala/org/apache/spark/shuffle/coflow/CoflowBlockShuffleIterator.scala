package org.apache.spark.shuffle.coflow

import org.apache.spark.{Logging, TaskKilledException, TaskContext}
import org.apache.spark.storage.{ShuffleBlockId, BlockId, BlockManager}
import java.util.concurrent.{ThreadFactory, Executors, ThreadPoolExecutor, LinkedBlockingQueue}
import org.apache.spark.network.{NioByteBufferManagedBuffer, ManagedBuffer}
import scala.concurrent.{ExecutionContext, Future}
import java.nio.ByteBuffer
import org.apache.spark.serializer.Serializer
import org.apache.spark.util.Utils
import java.util.concurrent.atomic.AtomicInteger
import com.google.common.util.concurrent.ThreadFactoryBuilder

/**
 * Created by hWX221863 on 2014/9/26.
 */
private[spark] class CoflowBlockShuffleIterator(
    val context: TaskContext,
    shuffleId: Int,
    reduceId: Int,
    blockMapIdsAndSize: Array[(Long, Int)],
    serializer: Serializer,
    coflowManager: CoflowManager,
    blockManager: BlockManager)
  extends Iterator[Iterator[Any]] with Logging {

  private[this] var numBlocksProcessed = 0
  private[this] val numBlocksToFetch = blockMapIdsAndSize.count(block => block._1 > 0)

  private[this] val blocks = new LinkedBlockingQueue[Iterator[Any]]
  private[this] val shuffleMetrics = context.taskMetrics.createShuffleReadMetricsForDependency()

  private[this] val threadPool = Executors.newCachedThreadPool()

  initialize()

  private[this] def initialize() {
    blockMapIdsAndSize.map(block => block._2).foreach(mapId => {
      // create a fetch block data task
      val blockFetcher = new Runnable {
        override def run(): Unit = {
          val dataBuffer: ByteBuffer = fetchBlock(mapId)
          logDebug(s"get block[shuffle id = $shuffleId, map id = $mapId, reduce id = $reduceId] data.")
          if(dataBuffer.array().length > 0) {
            val managedBuffer = new NioByteBufferManagedBuffer(dataBuffer)
            val blockIterator = serializer.newInstance().deserializeStream(
              blockManager.wrapForCompression(ShuffleBlockId(shuffleId, mapId, reduceId),
                managedBuffer.inputStream())).asIterator
            blocks.put(blockIterator)
          }
        }
      }
      // submit task to fetch data and put it into blocks queue
      threadPool.submit(blockFetcher)
    })

    threadPool.shutdown()
  }

  private[this] def fetchBlock(mapId: Int): ByteBuffer = {
    val fileId: String = CoflowManager.makeFileId(shuffleId, mapId, reduceId)
    logInfo(s"start to fetch block[$fileId] data through coflow")
    val data = coflowManager.getFile(shuffleId, fileId)
    ByteBuffer.wrap(data)
  }

  def hasNext: Boolean = {
      numBlocksProcessed < numBlocksToFetch
  }

  def next(): Iterator[Any] = {
    numBlocksProcessed += 1
    logInfo("fetch shuffle[shuffle id = %d, reduce id = %d] block %d time(s), total %d."
      .format(shuffleId, reduceId, numBlocksProcessed, numBlocksToFetch))

    val startFetchWait = System.currentTimeMillis()
    val block = blocks.take()
    val stopFetchWait = System.currentTimeMillis()
    shuffleMetrics.fetchWaitTime += (stopFetchWait - startFetchWait)
    block
  }
}