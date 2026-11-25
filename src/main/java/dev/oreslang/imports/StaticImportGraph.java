package dev.oreslang.imports;

import java.util.*;

/** Compile-time graph only. Export inventories must be authenticated by the caller.
 * No manifest parsing, file discovery, runtime namespace object or privilege grant. */
public final class StaticImportGraph {
    private static final int LIMIT = 256;
    public enum Kind { NAMESPACE, MODULE, FNC, CONST, CLASS, ACTOR, ENUM, TRAIT, INTERFACE, TYPE }
    public enum Tier { IMPLICIT, DELIBERATE, EXPLICIT }
    public enum Domain { BUILTIN, LOCAL, PACKAGE }
    public record Origin(Domain domain, String identity, String digest) {
        public Origin {
            if (domain == null || identity == null || identity.isBlank() || identity.length() > 256 ||
                digest == null || !digest.matches("[0-9a-f]{64}"))
                throw error("E_IMPORT_PATH", "invalid pinned origin");
        }
    }
    public record Export(String path, Kind kind, boolean visible) {}
    public record Selection(String path, String binding, Kind kind, boolean explicit) {}
    public record Alias(String name, String reference) {}
    public record Target(View view, String path, Kind kind) {}
    private record Binding(String name, Tier tier, Target target) {}
    private record Resolved(Target target, String remainder) {}

