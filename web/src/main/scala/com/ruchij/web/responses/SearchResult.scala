package com.ruchij.web.responses

import com.ruchij.services.models.SortBy

case class SearchResult[A](results: Seq[A], pageNumber: Int, pageSize: Int, searchTerm: Option[String], sortBy: SortBy)