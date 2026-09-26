package com.ruchij.api.services.fallback

import io.circe.{Json, Printer}
import io.circe.parser.parse

import java.nio.file.{Files, Path, Paths}

object ContractFixtures {
  // Forked tests run from the module directory and unforked ones from the repository root, so search upwards.
  private lazy val directory: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("fallback-api").resolve("contract"))
      .find(Files.isDirectory(_))
      .getOrElse(throw new IllegalStateException("fallback-api/contract not found"))

  def read(name: String): String = Files.readString(directory.resolve(name))

  def json(name: String): Json = parse(read(name)).fold(throw _, identity)

  def canonical(json: Json): String = Printer.noSpacesSortKeys.print(json)
}
