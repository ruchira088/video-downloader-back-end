package com.ruchij.core.types

trait NamedToString extends Product {
  override def toString: String =
    productElementNames.zip(productIterator)
      .map { case (key, value) =>
        val valueString = value match {
          case Some(v) => s"Some (\n\t${v.toString.replaceAll("\n", "\n\t")}\n)"
          case _ => value.toString
        }

        if (valueString.contains("\n")) {
          s"\t$key = ${valueString.replaceAll("\n", "\n\t")}"
        } else {
          s"\t$key = $valueString"
        }
      }
      .mkString(s"$productPrefix (\n", ",\n", "\n)")
}
