-- When each consumer group last polled, so that groups idle for longer than the expiry can be deleted, as Kafka does
-- with offsets.retention.minutes. Groups that existed before this migration count as having polled now.
ALTER TABLE message_queue_consumer_group
    ADD COLUMN last_polled_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- The subscription holding each claim, so that a subscription can release the claims it never acknowledged when it
-- ends. Claims made before this migration have none, and are only delivered again once they expire.
ALTER TABLE message_queue_delivery ADD COLUMN consumer_id VARCHAR(255);
