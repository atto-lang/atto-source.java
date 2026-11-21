package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Interpreter-backed actor-owned state, separate from class/instance fields.
 * The scheduler admits one actor turn; nested synchronous callbacks on that
 * turn obtain distinct ephemeral loans, with the outer loan fenced while the
 * nested loan is live. No JVM monitor is held while guest code executes.
 *
 * This is a synchronous typed-scalar milestone. Async source handle_state,
 * dynamic maps, FFI, and yielding live state loans must fail closed.
 */
public final class SealedActorState {
    private static final int MAX_FIELDS = 256;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_STRING_BYTES = 1024 * 1024;
    private static final int MAX_OWNED_FIELD_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_OWNED_BYTES = 1024 * 1024;
    private static final int MAX_PENDING_OWNED = 256;
    private static final int MAX_TENSOR_RANK = 16;
    private static final int MAX_TENSOR_ELEMENTS = 1_048_576;
    private static final int MAX_TENSOR_EXTENT = 1_048_576;
    private static final long MAX_TRACKED_TENSOR_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_TRACKED_TENSOR_SNAPSHOTS = 256;
    // Permanent non-null carrier marker installed before retirement clears any
    // state. It closes the check-then-retire race with a future multi-carrier
    // actor: critical() can no longer acquire the state gate once retirement
    // wins the CAS.
    private static final Thread RETIRED_STATE_CARRIER =
            new Thread("ores-retired-state-carrier-sentinel");

    private final ActorRuntime.ActorId owner;
    private final LinkedHashMap<String, String> schema;
    // Swappable as a unit: scalar ML publication never exposes partial field updates.
    private volatile LinkedHashMap<String, Object> values;
    // Nonparking, physical-carrier-exclusive state callback admission.
    // Nested loans on the same carrier are permitted, but two distinct
    // actor carriers must never race on state even if actor maxThreads=3.
    private final java.util.concurrent.atomic.AtomicReference<Thread> stateCarrier =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Access current;
    private long serial;
    private volatile long mutationRevision;
    private int retainedStringBytes;
    private int retainedOwnedBytes;
    private int pendingOwnedBytes;
    private final Set<OwnedBytes> pendingOwned =
            Collections.newSetFromMap(new IdentityHashMap<>());
    // Weak tracking bounds live immutable snapshot retention without making
    // the state root itself keep obsolete weight generations alive forever.
    private final Set<TensorSnapshotF64> tensorSnapshots =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Object tensorSnapshotGate = new Object();
    private volatile boolean retired;

    public SealedActorState(ActorRuntime.ActorId owner,
                            Map<String, String> schema,
                            Map<String, Object> defaults) {
        this.owner = Objects.requireNonNull(owner, "actor identity");
        this.schema = new LinkedHashMap<>(Objects.requireNonNull(schema, "schema"));
        if (this.schema.isEmpty() || this.schema.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("actor state requires 1..256 typed fields");
        }
        this.values = new LinkedHashMap<>();
        for (var item : this.schema.entrySet()) {
            switch (item.getValue()) {
                case "int", "bool", "string", "String", "owned:bytes", "tensor:f64" -> { }
                default -> throw new UnsupportedOperationException(
                        "runtime actor state type is not yet lowered: " + item.getValue());
            }
            values.put(item.getKey(), Uninitialized.INSTANCE);
        }
        for (var entry : Objects.requireNonNull(defaults, "defaults").entrySet()) {
            validateValue(entry.getKey(), entry.getValue());
            int size = stringBytes(entry.getValue());
            if (size > MAX_TOTAL_STRING_BYTES - retainedStringBytes) {
                throw new IllegalArgumentException("actor state string byte budget exceeded");
            }
            retainedStringBytes += size;
            values.put(entry.getKey(), entry.getValue());
        }
    }


    /**
     * Internal proof-of-transfer capsule, deliberately NOT a source-level
     * Owned<T> or public heap alias. The issuer owns the backing bytes until
     * an exclusive loan consumes the capsule into exactly one state field.
     * No method ever returns the raw backing array.
     */
    public final class OwnedBytes {
        private byte[] bytes;
        private OwnedBytes(int size) { bytes = new byte[size]; }
        private SealedActorState issuer() { return SealedActorState.this; }
        private void check() {
            requireOwner();
            if (bytes == null || !pendingOwned.contains(this))
                throw new IllegalStateException("actor-owned bytes already moved or revoked");
        }
        public int length() { check(); return bytes.length; }
        public int read(int index) {
            check();
            return Byte.toUnsignedInt(bytes[Objects.checkIndex(index, bytes.length)]);
        }
        public void write(int index, int unsignedByte) {
            check();
            requireOwnedRegionMutationAuthority();
            if (unsignedByte < 0 || unsignedByte > 255)
                throw new IllegalArgumentException("owned byte value must be 0..255");
            bytes[Objects.checkIndex(index, bytes.length)] = (byte) unsignedByte;
        }
    }

