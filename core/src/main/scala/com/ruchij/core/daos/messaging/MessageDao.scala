package com.ruchij.core.daos.messaging

trait MessageDao[F[_]] {
  def insert(channel: String, payload: String): F[Int]

  def notify(channel: String): F[Int]

  def maxId(channel: String): F[Long]

  def findAfter(channel: String, afterId: Long): F[List[(Long, String)]]
}
