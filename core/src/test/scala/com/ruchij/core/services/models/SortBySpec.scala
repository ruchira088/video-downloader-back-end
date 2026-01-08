package com.ruchij.core.services.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SortBySpec extends AnyFlatSpec with Matchers {

  "SortBy.withNameInsensitive" should "parse all valid sort by values" in {
    SortBy.withNameInsensitive("size") mustBe SortBy.Size
    SortBy.withNameInsensitive("SIZE") mustBe SortBy.Size
    SortBy.withNameInsensitive("duration") mustBe SortBy.Duration
    SortBy.withNameInsensitive("date") mustBe SortBy.Date
    SortBy.withNameInsensitive("title") mustBe SortBy.Title
    SortBy.withNameInsensitive("watch-time") mustBe SortBy.WatchTime
    SortBy.withNameInsensitive("random") mustBe SortBy.Random
  }

  "SortBy.withNameOption" should "return None for invalid input" in {
    SortBy.withNameOption("invalid") mustBe None
    SortBy.withNameOption("") mustBe None
    SortBy.withNameOption("watchtime") mustBe None
  }

  "SortBy.values" should "contain all SortBy types" in {
    SortBy.values must contain allOf (
      SortBy.Size,
      SortBy.Duration,
      SortBy.Date,
      SortBy.Title,
      SortBy.WatchTime,
      SortBy.Random
    )
    SortBy.values.size mustBe 6
  }

  "SortBy.entryName" should "return correct string representation" in {
    SortBy.Size.entryName mustBe "size"
    SortBy.Duration.entryName mustBe "duration"
    SortBy.Date.entryName mustBe "date"
    SortBy.Title.entryName mustBe "title"
    SortBy.WatchTime.entryName mustBe "watch-time"
    SortBy.Random.entryName mustBe "random"
  }
}