    private static final class OwnedRegion {
        private final byte[] bytes;
        private OwnedRegion(byte[] bytes) { this.bytes = bytes; }
        private void erase() { Arrays.fill(bytes, (byte) 0); }
    }

    /**
     * Trusted Java runtime substrate only. Source-level reference transfers
     * stay rejected until compiler-verified allocation provenance, moves,
     * and LLVM codegen share a documented ABI.
     */
    public OwnedBytes allocateOwnedBytes(int size) {
        requireOwnedRegionMutationAuthority();
        if (size < 0 || size > MAX_OWNED_FIELD_BYTES)
            throw new IllegalArgumentException("invalid actor-owned byte region size");
        if (pendingOwned.size() >= MAX_PENDING_OWNED
                || size > MAX_TOTAL_OWNED_BYTES - retainedOwnedBytes - pendingOwnedBytes)
            throw new IllegalArgumentException("actor-owned allocation budget exceeded");
        OwnedBytes capsule = new OwnedBytes(size);
        pendingOwned.add(capsule);
        pendingOwnedBytes += size;
        return capsule;
    }

    private enum Uninitialized { INSTANCE }

    /**
     * Runtime-only immutable scalar snapshot capability. It is issued only by
     * a live state Access and carries the exact version + mutation revision
     * observed with copied immutable scalar inputs. Callers cannot fabricate a
     * revision/version pair after releasing the loan.
     */
    public static final class ScalarSnapshot {
        private final SealedActorState issuer;
        private final String versionField;
        private final long version;
        private final long revision;
        private final Map<String, Object> scalars;

        private ScalarSnapshot(SealedActorState issuer, String versionField,
                               long version, long revision,
                               Map<String, Object> scalars) {
            this.issuer = issuer;
            this.versionField = versionField;
            this.version = version;
            this.revision = revision;
            this.scalars = Map.copyOf(scalars);
        }

        public long version() {
            issuer.requireActorAuthority();
            return version;
        }

        public long revision() {
            issuer.requireActorAuthority();
            return revision;
        }

        /** Immutable scalar copied under the originating state loan. */
        public Object scalar(String name) {
            issuer.requireActorAuthority();
            if (!scalars.containsKey(name))
                throw new IllegalArgumentException("scalar was not captured in snapshot: " + name);
            return scalars.get(name);
        }

        /**
         * Compiler/runtime bridge for detached candidate construction. Source
         * code never receives the issuer object itself; preparation remains
         * authority-bound to this exact snapshot and actor incarnation.
         */
        public ScalarPublication preparePublication(
                Map<String, Object> replacements) {
            issuer.requireActorAuthority();
            return issuer.prepareScalarPublication(this, replacements);
        }
    }

    /**
     * Runtime-only immutable prepared scalar transaction. Preparing may
     * allocate and validate, but it does not borrow the guest state root.
     * Capabilities are issuer-bound and single-use, not serializable wire IDs.
     */
    public static final class ScalarPublication {
        private final SealedActorState issuer;
        private final String versionField;
        private final long expectedVersion;
        private final long revision;
        private final Map<String, Object> replacements;
        private boolean consumed;

        private ScalarPublication(SealedActorState issuer, String versionField,
                                  long expectedVersion, long revision,
                                  Map<String, Object> replacements) {
            this.issuer = issuer;
            this.versionField = versionField;
            this.expectedVersion = expectedVersion;
            this.revision = revision;
            this.replacements = Map.copyOf(replacements);
        }
    }


    /**
     * Deeply immutable, actor-incarnation-bound f64 tensor snapshot.
     *
     * Shape and data are defensively copied at construction. No API returns
     * the backing arrays. Reads are authorized against the issuer's current
     * actor turn, so a Java/guest alias transported to another actor cannot
     * become a cross-actor memory capability.
     */
    public static final class TensorSnapshotF64 {
        private final SealedActorState issuer;
        private final long runId;
        private final long graphId;
        private final long incarnation;
        private final long version;
        private final int[] shape;
        private final double[] values;
        private volatile boolean erased;

        private TensorSnapshotF64(SealedActorState issuer,
                                  long runId, long graphId,
                                  long incarnation, long version,
                                  int[] shape, double[] values) {
            this.issuer = Objects.requireNonNull(issuer, "tensor issuer");
            if (runId < 0 || graphId < 0 || incarnation < 1 || version < 1)
                throw new IllegalArgumentException("ML_INVALID_TENSOR_IDENTITY");
            this.runId = runId;
            this.graphId = graphId;
            this.incarnation = incarnation;
            this.version = version;
            this.shape = Objects.requireNonNull(shape, "tensor shape").clone();
            this.values = Objects.requireNonNull(values, "tensor values").clone();
            validateShapeAndValues(this.shape, this.values);
        }

