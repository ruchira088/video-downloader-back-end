package com.ruchij.core.services.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class OrderSpec extends AnyFlatSpec with Matchers {

  "Order.withNameInsensitive" should "parse 'asc' to Ascending" in {
    Order.withNameInsensitive("asc") mustBe Order.Ascending
    Order.withNameInsensitive("ASC") mustBe Order.Ascending
    Order.withNameInsensitive("Asc") mustBe Order.Ascending
  }

  it should "parse 'desc' to Descending" in {
    Order.withNameInsensitive("desc") mustBe Order.Descending
    Order.withNameInsensitive("DESC") mustBe Order.Descending
    Order.withNameInsensitive("Desc") mustBe Order.Descending
  }

  "Order.withNameOption" should "return None for invalid input" in {
    Order.withNameOption("invalid") mustBe None
    Order.withNameOption("ascending") mustBe None
    Order.withNameOption("") mustBe None
  }

  "Order.values" should "contain all Order types" in {
    Order.values must contain allOf (Order.Ascending, Order.Descending)
    Order.values.size mustBe 2
  }

  "Order.entryName" should "return correct string representation" in {
    Order.Ascending.entryName mustBe "asc"
    Order.Descending.entryName mustBe "desc"
  }
}
