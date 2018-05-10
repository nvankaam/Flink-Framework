/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.codefeedr.core.library.internal.kafka.source

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.common.typeinfo.{TypeHint, TypeInformation}
import org.apache.flink.api.java.typeutils.ResultTypeQueryable
import org.apache.flink.runtime.state.{
  CheckpointListener,
  FunctionInitializationContext,
  FunctionSnapshotContext
}
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction
import org.apache.flink.streaming.api.functions.source.{RichSourceFunction, SourceFunction}
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext
import org.apache.flink.types.Row
import org.apache.kafka.common.TopicPartition
import org.codefeedr.core.library.internal.kafka.OffsetUtils
import org.codefeedr.core.library.metastore.SubjectNode
import org.codefeedr.core.library.metastore.sourcecommand.SourceCommand
import org.codefeedr.model.{RecordSourceTrail, SubjectType, TrailedRecord}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}

/**
  * Use a single thread to perform all polling operations on kafka
  * Note that new objects will still exist per distributed environment
  *
  * Currently this source is only used as base class for RowSource
  * This source does not support TimestampAssigners yet
  *
  * Because this class needs to be serializable and the LibraryServices are not, no dependency injection structure can be used here :(
  * Created by Niels on 18/07/2017.
  */
abstract class KafkaSource[T](subjectNode: SubjectNode, kafkaConsumerFactory: KafkaConsumerFactory)
//Flink interfaces
    extends RichSourceFunction[T]
    with ResultTypeQueryable[T]
    with CheckpointedFunction
    with CheckpointListener
    //Internal services
    with GenericKafkaSource
    with LazyLogging
    with Serializable {

  @transient protected lazy val consumer: KafkaSourceConsumer[T] = {
    val kafkaConsumer = kafkaConsumerFactory.create[RecordSourceTrail, Row](instanceUuid.toString)
    kafkaConsumer.subscribe(Iterable(topic).asJavaCollection)
    logger.debug(
      s"Source $instanceUuid of consumer $sourceUuid subscribed on topic $topic as group $instanceUuid")
    new KafkaSourceConsumer[T](s"Consumer $getLabel", topic, kafkaConsumer, mapToT)
  }

  //Unique id of the source the instance of this kafka source belongs to
  val sourceUuid: String

  @transient private lazy val topic = s"${subjectType.name}_${subjectType.uuid}"
  @transient private[kafka] lazy val instanceUuid = UUID.randomUUID().toString
  //@transient private lazy val closePromise: Promise[Unit] = Promise[Unit]()
  @transient protected lazy val subjectType: SubjectType =
    subjectNode.getDataSync().get

  //Manager of this source. This should "cleanly" be done by composition, but we cannot do so because this source is "constructed" by Flink
  @transient private[kafka] var manager: KafkaSourceManager = _

  //Running state
  @transient
  @volatile private var state: KafkaSourceState.Value = KafkaSourceState.UnSynchronized
  //Node in zookeeper representing state of the subject this consumer is subscribed on
  @volatile private[kafka] var running = true
  @volatile private[kafka] var inititialized = false

  //CheckpointId after which the source should start shutting down.
  @volatile private[kafka] var finalCheckpointId: Long = Long.MaxValue
  @volatile private[kafka] var finalSourceEpoch: Long = -2

  //TODO: Can we somehow perform this async?
  @transient private[kafka] lazy val finalSourceEpochOffsets =
    Await.result(manager.getEpochOffsets(finalSourceEpoch), Duration(1, SECONDS))
  @transient private[kafka] var checkpointingMode: Option[CheckpointingMode] = _

  //State of the source. We use the mutable map in operation,
  // and when a snapshot is performed we update the liststate. The liststate contains the offsets of the last comitted checkpoint
  @transient private[kafka] lazy val currentOffsets = consumer.getCurrentOffsets
  @transient private[kafka] lazy val checkpointOffsets = mutable.Map[Long, Map[Int, Long]]()
  @transient private var listState: ListState[(Int, Long)] = _
  @volatile private var initialized = false

  @volatile
  @transient private var alignmentOffsets: Map[Int, Long] = _

  /**
    * Get a display label for this source
    * @return
    */
  def getLabel: String = s"KafkaSource ${subjectType.name}($sourceUuid-$instanceUuid)"

  /**
    * Retrieve the current state of the source
    * @return
    */
  def getState: KafkaSourceState.Value = state

  /**
    * Get a readable print of the given partitions.
    * @param partitionOffsets Map of the partitions and their offset
    * @return
    */
  def getReadablePartitions(partitionOffsets: Map[TopicPartition, Long]): String =
    partitionOffsets
      .map(tp => s"p: ${tp._1.topic()}_${tp._1.partition()}, o: ${tp._2}")
      .mkString(", ")

  /**
    * Cancels this source on the final epoch of the source it is subscribed on
    */
  override def cancel(): Unit = {
    finalSourceEpoch =
      Await.result(subjectNode.getEpochs().getLatestEpochId(), Duration(5, SECONDS))
    logger.debug(s"Cancelling $getLabel after final source epoch $finalSourceEpoch.")
  }

  /**
    * Apply a command to perform a (state) transition
    * @param command the command
    */
  def apply(command: SourceCommand): Unit = {

    //Handle the synchronize command
    if (command.command == "prepareSynchronize") {
      if (state == KafkaSourceState.UnSynchronized) {
        state = KafkaSourceState.CatchingUp
        manager.startedCatchingUp()
      } else {
        val msg = s"Invalid state transition from $state to catchingUp"
        logger.error(msg)
        throw new Error(msg)
      }
    }
  }

  def readableOffsets(offsetMap: Map[TopicPartition, Long]): String = {
    offsetMap
      .map(tpo => s"(${tpo._1.topic()}_${tpo._1.partition()} -> ${tpo._2})")
      .mkString("\r\n")
  }

  //Called when restoring the state
  override def initializeState(context: FunctionInitializationContext): Unit = {
    //This construction would normally be done by composition, but because flink constructs the source we cannot do so here
    if (manager == null) {
      manager = new KafkaSourceManager(this, subjectNode, sourceUuid, instanceUuid)
    }
    logger.info(s"Initializing state of $getLabel")
    if (!getRuntimeContext.asInstanceOf[StreamingRuntimeContext].isCheckpointingEnabled) {
      logger.warn(
        "Started a custom source without checkpointing enabled. The custom source is designed to work with checkpoints only.")
      checkpointingMode = None
      // throw new Error(
      // "Started a custom source without checkpointing enabled. The custom source is designed to work with checkpoints only.")

    } else {
      checkpointingMode = Some(CheckpointingMode.EXACTLY_ONCE)
    }
    val descriptor = new ListStateDescriptor[(Int, Long)](
      "collected offsets",
      TypeInformation.of(new TypeHint[(Int, Long)]() {})
    )
    //On restore, we only need to restore the current offsets. We won't need the checkpoint offsets any more
    listState = context.getOperatorStateStore.getListState(descriptor)
    //If the state was nonempty, initialize the offsets with the recieved data
    if (listState.get().asScala.nonEmpty) {
      currentOffsets.clear()
      listState.get().asScala.foreach(o => currentOffsets(o._1) = o._2)
    } else {
      //Otherwise just initialize with the default value
      currentOffsets.values
    }
    initialized = true
  }

  //Called when starting a new checkpoint
  override def snapshotState(context: FunctionSnapshotContext): Unit = {
    logger.debug(s"Snapshotting epoch ${context.getCheckpointId} on $getLabel")
    checkpointOffsets(context.getCheckpointId) = currentOffsets.toMap
    //HACK: Sometimes cancelling on first checkpoint will cause incorrect offsets to be obtained (because no assignment happened yet)
    if (context.getCheckpointId > 1) {
      cancelIfNeeded(context.getCheckpointId)
    }
    //Handle state specific code
    state match {
      case KafkaSourceState.CatchingUp => snapshotCatchingUpState()
      case KafkaSourceState.Ready => snapshotReadyState()
      case _ =>
    }
    logger.debug(s"Done snapshotting epoch ${context.getCheckpointId} on $getLabel")
  }

  private def snapshotCatchingUpState(): Unit = {
    if (Await.result(manager.isCatchedUp(currentOffsets.toMap), Duration(1, SECONDS))) {
      state = KafkaSourceState.Ready
      logger.info(s"Transitioned to ready state in $getLabel")
      manager.notifyCatchedUp()
    }

  }

  /** If th source is in "ready" state, obtain the maximum partition to read up to*/
  private def snapshotReadyState(): Unit = {
    Await.ready(
      async {
        val epoch = await(subjectNode.getEpochs().getLatestEpochId())
        alignmentOffsets = await(manager.getEpochOffsets(epoch)).map(o => o.nr -> o.offset).toMap
      },
      Duration(1, SECONDS)
    )
  }

  /**
    * Checks if a cancel is required
    */
  private def cancelIfNeeded(currentCheckpoint: Long): Unit = {
    if (finalSourceEpoch > -2) {
      logger.debug(s"Attempting to finish on source epoch $finalSourceEpoch")
      logger.debug(s"Current offsets: ${currentOffsets.toMap} ($getLabel)")
      logger.debug(s"Final offsets: $finalSourceEpochOffsets ($getLabel)")
      if (OffsetUtils.HigherOrEqual(currentOffsets.toMap,
                                    finalSourceEpochOffsets.map(o => o.nr -> o.offset).toMap)) {
        logger.debug(s"$getLabel is cancelling after checkpoint $currentCheckpoint completed.")
        finalCheckpointId = currentCheckpoint
      }
    }
  }

  /**
    * The offset commit to kafka is done on the complete
    * @param checkpointId id of the the checkpoint to notify the completion of
    */
  override def notifyCheckpointComplete(checkpointId: Long): Unit = {
    logger.debug(s"$getLabel snapshotting offsets for epoch $checkpointId")
    listState.clear()
    //Obtain the offsets for the checkpoint that completed
    checkpointOffsets(checkpointId).foreach(o => listState.add(o._1, o._2))

    //Check if the final checkpoint completed
    if (checkpointId == finalCheckpointId) {
      logger.debug(s"$getLabel is stopping, final checkpoint $checkpointId completed")
      running = false
    }

    consumer.commit(checkpointOffsets(checkpointId))
  }

  /**
    * Performs all operations needed to start up the consumer
    * Blocks on the creation of zookeeper state
    */
  private[kafka] def initRun(): Unit = {
    if (!initialized) {
      throw new Exception(s"Cannot run $getLabel before calling initialize.")
    }
    manager.initializeRun()
    manager.cancel.onComplete(o => cancel())
  }

  /**
    * Finalizes the run
    * Called form the notifyCheckpointComplete, because the cleanup cannot occur until the last checkpoint has been completed
    */
  private[kafka] def finalizeRun(): Unit = {
    logger.debug(s"$getLabel performing finalization step")
    //First close the kafkaconsumer
    consumer.close()
    manager.finalizeRun() //Notify manager for distributed state update
    //Notify of the closing, to whoever is interested
    //closePromise.success()
  }

  def mapToT(record: TrailedRecord): T

  /**
    * @return A future that resolves when the source has been close
    */
  //private[kafka] def awaitClose(): Future[Unit] = closePromise.future

  /**
    * Perform a poll on the kafka consumer and collect data on the given method
    * Should be  under checkpoint lock because all of this method depends on the dataConsumer, which is not built for multi-threaded access
    */
  def poll(ctx: SourceFunction.SourceContext[T]): Unit = {
    ctx.getCheckpointLock.synchronized {
      val offsets =
        if (state == KafkaSourceState.Ready || state == KafkaSourceState.Synchronized) {
          //If synchronized, poll up to the maximum offsets
          consumer.poll(ctx, alignmentOffsets)
        } else {
          //Otherwise, just poll
          consumer.poll(ctx)
        }
      offsets.foreach(o => {
        currentOffsets(o._1) = o._2
      })
    }
  }

  /**
    * When synchronizing, we must make sure to read all data up to the desired offsets, before releasing the checkpoint lock
    * When the desired offset has been reached, it should no longer pull any data
    * @param ctx context to lock on and emit data to
    * @return
    */
  def synchronizedPoll(ctx: SourceFunction.SourceContext[T],
                       offsets: Map[TopicPartition, Long]): Unit = {}

  /**
    * Called at the start of a synchronized epoch
    * Determines the endOffsets for the current epoch
    */
  def startSynchronizedEpoch(): Unit = {
    subjectNode.getEpochs()
  }

  override def run(ctx: SourceFunction.SourceContext[T]): Unit = {
    logger.debug(s"Source $getLabel started running.")
    initRun()
    inititialized = true

    var increment = 0

    while (running) {
      poll(ctx)

      //HACK: Workaround to support running the source without checkpoints enabled. Currently just performs a checkpoint every loop. Need a proper solution for this!
      if (checkpointingMode.isEmpty) {
        logger.info(s"Performing fake checkpoint in $getLabel")
        increment += 1
        val context = new FunctionSnapshotContext {
          override def getCheckpointId: Long = increment
          override def getCheckpointTimestamp: Long = 0
        }
        //Snapshot the current state
        snapshotState(context)
        //And directly complete checkpoint
        notifyCheckpointComplete(increment)
      }
      //Give the checkpoint algorithm a chance to obtain the checkpoint lock
      //TODO: Find a way to perform most of the "poll" operations outside the checkpoint lock
      Thread.sleep(1)
    }

    logger.debug(s"Source reach endOffsets $getLabel")

    //Perform cleanup under checkpoint lock
    ctx.getCheckpointLock.synchronized {
      finalizeRun()
      logger.debug(s"Source $getLabel stopped running.")
    }

  }

  /**
    *
    * @param id index of the checkpoint to await
    */
  def awaitCheckpoint(id: Long): Unit = {}

  /**
    * Get typeinformation of the returned type
    * @return
    */
  def getProducedType: TypeInformation[T]
}
