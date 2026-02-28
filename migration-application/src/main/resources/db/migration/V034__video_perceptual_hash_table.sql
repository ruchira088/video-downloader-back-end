CREATE TABLE video_perceptual_hash (
    video_id VARCHAR(127) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    duration BIGINT NOT NULL,
    snapshot_perceptual_hash BIGINT NOT NULL,
    snapshot_timestamp BIGINT NOT NULL,

    PRIMARY KEY (video_id, snapshot_timestamp),

    CONSTRAINT fk_video_perceptual_hash_video FOREIGN KEY (video_id) REFERENCES video(video_metadata_id)
);
