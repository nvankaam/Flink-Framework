package org.codefeedr.core.library.internal.kafka.sink

import java.lang
import java.util.UUID

import org.apache.flink.api.java.tuple
import org.apache.flink.types.Row
import org.codefeedr.configuration.KafkaConfiguration
import org.codefeedr.core.library.internal.KeyFactory
import org.codefeedr.core.library.metastore.{JobNode, SubjectNode}
import org.codefeedr.model._
import org.codefeedr.util.NoEventTime._

class RowSink(subjectNode: SubjectNode,
              jobNode: JobNode,
              kafkaConfiguration: KafkaConfiguration,
              kafkaProducerFactory: KafkaProducerFactory,
              epochStateManager: EpochStateManager,
              override val sinkUuid: String)
    extends KafkaSink[tuple.Tuple2[lang.Boolean, Row], Row, RecordSourceTrail](
      subjectNode,
      jobNode,
      kafkaConfiguration,
      kafkaProducerFactory,
      epochStateManager,
      s"rowsink ${subjectNode.name}") {
  @transient lazy val keyFactory = new KeyFactory(subjectType, UUID.randomUUID())

  override def transform(value: tuple.Tuple2[lang.Boolean, Row]): (RecordSourceTrail, Row) = {
    val actionType = if (value.f0) ActionType.Add else ActionType.Remove
    //TODO: Optimize these steps
    val record = Record(value.f1, subjectType.uuid, actionType)
    val trailed = TrailedRecord(record, keyFactory.getKey(record))
    (trailed.trail, trailed.row)
  }
}
