CREATE TABLE scheduled_video(
    index BIGSERIAL,
    scheduled_at TIMESTAMP NOT NULL,
    last_updated_at TIMESTAMP NOT NULL,
    in_progress BOOLEAN NOT NULL,
    key VARCHAR(127),
    downloaded_bytes BIGINT,
    completed_at TIMESTAMP NULL,

    PRIMARY KEY (key),

    CONSTRAINT fk_scheduled_video_key FOREIGN KEY (key) REFERENCES video_metadata(key)
);