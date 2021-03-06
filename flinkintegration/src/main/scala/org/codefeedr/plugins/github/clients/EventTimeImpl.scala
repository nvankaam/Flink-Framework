package org.codefeedr.plugins.github.clients

import org.codefeedr.plugins.github.clients.GitHubProtocol.{Commit, PushEvent}
import org.codefeedr.util.EventTime

object EventTimeImpl {
  implicit val pushEventEventTime: EventTime[PushEvent] = new EventTime[PushEvent] {
    override def getEventTime(a: PushEvent): Option[Long] = Some(a.created_at.getTime)
  }
  implicit val commitEventEventTime: EventTime[Commit] = new EventTime[Commit] {
    override def getEventTime(a: Commit): Option[Long] = None
  }
}
