CREATE TABLE video_metadata(
    index BIGSERIAL,
    id VARCHAR(127),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    url VARCHAR(2047) NOT NULL UNIQUE,
    video_site VARCHAR(127) NOT NULL,
    title VARCHAR(255) NOT NULL,
    duration BIGINT NOT NULL,
    size BIGINT NOT NULL,
    thumbnail_id VARCHAR(127) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_video_metadata_thumbnail FOREIGN KEY (thumbnail_id) REFERENCES file_resource(id)
);