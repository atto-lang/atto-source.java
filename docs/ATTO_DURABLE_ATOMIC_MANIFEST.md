# Durable atomic manifest primitive (Java/GraalVM guest vertical slice)

**Scope: a linked `.atto` guest API, not a durable actor/WAL or AMQP confirm.**

`fs.write_text_durable_atomic_async(path, text)` is a deliberately small
privileged OS primitive. The guest issues a single awaited call; the Java
runtime's bounded `AsyncNativeIo` pool dispatches actual filesystem work on
an off-carrier worker. It refuses values above 1 MiB (UTF-8 bytes) before I/O.

On Linux/POSIX with a **pre-created private directory** (mode 0700, trusted
against replacement by other processes), it:

1. checks WRITE against the *lexical* destination AND its lexical parent
   before metadata lookup (an exact-file grant cannot authorize a temp file);
2. canonicalizes the parent and separately checks WRITE on the resolved
   parent and destination, preventing permission bypass via ancestor symlinks;
3. refuses the final symlink and group/world-accessible directories;
4. writes a newly created private sibling temp and `FileChannel.force(true)`;
5. replaces the destination using same-directory `ATOMIC_MOVE`;
6. syncs the containing directory via `FileChannel.force(true)`;
7. reports the returned Future as successful only after step 6.

The method intentionally fails closed when atomic rename or directory sync is
unsupported. Failure *after rename* means **outcome unknown**, not rollback.
No success receipt or AMQP confirm may be inferred from a failed Future.
The present path-based design is NOT secure against a hostile process capable
of replacing the parent or files in a shared namespace while this code runs.
A production actor storage implementation should instead use platform-aware
directory descriptors and trusted custody, with inode checks or `openat2`
as required by its threat model.

The companion `AttoDurableAtomicFilesystemLanguageTest` compiles and executes
linked `.atto` programs through `LinkedProgramRunner`, exercising successful
replace, subsequent read, reopening, symlink denial, denial-before-metadata,
file-only WRITE denial (no sibling-temp authority), symlinked-parent escapes,
and directory permissions. A second approved-only JUnit class
(`AttoDurableManifestProcessKillTest`) starts a **child JVM which compiles
and executes real .atto guest source**, awaits `BinaryFile.append_async`
and `sync_async` for a small WAL record, then writes the durable manifest,
waits for its awaited I/O completion receipt, issues real OS SIGKILL, and
starts a fresh child JVM to read **both** the WAL record and manifest via
another actual .atto guest. **That test has been authored, not
yet executed on the approved private runner.** It is a manifest-only crash
test, not WAL recovery, actor state recovery or OS power-loss proof.
The separate Rust WAL model in `atto-poc/atto-rmq-clone.atto` PR #36
does not compile or run Java Atto guest code.

## Private CI approval

Because this source repository is private, a separately reviewable
[private workflow-only PR #3121](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3121)
adds a `suite=durable_manifest` selection to the existing
`.github/workflows/private-fs-guest-execution.yml` trusted workflow.
That workflow remains on the **main ref** and still requires independent
administrator approval of this feature PR's exact source commit and tree,
**and its selected suite** (`ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SUITE=durable_manifest`),
plus a private source-local ephemeral runner. The feature branch itself does
not alter private-runner admission policy. No guest test execution may be
claimed before PR #3121 is separately reviewed/merged and the approved
source-local job actually completes with non-skipped JUnit receipts.
Never mirror this private source tree into another GitHub organization merely
to borrow Actions minutes.

The required downstream steps remain: true Atto `ClassicQueueActor`
command serialization, off-carrier append/fsync, durable queue generation,
journal replay, fsync-then-state commit, ambiguous receipt reconciliation,
Linux process kill/restart with an independent AMQP client, and Java/LLVM/.NET
runtime parity.

This new primitive writes a **manifest** only. The SIGKILL probe uses
existing `BinaryFile.append_async` and `BinaryFile.sync_async` to create
one small journal record and checks both resources on reopen; it does not
implement per-record CRCs, an atomic multi-file transaction, a persisted
committed offset, writer lease fencing, or actor ownership. Correctness of
a full WAL+manifest transaction and actor admission is not established.
