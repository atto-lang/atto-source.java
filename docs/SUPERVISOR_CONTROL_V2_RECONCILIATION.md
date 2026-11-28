# Supervisor CONTROL V2 — live lane reconciliation

Port of #1560 onto main 274c48ae93bf6f13655a96da4e50ba2cfa0b6d37. Main already owns the terminal CONTROL transport, parent dispatch, bounded bursts, root fallback, and preflighted all-or-nothing transfers. Preserve those paths and saturating metrics instead of reinstalling the obsolete staged DTO or lane.

Each poll returns opaque exact-attempt Delivery authority. Stale, cross-lane and post-transfer settlement fail closed. Delivery, root wrapper and Optional are constructed before dequeue. Transfers acquire a shared coordinator before either lane monitor; existing reservation preflight and FIFO are retained. Direct and live runtime tests cover this change; compiled guest and Native Image qualification are separate gates. Exact-head JDK25 CI and independent review required; no private export. Historical isolated model evidence does not qualify this live path.
