package org.codefeedr.demo.ghtorrent

import net.vankaam.flink.WebSocketSourceFunction
import org.apache.flink.api.common.restartstrategy.RestartStrategies
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.{DataStream, StreamExecutionEnvironment}
import org.codefeedr.core.plugin.SimplePluginComponent
import org.codefeedr.ghtorrent.User
import org.apache.flink.streaming.api.scala._
import org.codefeedr.configuration.ConfigUtilComponent
import org.codefeedr.core.library.SubjectFactoryComponent
import org.codefeedr.serde.GhTorrent._
import org.codefeedr.util.NoEventTime._

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.reflect.ClassTag
import scala.reflect._
import scala.reflect.runtime.{universe => ru}

trait WebSocketJsonPluginComponent extends SimplePluginComponent {
  this: SubjectFactoryComponent
    with ConfigUtilComponent =>

  def createWebSocketJsonPlugin[TData: ru.TypeTag: ClassTag: Serde](
      url: String,
      subject: String,
      batchSize: Int): WebSocketJsonPlugin[TData]

  class WebSocketJsonPlugin[TData: ru.TypeTag: ClassTag: Serde](url: String,
                                                                subject: String,
                                                                batchSize: Int)
      extends SimplePlugin[TData] {

    @transient private lazy val targetType =
      classTag[TData].runtimeClass.asInstanceOf[Class[TData]]
    @transient implicit lazy val typeInfo: TypeInformation[TData] = TypeInformation.of(targetType)
    @transient private lazy val source = WebSocketSourceFunction(url, subject, batchSize)

    /**
      * Method to implement as plugin to expose a datastream
      * Make sure this implementation is serializable!
      *
      * @param env The environment to create the datastream on
      * @return The datastream itself
      */
    override def getStream(env: StreamExecutionEnvironment): DataStream[TData] =
      env
        .addSource(source)
        .map((o: String) => implicitly[Serde[TData]].deserialize(o))
  }

  /**
    * Main class for a simple job that reads data from a websocket,
    * deserializes it and passes it as a kafka subject
    */
  object GhTorrentUserImporter {

    def main(args: Array[String]): Unit = {
      val parameter = ParameterTool.fromArgs(args)
      val url = parameter.getRequired("url")
      val subjectName = parameter.getRequired("subject")
      val batchSize = parameter.getInt("batchSize", 100)

      val plugin = new WebSocketJsonPlugin[User](url, subjectName, batchSize)
      awaitReady(plugin.reCreateSubject())
      val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setRestartStrategy(RestartStrategies.noRestart())
      //@transient implicit lazy val formats: DefaultFormats.type = DefaultFormats
      //env.addSource(socket).map[User]((o:String) => parse(o).extract[User])
      awaitReady(plugin.compose(env, "readusers"))
      env.execute()
    }
  }

  class WebSocketPlugin(url: String, subject: String, batchSize: Int)
      extends SimplePlugin[String] {
    @transient private lazy val source = WebSocketSourceFunction(url, subject, batchSize)

    override def getStream(env: StreamExecutionEnvironment): DataStream[String] =
      env.addSource(source)
  }

}
