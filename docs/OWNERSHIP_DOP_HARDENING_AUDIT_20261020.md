# Ownership / DOP hardening audit — 2026-10-20

The exact Java semantic blobs from PR #1710 were mirrored into the existing
Oreslang CI capacity branch in streempilot-test and compiled on a real
GitHub-hosted Ubuntu runner with JDK 25.

Verified source blob identities:

- AutoTraitRules.java: 5a536406639c75af9568cbfc7c4114a5324fbfc2
- DataOrientedBorrowChecker.java: e782bd96d41dad75b75356909123423a77fcd606

Mirror workflow run: 39473516056.

The runner reproduced exactly three fail-closed gaps:

1. Arc/Weak concurrency admission currently also manufactures Share. Share
   must additionally require the recursively reachable payload to be Share.
   In particular Arc<Box<T>> may remain Send/Sync when appropriate but must
   not become Share solely because the Arc control block is share-authorized.
2. Comparing logical places from different storage epochs currently returns an
   overlap result. Mixed epochs must be rejected as invalid, including
   read/read comparisons, so stale physical views cannot enter scheduling.
3. Optional archetype/component/field identities currently admit blank strings.
   Any present logical place segment must be nonblank.

RwLock<RefCell<T>> was explicitly probed and correctly remains !Sync; no change
is required there.

The implementation PR must remain draft until the three gaps above are fixed
and the exact-source mirror is rerun against the new blob SHAs.
