CREATE TABLE permission(
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    video_id VARCHAR(127) NOT NULL,
    user_id VARCHAR(127) NOT NULL,

    CONSTRAINT fk_video_permission_video_id FOREIGN KEY (video_id) REFERENCES scheduled_video(video_metadata_id),
    CONSTRAINT fk_video_permission_api_user_id FOREIGN KEY (user_id) REFERENCES api_user(id),

    PRIMARY KEY (video_id, user_id)
);