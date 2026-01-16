package com.ruchij.api.services.config.models

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ApiConfigKeySpec extends AnyFlatSpec with Matchers {

  "WorkerStatus key" should "have key 'worker-status'" in {
    ApiConfigKey.WorkerStatus.key mustBe "worker-status"
  }

  "ApiConfigKeySpace" should "have name 'api-config'" in {
    ApiConfigKey.ApiConfigKeySpace.name mustBe "api-config"
  }

  it should "not have TTL by default" in {
    ApiConfigKey.ApiConfigKeySpace.maybeTtl mustBe None
  }
}