        private static void validateShapeAndValues(int[] shape, double[] values) {
            if (shape.length > MAX_TENSOR_RANK)
                throw new IllegalArgumentException("ML_TENSOR_RANK_EXCEEDED");
            if (values.length > MAX_TENSOR_ELEMENTS)
                throw new IllegalArgumentException("ML_TENSOR_ELEMENT_BUDGET_EXCEEDED");
            long product = 1;
            boolean zero = false;
            for (int dimension : shape) {
                if (dimension < 0)
                    throw new IllegalArgumentException("ML_NEGATIVE_TENSOR_DIMENSION");
                if (dimension > MAX_TENSOR_EXTENT)
                    throw new IllegalArgumentException("ML_TENSOR_EXTENT_EXCEEDED");
                if (dimension == 0) {
                    zero = true;
                    product = 0;
                    continue;
                }
                if (!zero) {
                    if (product > MAX_TENSOR_ELEMENTS / (long) dimension)
                        throw new IllegalArgumentException("ML_TENSOR_SHAPE_OVERFLOW");
                    product *= dimension;
                }
            }
            if (product != values.length)
                throw new IllegalArgumentException("ML_TENSOR_SHAPE_STORAGE_MISMATCH");
            for (double value : values) {
                if (!Double.isFinite(value))
                    throw new IllegalArgumentException("ML_NONFINITE_TENSOR_VALUE");
            }
        }

        private void check() {
            issuer.requireActorAuthority();
            if (erased)
                throw new IllegalStateException("ML_TENSOR_SNAPSHOT_REVOKED");
        }

        private long byteSize() {
            return ((long) shape.length * Integer.BYTES)
                    + ((long) values.length * Double.BYTES);
        }

        private void erase() {
            Arrays.fill(shape, 0);
            Arrays.fill(values, 0.0d);
            erased = true;
        }

        public long runId() { check(); return runId; }
        public long graphId() { check(); return graphId; }
        public long incarnation() { check(); return incarnation; }
        public long version() { check(); return version; }
        public int rank() { check(); return shape.length; }
        public int length() { check(); return values.length; }
        public int dimension(int axis) {
            check();
            return shape[Objects.checkIndex(axis, shape.length)];
        }
        public double read(int index) {
            check();
            return values[Objects.checkIndex(index, values.length)];
        }
        public int[] copyShape() { check(); return shape.clone(); }
        public double[] copyValues() { check(); return values.clone(); }
    }

    /**
     * Prepared immutable tensor publication. Candidate allocation/copy happens
     * outside the state loan; publication validates the same run/graph/
     * incarnation/version/revision tuple immediately before one map swap.
     */
    public static final class TensorPublication {
        private final SealedActorState issuer;
        private final String tensorField;
        private final String versionField;
        private final String runField;
        private final String graphField;
        private final String incarnationField;
        private final long expectedVersion;
        private final long expectedRevision;
        private final long expectedRun;
        private final long expectedGraph;
        private final long expectedIncarnation;
        private final TensorSnapshotF64 sourceSnapshot;
        private final TensorSnapshotF64 snapshot;
        private boolean consumed;

        private TensorPublication(SealedActorState issuer,
                                  String tensorField, String versionField,
                                  String runField, String graphField,
                                  String incarnationField,
                                  long expectedVersion, long expectedRevision,
                                  long expectedRun, long expectedGraph,
                                  long expectedIncarnation,
                                  TensorSnapshotF64 sourceSnapshot,
                                  TensorSnapshotF64 snapshot) {
            this.issuer = issuer;
            this.tensorField = tensorField;
            this.versionField = versionField;
            this.runField = runField;
            this.graphField = graphField;
            this.incarnationField = incarnationField;
            this.expectedVersion = expectedVersion;
            this.expectedRevision = expectedRevision;
            this.expectedRun = expectedRun;
            this.expectedGraph = expectedGraph;
            this.expectedIncarnation = expectedIncarnation;
            this.sourceSnapshot = sourceSnapshot;
            this.snapshot = snapshot;
        }
    }

    private long trackedTensorBytesUnderGate() {
        long total = 0;
        for (TensorSnapshotF64 snapshot : tensorSnapshots) {
            long bytes = snapshot.byteSize();
            if (bytes > MAX_TRACKED_TENSOR_BYTES - total)
                return MAX_TRACKED_TENSOR_BYTES + 1;
            total += bytes;
        }
        return total;
    }

