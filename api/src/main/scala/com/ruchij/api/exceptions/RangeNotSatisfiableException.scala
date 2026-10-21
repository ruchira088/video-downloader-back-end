package com.ruchij.api.exceptions

/** The requested byte range does not overlap the file, which is fileSize bytes long */
final case class RangeNotSatisfiableException(fileSize: Long)
    extends Exception(s"Requested range not satisfiable for a resource of $fileSize bytes")
