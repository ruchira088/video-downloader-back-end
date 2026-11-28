package com.ruchij.migration.config

import pureconfig.ConfigReader

final case class DatabaseConfiguration(url: String, user: String, password: String) derives ConfigReader
