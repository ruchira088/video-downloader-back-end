package com.ruchij.services.scheduling
import com.ruchij.daos.scheduling.models.VideoMetadata
import org.http4s.Uri

class SchedulingServiceImpl[F[_]] extends SchedulingService[F] {

  override def schedule(uri: Uri): F[VideoMetadata] = ???
}
