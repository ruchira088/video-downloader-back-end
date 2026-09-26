# Video Downloader Backend

A Scala-based backend system for managing video downloads, scheduling, and streaming. This service provides a REST
API for video management, metadata handling, user authentication, and batch processing for video downloads.

## Features

- REST API for video management, metadata, and streaming
- Batch processing service for video downloads and synchronization
- Support for multiple video sources (including YouTube via yt-dlp)
- User authentication with role-based access control
- Playlist management
- Video watch history and analytics
- Real-time download progress via WebSocket
- Database migrations with Flyway
- Pluggable messaging backend (Kafka, Redis Streams, or PostgreSQL via Doobie)
- Redis caching layer

## Technology Stack

| Category | Technology |
|----------|------------|
| Language | Scala 2.13.18 |
| Build Tool | sbt 2.0.9 |
| Runtime | Java 25 (Eclipse Temurin) |
| Web Framework | HTTP4s 0.23.37 |
| Effect System | Cats Effect 3.7.1 |
| JSON | Circe 0.14.16 |
| Database Access | Doobie 1.0.0-RC12 |
| Database | PostgreSQL 17 |
| Migrations | Flyway 13.8.0 |
| Messaging | Apache Kafka 8.3.2-ccs / Redis Streams / PostgreSQL (Doobie) |
| Caching | Redis 8 |
| Configuration | PureConfig 0.17.10 |
| AWS | AWS SDK for Java 2.55.6 (SQS, DynamoDB — fallback sync) |
| Networking | Netty 4.1.138.Final (every module pinned to one version for the AWS SDK and Redis clients) |
| Testing | ScalaTest 3.2.20, ScalaMock 7.6.0, Cats Effect Testkit 3.7.1 |

## Prerequisites

- JDK 25 (Eclipse Temurin recommended)
- sbt 2.0.9
- Docker & Docker Compose (for full stack deployment)
- ffmpeg
- yt-dlp (`pip install yt-dlp`)

## Project Structure

```
video-downloader-back-end/
├── api/                          # REST API application
│   └── src/main/scala/com/ruchij/api/
│       ├── ApiApp.scala          # Main entry point
│       ├── config/               # Configuration classes
│       ├── services/             # Business logic
│       └── web/
│           ├── routes/           # HTTP route handlers
│           └── middleware/       # Authentication, CORS, exception handling
│
├── batch/                        # Batch processing service
│   └── src/main/scala/com/ruchij/batch/
│       ├── BatchApp.scala        # Main entry point
│       └── services/             # Scheduling, enrichment, synchronization
│
├── core/                         # Shared core library
│   └── src/main/scala/com/ruchij/core/
│       ├── daos/                 # Data access objects
│       ├── services/             # Shared business logic
│       ├── messaging/            # Pluggable pub/sub (Kafka, Redis Streams, Doobie)
│       └── kv/                   # Redis key-value store
│
├── migration-application/        # Flyway database migration runner
│   └── src/main/resources/db/migration/  # SQL migration scripts
│
├── development/                  # Development mode (all services in one JVM)
│
├── docker-compose/               # Docker Compose orchestration
├── playbooks/                    # Ansible deployment automation
├── nginx/                        # Nginx reverse proxy configuration
├── forward-proxy/                # ExpressVPN/OpenVPN forward proxy (see forward-proxy/README.md)
├── terraform/                    # Infrastructure as Code (AWS)
│
├── build.sbt                     # Main build definition
├── openapi.yaml                  # OpenAPI 3.0.3 specification
└── docker-compose.yml            # Full service orchestration
```

## Getting Started

### Building the Project

```bash
# Compile all modules
sbt compile

# Run all tests (sbt 2's `test` only re-runs tests affected by changes)
sbt testFull

# Run tests with coverage
sbt testWithCoverage

# Package applications
sbt "api/Universal/packageBin"
sbt "batch/Universal/packageBin"
sbt "migrationApplication/Universal/packageBin"
```

### Running Locally

**Option 1: Full Stack with Docker Compose**

```bash
docker-compose up -d
```

