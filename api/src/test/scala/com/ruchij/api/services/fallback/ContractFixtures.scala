package com.ruchij.api.services.fallback

import io.circe.{Json, Printer}
import io.circe.parser.parse

import scala.io.Source
import scala.util.Using

object ContractFixtures {
  // build.sbt adds fallback-api/contract as a test resource directory, so the fixtures are on the test classpath.
  def read(name: String): String =
    Option(getClass.getClassLoader.getResource(name))
      .map(url => Using.resource(Source.fromURL(url, "UTF-8"))(_.mkString))
      .getOrElse(throw new IllegalStateException(s"Contract fixture not found on the test classpath: $name"))

  def json(name: String): Json = parse(read(name)).fold(throw _, identity)

  def canonical(json: Json): String = Printer.noSpacesSortKeys.print(json)
}