    public TensorPublication prepareTensorPublication(
            ScalarSnapshot authority,
            TensorSnapshotF64 sourceSnapshot,
            String tensorField,
            String runField, String graphField, String incarnationField,
            int[] shape, double[] values) {
        requireActorAuthority();
        // Detached preparation cannot run while THIS carrier owns a state loan.
        // A future maxThreads>1 sibling carrier may briefly hold state; the
        // authenticated snapshot remains immutable and publication revalidates
        // all fences under the exclusive WRITE loan.
        if (stateCarrier.get() == Thread.currentThread())
            throw new IllegalStateException("ML_PREPARE_WITH_LIVE_STATE_LOAN");
        if (authority == null || authority.issuer != this)
            throw new SecurityException("ML_INVALID_SNAPSHOT_CAPABILITY");

        String versionField = authority.versionField;
        try {
            Set.of(
                    Objects.requireNonNull(tensorField, "tensor field"),
                    Objects.requireNonNull(versionField, "version field"),
                    Objects.requireNonNull(runField, "run field"),
                    Objects.requireNonNull(graphField, "graph field"),
                    Objects.requireNonNull(incarnationField, "incarnation field"));
        } catch (IllegalArgumentException duplicate) {
            throw new IllegalArgumentException(
                    "ML_DUPLICATE_TENSOR_PUBLICATION_FIELD", duplicate);
        }
        if (!"tensor:f64".equals(schema.get(tensorField))
                || !"int".equals(schema.get(versionField))
                || !"int".equals(schema.get(runField))
                || !"int".equals(schema.get(graphField))
                || !"int".equals(schema.get(incarnationField)))
            throw new IllegalArgumentException("ML_INVALID_TENSOR_PUBLICATION_SCHEMA");

        long expectedVersion = authority.version;
        long expectedRevision = authority.revision;
        long expectedRun = snapshotIntegral(authority, runField);
        long expectedGraph = snapshotIntegral(authority, graphField);
        long expectedIncarnation = snapshotIntegral(authority, incarnationField);
        if (expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
                || expectedRun < 0 || expectedGraph < 0 || expectedIncarnation < 1)
            throw new IllegalArgumentException("ML_INVALID_TENSOR_PUBLICATION_IDENTITY");

        if (expectedVersion == 0) {
            if (sourceSnapshot != null)
                throw new IllegalArgumentException("ML_UNEXPECTED_SOURCE_TENSOR");
        } else {
            if (sourceSnapshot == null)
                throw new IllegalArgumentException("ML_SOURCE_TENSOR_REQUIRED");
            if (sourceSnapshot.issuer != this || sourceSnapshot.erased)
                throw new SecurityException("ML_INVALID_SOURCE_TENSOR_CAPABILITY");
            sourceSnapshot.check();
            if (sourceSnapshot.version != expectedVersion
                    || sourceSnapshot.runId != expectedRun
                    || sourceSnapshot.graphId != expectedGraph
                    || sourceSnapshot.incarnation != expectedIncarnation)
                throw new IllegalArgumentException("ML_SOURCE_TENSOR_IDENTITY_MISMATCH");
        }

        TensorSnapshotF64 snapshot = new TensorSnapshotF64(
                this, expectedRun, expectedGraph, expectedIncarnation,
                expectedVersion + 1,
                Objects.requireNonNull(shape, "tensor shape"),
                Objects.requireNonNull(values, "tensor values"));
        long bytes = snapshot.byteSize();
        synchronized (tensorSnapshotGate) {
            long tracked = trackedTensorBytesUnderGate();
            if (tensorSnapshots.size() >= MAX_TRACKED_TENSOR_SNAPSHOTS
                    || bytes > MAX_TRACKED_TENSOR_BYTES - tracked) {
                snapshot.erase();
                throw new IllegalArgumentException("ML_TENSOR_SNAPSHOT_BUDGET_EXHAUSTED");
            }
            tensorSnapshots.add(snapshot);
        }

        TensorPublication publication = new TensorPublication(
                this, tensorField, versionField,
                runField, graphField, incarnationField,
                expectedVersion, expectedRevision,
                expectedRun, expectedGraph, expectedIncarnation,
                sourceSnapshot, snapshot);
        try {
            // If retirement won during detached allocation/copy, do not let a
            // prepared capability escape after the actor generation is dead.
            requireActorAuthority();
            return publication;
        } catch (RuntimeException failure) {
            synchronized (tensorSnapshotGate) {
                tensorSnapshots.remove(snapshot);
                snapshot.erase();
            }
            throw failure;
        }
    }

    private long snapshotIntegral(ScalarSnapshot snapshot, String field) {
        Object value = snapshot.scalars.get(field);
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException("ML_TENSOR_IDENTITY_NOT_CAPTURED");
        return ((Number) value).longValue();
    }

