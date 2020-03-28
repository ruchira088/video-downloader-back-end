CREATE TABLE video_metadata(
    index BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    uri VARCHAR(2047),
    video_site VARCHAR(127),
    title VARCHAR(255),
    duration BIGINT,
    size BIGINT,
    thumbnail VARCHAR(2047),
    PRIMARY KEY (uri)
)