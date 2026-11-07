package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * G1d source-level kernel/launch binding, not a GPU ABI or execution permit.
 *
 * Independently of CPU callable registration, this compiler pass resolves a
 * statically named gpu fnc to an explicit launch in the parsed compilation
 * unit, requires matching arity, rejects ambiguous/duplicate declarations and
 * rejects effectful argument expressions. All device-buffer symbols are still
 * unresolved lexical names, not authenticated device views. Compiler must
 * reject every physical launch after this check until type, loan and provider
 * proofs are issued from an immutable device snapshot.
 */
public final class GpuKernelSourceLinkV1 {
    private GpuKernelSourceLinkV1() { }

    public enum Evidence { SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE }
    public enum ArgumentKind { LEXICAL_NAME_UNTYPED, I64_LITERAL, BOOL_LITERAL }

    public record BoundArgument(ArgumentKind kind, GpuKernelSourceHirV1.Type expected,
                                String name) {
        public BoundArgument {
            Objects.requireNonNull(kind, "GPU argument kind");
            Objects.requireNonNull(expected, "GPU expected type");
        }
    }

    public record Link(String module, String kernel,
                       List<BoundArgument> arguments, Evidence evidence) {
        public Link {
            Objects.requireNonNull(module, "GPU kernel module");
            Objects.requireNonNull(kernel, "GPU kernel name");
            arguments = List.copyOf(arguments);
            if (evidence != Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE)
                throw error("008", "missing source-only evidence");
        }
        public boolean executable() { return false; }
    }

    public record Report(List<Link> links, Evidence evidence) {
        public Report {
            links = List.copyOf(links);
            if (evidence != Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE)
                throw error("008", "missing source-only evidence");
        }
        public boolean executable() { return false; }
    }

    private record Kernel(String module, GpuKernelSourceHirV1.Plan plan,
                          boolean publiclyExported) { }

    private static IllegalArgumentException error(String code, String why) {
        return new IllegalArgumentException("GPU-LAUNCH-" + code + ": " + why);
    }

    private static void register(Map<String, Kernel> qualified,
                                 Map<String, List<Kernel>> unqualified,
                                 Ast.ModuleDecl module, Ast.GpuKernelDecl declaration) {
        GpuKernelSourceHirV1.Plan plan = GpuKernelSourceHirV1.lower(declaration);
        String name = plan.symbol();
        String key = module.name() + "." + name;
        if (qualified.putIfAbsent(key, new Kernel(module.name(), plan,
                declaration.signature().visibility() == Ast.Visibility.PUBLIC)) != null)
            throw error("001", "duplicate gpu fnc declaration " + key);
        unqualified.computeIfAbsent(name, ignored -> new ArrayList<>())
                .add(qualified.get(key));
    }