This starts all services including PostgreSQL, Redis, Kafka, and the API/Batch applications.

The bundled `forward-proxy` service routes traffic through an ExpressVPN/OpenVPN tunnel.
It reads its credentials from `.env.docker-compose` and its `.ovpn` configs from
`.vpn-config/` in the repository root — the same files are shared with
`forward-proxy/docker-compose.yml`. Both contain secrets and must be gitignored. See
[`forward-proxy/README.md`](forward-proxy/README.md) for setup details.

**Option 2: Development Mode (All Services in One JVM)**

```bash
sbt "development/run"
```

**Option 3: Individual Services**

```bash
# API server (default: http://localhost:8000)
sbt "api/run"

# Batch processor
sbt "batch/run"

# Database migrations
sbt "migrationApplication/run"
```

### Local HTTPS Setup

#### Setting up Local JKS

When prompted for password enter: `changeit`

```bash
mkcert -pkcs12 cert.p12 localhost "api.localhost"

keytool -importkeystore \
  -srckeystore cert.p12 \
  -destkeystore localhost.jks \
  -srcstoretype PKCS12 \
  -deststoretype jks
```

#### Setting up Nginx SSL Certificates

```bash
mkcert -key-file key.pem \
  -cert-file cert.pem \
  localhost "api.localhost" "spa-renderer.localhost"
```

Copy `key.pem` and `cert.pem` to `nginx/ssl/`

## Configuration

### Environment Variables

#### API Service

| Variable | Description | Default |
|----------|-------------|---------|
| `HTTP_HOST` | Server bind address | `0.0.0.0` |
| `HTTP_PORT` | Server port | `8000` |
| `HTTP_ALLOWED_ORIGINS` | CORS allowed origins | - |
| `IMAGE_FOLDER` | Path to store thumbnails | `./images` |
| `VIDEO_FOLDER` | Path to store videos | `./videos` |
| `OTHER_VIDEO_FOLDERS` | Additional video directories | - |
| `DATABASE_URL` | PostgreSQL JDBC URL | H2 in-memory |
| `DATABASE_USER` | Database username | `sa` |
| `DATABASE_PASSWORD` | Database password | - |
| `REDIS_HOSTNAME` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | - |
| `PUBSUB_TYPE` | Messaging backend (`Kafka`, `Redis`, `Doobie`) | `Kafka` |
| `KAFKA_BROKERS` | Kafka bootstrap servers | - |
| `KAFKA_PREFIX` | Kafka topic/group prefix | `local` |
| `SCHEMA_REGISTRY` | Avro Schema Registry URL | - |
| `SESSION_DURATION` | Auth session timeout | - |
| `FALLBACK_SYNC_ENABLED` | Sync videos to the AWS fallback (requires the four settings below) | `false` |
| `FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL` | SQS queue URL for sync messages sent to the fallback | - |
| `FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL` | SQS queue URL for requests coming back from the fallback | - |
| `FALLBACK_SYNC_TABLE_NAME` | DynamoDB table holding the fallback's copy | - |
| `FALLBACK_SYNC_AWS_REGION` | AWS region of the queues and table | - |
| `FALLBACK_SYNC_AWS_ENDPOINT_URL` | Endpoint override for both SQS and DynamoDB (e.g. a local emulator) | - |
| `FALLBACK_SYNC_RECONCILE_ALLOW_MASS_REMOVAL` | Let every reconcile send a mass removal (see below) | `false` |

The queue URLs and table name are outputs of the fallback's SAM stack (`fallback-api/`): `MainToFallbackQueueUrl`,
`FallbackToMainQueueUrl` and `ScheduledVideosTableName`. `FALLBACK_SYNC_AWS_ENDPOINT_URL` applies to both clients;
to point just one of them elsewhere, leave it unset and use the SDK's own `AWS_ENDPOINT_URL_SQS` or
`AWS_ENDPOINT_URL_DYNAMODB` instead.

