package com.ruchij.api.web.requests.queryparams

import cats.ApplicativeError
import cats.data.Kleisli
import com.ruchij.api.web.requests.queryparams.QueryParameter._
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.services.models.Order
import org.http4s.QueryParamDecoder

import scala.concurrent.duration.FiniteDuration

abstract class SingleValueQueryParameter[A: QueryParamDecoder](key: String, defaultValue: A)
    extends QueryParameter[A] {
  override def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, A] =
    QueryParameter.parse[F, A](key).map(_.headOption.getOrElse(defaultValue))
}

object SingleValueQueryParameter {
  case object PageNumberQueryParameter extends SingleValueQueryParameter(key = "page-number", defaultValue = 0)

  case object PageSizeQueryParameter extends SingleValueQueryParameter(key = "page-size", defaultValue = 25)

  case object DurationRangeQueryParameter extends SingleValueQueryParameter[RangeValue[FiniteDuration]](key = "duration", defaultValue = RangeValue.all[FiniteDuration])

  case object SizeRangeQueryParameter extends SingleValueQueryParameter[RangeValue[Long]](key = "size", defaultValue = RangeValue.all[Long])

  case object SearchTermQueryParameter extends SingleValueQueryParameter[Option[String]](key = "search-term", defaultValue = None)

  case object OrderQueryParameter extends SingleValueQueryParameter[Order]("order", defaultValue = Order.Descending)

  case object DeleteVideoFileQueryParameter extends SingleValueQueryParameter[Boolean]("delete-video-file", defaultValue = false)
}
