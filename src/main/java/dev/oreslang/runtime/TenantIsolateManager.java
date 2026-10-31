package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted embedder's bounded, per-tenant Oreslang guest Context host.
 *
 * <p>This is a real guest execution boundary: each registered tenant owns a
 * distinct polyglot Context, and its source execution creates a distinct
 * OresContext, OresVM root scheduler and ActorRuntime. The current JVM/Graal
 * implementation does NOT prove a separate physical VM isolate or native
 * address-space isolation for these trusted contexts. Never expose the
 * registration or tenant handle to Oreslang guest code or a network client.</p>
 *
 * <p>This is an intentionally narrow step toward the per-tenant VM-isolate
 * model in ORESVM_SILO_CLUSTER_V1. Stronger modes fail closed rather than
 * falling back to ordinary Contexts. An OresVM-wide shared carrier pool and
 * genuine Native Image isolate manager remain separate integration work.</p>
 */
public final class TenantIsolateManager implements AutoCloseable {
    private static final int MAX_TENANTS = 128;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    /** Opaque, identity-checked host authority, not a serializable ActorRef. */
    public static final class TenantHandle {
        private final TenantIsolateManager owner;
        private final String tenantId;

        private TenantHandle(TenantIsolateManager owner, String tenantId) {
            this.owner = owner;
            this.tenantId = tenantId;
        }

        public String tenant_id() { return tenantId; }
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int limit;

        private BoundedOutput(int limit) { this.limit = limit; }

        @Override public synchronized void write(int value) throws IOException {
            if (buffer.size() >= limit) throw new IOException("TENANT_OUTPUT_QUOTA");
            buffer.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length > limit - buffer.size()) throw new IOException("TENANT_OUTPUT_QUOTA");
            buffer.write(bytes, offset, length);
        }

        private synchronized String drain() {
            String result = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            return result;
        }

