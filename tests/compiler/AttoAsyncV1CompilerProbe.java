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
    private static void rejectsType(String source) {
        try {
            AttoAsyncV1Compiler.compile(source);
        } catch (AttoAsyncV1Compiler.Reject expected) {
            check("E_TASK_RETURN_TYPE".equals(expected.getMessage()));
            return;
        }
        throw new AssertionError("non-void do sync task was accepted");
    }
    private static void rejectsSyntax(String source) {
        try {
            AttoAsyncV1Compiler.compile(source);
        } catch (AttoAsyncV1Compiler.Reject expected) {
            check("E_SYNTAX".equals(expected.getMessage()));
            return;
        }
        throw new AssertionError("bare sync task was admitted without do");
    }
    public static void main(String[] args) {
        if (args.length != 0) throw new AssertionError("unexpected args");
        String ok = "fnc plus_one(int x): int { ^ x + 1; }"
            + " pub susp fnc main(): int { val pending = task { ^ 41; };"
            + " ^ pending |> susp |f| -> await f |> plus_one >> val answer: int; }";
        String generated = AttoAsyncV1Compiler.compile(ok);
        check(generated.contains("CompletableFuture.completedFuture(41L)"));
        check(generated.contains("return Math.addExact(x, 1L);"));
        check(generated.contains("ATTO_ASYNC_V1_RESULT"));
        rejects(ok.replace("susp |f|", "|f|"));
        rejects(ok.replace("await f", "await g"));
        rejects(ok.replace("|> plus_one", "|> minus_one"));
        rejects(ok.replace("^ pending", "^ missing"));
        rejects(ok.replace("41", "1_000"));
        rejects(ok.replace("41", "-41"));
        rejects(ok.replace("41", "9223372036854775807"));
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
        check(typedGenerated.contains("CompletableFuture<Long> pending"));
        check(typedGenerated.contains("CompletableFuture.completedFuture(41L)"));
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
            .contains("completedFuture(40L)"));
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
            .contains("CompletableFuture<Long> sync_data"));
        // This restricted source-to-Java lowering only admits snake_case
        // guest names. An upper-case `Math` parameter previously shadowed
        // java.lang.Math and caused javac to fail after source admission.
        for (String bad : new String[] { "Math", "System", "Future", "PlusOne", "xValue" }) {
            rejects(ok.replace("int x", "int " + bad).replace("^ x +", "^ " + bad + " +"));
        }
        rejects(ok.replace("fnc plus_one", "fnc PlusOne"));
        rejects(ok.replace("val pending", "val Future"));
        check(AttoAsyncV1Compiler.compile(ok.replace("pending", "future_42"))
            .contains("CompletableFuture<Long> future_42"));
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
        // Pure lexical sync tasks do not allocate a Future or schedule work.
        String prefix = "fnc plus_one(int x): int { ^ x + 1; }";
        String inline = prefix + " pub fnc main(): int { "
            + "val answer = sync task<int> { return 41; }; "
            + "do sync task<void> { return; }; return plus_one(answer); }";
        String inlineJava = AttoAsyncV1Compiler.compile(inline);
        check(inlineJava.contains("atto_inline_task_0:"));
        check(inlineJava.contains("atto_inline_task_1:"));
        check(inlineJava.contains("return plus_one(answer);"));
        check(inlineJava.contains("PURE_INLINE_TASK_ONLY"));
        check(!inlineJava.contains("CompletableFuture") && !inlineJava.contains(".join()"));
        check(inlineJava.equals(AttoAsyncV1Compiler.compile(inline.replace(
            "pub fnc main", "pub susp fnc main"))));
        check(AttoAsyncV1Compiler.compile(inline.replace("sync task<int>", "sync task"))
            .contains("return plus_one(answer);"));
        String empty = prefix + " pub fnc main(): int { do sync task {}; return 42; }";
        check(AttoAsyncV1Compiler.compile(empty).contains("return 42L;"));
        check(AttoAsyncV1Compiler.compile(empty.replace("do sync task {}", "do sync task<void> {}"))
            .contains("return 42L;"));
        check(AttoAsyncV1Compiler.compile(empty.replace("do sync task {}", "do sync task<void> { return; }"))
            .contains("atto_inline_task_0:"));
        rejects(empty.replace("sync task {}", "sync task<int> {}"));
        rejects(empty.replace("sync task {}", "sync task<void> { return 5; }"));
        rejects(empty.replace("sync task {}", "sync task { await pending; }"));
        rejects(empty.replace("sync task {}", "sync task { using guard; }"));
        rejects(inline.replace("sync task<int> { return 41; }", "sync task<int> { return 41; return 99; }"));
        rejects(inline.replace("do sync task<void>", "do task<void>"));
        rejects(inline.replace("return plus_one(answer)", "return plus_one(other)"));
        rejects(inline.replace("return plus_one(answer)", "return missing(answer)"));
        rejects(inline.replace("sync task<int> { return 41; }", "sync task<Future<int>> { return 41; }"));
        rejects(inline.replace("sync task<int> { return 41; }", "sync task<int> { return 41; pending.cancel(); }"));
        rejectsSyntax("pub fnc main(): int { sync task {}; return 42; }");
        rejectsSyntax("pub fnc main(): int { sync task<void> { return; }; return 42; }");
        String standalone = "pub fnc main(): int { do sync task {}; return 42; }";
        check(AttoAsyncV1Compiler.compile(standalone).contains("atto_inline_task_0:"));
        check(!AttoAsyncV1Compiler.compile(standalone).contains("private static int plus_one"));
        check(AttoAsyncV1Compiler.compile("pub fnc main(): int { "
            + "val answer = sync task<int> { return 42; }; return answer; }")
            .contains("return answer;"));
        rejects("pub fnc main(): int { val answer = task<int> { return 42; }; return answer; }");
        rejects("pub fnc main(): int { val answer = sync task<int> { return 42; }; return other; }");
        // do sync task only has a void payload, whether omitted or explicit.
        // No explicit/inferred nonvoid return can escape this diagnostic.
        check(AttoAsyncV1Compiler.compile("pub fnc main(): int { "
            + "do sync task {}; return 42; }").contains("atto_inline_task_0:"));
        check(AttoAsyncV1Compiler.compile("pub fnc main(): int { "
            + "do sync task<void> { return; }; return 42; }")
            .contains("atto_inline_task_0:"));
        check(AttoAsyncV1Compiler.compile("pub fnc main(): int { "
            + "val answer = sync task<int> { return 42; }; "
            + "do sync task {}; return answer; }").contains("atto_inline_task_1:"));
        check(AttoAsyncV1Compiler.compile("pub fnc main(): int { "
            + "val answer = sync task<int> { return 42; }; "
            + "do sync task<void> { return; }; return answer; }")
            .contains("atto_inline_task_1:"));
        rejectsType("pub fnc main(): int { do sync task<int> {}; return 42; }");
        rejectsType("pub fnc main(): int { do sync task<int> { return 42; }; return 42; }");
        rejectsType("pub fnc main(): int { do sync task<bool> {}; return 42; }");
        rejectsType("pub fnc main(): int { do sync task<Future<int>> {}; return 42; }");
        rejectsType("pub fnc main(): int { do sync task { return 42; }; return 42; }");
        rejectsType("pub fnc main(): int { do sync task<void> { return 42; }; return 42; }");
        rejectsType("pub fnc main(): int { do sync task { return 42; } return 42; }");
        rejectsType("pub fnc main(): int { val answer = sync task<int> { return 41; }; "
            + "do sync task<int> {}; return answer; }");
        rejectsType("pub fnc main(): int { val answer = sync task<int> { return 41; }; "
            + "do sync task<void> { return 9; }; return answer; }");
        rejectsType("pub fnc main(): int { val answer = sync task<int> { return 41; }; "
            + "do sync task { return 9; }; return answer; }");
        System.out.println("ATTO_ASYNC_V1_ADMISSION_PASS checks=" + checks);
    }
}
