CREATE TABLE video_snapshot(
    index BIGSERIAL,
    video_id VARCHAR(127) NOT NULL,
    file_resource_id VARCHAR(127) NOT NULL,
    video_timestamp BIGINT NOT NULL,

    CONSTRAINT fk_video_snapshot_video_id FOREIGN KEY (video_id) REFERENCES video(video_metadata_id),
    CONSTRAINT fk_video_snapshot_file_resource_id FOREIGN KEY (file_resource_id) REFERENCES file_resource(id)
)