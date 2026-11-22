package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.lang.reflect.Method;
import java.util.List;

/** SDK-free and JUnit-free executable source-to-classfile smoke/negative tests. */
public final class RealtimePrimitiveSmoke {
    private RealtimePrimitiveSmoke() { }

    private static Ast.TypeRef i32() { return Ast.TypeRef.simple("i32"); }
    private static Ast.Param param(String name) { return new Ast.Param(i32(), name); }
    private static Ast.FunctionDecl fn(String name, List<Ast.Param> params, List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(name, Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, Ast.ActorKind.NONE, List.of(), params, i32(), List.of(), body);
    }
    private static Ast.Program program(Ast.FunctionDecl... fns) {
        return new Ast.Program(List.of(new Ast.ModuleDecl("test", List.of(fns))));
    }
    private static Ast.LiteralExpr num(int value) { return new Ast.LiteralExpr(value); }
    private static Ast.NameExpr name(String name) { return new Ast.NameExpr(name); }
    private static Ast.ReturnStmt ret(Ast.Expr e) { return new Ast.ReturnStmt(e); }

    private static void rejectsClassfile(String label, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("Accepted forbidden classfile case: " + label);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().startsWith("RT-CLASSFILE:")) {
                throw new AssertionError("Unclassified classfile failure: " + label, expected);
            }
        } catch (Exception unexpected) {
            throw new AssertionError("Unexpected classfile failure: " + label, unexpected);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    private static void rejects(String label, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Accepted forbidden case: " + label);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().startsWith("RT-PRIMITIVE:")) {
                throw new AssertionError("Unclassified failure: " + label, expected);
            }
        }
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length != 1) throw new IllegalArgumentException("expected output directory");
        Path out = Path.of(argv[0]);
        var budget = new RealtimePrimitiveCompiler.Budget(200, 3);
        Ast.FunctionDecl plus = fn("add1", List.of(param("x")),
                List.of(ret(new Ast.BinaryExpr("+", name("x"), num(1)))));
        Ast.FunctionDecl tick = fn("tick", List.of(param("x")),
                List.of(ret(new Ast.CallExpr(name("add1"), List.of(name("x"))))));
        Ast.Program simple = program(tick, plus);
        var artifact = RealtimePrimitiveCompiler.compile(simple, "tick", 1, budget, out);
        if (!artifact.classFile().toFile().isFile()
                || !artifact.manifestFile().toFile().isFile()
                || artifact.bytecodeSha256().length() != 64
                || artifact.sourceReport().reachableFunctions() != 2
                || artifact.sourceReport().maxCallDepth() != 2
                || artifact.classfileReport().verifiedMethods() != 2
                || !artifact.classfileReport().assurance().contains("CLASSFILE")) {
            throw new AssertionError("missing classfile, manifest, call graph, bytecode audit, or fingerprint");
        }
        String manifest = Files.readString(artifact.manifestFile(), StandardCharsets.UTF_8);
        if (!manifest.contains("schema=oreslang.realtime-artifact.v1")
                || !manifest.contains("bytecode.sha256=" + artifact.bytecodeSha256())
                || !manifest.contains("classfile.acyclic_call_graph=true")) {
            throw new AssertionError("realtime artifact manifest is incomplete or not bound to classfile");
        }

        Path forbiddenOut = out.resolve("forbidden-classfile");
        Files.createDirectories(forbiddenOut);
        Path forbiddenJava = forbiddenOut.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".java");
        Files.writeString(forbiddenJava, """
                final class RtPrimitiveModule {
                  static int tick(int x) {
                    Object forbidden = new Object();
                    return x;
                  }
                }
                """, StandardCharsets.UTF_8);
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null || javac.run(null, null, null,
                "--release", "8", "-g:none", "-proc:none",
                "-d", forbiddenOut.toString(), forbiddenJava.toString()) != 0) {
            throw new AssertionError("failed to build adversarial classfile fixture");
        }
        rejectsClassfile("heap allocation bytecode", () ->
                RealtimeClassfileAudit.audit(
                        forbiddenOut.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".class")));

        Path recursiveOut = out.resolve("recursive-classfile");
        Files.createDirectories(recursiveOut);
        Path recursiveJava = recursiveOut.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".java");
        Files.writeString(recursiveJava, """
                final class RtPrimitiveModule {
                  static int tick(int x) { return helper(x); }
                  static int helper(int x) { return tick(x); }
                }
                """, StandardCharsets.UTF_8);
        if (javac.run(null, null, null,
                "--release", "8", "-g:none", "-proc:none",
                "-d", recursiveOut.toString(), recursiveJava.toString()) != 0) {
            throw new AssertionError("failed to build recursive classfile fixture");
        }
        rejectsClassfile("mutually recursive bytecode call graph", () ->
                RealtimeClassfileAudit.audit(
                        recursiveOut.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".class")));

        Path synchronizedOut = out.resolve("synchronized-classfile");
        Files.createDirectories(synchronizedOut);
        Path synchronizedJava = synchronizedOut.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".java");
        Files.writeString(synchronizedJava, """
                final class RtPrimitiveModule {
                  static synchronized int tick(int x) { return x; }
                }
                """, StandardCharsets.UTF_8);
        if (javac.run(null, null, null,
                "--release", "8", "-g:none", "-proc:none",
                "-d", synchronizedOut.toString(), synchronizedJava.toString()) != 0) {
            throw new AssertionError("failed to build synchronized classfile fixture");
        }
        rejectsClassfile("implicit synchronized monitor", () ->
                RealtimeClassfileAudit.audit(
                        synchronizedOut.resolve(RealtimePrimitiveCompiler.CLASS_NAME + ".class")));
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {out.toUri().toURL()})) {
            Class<?> generated = Class.forName(RealtimePrimitiveCompiler.CLASS_NAME, true, loader);
            Method method = generated.getDeclaredMethod("tick", int.class);
            method.setAccessible(true);
            for (int i : new int[] {-25, -1, 0, 1, 200}) {
                int actual = (int) method.invoke(null, i);
                if (actual != i + 1) throw new AssertionError("bad generated result " + actual);
            }
        }
        rejects("budget", () -> RealtimePrimitiveCompiler.analyze(
                simple, "tick", 1, new RealtimePrimitiveCompiler.Budget(2, 1)));
        rejects("unknown function", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(), List.of(ret(new Ast.CallExpr(name("external"), List.of()))))),
                "tick", 0, budget));
        rejects("recursive function", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(), List.of(ret(new Ast.CallExpr(name("tick"), List.of()))))),
                "tick", 0, budget));
        rejects("possible zero division exception", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(), List.of(ret(new Ast.BinaryExpr("/", num(8), num(2)))))),
                "tick", 0, budget));
        rejects("unbounded loop", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(), List.of(new Ast.LoopStmt(List.of(ret(num(1))))))),
                "tick", 0, budget));
        rejects("heap/runtime primitive", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(), List.of(ret(new Ast.RuntimeCallExpr("copy", List.of(num(2))))))),
                "tick", 0, budget));
        rejects("unknown local", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(), List.of(ret(name("unbound"))))),
                "tick", 0, budget));
        rejects("heap parameter", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("tick", List.of(new Ast.Param(Ast.TypeRef.simple("String"), "s")),
                        List.of(ret(num(1))))),
                "tick", 1, budget));
        rejects("class or global mutable state", () -> RealtimePrimitiveCompiler.analyze(
                new Ast.Program(List.of(new Ast.ModuleDecl("test",
                        List.of(new Ast.FieldDecl("counter", Ast.Visibility.PUBLIC,
                                Ast.BindingKind.VAL, i32(), List.of(), num(0)))))),
                "tick", 0, budget));
        Ast.FunctionDecl condition = fn("choose", List.of(param("x")), List.of(
                new Ast.IfStmt(List.of(new Ast.IfBranch(
                        new Ast.BinaryExpr(">", name("x"), num(0)), List.of(ret(num(4))))),
                        List.of(ret(num(3))))));
        var ifProof = RealtimePrimitiveCompiler.analyze(program(condition), "choose", 1, budget);
        if (ifProof.maxSourceSteps() < 4) throw new AssertionError("branch cost was not analyzed");
        Path branchOut = out.resolve("branch");
        RealtimePrimitiveCompiler.compile(program(condition), "choose", 1, budget, branchOut);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {branchOut.toUri().toURL()})) {
            Method choose = Class.forName(RealtimePrimitiveCompiler.CLASS_NAME, true, loader)
                    .getDeclaredMethod("choose", int.class);
            choose.setAccessible(true);
            for (int input : new int[] {-7, 0, 1, 30}) {
                int actual = (int) choose.invoke(null, input);
                if (actual != (input > 0 ? 4 : 3)) throw new AssertionError("bad branch output");
            }
        }

        rejects("missing return", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("missing", List.of(), List.of(new Ast.ExprStmt(num(1))))),
                "missing", 0, budget));
        rejects("unreachable statement after return", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("dead", List.of(), List.of(ret(num(1)), ret(num(2))))),
                "dead", 0, budget));
        Ast.IfBranch returningBranch = new Ast.IfBranch(
                new Ast.BinaryExpr(">", name("x"), num(0)), List.of(ret(num(1))));
        Ast.IfStmt incompleteBranch = new Ast.IfStmt(
                List.of(returningBranch), List.of(new Ast.ExprStmt(num(3))));
        rejects("unreturned else path", () -> RealtimePrimitiveCompiler.analyze(
                program(fn("branch", List.of(param("x")), List.of(incompleteBranch))),
                "branch", 1, budget));
        rejects("excessive expression nesting", () -> {
            Ast.Expr nested = num(0);
            for (int k = 0; k < 70; k++) nested = new Ast.UnaryExpr("-", nested);
            RealtimePrimitiveCompiler.analyze(program(fn("deep", List.of(), List.of(ret(nested)))),
                    "deep", 0, budget);
        });
        rejects("excessive parameter count", () -> {
            java.util.ArrayList<Ast.Param> params = new java.util.ArrayList<>();
            for (int k = 0; k < 33; k++) params.add(param("p" + k));
            RealtimePrimitiveCompiler.analyze(program(fn("many", params, List.of(ret(num(1))))),
                    "many", 33, budget);
        });
        System.out.println("PASS: primitive compiler, exact classfile audit, branches, negative cases, and independent admission");
    }
}
