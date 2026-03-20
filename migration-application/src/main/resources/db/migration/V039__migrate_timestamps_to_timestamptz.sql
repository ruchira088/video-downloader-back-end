-- file_resource (V001)
ALTER TABLE file_resource ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- video_metadata (V002)
ALTER TABLE video_metadata ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- scheduled_video (V003, V008)
ALTER TABLE scheduled_video ALTER COLUMN scheduled_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE scheduled_video ALTER COLUMN completed_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE scheduled_video ALTER COLUMN last_updated_at TYPE TIMESTAMP WITH TIME ZONE;

-- video (V004)
ALTER TABLE video ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- worker (V005, V011) — reserved_at dropped in V015, worker_task dropped in V031
ALTER TABLE worker ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE worker ALTER COLUMN task_assigned_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE worker ALTER COLUMN heart_beat_at TYPE TIMESTAMP WITH TIME ZONE;

-- file_sync (V017)
ALTER TABLE file_sync ALTER COLUMN locked_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE file_sync ALTER COLUMN synced_at TYPE TIMESTAMP WITH TIME ZONE;

-- playlist (V018)
ALTER TABLE playlist ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- api_user (V019)
ALTER TABLE api_user ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- credentials (V019)
ALTER TABLE credentials ALTER COLUMN last_updated_at TYPE TIMESTAMP WITH TIME ZONE;

-- permission (V021)
ALTER TABLE permission ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- credentials_reset_token (V024)
ALTER TABLE credentials_reset_token ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- video_watch_history (V026)
ALTER TABLE video_watch_history ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE video_watch_history ALTER COLUMN last_updated_at TYPE TIMESTAMP WITH TIME ZONE;

-- scheduled_video_error (V030)
ALTER TABLE scheduled_video_error ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- video_perceptual_hash (V034)
ALTER TABLE video_perceptual_hash ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;

-- duplicate_video (V035)
ALTER TABLE duplicate_video ALTER COLUMN created_at TYPE TIMESTAMP WITH TIME ZONE;
