package org.codefeedr.plugins.github.generate

import org.codefeedr.ghtorrent.{Issue, IssueComment, PullRequestComment}
import org.codefeedr.plugins.{BaseEventTimeGenerator, GenerationResponse, WaitForNextCheckpoint}
import org.joda.time.DateTime
import org.codefeedr.plugins.github.generate.EventTimeImpl._

class IssueCommentGenerator(seed: Long,
                            checkpoint: Long,
                            offset: Long,
                            issuesPerCheckpoint: Int,
                            val staticEventTime: Option[Long] = None)
    extends BaseEventTimeGenerator[IssueComment](seed, checkpoint, offset) {
  private val types = Array("TypeA", "TypeB")

  override val enableEventTime: Boolean = true

  /**
    * Implement to generate a random value
    *
    * @return
    */
  override def generate(): Either[GenerationResponse, IssueComment] = {
    Right(
      IssueComment(
        issue_id = nextCheckpointRelation(issuesPerCheckpoint),
        user_id = nextInt(10000),
        comment_id = nextId(),
        created_at = nextDateTimeLong(),
        eventTime = getEventTime
      ))
  }
}