With fallback sync enabled, the API also needs AWS credentials from the default provider chain for the stack's
`MainSideSyncUser`. The stack creates the user but never its access keys: `terraform/fallback-sync.tf` creates them
for both stages and stores them in Secrets Manager as
`video-downloader/<staging|prod>/fallback-sync/aws-credentials`, a JSON object with `AWS_ACCESS_KEY_ID` and
`AWS_SECRET_ACCESS_KEY`. Apply it once both stacks are deployed, and pass the keys to the API under those names, or
as a static profile in `~/.aws/credentials`. SSO and web-identity credentials are not
supported: the API ships without the SDK's `sso`, `ssooidc` and `sts` modules they need. When enabling it:

- Point each fallback stack at exactly one database. The reconcile removes every video in the table that its own
  database doesn't have, so two databases (e.g. a dev branch and production) sharing a table remove each other's
  videos.
- With `PUBSUB_TYPE=Kafka`, **always** create the `<KAFKA_PREFIX>-fallback-sync-requests` topic before enabling
  sync, even when topic auto-creation is on: its consumer starts from the latest offset, so an auto-created topic
  loses the message that created it. While the topic is missing, publishes block for up to a minute, and the API
  pauses sync requests and flags reconciles instead.
- A reconcile withholds its removals (logging an error, still sending upserts) when the database returns no
  videos while the fallback holds some, or when it would remove more than 50 videos or 20% of the fallback's,
  whichever is more. If such a removal is intended, set `FALLBACK_SYNC_RECONCILE_ALLOW_MASS_REMOVAL=true` and
  restart the API, which runs a reconcile. It is read at startup and applies to every reconcile until the API is
  restarted without it, so unset it and restart again afterwards.
- To be e-mailed when a sync message lands in a dead-letter queue, deploy the stack with its `AlarmEmail`
  parameter, then confirm the SNS subscription from the e-mail AWS sends to that address; until then no alarm is
  delivered.

#### Batch Service

| Variable | Description | Default |
|----------|-------------|---------|
| `HOSTNAME` | Worker hostname identifier | - |
| `MAX_CONCURRENT_DOWNLOADS` | Parallel download limit | - |
| `START_TIME` | Scheduling window start | - |
| `END_TIME` | Scheduling window end | - |
| `INSTANCE_ID` | Unique instance identifier | - |
| `GIT_BRANCH` | Git branch for build info | - |
| `GIT_COMMIT` | Git commit for build info | - |
| `BUILD_TIMESTAMP` | Build timestamp | - |

#### Migration Service

| Variable | Description | Default |
|----------|-------------|---------|
| `HASHED_ADMIN_PASSWORD` | BCrypt-hashed admin password | Hash of `top-secret` |

## API Documentation

The API provides 30+ endpoints organized into the following categories:

### Service Routes
- `GET /service/info` - Service metadata, version, and build info
- `GET /service/health` - Health check endpoint

### Authentication
- `POST /authentication/login` - User login
- `DELETE /authentication/logout` - User logout
- `GET /authentication/user` - Get current user info

### Users
- `POST /users` - Create user
- `POST /users/forgot-password` - Request password reset
- `PUT /users/id/{userId}/reset-password` - Reset password
- `PUT /users/id/{userId}` - Update user
- `DELETE /users/id/{userId}` - Delete user

### Videos
- `GET /videos/search` - Search videos with filters (duration, size, sites)
- `GET /videos/summary` - Video statistics (admin only)
- `GET /videos/history` - User's watch history
- `POST /videos/scan` - Trigger video library scan
- `GET /videos/id/{videoId}` - Get video details
- `PUT /videos/id/{videoId}/metadata` - Update video metadata
- `GET /videos/id/{videoId}/snapshots` - Get video snapshots

### Scheduling
- `GET /schedule/search` - Search scheduled downloads
- `POST /schedule` - Schedule a video download
- `DELETE /schedule/id/{videoId}` - Cancel scheduled download
- `GET /schedule/updates` - WebSocket for real-time download progress
- `GET /schedule/worker-status` - Worker health status

### Playlists
- `GET /playlists` - List playlists
- `POST /playlists` - Create playlist
- `PUT /playlists/id/{playlistId}` - Update playlist
- `DELETE /playlists/id/{playlistId}` - Delete playlist

