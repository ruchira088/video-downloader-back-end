package com.ruchij.batch.exceptions

case object CorruptedFrameGrabException extends Exception {
  override def getMessage: String = "FrameGrab is corrupted"
}
