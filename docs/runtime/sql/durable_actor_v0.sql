-- v0 experimental durable actor storage. Apply explicitly; never from an actor callback.
-- Tenant identity is part of every primary/foreign key; adapters must still authorize callers.
CREATE TABLE IF NOT EXISTS ores_durable_actor (
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  activation_epoch bigint NOT NULL DEFAULT 0 CHECK (activation_epoch >= 0),
  active boolean NOT NULL DEFAULT false,
  lease_owner uuid,
  lease_until timestamptz,
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
  claim_token uuid,
  claim_epoch bigint,
  claim_until timestamptz,
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  PRIMARY KEY (tenant_id, workflow_id, run_id, node_id, intent_id),
  UNIQUE (tenant_id, workflow_id, run_id, node_id, idempotency_key),
  FOREIGN KEY (tenant_id, workflow_id, run_id, node_id)
    REFERENCES ores_durable_actor (tenant_id, workflow_id, run_id, node_id)
);
CREATE INDEX IF NOT EXISTS ores_durable_unacknowledged_outbox
  ON ores_durable_outbox (tenant_id, workflow_id, run_id, node_id) WHERE receipt_id IS NULL;

-- One expected webhook wait per logical actor. The ingress transaction obtains
-- the actor row lock, then compares both the stored state_version and the
-- identity/ref/SHA/iteration below before enqueuing exactly one delivery.
CREATE TABLE IF NOT EXISTS ores_durable_webhook_wait (
  tenant_id text NOT NULL, workflow_id text NOT NULL, run_id text NOT NULL, node_id text NOT NULL,
  installation_id bigint NOT NULL CHECK (installation_id > 0),
  repository_id bigint NOT NULL CHECK (repository_id > 0),
  expected_ref text NOT NULL,
  expected_sha text NOT NULL CHECK (expected_sha ~ '^[0-9a-f]{40}$'),
  iteration bigint NOT NULL CHECK (iteration > 0),
  state_version bigint NOT NULL CHECK (state_version >= 0),
  stage text NOT NULL CHECK (stage IN ('waiting_webhook', 'done')),
  PRIMARY KEY (tenant_id,workflow_id,run_id,node_id),
  FOREIGN KEY (tenant_id,workflow_id,run_id,node_id)
    REFERENCES ores_durable_actor(tenant_id,workflow_id,run_id,node_id)
);
CREATE INDEX IF NOT EXISTS ores_durable_wait_repository
  ON ores_durable_webhook_wait (installation_id,repository_id,expected_sha,expected_ref)
  WHERE stage='waiting_webhook';

-- Existing v0 databases predate the lease/claim columns. These additions are
-- idempotent; apply under an operator-controlled migration before deploying
-- the new adapter. Live lease cutover requires all old writers to be stopped.
ALTER TABLE ores_durable_actor ADD COLUMN IF NOT EXISTS lease_owner uuid;
ALTER TABLE ores_durable_actor ADD COLUMN IF NOT EXISTS lease_until timestamptz;
ALTER TABLE ores_durable_outbox ADD COLUMN IF NOT EXISTS claim_token uuid;
ALTER TABLE ores_durable_outbox ADD COLUMN IF NOT EXISTS claim_epoch bigint;
ALTER TABLE ores_durable_outbox ADD COLUMN IF NOT EXISTS claim_until timestamptz;
ALTER TABLE ores_durable_outbox ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
