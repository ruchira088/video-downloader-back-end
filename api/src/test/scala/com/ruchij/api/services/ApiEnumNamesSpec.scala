package com.ruchij.api.services

import com.ruchij.api.daos.playlist.models.PlaylistSortBy
import com.ruchij.api.daos.user.models.Role
import com.ruchij.api.services.ApiEnumNamesSpec.enums
import com.ruchij.api.services.asset.models.AssetType
import com.ruchij.api.services.health.models.HealthStatus
import com.ruchij.api.web.responses.EventStreamEventType
import com.ruchij.core.formats.{EnumNames, EnumNamesSpec}

// Add every new enum defined in api here
class ApiEnumNamesSpec extends EnumNamesSpec(enums)

object ApiEnumNamesSpec {
  val enums: List[EnumNames] =
    List(
      EnumNames("PlaylistSortBy", PlaylistSortBy.values.toList, List("Title", "CreatedAt"))(
        _.entryName,
        PlaylistSortBy.withNameInsensitiveOption
      ),
      EnumNames("Role", Role.values.toList, List("User", "Admin"))(_.entryName, Role.withNameInsensitiveOption),
      EnumNames("AssetType", AssetType.values.toList, List("Video", "Thumbnail", "Snapshot", "AlbumArt"))(
        _.entryName,
        AssetType.withNameInsensitiveOption
      ),
      EnumNames("HealthStatus", HealthStatus.values.toList, List("Healthy", "Unhealthy"))(
        _.entryName,
        HealthStatus.withNameInsensitiveOption
      ),
      EnumNames(
        "EventStreamEventType",
        EventStreamEventType.values.toList,
        List("heart-beat", "active-download", "scheduled-video-download-update")
      )(_.entryName, EventStreamEventType.withNameInsensitiveOption)
    )
}
