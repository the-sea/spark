package org.apache.spark.shuffle.coflow

import varys.framework.client.VarysClient
import java.io.File
import org.apache.spark.storage.FileSegment
import varys.VarysException
import varys.framework.CoflowType._
import org.apache.spark.SparkConf

/**
 * Created by hWX221863 on 2014/9/24.
 */
abstract class CoflowManager(executorId: String, conf: SparkConf) {
  val varysMaster: String = CoflowManager.getCoflowMasterUrl(conf)
  val clientName: String = conf.get("spark.app.name", "") + "-" + executorId
  val varysClient: VarysClient = new VarysClient(
                                      clientName,
                                      varysMaster,
                                      new CoflowClientListener)
  
  varysClient.start()

  def getCoflowId(shuffleId: Int): String

  def registerCoflow(shuffleId: Int,
                     coflowName: String,
                     maxFlows: Int,
                     coflowType: CoflowType,
                     size: Long = Int.MaxValue): String
  
  def unregisterCoflow(shuffleId: Int): Unit

  def stop(): Unit

  def putBlock(shuffleId: Int, blockId: String, size: Long, numReceivers: Int) {
    val coflowId: String = getCoflowId(shuffleId)
    varysClient.putFake(blockId, coflowId, size, numReceivers)
  }

  def waitBlockReady(shuffleId: Int, blockId: String) {
    val coflowId: String = getCoflowId(shuffleId)
    varysClient.getFake(blockId, coflowId)
  }

  def putFile(shuffleId: Int, fileId: String, file: File, numReceivers: Int) {
    val coflowId: String = getCoflowId(shuffleId)
    putFile(coflowId, fileId, file.getAbsolutePath, 0, file.length(), numReceivers)
  }

  def putFile(shuffleId: Int, fileId: String, file: FileSegment, numReceivers: Int) {
    val coflowId: String = getCoflowId(shuffleId)
    putFile(coflowId, fileId, file.file.getAbsolutePath, file.offset, file.length, numReceivers)
  }

  def getFile(shuffleId: Int, fileId: String): Array[Byte] = {
    val coflowId: String = getCoflowId(shuffleId)
    varysClient.getFile(fileId, coflowId)
  }

  private def putFile(coflowId: String,
                      fileId: String,
                      path: String,
                      offset: Long,
                      size: Long,
                      numReceivers: Int) {
    varysClient.putFile(fileId, path, coflowId, offset, size, numReceivers)
  }
}

private[spark] object CoflowManager {
  val CoflowEnableConfig = "spark.use.coflow"
  val CoflowMasterConfig = "spark.coflow.master"
  // Fake Block Size
  val BLOCK_SIZE: Long = 1

  def getCoflowMasterUrl(conf: SparkConf): String = {
    val defaultMaster: String = "varys://" + conf.get("spark.driver.host", "localhost") + ":1606"
    conf.get(CoflowMasterConfig, defaultMaster)
  }

  def useCoflow(conf: SparkConf): Boolean = {
    conf.getBoolean(CoflowEnableConfig, false)
  }

  def makeBlockId(mapId: Int, reduceId: Int): String = {
    "shuffle_" + mapId + "_" + reduceId
  }

  def makeCoflowName(shuffleId: Int, conf: SparkConf): String = {
    conf.get("spark.app.name") + "-Shuffle[" + shuffleId + "]-Coflow"
  }
}
