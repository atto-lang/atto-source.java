-- v0 experimental durable actor storage. Apply explicitly; never from an actor callback.
-- Tenant identity is part of every primary/foreign key; adapters must still authorize callers.
CREATE TABLE IF NOT EXISTS ores_durable_actor (
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  activation_epoch bigint NOT NULL DEFAULT 0 CHECK (activation_epoch >= 0),
  active boolean NOT NULL DEFAULT false,
  lease_owner text,
  lease_expires_at timestamptz,
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

-- Workflow routing requires a unique tuple for an outstanding (installation,repo,sha,ref).
-- Multiple independent workflows may coexist if they target different SHA/ref pairs.
CREATE TABLE IF NOT EXISTS ores_durable_webhook_expectation (
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  installation_id bigint NOT NULL CHECK (installation_id > 0),
  repository_id bigint NOT NULL CHECK (repository_id > 0),
  expected_sha text NOT NULL CHECK (expected_sha ~ '^[0-9a-f]{40}$'),
  expected_ref text NOT NULL CHECK (left(expected_ref,11) = 'refs/heads/'),
  iteration integer NOT NULL CHECK (iteration > 0),
  expected_state_version bigint NOT NULL CHECK (expected_state_version >= 0),
  stage text NOT NULL CHECK (stage IN ('waiting_webhook','closed')),
  PRIMARY KEY (tenant_id,workflow_id,run_id,node_id),
  FOREIGN KEY (tenant_id,workflow_id,run_id,node_id)
    REFERENCES ores_durable_actor (tenant_id,workflow_id,run_id,node_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS ores_durable_unique_waiting_webhook
  ON ores_durable_webhook_expectation (installation_id,repository_id,expected_sha,expected_ref)
  WHERE stage='waiting_webhook';

-- Claims are advisory leases for external effect dispatch, not exactly-once guarantees.
ALTER TABLE ores_durable_outbox
  ADD COLUMN IF NOT EXISTS claim_token text,
  ADD COLUMN IF NOT EXISTS claim_until timestamptz,
  ADD COLUMN IF NOT EXISTS claim_epoch bigint,
  ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;

-- GitHub deliveries are unique across workflow actors for the same App
-- installation; keep this idempotency ledger even after the inbox is consumed.
CREATE TABLE IF NOT EXISTS ores_durable_github_delivery (
  installation_id bigint NOT NULL CHECK (installation_id > 0),
  delivery_id text NOT NULL,
  repository_id bigint NOT NULL CHECK (repository_id > 0),
  tenant_id text NOT NULL,
  expected_sha text NOT NULL,
  expected_ref text NOT NULL,
  workflow_id text NOT NULL,
  run_id text NOT NULL,
  node_id text NOT NULL,
  PRIMARY KEY (installation_id, delivery_id)
);