    private static BoundArgument argument(Ast.Expr expr,
                                         GpuKernelSourceHirV1.Type expected) {
        if (expr instanceof Ast.NameExpr name) {
            // A lexical name alone cannot establish buffer provenance or even
            // its value type. The later typed ABI pass must resolve it.
            if (name.name().isBlank()) throw error("002", "blank GPU argument name");
            return new BoundArgument(ArgumentKind.LEXICAL_NAME_UNTYPED, expected,
                    name.name());
        }
        if (expr instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long n) {
                if (expected != GpuKernelSourceHirV1.Type.I64)
                    throw error("002", "i64 argument cannot implicitly convert to " + expected);
                return new BoundArgument(ArgumentKind.I64_LITERAL, expected, Long.toString(n));
            }
            if (literal.value() instanceof Boolean b) {
                if (expected != GpuKernelSourceHirV1.Type.BOOL)
                    throw error("002", "bool argument cannot implicitly convert to " + expected);
                return new BoundArgument(ArgumentKind.BOOL_LITERAL, expected, b.toString());
            }
            throw error("002", "no implicit f64-to-f32 or host-object GPU argument");
        }
        throw error("006", "GPU argument calls, suspension and host effects are not source-proven");
    }

    private static void link(Ast.GpuLaunchExpr launch, String callerModule,
                             Map<String, Kernel> qualified,
                             Map<String, List<Kernel>> unqualified,
                             List<Link> out) {
        GpuLaunchSourceHirV1.Candidate source = GpuLaunchSourceHirV1.lower(launch);
        String target = source.kernelSymbol();
        // No implicit cross-module search. Name-only references are lexical;
        // even pub-qualified external names remain denied until typed import
        // resolution is available. Source spelling is never import authority.
        Kernel kernel = qualified.get(target.contains(".")
                ? target : callerModule + "." + target);
        if (kernel == null)
            throw error("001", "GPU kernel declaration not found in lexical module: " + target);
        if (!callerModule.equals(kernel.module())) {
            if (!kernel.publiclyExported())
                throw error("001", "private GPU kernel is inaccessible across modules: " + target);
            // Public visibility is necessary but not sufficient: no parsed
            // module-import or linker capability is issued by this source pass.
            throw error("001", "cross-module GPU launch needs a verified typed import");
        }
        List<GpuKernelSourceHirV1.Parameter> parameters = kernel.plan().parameters();
        if (parameters.size() != source.orderedArguments().size())
            throw error("002", "kernel argument arity mismatch for " + target
                    + ": expected " + parameters.size() + " got "
                    + source.orderedArguments().size());
        List<BoundArgument> args = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++)
            args.add(argument(source.orderedArguments().get(i),
                    parameters.get(i).type()));
        out.add(new Link(kernel.module(), kernel.plan().symbol(), args,
                Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE));
    }

    private static void expression(Ast.Expr value, String callerModule,
                                   Map<String, Kernel> qualified,
                                   Map<String, List<Kernel>> unqualified,
                                   List<Link> out, int depth) {
        if (value == null) return;
        if (depth > 64) throw error("006", "GPU launch expression tree exceeds quota");
        if (value instanceof Ast.GpuLaunchExpr launch) {
            link(launch, callerModule, qualified, unqualified, out);
        } else if (value instanceof Ast.AwaitExpr awaited) {
            expression(awaited.expression(), callerModule, qualified, unqualified, out, depth + 1);
        } else if (value instanceof Ast.CallExpr call) {
            expression(call.callee(), callerModule, qualified, unqualified, out, depth + 1);
            for (Ast.Expr arg : call.arguments())
                expression(arg, callerModule, qualified, unqualified, out, depth + 1);
        } else if (value instanceof Ast.BinaryExpr binary) {
            expression(binary.left(), callerModule, qualified, unqualified, out, depth + 1);
            expression(binary.right(), callerModule, qualified, unqualified, out, depth + 1);
        } else if (value instanceof Ast.UnaryExpr unary) {
            expression(unary.operand(), callerModule, qualified, unqualified, out, depth + 1);
        } else if (value instanceof Ast.AssignExpr assign) {
            expression(assign.target(), callerModule, qualified, unqualified, out, depth + 1);
            expression(assign.value(), callerModule, qualified, unqualified, out, depth + 1);
        } else if (value instanceof Ast.IndexExpr index) {
            expression(index.receiver(), callerModule, qualified, unqualified, out, depth + 1);
            expression(index.index(), callerModule, qualified, unqualified, out, depth + 1);
        } else if (value instanceof Ast.ConditionalExpr conditional) {
            expression(conditional.condition(), callerModule, qualified, unqualified, out, depth + 1);
            expression(conditional.whenTrue(), callerModule, qualified, unqualified, out, depth + 1);
            expression(conditional.whenFalse(), callerModule, qualified, unqualified, out, depth + 1);
        }
        // This is a source-level diagnostic preflight, NOT a completeness
        // proof for arbitrary nested AST nodes. Every unproven launch remains
        // blocked by the normal GPU-LAUNCH-008 typechecker path.
    }

    private static void statements(List<Ast.Stmt> body, String callerModule,
                                   Map<String, Kernel> qualified,
                                   Map<String, List<Kernel>> unqualified,
                                   List<Link> out, int depth) {
        if (depth > 32 || body.size() > 1024)
            throw error("006", "GPU launch source control-flow quota exceeded");
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.BindingStmt bind) {
                expression(bind.initializer(), callerModule, qualified, unqualified, out, 0);
            } else if (stmt instanceof Ast.ExprStmt expr) {
                expression(expr.expression(), callerModule, qualified, unqualified, out, 0);
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                expression(ret.value(), callerModule, qualified, unqualified, out, 0);
            } else if (stmt instanceof Ast.BlockStmt block) {
                statements(block.body(), callerModule, qualified, unqualified, out, depth + 1);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    expression(branch.condition(), callerModule, qualified, unqualified, out, 0);
                    statements(branch.body(), callerModule, qualified, unqualified, out, depth + 1);
                }
                statements(conditional.elseBody(), callerModule, qualified, unqualified, out, depth + 1);
            }
        }
    }

    /** Source signature/arity check only. Never use this report to enqueue. */
    public static Report audit(Ast.Program program) {
        Objects.requireNonNull(program, "AttoLang program");
        Map<String, Kernel> qualified = new HashMap<>();
        Map<String, List<Kernel>> unqualified = new HashMap<>();
        Map<String, Set<String>> hostFunctions = new HashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.GpuKernelDecl gpu)
                    register(qualified, unqualified, module, gpu);
                else if (decl instanceof Ast.FunctionDecl host)
                    hostFunctions.computeIfAbsent(module.name(), ignored -> new HashSet<>())
                            .add(host.name());
            }
        }
        for (Kernel kernel : qualified.values()) {
            if (hostFunctions.getOrDefault(kernel.module(), Set.of())
                    .contains(kernel.plan().symbol()))
                throw error("001", "GPU kernel conflicts with host function in same module");
        }
        List<Link> links = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl host)
                    statements(host.body(), module.name(), qualified, unqualified, links, 0);
            }
        }
        return new Report(links, Evidence.SOURCE_SIGNATURE_ONLY_NOT_EXECUTABLE);
    }
}
