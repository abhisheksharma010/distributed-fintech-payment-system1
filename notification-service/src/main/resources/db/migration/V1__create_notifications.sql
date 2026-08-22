CREATE TABLE notifications (
                               id UUID PRIMARY KEY,

                               user_id UUID NOT NULL,
                               payment_id UUID NOT NULL,

                               type VARCHAR(50) NOT NULL,
                               channel VARCHAR(20) NOT NULL,
                               status VARCHAR(20) NOT NULL,

                               message VARCHAR(500) NOT NULL,

                               created_at TIMESTAMP NOT NULL,
                               sent_at TIMESTAMP,

                               CONSTRAINT uk_notifications_payment_type
                                   UNIQUE (payment_id, type),

                               CONSTRAINT chk_notification_type
                                   CHECK (
                                       type IN (
                                                'PAYMENT_COMPLETED',
                                                'PAYMENT_FAILED'
                                           )
                                       ),

                               CONSTRAINT chk_notification_channel
                                   CHECK (
                                       channel IN ('MOCK')
                                       ),

                               CONSTRAINT chk_notification_status
                                   CHECK (
                                       status IN (
                                                  'PENDING',
                                                  'SENT',
                                                  'FAILED'
                                           )
                                       )
);

CREATE INDEX idx_notifications_user_created
    ON notifications (
                      user_id,
                      created_at DESC
        );

CREATE INDEX idx_notifications_payment
    ON notifications (
                      payment_id
        );