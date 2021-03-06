package org.codefeedr.core.library.internal.kafka.meta

import org.codefeedr.model.zookeeper.Partition

//Description of a source Epoch
case class SourceEpoch(partitions: Map[Int, Long], epochId: Long, subjectEpochId: Long)
