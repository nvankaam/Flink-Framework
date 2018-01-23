package org.codefeedr.Core.Library.Metastore

import org.codefeedr.Core.Library.Internal.Zookeeper.{ZkNode, ZkNodeBase}
import org.codefeedr.Model.Zookeeper.Producer

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ProducerNode(name: String, parent: ZkNodeBase) extends ZkNode[Producer](name, parent){
  override def PostCreate(): Future[Unit] = async {
    await(GetState().Create(true))
  }

  /**
    * Retrieves the state of the consumer, checks if the consumer is still open
    * @return
    */
  def GetState(): ZkNode[Boolean] = GetChild[Boolean]("state")
}
