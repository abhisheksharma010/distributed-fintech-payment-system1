CREATE TABLE failed_events (
                               id UUID PRIMARY KEY,
                               event_type VARCHAR(255),
                               payload TEXT,
                               failure_reason VARCHAR(255),
                               created_at TIMESTAMP
);