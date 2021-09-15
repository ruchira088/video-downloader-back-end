package com.ruchij.api.web.responses

import com.ruchij.core.services.models.Order

case class PagingResponse[A, B](results: Seq[A], pageSize: Int, pageNumber: Int, order: Order, sortBy: Option[B])
