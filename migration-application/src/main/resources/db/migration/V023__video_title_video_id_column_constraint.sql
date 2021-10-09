ALTER TABLE video_title
    DROP CONSTRAINT fk_video_title_video_id;

ALTER TABLE video_title
    ADD CONSTRAINT fk_video_title_video_id FOREIGN KEY (video_id) REFERENCES scheduled_video(video_metadata_id);