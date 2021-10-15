package com.ruchij.core.external.containers

import org.testcontainers.containers.PostgreSQLContainer

class PostgresContainer extends PostgreSQLContainer[PostgresContainer]("postgres:14")
