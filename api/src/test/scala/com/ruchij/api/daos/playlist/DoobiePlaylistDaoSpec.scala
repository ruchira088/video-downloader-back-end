package com.ruchij.api.daos.playlist

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class DoobiePlaylistDaoSpec extends AnyFlatSpec with Matchers {

  "DoobiePlaylistDao" should "perform CRUD operations" in runIO {
    IO.unit
  }

}
