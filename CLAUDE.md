# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Scala 2.13 backend for managing video downloads, built on the Typelevel stack (cats-effect, http4s, doobie, fs2).
Uses sbt 2.x. The build compiles with `-Xfatal-warnings`, so any compiler warning fails the build.

## Commands

```bash
sbt compile                  # Compile all modules
sbt test                     # Run all tests
sbt "core/testOnly *ClockSpec"                                  # Single test class (glob)
sbt "api/testOnly com.ruchij.api.services.user.UserServiceSpec" # Single test class (FQN)
sbt testWithCoverage         # Clean, test with scoverage, open HTML report
sbt cleanCompile / cleanTest # Aliases for clean;compile / clean;test

sbt "development/run"        # Run everything (migrations + API + batch) in one JVM,
                             # backed by Docker containers via TestContainers; HTTPS on port 443
sbt "api/run"                # API server only (default: http://localhost:8000)
sbt "batch/run"              # Batch worker only
sbt "migration-application/run"  # Run Flyway migrations

sbt "api/Universal/packageBin"   # Package a distributable app (also batch, migration-application)

docker-compose up -d         # Full stack: Postgres, Redis, Kafka, Schema Registry, API x3, batch x3, nginx
```

Integration-style tests use TestContainers, so Docker must be running. `core` tests are forked and run sequentially
(`Test / parallelExecution := false` for that module only).

## Module Structure

sbt modules and their dependency chain:

- **migration-application** — Flyway migration runner; SQL scripts live in
  `migration-application/src/main/resources/db/migration/`
- **core** — depends on migration-application. Shared DAOs, services, messaging, Redis KV store
- **api** — REST API (http4s Ember server, WebSocket download progress). Depends on
  `core % "compile->compile;test->test"`
- **batch** — download worker: scheduler, video enrichment, file sync. Same core dependency
- **development** — aggregates all of the above into a single runnable app (`DevelopmentApp`)

Not part of the sbt build: `fallback-api/` (Python AWS SAM Lambda), `terraform/`, `playbooks/` (Ansible), `nginx/`,
`forward-proxy/` (VPN proxy container — its `.env.docker-compose` and `.vpn-config/` contain secrets and must stay
gitignored).

## Architecture

**Tagless final everywhere.** Services and apps are parameterized over `F[_]: Async` (concrete type is `IO`, applied
only in the `*App` main objects). Compiler plugins `kind-projector` and `better-monadic-for` are enabled.

**Two-effect service pattern.** Database-touching services take two type parameters, e.g.
`VideoServiceImpl[F[_], T[_]]`, where `T` is doobie's `ConnectionIO`. DAOs (`Doobie*Dao` objects in `core/.../daos/`
and `api/.../daos/`) are written purely in `ConnectionIO`; services compose DAO calls into transactions and lift them
via an `implicit transactor: ConnectionIO ~> F` (natural transformation from the Hikari transactor). No DI framework —
everything is wired by hand in `ApiApp.program` / `BatchApp` / `DevelopmentApp`.

**Pluggable messaging** (`core/.../messaging/`). `PubSub[F, A]` = `Publisher` + `Subscriber`. Three backends selected
at runtime by `PUBSUB_TYPE` (`Kafka` | `Redis` | `Doobie`): Kafka uses Avro + Schema Registry, Redis Streams and
Doobie (Postgres-as-queue) use JSON. Each message type has an `implicit case object` `MessagingTopic[A]` instance
declaring the topic name plus both an Avro codec (vulcan) and a JSON codec (circe). `Subscriber` abstracts backend
wrapper types with a type member `C[_]` (Kafka's `CommittableRecord` vs identity). API and batch communicate
exclusively through these topics (scheduled downloads, download progress, health checks, metrics).

**Redis KV store** (`core/.../kv/`). `KeyValueStore[F]` wrapped by `KeySpacedKeyValueStore` for typed key spaces —
used for auth session tokens, health checks, and dynamic config (`ConfigurationService`).

**ResourcesProvider pattern** (the `external/` packages in core, api, and batch). `CoreResourcesProvider` /
`ApiResourcesProvider` abstract where external services come from, with three implementations each: `local`
(already-running services), `containers` (TestContainers), and `embedded` (embedded Redis/Kafka). Tests and
`DevelopmentApp` use these; production parses `application.conf` instead.

**Configuration.** PureConfig reads each module's `src/main/resources/application.conf`; every setting has an env-var
override (`${?VAR}`). Default DB is in-memory H2 in PostgreSQL mode; production uses PostgreSQL.

**BuildInfo.** Packaged apps generate `BuildInfo` objects (git branch/commit, build timestamp) under the
`com.eed3si9n.ruchij.*` packages via sbt-buildinfo — defined in `packagedApp(...)` in `build.sbt`.

## Conventions

- When bumping any dependency version in `project/Dependencies.scala` or the sbt version in
  `project/build.properties`, update the matching entries in `README.md` — the Technology Stack table, and the
  Prerequisites list (which also mentions the sbt version).
- Commit messages follow the existing style, e.g. `Bump dependencies: Http4s to 0.23.36, Flyway to 12.11.0.`
- Markdown files must keep lines at 120 characters or fewer.
