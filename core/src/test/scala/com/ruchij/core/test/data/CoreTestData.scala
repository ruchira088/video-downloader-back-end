package com.ruchij.core.test.data

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.VideoSite.YTDownloaderSite
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata, VideoSite}
import com.ruchij.core.types.TimeUtils
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object CoreTestData {

  val Timestamp = TimeUtils.instantOf(2021, 10, 20, 20, 12, 8, 321)

  val SpankBangVideo: Video =
    Video(
      VideoMetadata(
        uri"https://spankbang.com/63tot/video/cryst+l+h3+rt",
        "spankbang-65a082c5",
        CustomVideoSite.SpankBang,
        "Crystal Heart",
        43 minutes,
        758219888,
        FileResource(
          "spankbang-65a082c5-fd526102",
          Timestamp,
          "/home/images/thumbnail-spankbang-65a082c5-cryst-l-h3-rt.jpg",
          MediaType.image.jpeg,
          7498
        )
      ),
      FileResource(
        "spankbang-65a082c5",
        Timestamp,
        "/home/videos/spankbang-65a082c5-10256141-720p.mp4",
        MediaType.video.mp4,
        758219888
      ),
      Timestamp,
      10 minutes
    )

  val YouTubeVideo: Video =
    Video(
      VideoMetadata(
        uri"https://www.youtube.com/watch?v=S5n9emOr7SQ",
        "youtube-7488acd8",
        YTDownloaderSite("youtube"),
        "Ed Sheeran - Cross Me",
        215 seconds,
        56702481,
        FileResource(
          "youtube-7488acd8-140a731a",
          Timestamp,
          "/home/images/thumbnail-youtube-7488acd8-hqdefault.jpg",
          MediaType.image.webp,
          10096
        )
      ),
      FileResource("youtube-7488acd8", Timestamp, "/home/videos-1/youtube-7488acd8.mp4", MediaType.video.mp4, 56702481),
      Timestamp,
      13 minutes
    )

  val LocalVideo: Video =
    Video(
      VideoMetadata(
        uri"/home/videos/my_local_1920x1080_4000k.mp4",
        "local-8df3cff",
        VideoSite.Local,
        "My Local Video",
        753 seconds,
        387388087,
        FileResource(
          "snapshot-3cab02a2-75300",
          Timestamp,
          "/home/images/thumbnail-local-8df3cff.png",
          MediaType.image.png,
          361604
        )
      ),
      FileResource(
        "local-8df3cff",
        Timestamp,
        "/home/videos/pornone-c646d7e3-277578829_1920x1080_4000k.mp4",
        MediaType.video.mp4,
        387388087
      ),
      Timestamp,
      340165 milliseconds
    )

}
