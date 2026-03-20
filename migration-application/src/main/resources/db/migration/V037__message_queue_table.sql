CREATE TABLE message_queue (
    id BIGSERIAL PRIMARY KEY,
    channel VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_message_queue_channel_id ON message_queue (channel, id);
