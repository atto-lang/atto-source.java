package atto.compiler.asyncv1;

/** Compile-time source admission regressions, not a runtime Future scheduler. */
public final class AttoAsyncV1CompilerProbe {
    private AttoAsyncV1CompilerProbe() {}
    private static int checks;
    private static void check(boolean value) {
        if (!value) throw new AssertionError("checked async source admission");
        checks++;
    }
    private static void rejects(String source) {
        try {
            AttoAsyncV1Compiler.compile(source);
        } catch (AttoAsyncV1Compiler.Reject expected) {
            checks++;
            return;
        }
        throw new AssertionError("unsupported source was accepted: " + source);
    }
    public static void main(String[] args) {
        if (args.length != 0) throw new AssertionError("unexpected args");
        String ok = "fnc plus_one(int x): int { ^ x + 1; }"
            + " pub susp fnc main(): int { val pending = task { ^ 41; };"
            + " ^ pending |> susp |f| -> await f |> plus_one >> val answer: int; }";
        String generated = AttoAsyncV1Compiler.compile(ok);
        check(generated.contains("CompletableFuture.completedFuture(41)"));
        check(generated.contains("return Math.addExact(x, 1);"));
        check(generated.contains("ATTO_ASYNC_V1_RESULT"));
        rejects(ok.replace("susp |f|", "|f|"));
        rejects(ok.replace("await f", "await g"));
        rejects(ok.replace("|> plus_one", "|> minus_one"));
        rejects(ok.replace("^ pending", "^ missing"));
        rejects(ok.replace("41", "1_000"));
        rejects(ok.replace("41", "-41"));
        rejects(ok.replace("41", "2147483647"));
        rejects(ok.replace(">> val answer: int;", ">> val answer: float;"));
        rejects(ok.replace(">> val answer: int;", ">> val answer: int; ^ 42;"));
        rejects(ok.replace("|> plus_one", "|> plus_one |> plus_one"));
        rejects(ok.replace("fnc plus_one", "fnc plus_one //comment"));
        rejects(ok.replace(">> val answer", ">> val f"));
        rejects(ok + " class Other {}");
        rejects(ok + "\u0000");
        // Canonical payload typing: task<int> returns Future<int>, not int.
        String typed = ok.replace("task { ^ 41; }", "task<int> { return 41; }");
        String typedGenerated = AttoAsyncV1Compiler.compile(typed);
        check(typedGenerated.contains("CompletableFuture<Integer> pending"));
        check(typedGenerated.contains("CompletableFuture.completedFuture(41)"));
        check(typedGenerated.equals(AttoAsyncV1Compiler.compile(
            ok.replace("task { ^ 41; }", "task<int> { ^ 41; }"))));
        // Exactly one Future layer; this tiny source compiler has neither
        // nested Future result types nor a cancellable TaskScope runtime.
        rejects(typed.replace("task<int>", "task<Future<int>>"));
        rejects(typed.replace("task<int>", "task<void>"));
        rejects(typed.replace("task<int>", "sync task<int>"));
        rejects(typed.replace("task<int>", "await task<int>"));
        rejects(typed.replace("val pending", "val sync"));
        rejects(typed.replace("task<int>", "do sync task<int>"));
        rejects(typed.replace("task<int>", "do task<int>"));
        rejects(typed.replace("return 41;", "return 41; pending.cancel();"));
        rejects(typed.replace("task<int>", "task<int> /* cancel lock */"));
        check(AttoAsyncV1Compiler.compile(ok.replace("41", "40"))
            .contains("completedFuture(40)"));
        // Reject both Atto control words and Java keywords before writing
        // generated code, not only when javac later refuses that code.
        for (String name : new String[] {
            "task", "sync", "await", "class", "new", "record",
            "sealed", "yield", "return", "static"
        }) {
            rejects(ok.replace("plus_one", name));
        }
        rejects(ok.replace("val pending", "val class"));
        rejects(ok.replace("val pending", "val task"));
        rejects(ok.replace("susp |f|", "susp |return|"));
        rejects(ok.replace("plus_one", "_"));
        rejects(ok.replace("val pending", "val _"));
        rejects(ok.replace("int x", "int _"));
        check(AttoAsyncV1Compiler.compile(ok.replace("pending", "sync_data"))
            .contains("CompletableFuture<Integer> sync_data"));
        // This restricted source-to-Java lowering only admits snake_case
        // guest names. An upper-case `Math` parameter previously shadowed
        // java.lang.Math and caused javac to fail after source admission.
        for (String bad : new String[] { "Math", "System", "Future", "PlusOne", "xValue" }) {
            rejects(ok.replace("int x", "int " + bad).replace("^ x +", "^ " + bad + " +"));
        }
        rejects(ok.replace("fnc plus_one", "fnc PlusOne"));
        rejects(ok.replace("val pending", "val Future"));
        check(AttoAsyncV1Compiler.compile(ok.replace("pending", "future_42"))
            .contains("CompletableFuture<Integer> future_42"));
        // A rejected source token must never be echoed into diagnostics.
        // This string is deliberately shaped like a private guest identifier.
        final String canary = "private_guest_token_abc123";
        try {
            AttoAsyncV1Compiler.compile(ok.replace("pub susp", canary + " susp"));
            throw new AssertionError("malformed source unexpectedly compiled");
        } catch (AttoAsyncV1Compiler.Reject rejected) {
            check("E_ASYNC_UNEXPECTED_TOKEN".equals(rejected.getMessage()) &&
                !rejected.getMessage().contains(canary));
        }
        System.out.println("ATTO_ASYNC_V1_ADMISSION_PASS checks=" + checks);
    }
}
