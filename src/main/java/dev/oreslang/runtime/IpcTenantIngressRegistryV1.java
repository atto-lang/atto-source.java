package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;

/** Runtime-owned host registry. Domain strings are trusted provisioned names,
 * not a source of authority; authenticated endpoints must be registered by host.
 * Retired entries remain tombstones until runtime destruction, preventing ABA. */
final class IpcTenantIngressRegistryV1 {
    private static final int MAX_TENANTS=1024;
    private final Map<String,IpcIngressBudgetV1> tenants=new HashMap<>();
    private boolean closed;

    synchronized IpcIngressBudgetV1 register(String domain,long bytes,int frames) {
        RuntimeBlockingGuard.requireBlockingHostThread("register IPC tenant");
        if(closed || tenants.size()>=MAX_TENANTS || tenants.containsKey(domain))
            throw new IllegalStateException("IPC tenant registry unavailable or duplicate");
        if(domain==null || domain.isEmpty() || domain.length()>96
                || !domain.chars().allMatch(c->c>=33 && c<=126))
            throw new IllegalArgumentException("invalid trusted tenant domain");
        var budget=new IpcIngressBudgetV1(bytes,frames);
        tenants.put(domain,budget);
        return budget;
    }
    synchronized void require(String domain,IpcIngressBudgetV1 budget) {
        RuntimeBlockingGuard.requireBlockingHostThread("bind IPC tenant endpoint");
        if(closed || budget==null || tenants.get(domain)!=budget || budget.retired())
            throw new IllegalArgumentException("unregistered or retired IPC tenant budget");
    }
    synchronized void retire(String domain) {
        RuntimeBlockingGuard.requireBlockingHostThread("retire IPC tenant");
        var budget=tenants.get(domain);
        if(budget==null)throw new IllegalArgumentException("unknown tenant");
        budget.retire();
    }
    synchronized void close() {
        closed=true;
        for(var budget:tenants.values())budget.retire();
    }
}
