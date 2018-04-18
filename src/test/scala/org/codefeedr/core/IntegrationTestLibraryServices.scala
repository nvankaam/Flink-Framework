package org.codefeedr.core

import com.typesafe.config.Config
import org.codefeedr.core.engine.query.StreamComposerFactoryComponent
import org.codefeedr.core.library.{ConfigFactoryComponent, LibraryServices, SubjectFactoryComponent}
import org.codefeedr.core.library.internal.kafka.sink.{KafkaProducerFactory, KafkaProducerFactoryComponent}
import org.codefeedr.core.library.internal.kafka.source.{KafkaConsumerFactory, KafkaConsumerFactoryComponent}
import org.codefeedr.core.library.internal.zookeeper.{ZkClient, ZkClientComponent}
import org.codefeedr.core.library.metastore.{SubjectLibrary, SubjectLibraryComponent}

trait IntegrationTestLibraryServices extends ZkClientComponent
  with SubjectLibraryComponent
  with ConfigFactoryComponent
  with KafkaConsumerFactoryComponent
  with KafkaProducerFactoryComponent
  with SubjectFactoryComponent
  with StreamComposerFactoryComponent {
  override val zkClient: ZkClient = LibraryServices.zkClient
  override val subjectLibrary: SubjectLibrary = LibraryServices.subjectLibrary
  override val conf: Config = LibraryServices.conf
  override val kafkaConsumerFactory: KafkaConsumerFactory = LibraryServices.kafkaConsumerFactory
  override val kafkaProducerFactory: KafkaProducerFactory = LibraryServices.kafkaProducerFactory
  val subjectFactory: SubjectFactoryController = LibraryServices.subjectFactory.asInstanceOf[SubjectFactoryController]
  val streamComposerFactory: StreamComposerFactory = LibraryServices.streamComposerFactory.asInstanceOf[StreamComposerFactory]
}