package com.ruchij.core.formats

import com.ruchij.core.daos.doobie.DatabaseDriver
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.videometadata.models.CustomVideoSite
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.YTDataUnit

// Add every new enum defined in core here
class CoreEnumNamesSpec extends EnumNamesSpec(CoreEnumNamesSpec.enums)

object CoreEnumNamesSpec {
  val enums: List[EnumNames] =
    List(
      EnumNames(
        "SchedulingStatus",
        SchedulingStatus.values.toList,
        List(
          "Active",
          "Completed",
          "Downloaded",
          "Acquired",
          "Stale",
          "Error",
          "WorkersPaused",
          "Paused",
          "Queued",
          "Deleted"
        )
      )(_.entryName, SchedulingStatus.withNameInsensitiveOption),
      EnumNames(
        "WorkerStatus",
        WorkerStatus.values.toList,
        List("Active", "Reserved", "Available", "Paused", "Deleted")
      )(_.entryName, WorkerStatus.withNameInsensitiveOption),
      EnumNames("ScanStatus", ScanStatus.values.toList, List("Idle", "Scheduled", "InProgress", "Error"))(
        _.entryName,
        ScanStatus.withNameInsensitiveOption
      ),
      EnumNames("PubsubType", PubsubType.values.toList, List("Kafka", "Redis", "Doobie"))(
        _.entryName,
        PubsubType.withNameInsensitiveOption
      ),
      EnumNames("Order", Order.values.toList, List("asc", "desc"))(_.entryName, Order.withNameInsensitiveOption),
      EnumNames("SortBy", SortBy.values.toList, List("size", "duration", "date", "title", "watch-time", "random"))(
        _.entryName,
        SortBy.withNameInsensitiveOption
      ),
      EnumNames("YTDataUnit", YTDataUnit.values.toList, List("KiB", "MiB", "GiB"))(
        _.entryName,
        YTDataUnit.withNameInsensitiveOption
      ),
      EnumNames(
        "CustomVideoSite",
        CustomVideoSite.values.toList,
        List("FreshPorno", "PornOne", "SpankBang", "XFreeHD", "SxyPrn", "TXXX", "UPornia", "HClips", "HotMovs", "HdZog")
      )(_.entryName, CustomVideoSite.withNameInsensitiveOption),
      EnumNames("DatabaseDriver", DatabaseDriver.values.toList, List("PostgreSQL", "H2"))(
        _.entryName,
        DatabaseDriver.withNameInsensitiveOption
      )
    )
}
