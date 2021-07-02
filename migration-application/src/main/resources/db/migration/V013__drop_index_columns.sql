ALTER TABLE file_resource DROP COLUMN index;
ALTER TABLE scheduled_video DROP COLUMN index;
ALTER TABLE video DROP COLUMN index;
ALTER TABLE video_metadata DROP COLUMN index;
ALTER TABLE video_snapshot DROP COLUMN index;
ALTER TABLE worker DROP COLUMN index;
ALTER TABLE worker_task DROP COLUMN index;