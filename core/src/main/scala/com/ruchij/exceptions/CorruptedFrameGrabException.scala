package com.ruchij.exceptions

case object CorruptedFrameGrabException extends Exception {
  override def getMessage: String = "FrameGrab is corrupted"
}