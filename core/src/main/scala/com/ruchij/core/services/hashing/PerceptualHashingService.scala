package com.ruchij.core.services.hashing

import fs2.Stream

trait PerceptualHashingService[F[_]] {

  def hashImage(image: Stream[F, Byte]): F[BigInt]

  def compareHashes(hashA: BigInt, hashB: BigInt): F[Double]

}
