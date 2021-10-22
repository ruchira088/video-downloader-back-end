ALTER TABLE playlist ADD COLUMN user_id VARCHAR(127) NOT NULL;

ALTER TABLE playlist ADD CONSTRAINT fk_playlist_user_id FOREIGN KEY (user_id) REFERENCES api_user(id);