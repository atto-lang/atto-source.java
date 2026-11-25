package dev.oreslang.imports;

import java.util.*;

/** Verified, bounded package admission graph. No network or filesystem lookup.
 * This is metadata admission, not authorization to lower provider imports.
 * Each supplied snapshot already has an independent trusted lock pin.
 */
public final class FrozenDependencyGraph {
    private final Map<String,FrozenImportSnapshot> packages;
    private final List<String> dependencyOrder;
    private FrozenDependencyGraph(Map<String,FrozenImportSnapshot> packages,List<String> order) {
        this.packages=Map.copyOf(packages); this.dependencyOrder=List.copyOf(order);
    }
    private static IllegalArgumentException fail(String code,String detail) {
        return new IllegalArgumentException(code+": "+detail);
    }
    public static FrozenDependencyGraph verify(List<FrozenImportSnapshot> supplied) {
        if(supplied==null||supplied.isEmpty()||supplied.size()>16)
            throw fail("E_SOURCE_LIMIT","package inventory budget exceeded");
        var packages=new TreeMap<String,FrozenImportSnapshot>();
        for(var p:supplied) {
            if(p==null) throw fail("E_LOCK","null snapshot");
            if(packages.putIfAbsent(p.name(),p)!=null)
                throw fail("E_AMBIGUOUS","multiple snapshots for package "+p.name());
        }
        var remaining=new TreeMap<String,TreeSet<String>>();
        int edges=0;
        for(var p:packages.values()) {
            var deps=new TreeSet<>(p.dependencies().keySet());
            edges+=deps.size();
            if(edges>128) throw fail("E_SOURCE_LIMIT","dependency edge budget exceeded");
            for(String name:deps)
                if(!packages.containsKey(name))
                    throw fail("E_UNBOUND","missing pinned dependency "+p.name()+" -> "+name);
            remaining.put(p.name(),deps);
        }
        // Deterministic dependency-first order; never recursively traverse input.
        var order=new ArrayList<String>();
        while(!remaining.isEmpty()) {
            String next=null;
            for(var e:remaining.entrySet())
                if(e.getValue().isEmpty()) { next=e.getKey(); break; }
            if(next==null) {
                var path=new ArrayList<String>();
                var seen=new HashMap<String,Integer>();
                String node=remaining.firstKey();
                while(!seen.containsKey(node)) {
                    seen.put(node,path.size()); path.add(node);
                    node=remaining.get(node).first();
                }
                var cycle=new ArrayList<>(path.subList(seen.get(node),path.size()));
                cycle.add(node);
                throw fail("E_IMPORT_CYCLE",String.join(" -> ",cycle));
            }
            order.add(next); remaining.remove(next);
            for(var deps:remaining.values()) deps.remove(next);
        }
        // All edges must pin the exact separately verified target snapshot.
        for(var p:packages.values())
            for(var e:new TreeMap<>(p.dependencies()).entrySet()) {
                var target=packages.get(e.getKey());
                if(!target.version().equals(e.getValue().version())||
                   !target.lockDigest().equals(e.getValue().lockDigest()))
                    throw fail("E_LOCK_HASH","dependency pin mismatch "+p.name()+" -> "+target.name());
            }
        return new FrozenDependencyGraph(packages,order);
    }
    public List<String> dependencyOrder() { return dependencyOrder; }
    public FrozenImportSnapshot requireDependency(String owner,String dependency) {
        var p=packages.get(owner);
        if(p==null||!p.dependencies().containsKey(dependency))
            throw fail("E_UNBOUND","undeclared direct dependency "+owner+" -> "+dependency);
        return packages.get(dependency);
    }
    /**
     * Source-level invalidation only. Reusable entries are candidates, not
     * validated cache artifacts; compiler/target/options and artifact integrity
     * must be checked separately. A null previous graph means a cold build.
     */
    public record InvalidationPlan(List<String> rebuild,List<String> reusable,List<String> removed) {
        public InvalidationPlan {
            rebuild=List.copyOf(rebuild);
            reusable=List.copyOf(reusable);
            removed=List.copyOf(removed);
        }
    }
    public InvalidationPlan planAgainst(FrozenDependencyGraph previous) {
        var dirty=new HashSet<String>();
        var rebuild=new ArrayList<String>();
        var reusable=new ArrayList<String>();
        var removed=new ArrayList<String>();
        for(String name:dependencyOrder) {
            var current=packages.get(name);
            var old=previous==null?null:previous.packages.get(name);
            boolean changed=old==null||!current.version().equals(old.version())||
                !current.lockDigest().equals(old.lockDigest())||
                current.dependencies().keySet().stream().anyMatch(dirty::contains);
            if(changed) {dirty.add(name);rebuild.add(name);}
            else reusable.add(name);
        }
        if(previous!=null)
            for(int i=previous.dependencyOrder.size()-1;i>=0;i--) {
                String name=previous.dependencyOrder.get(i);
                if(!packages.containsKey(name)) removed.add(name);
            }
        return new InvalidationPlan(rebuild,reusable,removed);
    }

    /** Stable logical ID, not a cache key. Input must belong to this verified graph. */
    public String providerIdentity(String owner,String namespace) {
        var snapshot=packages.get(owner);
        if(snapshot==null||!snapshot.namespaceNames().contains(namespace))
            throw fail("E_UNBOUND","unknown provider identity");
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((owner+"\n"+namespace).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
