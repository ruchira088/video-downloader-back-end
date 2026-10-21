package com.ruchij.api.services.asset

import com.ruchij.api.daos.user.models.User
import com.ruchij.api.services.asset.AssetService.FileByteRange
import com.ruchij.api.services.asset.models.Asset
import com.ruchij.api.services.asset.models.AssetType.{AlbumArt, Snapshot, Thumbnail, Video}

trait AssetService[F[_]] {
  def videoFile(
    id: String,
    user: User,
    fileByteRange: Option[FileByteRange],
    maybeMaxStreamSize: Option[Long]
  ): F[Asset[F, Video.type]]

  def snapshot(id: String, user: User): F[Asset[F, Snapshot.type]]

  def albumArt(id: String, user: User): F[Asset[F, AlbumArt.type]]

  /**
    * Unlike the other assets, a thumbnail is not checked against the user's permissions. It belongs to the video's
    * metadata, which POST /videos/metadata creates or returns for any video URL before the video is scheduled, and
    * the front-end shows it in the schedule page's preview, when the user has no permission for the video yet. Since
    * anyone with the video's URL can get the metadata, and so the thumbnail, a permission check would protect nothing.
    */
  def thumbnail(id: String): F[Asset[F, Thumbnail.type]]
}

object AssetService {

  /** A single byte range of a Range request (RFC 7233), before it is resolved against the size of the file */
  sealed trait FileByteRange

  object FileByteRange {

    /** bytes=first-last or bytes=first- where both positions are inclusive */
    final case class ByteRange(first: Long, last: Option[Long]) extends FileByteRange

    /** bytes=-length, the last length bytes of the file */
    final case class SuffixRange(length: Long) extends FileByteRange
  }
}
