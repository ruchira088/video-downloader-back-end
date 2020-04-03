package com.ruchij.web.responses

case class SeqResponse[+A](results: Seq[A])