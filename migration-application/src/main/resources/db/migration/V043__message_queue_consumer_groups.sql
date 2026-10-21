-- Consumer groups for the Doobie (database-backed) message queue. A group only receives messages with an id above
-- start_id, which is the latest message on the channel when the group first subscribed.
CREATE TABLE message_queue_consumer_group (
    channel VARCHAR(255) NOT NULL,
    group_id VARCHAR(255) NOT NULL,
    start_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (channel, group_id)
);

-- One row per message claimed by a consumer group. Claims are tracked per message rather than with a single cursor,
-- since BIGSERIAL ids become visible in commit order: a message with a lower id can show up after a higher one.
CREATE TABLE message_queue_delivery (
    group_id VARCHAR(255) NOT NULL,
    message_id BIGINT NOT NULL,
    delivered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (group_id, message_id),
    CONSTRAINT fk_message_queue_delivery_message_id
        FOREIGN KEY (message_id) REFERENCES message_queue (id) ON DELETE CASCADE
);

CREATE INDEX idx_message_queue_delivery_message_id ON message_queue_delivery (message_id);
