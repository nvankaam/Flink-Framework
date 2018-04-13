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

import org.apache.flink.api.common.typeinfo.{TypeHint, TypeInformation}
import org.apache.flink.api.java.typeutils.TypeExtractor
import org.codefeedr.core.library.{SubjectFactory, TypeInformationServices}
import org.codefeedr.model.{SubjectType, TrailedRecord}
import org.apache.flink.streaming.api.scala._
import org.codefeedr.core.library.metastore.SubjectNode

import scala.reflect.ClassTag
import scala.reflect.runtime.{universe => ru}

class KafkaGenericSource[T: ru.TypeTag: ClassTag: TypeInformation](subjectNode: SubjectNode,
                                                                   override val sourceUuid: String)
    extends KafkaSource[T](subjectNode: SubjectNode) {
  @transient private lazy val Transformer = SubjectFactory.getUnTransformer[T](subjectType)

  override def mapToT(record: TrailedRecord): T = Transformer.apply(record)

  /**
    * Get typeinformation of the returned type
    *
    * @return
    */
  override def getProducedType: TypeInformation[T] = createTypeInformation[T]
}