### Assets
- `GET /assets/thumbnail/id/{id}` - Video thumbnail
- `GET /assets/snapshot/id/{id}` - Video snapshot
- `GET /assets/video/id/{id}` - Stream video file

For the complete API specification, see [openapi.yaml](openapi.yaml).

## Database

### Schema

The database schema is managed through 39 Flyway migration scripts located in
`migration-application/src/main/resources/db/migration/`.

Key tables include:
- `file_resource` - File metadata storage
- `video_metadata` - Video metadata (title, duration, size, site)
- `video` - Video records linking to files and metadata
- `scheduled_video` - Download queue and scheduling
- `worker` - Batch worker registration
- `api_user` - User accounts with BCrypt password hashing
- `playlist` - User playlists
- `video_watch_history` - Watch history tracking
- `video_permission` - Access control

### Running Migrations

```bash
# Via sbt
sbt "migrationApplication/run"

# Via Docker Compose
docker-compose up migration-application
```

Migrations run automatically before API/Batch services start in Docker Compose.

## Testing

```bash
# Run all tests (sbt 2's `test` only re-runs tests affected by changes)
sbt testFull

# Run with coverage
sbt testWithCoverage

# View coverage report
sbt viewCoverageResults
```

### Test Infrastructure

- **Unit tests**: In-memory H2 database
- **Integration tests**: TestContainers (PostgreSQL, Kafka, Redis)
- **Embedded services**: H2 database plus embedded Kafka and Schema Registry for isolated testing

## Messaging

The messaging layer uses a pluggable `PubSub` abstraction, configured via the `PUBSUB_TYPE` environment variable
(or `pubsub-configuration.type` in `application.conf`).

| Backend | Serialization | Use Case |
|---------|---------------|----------|
| **Kafka** | Avro (Schema Registry) | Production — high throughput, durable messaging |
| **Redis** | JSON (Circe) | Lightweight deployments — uses Redis Streams |
| **Doobie** | JSON (Circe) | Simple deployments — uses PostgreSQL as a message store |

All backends implement the unified `MessagingTopic[A]` trait, which provides both an Avro codec (for Kafka) and a
JSON codec (for Redis/Doobie). The `Subscriber` trait uses a type member `C[_]` to abstract over backend-specific
wrapper types (e.g., Kafka's `CommittableRecord` for offset management vs. identity for Redis/Doobie).

## Docker Deployment

### Services

The `docker-compose.yml` orchestrates the following services:

| Service | Port | Description |
|---------|------|-------------|
| API (x3) | 8000 | REST API instances |
| Batch (x3) | - | Batch processing workers |
| PostgreSQL | 5432 | Primary database |
| Redis | 6379 | Cache layer |
| Kafka | 9092 | Message broker (KRaft mode, default pubsub backend) |
| Schema Registry | 8081 | Avro schema management |
| Redpanda Console | 3000 | Kafka monitoring UI |
| Nginx (load balancer) | 80, 443 | Load balancer / reverse proxy |
| Forward Proxy | 8888 | ExpressVPN/OpenVPN forward proxy |
| SPA Renderer | - | Server-side rendering for the front-end SPA |
| Front End | - | Web UI |

### Building Docker Images

```bash
# Build all images
ansible-playbook playbooks/build-docker-images.yml
```

Images are published to `ghcr.io/ruchira088/` via GitHub Actions.

## CI/CD

GitHub Actions workflows handle continuous integration and deployment:

- **backend-test.yml** - Runs on all PRs/commits: compiles, tests, caches dependencies
- **backend-pipeline.yml** - Full pipeline: build, test, Docker publish, deploy
- **backend-daily-pipeline.yml** - Daily scheduled builds

### Deployment Stages

1. **Build & Test** - Compile and run test suite
2. **Publish Docker Images** - Build and push to GHCR
3. **Deploy to Dev** - Non-main branch deployments
4. **Deploy to Staging** - Main branch (requires approval)
5. **Deploy to Production** - After staging succeeds

## License

This project is proprietary software.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `sbt testFull`
5. Submit a pull request to the `main` branch
