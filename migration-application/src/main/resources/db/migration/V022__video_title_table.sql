CREATE TABLE video_title(
    video_id VARCHAR(127) NOT NULL,
    user_id VARCHAR(127) NOT NULL,
    title VARCHAR(255) NOT NULL,

    PRIMARY KEY (video_id, user_id),
    CONSTRAINT fk_video_title_video_id FOREIGN KEY (video_id) REFERENCES video(video_metadata_id),
    CONSTRAINT fk_video_title_user_id FOREIGN KEY (user_id) REFERENCES api_user(id)
);