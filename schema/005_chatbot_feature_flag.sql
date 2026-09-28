-- Administrator-controlled runtime switch. The environment setting remains a hard kill switch.
CREATE TABLE service_feature_flags (
    feature_key VARCHAR(50) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_feature_flags (feature_key, enabled) VALUES ('CHATBOT', true);
