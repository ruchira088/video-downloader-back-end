package com.ruchij.core.test.external.containers

import org.testcontainers.containers.PostgreSQLContainer

class PostgresContainer extends PostgreSQLContainer[PostgresContainer]("postgres:14")
