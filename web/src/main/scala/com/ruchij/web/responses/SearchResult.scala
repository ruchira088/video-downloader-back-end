package com.ruchij.web.responses

case class SearchResult[A](results: Seq[A], pageNumber: Int, pageSize: Int, searchTerm: Option[String])