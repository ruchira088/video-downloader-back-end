package com.ruchij.core.services.download.models

import cats.data.NonEmptyList
import org.http4s.Header.Parsed
import org.http4s.{Header, HeaderKey, ParseFailure, ParseResult, RangeUnit}
import org.http4s.headers.Range
import org.http4s.HeaderKey.Singleton
import org.http4s.util.{CaseInsensitiveString, Writer}

object RangeFrom extends Singleton {
  override type HeaderT = RangeFrom

  override def name: CaseInsensitiveString = Range.name

  override def matchHeader(header: Header): Option[RangeFrom] =
    Range
      .matchHeader(header)
      .flatMap(fromRange)

  override def parse(input: String): ParseResult[RangeFrom] =
    Range.parse(input).flatMap { range =>
      fromRange(range)
        .fold[ParseResult[RangeFrom]](Left(ParseFailure("Valid Range header but invalid RangeFrom header", input))) {
          Right.apply
        }
    }

  val fromRange: Range => Option[RangeFrom] = {
    case Range(unit, NonEmptyList(head, tail)) =>
      if (unit == RangeUnit.Bytes && head.second.isEmpty && tail.isEmpty) Some(RangeFrom(head.first)) else None
  }
}

case class RangeFrom(start: Long) extends Parsed {
  override def key: HeaderKey = RangeFrom

  override def renderValue(writer: Writer): writer.type =
    writer << RangeUnit.Bytes << "=" << start << "-"
}
