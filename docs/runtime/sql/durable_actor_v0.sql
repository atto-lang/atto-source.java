-- v0 experimental durable actor storage. Apply explicitly; never from an actor callback.
-- Tenant identity is part of every primary/foreign key; adapters must still authorize callers.
CREATE TABLE IF NOT EXISTS ores_durable_actor (
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  activation_epoch bigint NOT NULL DEFAULT 0 CHECK (activation_epoch >= 0),
  active boolean NOT NULL DEFAULT false,
  state_version bigint NOT NULL DEFAULT 0 CHECK (state_version >= 0),
  format_version integer NOT NULL DEFAULT 0 CHECK (format_version = 0),
  state_schema_version integer NOT NULL CHECK (state_schema_version >= 0),
  code_generation text NOT NULL,
  encoded_state bytea NOT NULL,
  PRIMARY KEY (tenant_id, workflow_id, run_id, node_id)
);
CREATE TABLE IF NOT EXISTS ores_durable_inbox (
  sequence_number bigint GENERATED ALWAYS AS IDENTITY,
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  delivery_id text NOT NULL, payload bytea NOT NULL, consumed boolean NOT NULL DEFAULT false,
  PRIMARY KEY (tenant_id, workflow_id, run_id, node_id, delivery_id),
  FOREIGN KEY (tenant_id, workflow_id, run_id, node_id)
    REFERENCES ores_durable_actor (tenant_id, workflow_id, run_id, node_id)
);
CREATE INDEX IF NOT EXISTS ores_durable_pending_inbox
  ON ores_durable_inbox (tenant_id, workflow_id, run_id, node_id, sequence_number) WHERE NOT consumed;
CREATE TABLE IF NOT EXISTS ores_durable_outbox (
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  intent_id text NOT NULL, idempotency_key text NOT NULL, payload bytea NOT NULL,
  receipt_id text,
  PRIMARY KEY (tenant_id, workflow_id, run_id, node_id, intent_id),
  UNIQUE (tenant_id, workflow_id, run_id, node_id, idempotency_key),
  FOREIGN KEY (tenant_id, workflow_id, run_id, node_id)
    REFERENCES ores_durable_actor (tenant_id, workflow_id, run_id, node_id)
);
CREATE INDEX IF NOT EXISTS ores_durable_unacknowledged_outbox
  ON ores_durable_outbox (tenant_id, workflow_id, run_id, node_id) WHERE receipt_id IS NULL;
