CREATE TABLE video (
    index BIGSERIAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    video_metadata_key VARCHAR(127),
    file_resource_id VARCHAR(127) NOT NULL,

    PRIMARY KEY (video_metadata_key),

    CONSTRAINT fk_video_metadata FOREIGN KEY (video_metadata_key) REFERENCES video_metadata(key),
    CONSTRAINT fk_video_file_resource FOREIGN KEY (file_resource_id) REFERENCES file_resource(id)
);