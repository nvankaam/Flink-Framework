package org.codefeedr.core.library.internal.kafka.sink

import org.codefeedr.core.library.metastore.{JobNode, SubjectNode}
import org.codefeedr.model.{ActionType, Record, RecordSourceTrail, TrailedRecord}

/**
  * A sink for directly dumping trailed records
  * @param subjectNode node of the subject represented by the trailed record
  * @param jobNode node of the job this sink is part of
  * @param kafkaProducerFactory factory for kafka producers
  * @param epochStateManager
  * @param sinkUuid
  */
class TrailedRecordSink(subjectNode: SubjectNode,
                        jobNode: JobNode,
                        kafkaProducerFactory: KafkaProducerFactory,
                        epochStateManager: EpochStateManager,
                        override val sinkUuid: String)
    extends KafkaSink[TrailedRecord, Record, RecordSourceTrail](subjectNode,
                                                                jobNode,
                                                                kafkaProducerFactory,
                                                                epochStateManager) {

  override def transform(value: TrailedRecord): (RecordSourceTrail, Record) = {
    val record = Record(value.row, subjectType.uuid, ActionType.Add)
    (value.trail, record)
  }
}