        private synchronized void discard() { buffer.reset(); }
    }

    private static final class Tenant {
        private final TenantHandle handle;
        private final Context context;
        private final BoundedOutput output;
        private boolean retiring;

        private Tenant(TenantHandle handle, Context context, BoundedOutput output) {
            this.handle = handle;
            this.context = context;
            this.output = output;
        }
    }

    private final int maxTenants;
    private final int maxOutputBytes;
    private final Map<String, Tenant> tenants = new LinkedHashMap<>();
    private boolean sealed;

    public TenantIsolateManager(int maxTenants, int maxOutputBytes) {
        if (maxTenants < 1 || maxTenants > MAX_TENANTS
                || maxOutputBytes < 1 || maxOutputBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException("invalid bounded tenant host configuration");
        }
        this.maxTenants = maxTenants;
        this.maxOutputBytes = maxOutputBytes;
    }

    /** Register a TRUSTED tenant; never infer tenant identity from guest input. */
    public synchronized TenantHandle open_trusted_tenant(
            String tenantId, IsolatePolicy requestedPolicy) {
        require_host_turn();
        validate_tenant_id(tenantId);
        IsolatePolicy policy = Objects.requireNonNull(requestedPolicy, "requestedPolicy");
        if (sealed) throw new IllegalStateException("tenant host is closed");
        if (tenants.containsKey(tenantId)) {
            throw new IllegalStateException("TENANT_ALREADY_REGISTERED");
        }
        if (tenants.size() >= maxTenants) throw new IllegalStateException("TENANT_HOST_FULL");
        if (policy.adversarial()) {
            throw new SecurityException(
                    "UNVERIFIED_TENANT_ISOLATION: use a verified sandbox broker; "
                    + "do not silently share a trusted JVM context");
        }
        // PR #2278's admission is deliberately fail-closed on protected modes.
        ActorHeapAdmission.requireSupportedTenantIsolationMode(
                System.getProperty("ores.runtime.tenant.isolation", "trusted-inprocess"));
        BoundedOutput output = new BoundedOutput(maxOutputBytes);
        BoundedOutput errors = new BoundedOutput(maxOutputBytes);
        // OresContext/OresVM are constructed through the actual guest language
        // context, not a mock actor graph or detached host-only ActorRuntime.
        // Bound BOTH output streams: stderr is not an unlimited tenant bypass.
        Context context = policy.restrictedContextBuilder()
                .out(output).err(errors).build();
        TenantHandle handle = new TenantHandle(this, tenantId);
        tenants.put(tenantId, new Tenant(handle, context, output));
        return handle;
    }

    /**
     * Execute a real Oreslang source guest under the host-owned tenant handle.
     * No guest Value, Context, host object or raw actor reference escapes this
     * interface. Tenant-local evals serialize; unrelated tenants may progress.
     */
    public String evaluate_source(TenantHandle handle, Source source) {
        Objects.requireNonNull(source, "source");
        if (!OresLanguage.ID.equals(source.getLanguage())) {
            throw new SecurityException("FOREIGN_GUEST_LANGUAGE");
        }
        Tenant tenant = lookup(handle);
        synchronized (tenant) {
            if (tenant.retiring) throw new IllegalStateException("TENANT_RETIRING");
            tenant.output.discard();
            tenant.context.eval(source);
            return tenant.output.drain();
        }
    }

    private synchronized Tenant lookup(TenantHandle handle) {
        require_host_turn();
        if (handle == null || handle.owner != this) {
            throw new SecurityException("INVALID_TENANT_HANDLE");
        }
        Tenant tenant = tenants.get(handle.tenantId);
        if (tenant == null || tenant.handle != handle) {
            throw new SecurityException("INVALID_TENANT_HANDLE");
        }
        if (sealed) throw new IllegalStateException("tenant host is closed");
        return tenant;
    }

    /** Retire one tenant without retiring any other tenant's execution context. */
    public void close_tenant(TenantHandle handle) {
        require_host_turn();
        final Tenant tenant;
        synchronized (this) {
            if (handle == null || handle.owner != this) {
                throw new SecurityException("INVALID_TENANT_HANDLE");
            }
            tenant = tenants.get(handle.tenantId);
            if (tenant == null || tenant.handle != handle) {
                throw new SecurityException("INVALID_TENANT_HANDLE");
            }
        }
        synchronized (tenant) {
            tenant.retiring = true;
            // OresContext.close() owns the physical actor/Task/native-I/O
            // retirement barrier. On failure, retain context + actor heaps
            // strongly reachable in this registry for quarantine/retry.
            tenant.context.close();
        }
        synchronized (this) {
            tenants.remove(handle.tenantId, tenant);
        }
    }

    public synchronized int tenant_count() { return tenants.size(); }

    /** Blocking host teardown. A failed tenant remains registered/quarantined. */
    @Override public void close() {
        require_host_turn();
        final ArrayList<TenantHandle> pending;
        synchronized (this) {
            sealed = true;
            pending = new ArrayList<>(tenants.size());
            for (Tenant tenant : tenants.values()) pending.add(tenant.handle);
        }
        RuntimeException first = null;
        for (TenantHandle handle : pending) {
            try {
                close_tenant(handle);
            } catch (RuntimeException failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first != null) {
            throw new IllegalStateException(
                    "tenant shutdown incomplete; unretired tenant contexts quarantined", first);
        }
    }

    private static void validate_tenant_id(String id) {
        if (id == null || id.isEmpty() || id.length() > 128
                || !Normalizer.isNormalized(id, Normalizer.Form.NFC)
                || id.codePoints().anyMatch(c -> c <= 0x1f || c == 0x7f
                        || (c >= 0xd800 && c <= 0xdfff))
                || id.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new IllegalArgumentException("INVALID_TENANT_ID");
        }
    }

    private static void require_host_turn() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()
                || OresScheduler.current() != null) {
            throw new SecurityException(
                    "tenant host management belongs to the trusted embedder, not guest or CONTROL turns");
        }
    }
}
