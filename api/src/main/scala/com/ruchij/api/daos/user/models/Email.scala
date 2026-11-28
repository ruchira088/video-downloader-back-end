package com.ruchij.api.daos.user.models

import doobie.Meta

final case class Email(value: String) extends AnyVal

object Email {
  implicit val emailMeta: Meta[Email] = Meta[String].timap(Email.apply)(_.value)
}
