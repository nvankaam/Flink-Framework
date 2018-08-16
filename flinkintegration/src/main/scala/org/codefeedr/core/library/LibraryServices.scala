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

package org.codefeedr.core.library

import com.typesafe.config.{Config, ConfigFactory}
import org.codefeedr.configuration.{ConfigurationProvider, KafkaConfiguration, KafkaConfigurationComponent}
import org.codefeedr.core.engine.query.StreamComposerFactoryComponent
import org.codefeedr.core.library.internal.kafka.KafkaControllerComponent
import org.codefeedr.core.library.internal.kafka.sink.{EpochStateManager, EpochStateManagerComponent, KafkaProducerFactoryComponent}
import org.codefeedr.core.library.internal.kafka.source.KafkaConsumerFactoryComponent
import org.codefeedr.core.library.internal.zookeeper.{ZkClient, ZkClientComponent}
import org.codefeedr.core.library.metastore.{SubjectLibrary, SubjectLibraryComponent}

//HACK: To make libraryServices available in the static context
object LibraryServices
    extends ZkClientComponent
    with SubjectLibraryComponent
    with KafkaConsumerFactoryComponent
    with KafkaProducerFactoryComponent
    with KafkaControllerComponent
    with KafkaConfigurationComponent
    with SubjectFactoryComponent
    with StreamComposerFactoryComponent
    with EpochStateManagerComponent {

  lazy override val zkClient = new ZkClient()
  lazy override val subjectLibrary = new SubjectLibrary()
  lazy override val kafkaConsumerFactory = new KafkaConsumerFactory()
  lazy override val kafkaProducerFactory = new KafkaProducerFactory()
  lazy override val subjectFactory = new SubjectFactoryController()
  lazy override val streamComposerFactory = new StreamComposerFactory()
  lazy override val epochStateManager = new EpochStateManager()
  lazy override val kafkaController = new KafkaController()

  //Calls to static configuration provider
  lazy override val kafkaConfiguration = ConfigurationProvider.getKafkaConfiguration

}
