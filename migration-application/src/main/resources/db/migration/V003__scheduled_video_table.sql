CREATE TABLE scheduled_video(
    index BIGSERIAL,
    scheduled_at TIMESTAMP NOT NULL,
    download_started_at TIMESTAMP NULL,
    last_updated_at TIMESTAMP NOT NULL,
    video_metadata_id VARCHAR(127),
    completed_at TIMESTAMP NULL,

    PRIMARY KEY (video_metadata_id),

    CONSTRAINT fk_scheduled_video_key FOREIGN KEY (video_metadata_id) REFERENCES video_metadata(id)
);