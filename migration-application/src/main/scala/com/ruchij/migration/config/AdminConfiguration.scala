package com.ruchij.migration.config

import pureconfig.ConfigReader

final case class AdminConfiguration(hashedAdminPassword: String) derives ConfigReader
