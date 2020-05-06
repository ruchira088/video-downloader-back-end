CREATE TABLE scheduled_video(
    index BIGSERIAL,
    scheduled_at TIMESTAMP NOT NULL,
    last_updated_at TIMESTAMP NOT NULL,
    in_progress BOOLEAN NOT NULL,
    video_metadata_id VARCHAR(127),
    downloaded_bytes BIGINT,
    completed_at TIMESTAMP NULL,

    PRIMARY KEY (video_metadata_id),

    CONSTRAINT fk_scheduled_video_key FOREIGN KEY (video_metadata_id) REFERENCES video_metadata(id)
);