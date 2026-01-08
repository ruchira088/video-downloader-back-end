# Video Downloader Backend

A Scala-based backend system for managing video downloads, scheduling, and streaming. This service provides a REST API for video management, metadata handling, user authentication, and batch processing for video downloads.

## Features

- REST API for video management, metadata, and streaming
- Batch processing service for video downloads and synchronization
- Support for multiple video sources (including YouTube via yt-dlp)
- User authentication with role-based access control
- Playlist management
- Video watch history and analytics
- Real-time download progress via WebSocket
- Database migrations with Flyway
- Kafka-based messaging for asynchronous operations
- Redis caching layer

## Technology Stack

| Category | Technology |
|----------|------------|
| Language | Scala 2.13.18 |
| Build Tool | sbt 1.12.0 |
| Runtime | Java 25 (Eclipse Temurin) |
| Web Framework | HTTP4s 0.23.33 |
| Effect System | Cats Effect 3.6.3 |
| JSON | Circe 0.14.15 |
| Database Access | Doobie 1.0.0-RC11 |
| Database | PostgreSQL 17 |
| Migrations | Flyway 11.20.1 |
| Message Broker | Apache Kafka 8.1.1 |
| Caching | Redis 8 |
| Configuration | PureConfig 0.17.9 |
| Testing | ScalaTest 3.2.19, ScalaMock 7.5.3 |

## Prerequisites

- JDK 25 (Eclipse Temurin recommended)
- sbt 1.12.0
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
│       ├── messaging/            # Kafka pub/sub
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

# Run tests
sbt test

# Run tests with coverage
sbt testWithCoverage

# Package applications
sbt "api/Universal/packageBin"
sbt "batch/Universal/packageBin"
sbt "migration-application/Universal/packageBin"
```

### Running Locally

**Option 1: Full Stack with Docker Compose**

```bash
docker-compose up -d
```

This starts all services including PostgreSQL, Redis, Kafka, and the API/Batch applications.

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
sbt "migration-application/run"
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
  localhost "api.localhost"
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
| `KAFKA_BROKERS` | Kafka bootstrap servers | `localhost:9092` |
| `SCHEMA_REGISTRY` | Schema Registry URL | `http://localhost:8081` |
| `SESSION_DURATION` | Auth session timeout | - |

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

The database schema is managed through 33 Flyway migration scripts located in `migration-application/src/main/resources/db/migration/`.

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
sbt "migration-application/run"

# Via Docker Compose
docker-compose up migration-application
```

Migrations run automatically before API/Batch services start in Docker Compose.

## Testing

```bash
# Run all tests
sbt test

# Run with coverage
sbt testWithCoverage

# View coverage report
sbt viewCoverageResults
```

### Test Infrastructure

- **Unit tests**: In-memory H2 database
- **Integration tests**: TestContainers (PostgreSQL, Kafka, Redis)
- **Embedded services**: Redis and Kafka Schema Registry for isolated testing

## Docker Deployment

### Services

The `docker-compose.yml` orchestrates the following services:

| Service | Port | Description |
|---------|------|-------------|
| API (x3) | 8000 | REST API instances |
| Batch (x3) | - | Batch processing workers |
| PostgreSQL | 5432 | Primary database |
| Redis | 6379 | Cache layer |
| Kafka | 9092 | Message broker |
| Schema Registry | 8081 | Avro schema management |
| Zookeeper | 2181 | Kafka coordination |
| Kpow | 3000 | Kafka monitoring UI |
| Nginx | 80, 443 | Load balancer / reverse proxy |

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
4. Run tests: `sbt test`
5. Submit a pull request to the `dev` branch
