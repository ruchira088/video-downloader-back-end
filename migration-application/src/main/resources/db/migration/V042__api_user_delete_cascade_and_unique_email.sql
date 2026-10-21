-- Deleting a user also deletes their playlists (and, through V041, the playlists' videos), watch history and
-- password reset tokens.
ALTER TABLE playlist DROP CONSTRAINT fk_playlist_user_id;

ALTER TABLE playlist ADD CONSTRAINT fk_playlist_user_id
    FOREIGN KEY (user_id) REFERENCES api_user(id) ON DELETE CASCADE;

ALTER TABLE video_watch_history DROP CONSTRAINT fk_video_watch_history_user_id;

ALTER TABLE video_watch_history ADD CONSTRAINT fk_video_watch_history_user_id
    FOREIGN KEY (user_id) REFERENCES api_user(id) ON DELETE CASCADE;

ALTER TABLE credentials_reset_token DROP CONSTRAINT fk_credentials_reset_token_user_id;

ALTER TABLE credentials_reset_token ADD CONSTRAINT fk_credentials_reset_token_user_id
    FOREIGN KEY (user_id) REFERENCES api_user(id) ON DELETE CASCADE;

-- Emails are stored and looked up exactly as entered (case-sensitively), so uniqueness is enforced on the raw value.
-- This fails if api_user already holds duplicate emails; those must be resolved before migrating.
ALTER TABLE api_user ADD CONSTRAINT uq_api_user_email UNIQUE (email);
