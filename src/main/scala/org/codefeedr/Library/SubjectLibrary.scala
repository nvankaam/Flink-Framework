/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.codefeedr.Library

import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.{Calendar, UUID}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.codefeedr.Library.Internal.Kafka.{
  KafkaConsumerFactory,
  KafkaController,
  KafkaProducerFactory
}
import org.codefeedr.Library.Internal.SubjectTypeFactory
import org.codefeedr.Model.{ActionType, SubjectType, SubjectTypeEvent}

import scala.collection.JavaConverters._
import scala.collection.immutable.Iterable
import scala.collection.{concurrent, immutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.runtime.{universe => ru}

import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.duration._

/**
  * ThreadSafe
  * Created by Niels on 14/07/2017.
  */
object SubjectLibrary extends LazyLogging {
  //Topic used to publish all types and topics on
  //MAke this configurable?
  @transient private val SubjectTopic = "Subjects"
  @transient private val SubjectAwaitTime = 10
  @transient private val PollTimeout = 1000
  @transient private lazy val system = ActorSystem("SubjectLibrary")
  @transient private lazy val uuid = UUID.randomUUID()

  //Producer to send type information
  @transient private lazy val subjectTypeProducer: KafkaProducer[String, SubjectTypeEvent] =
    KafkaProducerFactory.create[String, SubjectTypeEvent]

  @transient private lazy val subjects = new SynchronisedSubjects()

  /**
    * Retrieve a subjectType for an arbitrary scala type
    * Creates type information and registers the type in the library
    * @tparam T The type to register
    * @return The subjectType when it is registered in the library
    */
  def GetType[T: ru.TypeTag](): Future[SubjectType] = {
    val typeDef = SubjectTypeFactory.getSubjectType[T]
    val r = subjects.get().get(typeDef.name) map (o => Future { o })
    r.getOrElse(RegisterAndAwaitType[T]())
  }

  /**
    * Get a type with the given uuid.
    * Remains polling the store untill the given uuid occurs in the store
    * @param uuid uuid of the type to find
    * @return A future that will resolve when the type is found
    */
  def GetType(uuid: String)(): Future[SubjectType] = {
    val r = subjects.get().values.filter(o => o.uuid == uuid).toArray
    if (r.length == 1) {
      Future { r(0) }
    } else if (r.length == 0) {
      akka.pattern.after(SubjectAwaitTime milliseconds, using = system.scheduler)(GetType(uuid))
    } else {
      throw new Exception(s"UUID $uuid was not unique in dictionary. This should not happen")
    }
  }

  /**
    * Retrieves the current set of registered subject names
    * This set might not contain new subjects straight after GetType is called, if the future is not yet completed
    * @return
    */
  def GetSubjectNames(): immutable.Set[String] = {
    subjects.get().values.map(o => o.name).toSet
  }

  /**
    * Register a type and resolve the future once the type has been registered
    * Returns a value once the requested type has been found
    * @tparam T Type to register
    * @return The subjectType once it has been registered
    */
  private def RegisterAndAwaitType[T: ru.TypeTag](): Future[SubjectType] = {
    val typeDef = SubjectTypeFactory.getSubjectType[T]
    logger.debug(s"Registering new type ${typeDef.name}")
    KafkaController
      .GuaranteeTopic(typeDef.name)
      .flatMap(_ => {
        val event = SubjectTypeEvent(typeDef, ActionType.Add)
        subjectTypeProducer.send(new ProducerRecord(SubjectTopic, typeDef.name, event))
        //Not sure if this is the cleanest way to do this
        awaitType(typeDef.name)
      })
  }

  /**
    * Rerursive functions that retrieves the type store until the given name occurs
    * @param typeName name of the type to find
    * @return future that will resolve when the given type has been found
    */
  private def awaitType(typeName: String): Future[SubjectType] = {
    if (!subjects.get().contains(typeName)) {
      akka.pattern.after(SubjectAwaitTime milliseconds, using = system.scheduler)(
        awaitType(typeName))
    } else {
      Future {
        subjects.get()(typeName)
      }
    }
  }

  /**
    * Un-register a subject from the library
    * This method is mainly added to make the unit tests have no side-effect, but should likely not be exposed or used in the final product
    * This method currently has unwanted side-effects, and should not be made public in its current state
    * @param name: String
    * @return A future that returns when the subject has actually been removed from the library
    */
  private[Library] def UnRegisterSubject(name: String): Future[Unit] = {
    //Send the removal event
    //Note that this causes an exception if the type is actually not registered
    val event = SubjectTypeEvent(subjects.get()(name), ActionType.Remove)
    subjectTypeProducer.send(new ProducerRecord(SubjectTopic, name, event))

    IsUnregistered(name)
  }

  /**
    * Retrieve a future that resolves whenever the given typename is unregistered
    * Note that this might cause a deadlock when another thread creates the subject again.
    * Should be fixed if this method is made public and used outside unit tests
    * @param typeName the name to wait for
    * @return
    */
  private def IsUnregistered(typeName: String): Future[Unit] = {
    if (subjects.get().contains(typeName)) {
      akka.pattern.after(SubjectAwaitTime milliseconds, using = system.scheduler)(
        IsUnregistered(typeName))
    } else {
      Future {
        Unit
      }
    }
  }

  /**
    * This class is responsible for keeping the current active subjects synchronized
    * Currently every subject request checks kafka for updates and blocks untill a response has been recieved
    */
  private class SynchronisedSubjects() {
    @transient private lazy val subjects = concurrent.TrieMap[String, SubjectType]()

    @transient private lazy val subjectTypeConsumer: KafkaConsumer[String, SubjectTypeEvent] = {
      val consumer = KafkaConsumerFactory.create[String, SubjectTypeEvent](uuid.toString)
      consumer.subscribe(Iterable(SubjectTopic).asJavaCollection)
      consumer
    }

    def get(): Map[String, SubjectType] = {
      this.synchronized {
        subjectTypeConsumer
          .poll(PollTimeout)
          .iterator()
          .asScala
          .map(o => o.value())
          .foreach(handleEvent)

        subjects.toMap
      }
    }
    //Perform initial scan on creation

    /**
      * Event handler managing the internal state of registered subjects
      * @param event: SubjectTypeEvent
      */
    def handleEvent(event: SubjectTypeEvent): Unit =
      event.actionType match {
        case ActionType.Add => insert(event.subjectType)
        case ActionType.Update => update(event.subjectType)
        case ActionType.Remove => delete(event.subjectType)
      }

    def insert(s: SubjectType): Unit =
      if (!subjects.contains(s.name)) {
        subjects.put(s.name, s)
      }

    def update(s: SubjectType): Unit =
      subjects.put(s.name, s)

    def delete(s: SubjectType): Unit =
      if (subjects.contains(s.name)) {
        subjects.remove(s.name)
      }
  }
}