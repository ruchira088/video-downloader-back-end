CREATE TABLE duplicate_video (
    video_id VARCHAR(127) NOT NULL,
    duplicate_group_id VARCHAR(127) NOT NULL,
    created_at TIMESTAMP NOT NULL,

    PRIMARY KEY (video_id),

    CONSTRAINT fk_duplicate_video_video FOREIGN KEY (video_id) REFERENCES video(video_metadata_id)
);
