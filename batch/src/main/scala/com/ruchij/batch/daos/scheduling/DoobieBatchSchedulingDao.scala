package com.ruchij.batch.daos.scheduling

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import doobie.free.connection.ConnectionIO
import doobie.implicits._

object DoobieBatchSchedulingDao extends BatchSchedulingDao[ConnectionIO] {

  override def lockStatusById(id: String): ConnectionIO[Option[SchedulingStatus]] =
    sql"SELECT status FROM scheduled_video WHERE video_metadata_id = $id FOR UPDATE"
      .query[SchedulingStatus]
      .option
}