    /**
     * Prepare detached scalar values outside any live state loan. The
     * version/revision authority comes only from an issuer-bound snapshot that
     * was created while the model inputs were copied under a state loan.
     * Production training must still authenticate gradient/edge provenance.
     */
    public ScalarPublication prepareScalarPublication(ScalarSnapshot snapshot,
                                                       Map<String, Object> replacements) {
        requireActorAuthority();
        // Detached preparation must not execute from inside this carrier's
        // live loan, but another maxThreads carrier may hold state while this
        // carrier computes/stages from an immutable snapshot. Publication will
        // still compare the snapshot revision/version under exclusive WRITE.
        if (stateCarrier.get() == Thread.currentThread())
            throw new IllegalStateException("ML_PREPARE_WITH_LIVE_STATE_LOAN");
        if (snapshot == null || snapshot.issuer != this)
            throw new SecurityException("ML_INVALID_SNAPSHOT_CAPABILITY");
        if (snapshot.version < 0 || snapshot.version == Long.MAX_VALUE
                || snapshot.revision < 0 || snapshot.revision == Long.MAX_VALUE)
            throw new IllegalArgumentException("ML_INVALID_VERSION");
        Objects.requireNonNull(replacements, "scalar replacements");
        if (replacements.isEmpty() || replacements.size() > schema.size())
            throw new IllegalArgumentException("ML_INVALID_REPLACEMENTS");
        var staged = new LinkedHashMap<String, Object>();
        for (var entry : replacements.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "scalar name");
            if ("tensor:f64".equals(schema.get(name)))
                throw new IllegalArgumentException("ML_TENSOR_REQUIRES_TENSOR_PUBLICATION");
            validateValue(name, entry.getValue());
            // A candidate may only replace a scalar whose previous value was
            // captured in the same authenticated snapshot. The version field
            // is already captured separately by the snapshot capability.
            if (!name.equals(snapshot.versionField)
                    && !snapshot.scalars.containsKey(name))
                throw new IllegalArgumentException("ML_REPLACEMENT_NOT_SNAPSHOTTED");
            // Owned regions have no general alias-safe scalar publication path.
            staged.put(name, entry.getValue());
        }
        Object proposed = staged.get(snapshot.versionField);
        if (!(proposed instanceof Long next)
                || next.longValue() != snapshot.version + 1)
            throw new IllegalArgumentException("ML_VERSION_ADVANCE_REQUIRED");
        ScalarPublication publication = new ScalarPublication(
                this, snapshot.versionField, snapshot.version,
                snapshot.revision, staged);
        // If retirement won while preparation was allocating/validating,
        // revoke the result before it escapes.
        requireActorAuthority();
        return publication;
    }

    private long nextMutationRevision() {
        if (mutationRevision == Long.MAX_VALUE)
            throw new IllegalStateException("ML_MUTATION_REVISION_OVERFLOW");
        return mutationRevision + 1;
    }

    /**
     * Authority for detached immutable actor capabilities. Unlike requireOwner,
     * this does not require the current physical carrier to own the live state
     * gate: maxThreads>1 computations may safely read copied snapshots while a
     * different carrier briefly holds a state loan.
     */
    private void requireActorAuthority() {
        if (retired || stateCarrier.get() == RETIRED_STATE_CARRIER)
            throw new IllegalStateException("actor state retired");
        if (!owner.equals(ActorRuntime.currentActorId().orElse(null))) {
            throw new SecurityException("actor state requires the owning actor's admitted turn");
        }
        // Close the small retirement race between the first fence read and
        // actor-identity validation.
        if (retired || stateCarrier.get() == RETIRED_STATE_CARRIER)
            throw new IllegalStateException("actor state retired");
    }

    private void requireOwner() {
        requireActorAuthority();
        Thread active = stateCarrier.get();
        if (active == RETIRED_STATE_CARRIER || retired)
            throw new IllegalStateException("actor state retired");
        if (active != null && active != Thread.currentThread())
            throw new SecurityException("ML_STATE_LOAN_FOREIGN_CARRIER");
    }

    private void requireOwnedRegionMutationAuthority() {
        requireOwner();
        // The capsule must not become a back door to mutate actor-owned
        // memory during a nested or top-level read-only state grant.
        if (current != null && !current.writable)
            throw new SecurityException("critical read cannot allocate or mutate owned state");
    }

    private static int stringBytes(Object value) {
        if (!(value instanceof String text)) return 0;
        if (text.length() > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("actor state string exceeds per-field quota");
        }
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("actor state UTF-8 bytes exceed per-field quota");
        }
        return bytes;
    }

    private void validateValue(String name, Object value) {
        String expected = schema.get(name);
        if (expected == null) throw new IllegalArgumentException("unknown actor state key " + name);
        boolean valid = switch (expected) {
            case "int" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "bool" -> value instanceof Boolean;
            case "string", "String" -> value instanceof String;
            case "tensor:f64" -> value instanceof TensorSnapshotF64 snapshot
                    && snapshot.issuer == this && !snapshot.erased;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("actor state key '" + name
                    + "' expects " + expected + ", got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
    }

    public final class Access {
        private final long token;
        private final boolean writable;
        private final Thread carrier;
        private boolean live = true;
        private Access(long token, boolean writable) {
            this.token = token;
            this.writable = writable;
            this.carrier = Thread.currentThread();
        }
        private void check() {
            requireOwner();
            if (Thread.currentThread() != carrier || !live || current != this || token <= 0) {
                throw new IllegalStateException("expired or fenced actor state loan");
            }
        }
        /** Observe the state revision in the SAME loan as model parameters. */
        public long revision() {
            check();
            return mutationRevision;
        }

        /**
         * Copy immutable scalar inputs and their version/revision under one
         * live state loan. Owned/reference-bearing regions are never admitted.
         */
        public ScalarSnapshot snapshotScalars(String versionField, String... names) {
            check();
            if (!"int".equals(schema.get(versionField)))
                throw new IllegalArgumentException("ML_INVALID_VERSION_FIELD");
            Object rawVersion = read(versionField);
            if (!(rawVersion instanceof Number number))
                throw new IllegalStateException("ML_INVALID_VERSION_VALUE");
            long version = number.longValue();
            if (version < 0)
                throw new IllegalStateException("ML_INVALID_VERSION_VALUE");
            Objects.requireNonNull(names, "snapshot scalar names");
            if (names.length == 0)
                throw new IllegalArgumentException(
                        "ML_SNAPSHOT_REQUIRES_NON_VERSION_SCALAR");
            var copied = new LinkedHashMap<String, Object>();
            for (String name : names) {
                Objects.requireNonNull(name, "snapshot scalar name");
                if (name.equals(versionField))
                    throw new IllegalArgumentException(
                            "ML_SNAPSHOT_VERSION_FIELD_DUPLICATED");
                if (copied.containsKey(name))
                    throw new IllegalArgumentException("duplicate snapshot scalar: " + name);
                if ("tensor:f64".equals(schema.get(name)))
                    throw new SecurityException("ML_SNAPSHOT_NONSCALAR_FIELD");
                Object value = read(name);
                // read() already rejects OwnedRegion. Keep the runtime
                // capability narrow to immutable scalar types accepted by
                // safeResult rather than returning mutable host wrappers.
                if (!safeResult(value, 0))
                    throw new SecurityException("ML_SNAPSHOT_NONSCALAR_FIELD");
                copied.put(name, value);
            }
            return new ScalarSnapshot(SealedActorState.this, versionField,
                    version, mutationRevision, copied);
        }

        public Object read(String name) {
            check();
            if (!values.containsKey(name)) throw new IllegalArgumentException("unknown actor state key " + name);
            Object value = values.get(name);
            if (value == Uninitialized.INSTANCE) {
                throw new IllegalStateException("actor state key '" + name + "' is uninitialized");
            }
            if (value instanceof OwnedRegion)
                throw new SecurityException("raw actor-owned reference cannot escape state loan");
            return value;
        }

        /**
         * Consumes an issuer-bound allocation exactly once under a WRITE loan.
         * Failed validation leaves the source live and its destination intact.
         */
        public void takeOwnedBytes(String name, OwnedBytes source) {
            check();
            if (!writable) throw new SecurityException("critical read cannot transfer owned state");
            if (!"owned:bytes".equals(schema.get(name)))
                throw new IllegalArgumentException("actor state key is not owned:bytes: " + name);
            Objects.requireNonNull(source, "owned source");
            if (source.getClass() != OwnedBytes.class || source.issuer() != SealedActorState.this)
                throw new SecurityException("foreign actor-owned allocation issuer");
            source.check();
            long revision = nextMutationRevision();
            byte[] moved = source.bytes;
            Object prior = values.get(name);
            int released = prior instanceof OwnedRegion region ? region.bytes.length : 0;
            // The allocation was accounted for when created and cannot
            // exceed the total live+pending budget on this actor incarnation.
            values.put(name, new OwnedRegion(moved));
            source.bytes = null;
            pendingOwned.remove(source);
            pendingOwnedBytes -= moved.length;
            retainedOwnedBytes = retainedOwnedBytes - released + moved.length;
            if (prior instanceof OwnedRegion region) region.erase();
            mutationRevision = revision;
        }

        private OwnedRegion ownedRegion(String name) {
            if (!"owned:bytes".equals(schema.get(name)))
                throw new IllegalArgumentException("actor state key is not owned:bytes: " + name);
            Object value = values.get(name);
            if (value == Uninitialized.INSTANCE)
                throw new IllegalStateException("actor-owned state field is uninitialized: " + name);
            return (OwnedRegion) value;
        }

        public int readOwnedByte(String name, int index) {
            check();
            byte[] data = ownedRegion(name).bytes;
            return Byte.toUnsignedInt(data[Objects.checkIndex(index, data.length)]);
        }

        public void writeOwnedByte(String name, int index, int unsignedByte) {
            check();
            if (!writable) throw new SecurityException("critical read cannot write actor state");
            if (unsignedByte < 0 || unsignedByte > 255)
                throw new IllegalArgumentException("owned byte value must be 0..255");
            byte[] data = ownedRegion(name).bytes;
            int checkedIndex = Objects.checkIndex(index, data.length);
            long revision = nextMutationRevision();
            data[checkedIndex] = (byte) unsignedByte;
            mutationRevision = revision;
        }
        public void write(String name, Object value) {
            check();
            if (!writable) throw new SecurityException("critical read cannot write actor state");
            if ("tensor:f64".equals(schema.get(name)))
                throw new SecurityException("ML_TENSOR_WRITE_REQUIRES_PUBLICATION");
            validateValue(name, value);
            int previousBytes = stringBytes(values.get(name));
            int nextBytes = stringBytes(value);
            if (nextBytes > MAX_TOTAL_STRING_BYTES
                    - (retainedStringBytes - previousBytes)) {
                throw new IllegalArgumentException("actor state string byte budget exceeded");
            }
            long revision = nextMutationRevision();
            values.put(name, value);
            retainedStringBytes = retainedStringBytes - previousBytes + nextBytes;
            mutationRevision = revision;
        }

        /**
         * All-or-nothing scalar publication within a verified WRITE loan.
         * Clone/validate before the single map-pointer swap. No callback,
         * allocation or user code is invoked after that swap.
         */
        public boolean publishScalars(ScalarPublication publication) {
            check();
            if (!writable)
                throw new SecurityException("ML_PUBLISH_REQUIRES_CRITICAL_WRITE");
            if (publication == null || publication.issuer != SealedActorState.this
                    || publication.consumed)
                throw new SecurityException("ML_INVALID_PUBLICATION_CAPABILITY");
            if (mutationRevision != publication.revision)
                throw new IllegalStateException("ML_STALE_STATE_REVISION");
            Object version = values.get(publication.versionField);
            if (!(version instanceof Number previous)
                    || previous.longValue() != publication.expectedVersion)
                throw new IllegalStateException("ML_STALE_WEIGHT_VERSION");
            long revision = nextMutationRevision();

            // The map and all string quota checks are completed before any
            // guest-visible mutation. Bounds are computed by subtraction
            // rather than potentially overflowing intermediate additions.
            int nextStringBytes = retainedStringBytes;
            LinkedHashMap<String, Object> nextValues = new LinkedHashMap<>(values);
            for (var entry : publication.replacements.entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();
                validateValue(name, value);
                int oldBytes = stringBytes(nextValues.get(name));
                int newBytes = stringBytes(value);
                if (newBytes > MAX_TOTAL_STRING_BYTES - (nextStringBytes - oldBytes))
                    throw new IllegalArgumentException("ML_STRING_BUDGET_EXHAUSTED");
                nextStringBytes = nextStringBytes - oldBytes + newBytes;
                nextValues.put(name, value);
            }
            // Single publication point. The following assignments are
            // primitive/reference writes and cannot fail due to allocation.
            values = nextValues;
            retainedStringBytes = nextStringBytes;
            mutationRevision = revision;
            publication.consumed = true;
            return true;
        }

        public TensorSnapshotF64 publishTensorSnapshot(TensorPublication publication) {
            check();
            if (!writable)
                throw new SecurityException("ML_PUBLISH_REQUIRES_CRITICAL_WRITE");
            if (publication == null || publication.issuer != SealedActorState.this
                    || publication.consumed)
                throw new SecurityException("ML_INVALID_TENSOR_PUBLICATION_CAPABILITY");
            if (mutationRevision != publication.expectedRevision)
                throw new IllegalStateException("ML_STALE_STATE_REVISION");
            if (readIntegral(publication.versionField) != publication.expectedVersion)
                throw new IllegalStateException("ML_STALE_WEIGHT_VERSION");
            if (readIntegral(publication.runField) != publication.expectedRun)
                throw new IllegalStateException("ML_STALE_RUN");
            if (readIntegral(publication.graphField) != publication.expectedGraph)
                throw new IllegalStateException("ML_STALE_GRAPH");
            if (readIntegral(publication.incarnationField) != publication.expectedIncarnation)
                throw new IllegalStateException("ML_STALE_ACTOR_INCARNATION");
            Object currentTensor = values.get(publication.tensorField);
            if (publication.expectedVersion == 0) {
                if (currentTensor != Uninitialized.INSTANCE)
                    throw new IllegalStateException("ML_STALE_TENSOR_SOURCE");
            } else if (currentTensor != publication.sourceSnapshot) {
                throw new IllegalStateException("ML_STALE_TENSOR_SOURCE");
            }

            long revision = nextMutationRevision();
            LinkedHashMap<String, Object> nextValues = new LinkedHashMap<>(values);
            nextValues.put(publication.tensorField, publication.snapshot);
            nextValues.put(publication.versionField, publication.expectedVersion + 1);
            values = nextValues;
            mutationRevision = revision;
            publication.consumed = true;
            return publication.snapshot;
        }

        private long readIntegral(String field) {
            Object value = values.get(field);
            if (!(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long))
                throw new IllegalStateException("ML_INVALID_IDENTITY_FIELD");
            return ((Number) value).longValue();
        }
    }

    public <T> T critical(Function<Access, T> callback) {
        return critical(true, callback); // trusted Java-side internal callers
    }

    /** Guest source must supply its verified read/write mode. */
    public <T> T critical(boolean writable, Function<Access, T> callback) {
        Objects.requireNonNull(callback, "critical state callback");
        requireOwner();
        final Thread carrier = Thread.currentThread();
        final boolean newlyAdmitted = stateCarrier.compareAndSet(null, carrier);
        if (!newlyAdmitted && stateCarrier.get() != carrier) {
            if (stateCarrier.get() == RETIRED_STATE_CARRIER || retired)
                throw new IllegalStateException("actor state retired");
            throw new IllegalStateException("ML_STATE_LOAN_CONCURRENT_CARRIER");
        }
        if (serial == Long.MAX_VALUE) {
            if (newlyAdmitted) stateCarrier.compareAndSet(carrier, null);
            throw new IllegalStateException("actor loan serial exhausted");
        }
        Access previous = current;
        // A read grant must never be upgraded while it is live, even through
        // indirect calls that evade source-level interprocedural inference.
        if (previous != null && !previous.writable && writable) {
            if (newlyAdmitted) stateCarrier.compareAndSet(carrier, null);
            throw new SecurityException("cannot upgrade a live critical read loan to write");
        }
        Access access = null;
        try {
            access = new Access(++serial, writable);
            current = access;
            T result = callback.apply(access);
            if (!safeResult(result, 0)) {
                throw new IllegalArgumentException("critical state access cannot escape in result");
            }
            return result;
        } finally {
            if (access != null) access.live = false;
            current = previous;
            // Also executes when loan allocation/callback construction throws.
            if (newlyAdmitted && !stateCarrier.compareAndSet(carrier, null))
                throw new IllegalStateException("ML_STATE_LOAN_CARRIER_CORRUPT");
        }
    }

    private boolean safeResult(Object value, int depth) {
        // Never accept an arbitrary Number subclass: AtomicInteger/AtomicLong
        // and user-defined host Number types can be mutable and may retain
        // references to the active state loan through their own fields.
        if (value == null || value instanceof String
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal
                || value instanceof Boolean || value instanceof Character) return true;
        // Structured loan escapes are limited to private-constructor immutable
        // snapshots, both authority-bound to this exact state issuer.
        if (value instanceof ScalarSnapshot snapshot)
            return snapshot.issuer == this;
        if (value instanceof TensorSnapshotF64 snapshot)
            return snapshot.issuer == this && !snapshot.erased;
        // Avoid arbitrary mutable guest/Java wrappers and every possible
        // deferred object; the source ownership checker must prove complex
        // returned values before this boundary can be broadened.
        return false;
    }

    /**
     * Called once by ActorRuntime after the actor's execution turn has fully
     * quiesced, not by arbitrary guest code. The owner ID fences accidental
     * cross-actor finalization or stale actor incarnation references.
     */
    void retireFromRuntime(ActorRuntime.ActorId confirmedOwner) {
        if (!owner.equals(confirmedOwner)) {
            throw new SecurityException("cannot retire state for a foreign actor");
        }
        // Atomically close admission BEFORE clearing any state. If a loan won
        // the race its carrier is non-null and retirement fails without
        // mutation. If retirement wins, every subsequent critical() CAS fails
        // against the permanent sentinel.
        if (!stateCarrier.compareAndSet(null, RETIRED_STATE_CARRIER)) {
            if (retired || stateCarrier.get() == RETIRED_STATE_CARRIER)
                throw new IllegalStateException("actor state already retired");
            throw new IllegalStateException("cannot retire actor state with live critical loan");
        }
        retired = true;
        if (current != null)
            throw new IllegalStateException("actor state carrier/current invariant corrupted");
        for (OwnedBytes capsule : pendingOwned) {
            if (capsule.bytes != null) Arrays.fill(capsule.bytes, (byte) 0);
            capsule.bytes = null;
        }
        pendingOwned.clear();
        synchronized (tensorSnapshotGate) {
            for (TensorSnapshotF64 snapshot : tensorSnapshots) snapshot.erase();
            tensorSnapshots.clear();
        }
        for (Object value : values.values()) {
            if (value instanceof OwnedRegion region) region.erase();
        }
        values.clear();
        retainedStringBytes = 0;
        retainedOwnedBytes = 0;
        pendingOwnedBytes = 0;
    }

    public Set<String> declaredKeys() { return Set.copyOf(schema.keySet()); }
}
