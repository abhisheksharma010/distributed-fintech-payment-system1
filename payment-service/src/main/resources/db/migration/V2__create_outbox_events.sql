CREATE TABLE outbox_events (

                               id UUID PRIMARY KEY,

                               aggregate_type VARCHAR(50) NOT NULL,

                               aggregate_id UUID NOT NULL,

                               event_type VARCHAR(100) NOT NULL,

                               payload JSONB NOT NULL,

                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               published BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_outbox_published
    ON outbox_events(published);