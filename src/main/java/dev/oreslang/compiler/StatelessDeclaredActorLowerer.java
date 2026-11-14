package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicitly opt-in, tightly constrained first executable lowering for a
 * canonical standalone actor in ONE source compilation unit.
 *
 * <p>Only stateless actors with an optional inert `external init(): void { return; }`
 * and one required `external receive(ActorMail<T>): void` are admitted.
 * The missing init uses a compiler-generated no-op; it is never an
 * exported guest callable hook or a state-builder capability. The transform
 * occurs before normal type/ownership checking and uses the production
 * ClassDecl actor dispatcher, but never fabricates an executable class for an
 * unbound or incomplete nominal declaration. No state access or cross-file
 * executable attachment is claimed.</p>
 */
public final class StatelessDeclaredActorLowerer {
    private StatelessDeclaredActorLowerer() { }

    public static Ast.Program lower(Ast.Program program) {
        Objects.requireNonNull(program, "declared actor source program");

        // Fail before transforming anything: this proves module-local actor
        // identity, external roles, privacy and mailbox signature. The normal
        // TypeChecker will recheck all emitted executable method bodies.
        ActorReceiverBinder.bind(program);
        List<Ast.ModuleDecl> modules = new ArrayList<>();
        boolean loweredAny = false;

        for (Ast.ModuleDecl module : program.modules()) {
            Map<String, Ast.StandaloneActorDecl> declared = new LinkedHashMap<>();
            Map<String, List<Ast.ExternalActorMethodDecl>> methods = new LinkedHashMap<>();
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.StandaloneActorDecl actor) {
                    if (actor.receiverName() != null) {
                        throw new IllegalArgumentException(
                                "STATELESS_ACTOR_LEGACY_UNSUPPORTED: use 'declare actor' for the opt-in executable subset");
                    }
                    declared.put(actor.name(), actor);
                } else if (decl instanceof Ast.ExternalActorMethodDecl method) {
                    methods.computeIfAbsent(method.actorName(),
                            unused -> new ArrayList<>()).add(method);
                }
            }
            if (declared.isEmpty() && methods.isEmpty()) {
                modules.add(module);
                continue;
            }
            for (String actor : methods.keySet()) {
                if (!declared.containsKey(actor)) {
                    throw new IllegalArgumentException(
                            "STATELESS_ACTOR_CROSS_UNIT_UNSUPPORTED: runtime actor method '"
                                    + actor + "' must attach to a local canonical declaration");
                }
            }

            List<Ast.Decl> lowered = new ArrayList<>();
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ExternalActorMethodDecl) continue;
                if (!(decl instanceof Ast.StandaloneActorDecl actor)) {
                    lowered.add(decl);
                    continue;
                }
                List<Ast.ExternalActorMethodDecl> roles =
                        methods.getOrDefault(actor.name(), List.of());
                Ast.ExternalActorMethodDecl init = null;
                Ast.ExternalActorMethodDecl receive = null;
                for (Ast.ExternalActorMethodDecl method : roles) {
                    if (!method.runtimeEntrypoint()) {
                        throw new IllegalArgumentException(
                                "STATELESS_ACTOR_UNVERIFIED_METHOD: only explicit external lifecycle entrypoints may lower");
                    }
                    switch (method.name()) {
                        case "init" -> {
                            if (init != null) throw new IllegalArgumentException(
                                    "STATELESS_ACTOR_DUPLICATE_INIT");
                            init = method;
                        }
                        case "receive" -> {
                            if (receive != null) throw new IllegalArgumentException(
                                    "STATELESS_ACTOR_DUPLICATE_RECEIVE");
                            receive = method;
                        }
                        default -> throw new IllegalArgumentException(
                                "STATELESS_ACTOR_UNVERIFIED_METHOD: " + method.name());
                    }
                }
                if (receive == null) {
                    throw new IllegalArgumentException(
                            "STATELESS_ACTOR_INCOMPLETE: mailbox actor requires external receive");
                }
                if (init != null && (!init.parameters().isEmpty()
                        || init.body().size() != 1
                        || !(init.body().getFirst() instanceof Ast.ReturnStmt ret)
                        || ret.value() != null)) {
                    throw new IllegalArgumentException(
                            "STATELESS_ACTOR_INIT_NOT_INERT: only zero-argument 'return;' is admitted; "
                                    + "stateful init requires checked state builder and atomic publication");
                }
                // Preserve the validated receive payload type and source body.
                // Never expose the declared receiver alias as a capability:
                // the executable actor receives only its compiler-owned self.
                // A no-init declared actor must still pass the same runtime
                // initialization/READY barrier. Synthesize only the inert
                // source-level start method, never a raw actor/state return.
                Ast.MethodDecl onStart = init == null
                        ? method("on_start", List.of(), Ast.TypeRef.simple("void"),
                                List.of(new Ast.ReturnStmt(null)))
                        : method("on_start", init.parameters(), init.returnType(), init.body());
                Ast.MethodDecl onReceive = method(
                        "receive", receive.parameters(), receive.returnType(), receive.body());
                lowered.add(new Ast.ClassDecl(
                        actor.name(), actor.visibility(), false, actor.actorKind(),
                        List.of(), List.of(), List.of(), List.of(), null,
                        List.of(onStart, onReceive),
                        Ast.ActorConcurrency.serial(Ast.ActorTransport.GENERIC)));
                loweredAny = true;
            }
            modules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), lowered));
        }

        if (!loweredAny) {
            throw new IllegalArgumentException(
                    "STATELESS_ACTOR_NOT_FOUND: opt-in executable path requires 'declare actor'");
        }
        return new Ast.Program(program.namespace(), program.imports(), modules);
    }

    private static Ast.MethodDecl method(
            String name, List<Ast.Param> parameters, Ast.TypeRef returns,
            List<Ast.Stmt> body) {
        return new Ast.MethodDecl(
                name, Ast.Visibility.PRIVATE, false, false, false, false,
                false, null, List.of(), parameters, returns, List.of(), body);
    }
}
