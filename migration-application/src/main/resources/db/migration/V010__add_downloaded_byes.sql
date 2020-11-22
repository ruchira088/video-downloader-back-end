ALTER TABLE scheduled_video
    ADD COLUMN downloaded_bytes BIGINT DEFAULT 0 NOT NULL;

-- UPDATE scheduled_video
--     SET downloaded_bytes = file_resource.size
--     FROM scheduled_video sv
--     JOIN video ON sv.video_metadata_id = video.video_metadata_id
--     JOIN file_resource ON video.file_resource_id = file_resource.id
--     WHERE scheduled_video.video_metadata_id = sv.video_metadata_id;