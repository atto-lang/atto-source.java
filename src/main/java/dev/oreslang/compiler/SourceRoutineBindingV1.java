package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.SourceIdentityV1;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java/Truffle frontend-owned exact routine ID binding.
 *
 * This is a cold-path candidate accepting previously registry-verified entries.
 * It operates on Java's own parsed Ast.Program and indexes callable nodes by
 * object identity. It does NOT install Truffle probes, compile JIT code or
 * authorize untrusted callers to supply an arbitrary production manifest.
 */
public final class SourceRoutineBindingV1 {
    private SourceRoutineBindingV1() { }

    public record Entry(String module, String function, int arity, String id) {
        public Entry {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(id, "checked routine id");
            if (module.isBlank() || function.isBlank() || arity < 0) {
                throw new IllegalArgumentException("invalid exact callable source selector");
            }
        }
    }

    private record Selector(String module, String function, int arity) { }

    /**
     * Return an immutable identity-based table; never hash source AST records
     * structurally or accidentally conflate identical function bodies.
     */
    public static Map<Ast.FunctionDecl, SourceIdentityV1.Id> bind(
            Ast.Program program, List<Entry> checked) {
        Objects.requireNonNull(program, "validated Java guest program");
        Objects.requireNonNull(checked, "source registry entries");
        if (checked.isEmpty() || checked.size() > 4096) {
            throw new IllegalArgumentException("checked function registry must be bounded and nonempty");
        }
        Map<Selector, SourceIdentityV1.Id> registry = new HashMap<>();
        var uniqueIds = new HashSet<SourceIdentityV1.Id>();
        for (Entry entry : checked) {
            Objects.requireNonNull(entry, "source registry entry");
            var id = SourceIdentityV1.parse(entry.id(), SourceIdentityV1.Kind.ROUTINE);
            if (!uniqueIds.add(id)
                    || registry.putIfAbsent(
                            new Selector(entry.module(), entry.function(), entry.arity()), id)
                            != null) {
                throw new IllegalArgumentException("duplicate routine source identity/selector");
            }
        }
        var bound = new IdentityHashMap<Ast.FunctionDecl, SourceIdentityV1.Id>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)
                        || fn.actorKind() != Ast.ActorKind.NONE
                        || fn.async() || fn.suspends() || fn.generator()
                        || !fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException(
                            "source ID metadata requires exact synchronous scalar function coverage");
                }
                var selector = new Selector(
                        module.name(), fn.name(), fn.parameters().size());
                var id = registry.remove(selector);
                if (id == null) {
                    throw new IllegalArgumentException(
                            "missing checked routine source ID for " + selector);
                }
                bound.put(fn, id);
            }
        }
        if (!registry.isEmpty() || bound.size() != checked.size()) {
            throw new IllegalArgumentException("stale or duplicate checked Java routine registry");
        }
        return Collections.unmodifiableMap(bound);
    }
}