    public static final class View {
        private final Origin origin;
        private final Map<String, Export> exports;
        public View(Origin origin, List<Export> inventory) {
            this.origin = Objects.requireNonNull(origin);
            if (inventory.size() > LIMIT) throw error("E_SOURCE_LIMIT", "export budget exceeded");
            var staged = new TreeMap<String, Export>();
            for (Export e : inventory) {
                requirePath(e.path());
                Objects.requireNonNull(e.kind());
                if (staged.putIfAbsent(e.path(), e) != null)
                    throw error("E_DUPLICATE", "duplicate export");
            }
            exports = Map.copyOf(staged);
        }
        public Origin origin() { return origin; }
        public Target lookup(String path) {
            requirePath(path);
            Export leaf = exports.get(path);
            if (leaf == null) throw error("E_UNBOUND", "unknown export");
            if (!leaf.visible()) throw error("E_PRIVATE", "private export");
            for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
                Export parent = exports.get(path.substring(0, dot));
                if (parent == null) throw error("E_UNBOUND", "missing parent namespace");
                if (!parent.visible()) throw error("E_PRIVATE", "private parent namespace");
                if (!namespace(parent.kind())) throw error("E_IMPORT_KIND", "non-namespace parent");
            }
            return new Target(this, path, leaf.kind());
        }
    }

    private List<Binding> bindings = List.of();
    private static IllegalArgumentException error(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }
    private static void requirePath(String path) {
        if (path == null || path.length() > 519 || path.isEmpty())
            throw error("E_IMPORT_PATH", "invalid static path");
        String[] parts = path.split("\\.", -1);
        if (parts.length > 8 || Arrays.stream(parts).anyMatch(p -> !ContextualImportPath.valid(p)))
            throw error("E_IMPORT_PATH", "invalid static path");
    }
    private static boolean namespace(Kind kind) { return kind == Kind.NAMESPACE || kind == Kind.MODULE; }
    private static boolean prefix(String a, String b) { return b.startsWith(a + "."); }
    private static void insert(List<Binding> staged, Binding b) {
        requirePath(b.name());
        if (staged.size() >= LIMIT) throw error("E_SOURCE_LIMIT", "binding budget exceeded");
        for (Binding prior : staged) {
            if (prior.target().view() != b.target().view() &&
                prior.target().view().origin().equals(b.target().view().origin()))
                throw error("E_AMBIGUOUS", "multiple views for pinned origin");
            if (prior.tier() == Tier.EXPLICIT && b.tier() == Tier.EXPLICIT && prior.name().equals(b.name()))
                throw error("E_DUPLICATE", "duplicate explicit binding");
            if ((prior.tier() == b.tier() || prior.tier() == Tier.EXPLICIT || b.tier() == Tier.EXPLICIT)
                && ((!namespace(prior.target().kind()) && prefix(prior.name(), b.name()))
                    || (!namespace(b.target().kind()) && prefix(b.name(), prior.name()))))
                throw error("E_DUPLICATE", "non-namespace obstructs binding prefix");
        }
        staged.add(b);
    }
    /** The caller supplies a verified view; matching strings alone are not authentication. */
    public void bindRoot(View view, String binding, Tier tier) {
        var staged = new ArrayList<>(bindings);
        insert(staged, new Binding(binding, Objects.requireNonNull(tier), new Target(view, "", Kind.NAMESPACE)));
        bindings = List.copyOf(staged);
    }
    /** Atomic natural suffix candidates. Original package identity is unchanged. */
    public void bindPackage(View view, String explicitAlias) {
        if(view.origin().domain()!=Domain.PACKAGE)
            throw error("E_IMPORT_PATH","expected frozen external package view");
        String identity=view.origin().identity();
        if(!identity.matches("@[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw error("E_IMPORT_PATH","invalid package root");
        var staged=new ArrayList<>(bindings);
        var target=new Target(view,"",Kind.NAMESPACE);
        if(explicitAlias!=null) insert(staged,new Binding(explicitAlias,Tier.EXPLICIT,target));
        else {
            String[] parts=identity.substring(1).replace('-','_').split("/");
            int count=0;
            for(int i=parts.length-1;i>=0;i--) {
                String[] suffix=Arrays.copyOfRange(parts,i,parts.length);
                if(Arrays.stream(suffix).anyMatch(p->!ContextualImportPath.valid(p))) continue;
                insert(staged,new Binding(String.join(".",suffix),Tier.DELIBERATE,target));
                count++;
            }
            if(count==0) throw error("E_IMPORT_PATH","package requires explicit alias");
        }
        bindings=List.copyOf(staged);
    }
    public void bindGroup(Origin requested, View view, List<Selection> selections) {
        if (!view.origin().equals(requested)) throw error("E_IMPORT_PATH", "wrong source identity");
        if (selections.isEmpty() || selections.size() > LIMIT - bindings.size())
            throw error("E_SOURCE_LIMIT", "invalid selection count");
        var staged = new ArrayList<>(bindings);
        var seen = new HashSet<String>();
        for (Selection s : selections) {
            if (!seen.add(s.binding())) throw error("E_DUPLICATE", "duplicate selection");
            Target t = view.lookup(s.path());
            if (t.kind() != s.kind()) throw error("E_IMPORT_KIND", "wrong export kind");
            if (s.binding().contains(".") && !namespace(t.kind()))
                throw error("E_IMPORT_KIND", "qualified alias requires namespace");
            insert(staged, new Binding(s.binding(), s.explicit() ? Tier.EXPLICIT : Tier.DELIBERATE, t));
        }
        bindings = List.copyOf(staged);
    }
    private static Target resolve(List<Binding> scope, String reference) {
        requirePath(reference);
        Tier tier = null;
        Resolved chosen = null;
        boolean ambiguous = false;
        for (Binding b : scope) {
            if (!b.name().equals(reference) && !prefix(b.name(), reference)) continue;
            var candidate = new Resolved(b.target(), b.name().equals(reference) ? "" : reference.substring(b.name().length()+1));
            if (tier == null || b.tier().compareTo(tier) > 0) {
                tier = b.tier(); chosen = candidate; ambiguous = false;
            } else if (b.tier() == tier && !chosen.equals(candidate)) ambiguous = true;
        }
        if (chosen == null) throw error("E_UNBOUND", "unbound static reference");
        if (ambiguous) throw error("E_AMBIGUOUS", "ambiguous static prefix");
        Target t = chosen.target();
        if (chosen.remainder().isEmpty()) return t;
        if (!namespace(t.kind())) throw error("E_IMPORT_KIND", "non-namespace prefix");
        return t.view().lookup(t.path().isEmpty() ? chosen.remainder() : t.path() + "." + chosen.remainder());
    }
    public Target resolve(String reference) { return resolve(bindings, reference); }

    private static TreeMap<String,String> inventory(List<Alias> aliases) {
        if (aliases.size() > LIMIT) throw error("E_SOURCE_LIMIT", "alias budget exceeded");
        var pending = new TreeMap<String,String>();
        for (Alias a : aliases) {
            if (a.name() == null || !ContextualImportPath.valid(a.name()))
                throw error("E_IMPORT_PATH", "invalid alias");
            requirePath(a.reference());
            if (pending.putIfAbsent(a.name(), a.reference()) != null)
                throw error("E_DUPLICATE", "duplicate alias");
        }
        var checked = new HashSet<String>();
        for (String start : pending.keySet()) {
            var walk = new ArrayList<String>();
            var positions = new HashMap<String,Integer>();
            String current = start;
            while (pending.containsKey(current) && !checked.contains(current)) {
                Integer at = positions.putIfAbsent(current, walk.size());
                if (at != null) {
                    var cycle = new ArrayList<>(walk.subList(at, walk.size()));
                    cycle.add(current);
                    throw error("E_IMPORT_CYCLE", String.join(" -> ", cycle));
                }
                walk.add(current);
                current = root(pending.get(current));
            }
            checked.addAll(walk);
        }
        return pending;
    }
    private static String root(String path) { int dot = path.indexOf('.'); return dot < 0 ? path : path.substring(0,dot); }
    public static void precheckAliases(List<Alias> aliases) { inventory(aliases); }
    public void bindAliases(List<Alias> aliases) {
        var pending = inventory(aliases);
        if (pending.size() > LIMIT - bindings.size()) throw error("E_SOURCE_LIMIT", "binding budget exceeded");
        var staged = new ArrayList<>(bindings);
        while (!pending.isEmpty()) {
            var ready = new ArrayList<String>();
            for (var e : pending.entrySet()) {
                if (pending.containsKey(root(e.getValue()))) continue;
                Target t = resolve(staged, e.getValue());
                if (!namespace(t.kind())) throw error("E_IMPORT_KIND", "alias requires namespace");
                insert(staged, new Binding(e.getKey(), Tier.EXPLICIT, t));
                ready.add(e.getKey());
            }
            if (ready.isEmpty()) throw error("E_IMPORT_CYCLE", "cyclic namespace aliases");
            ready.forEach(pending::remove);
        }
        bindings = List.copyOf(staged);
    }
}
