package atto.compiler.asyncv1;

import java.util.List;

/** Adversarial typed inline-task parser/lowering checks, no scheduler claims. */
public final class AttoInlineTaskV2Probe {
    private AttoInlineTaskV2Probe() {}
    private static int positives, negatives;

    private static AttoInlineTaskV2Compiler.Plan accept(String src) {
        AttoInlineTaskV2Compiler.Plan p = AttoInlineTaskV2Compiler.compilePlan(src);
        if (!p.javaSource().contains("PURE_INLINE_TYPED_V2")
                || p.javaSource().contains("CompletableFuture")
                || p.javaSource().contains(".join(")
                || p.javaSource().contains("TaskSpawn")) {
            throw new AssertionError("inline task accidentally schedules a child");
        }
        long enters = p.typedOps().stream()
            .filter(x -> x == AttoInlineTaskV2Compiler.MirOp.INLINE_TASK_ENTER).count();
        long exits = p.typedOps().stream()
            .filter(x -> x == AttoInlineTaskV2Compiler.MirOp.INLINE_TASK_EXIT).count();
        if (enters == 0 || enters != exits)
            throw new AssertionError("unbalanced inline enter/exit");
        positives++;
        return p;
    }
    private static void reject(String src, String code) {
        try {
            AttoInlineTaskV2Compiler.compilePlan(src);
        } catch (AttoAsyncV1Compiler.Reject expected) {
            if (!code.equals(expected.getMessage()))
                throw new AssertionError("unexpected diagnostic: " + expected.getMessage());
            negatives++;
            return;
        }
        throw new AssertionError("bad inline task accepted");
    }
    private static void rejectIntegratedLexical(String source) {
        try {
            AttoAsyncV1Compiler.compile(source);
        } catch (AttoAsyncV1Compiler.Reject expected) {
            if (!expected.getMessage().startsWith("E_ASYNC_UNKNOWN_TOKEN"))
                throw new AssertionError("public compiler lexer diagnostic changed");
            negatives++;
            return;
        }
        throw new AssertionError("malformed separator reached guest compilation");
    }
    private static String main(String body) {
        return "pub fnc main(): int { " + body + " }";
    }
    public static void main(String[] args) {
        if (args.length != 0) throw new AssertionError("unexpected arguments");

        accept(main("do sync task {}; return 42;"));
        accept(main("do sync task<void> { return; }; return 42;"));
        accept(main("val answer = sync task<int> { return 42; }; return answer;"));
        accept(main("val int answer = sync task<int> { val n = 40; return n + 2; }; return answer;"));
        accept(main("val x = sync task { val a = 40; val b = 2; return a + b; }; return x;"));
        accept(main("do sync task { val x = 5; do sync task<void> { return; }; }; "
            + "val n = sync task<int> { return 40; }; return n + 2;"));
        accept(main("val n = sync task<int> { val x = sync task<int> { return 40; }; "
            + "return x + 2; }; return n;"));
        accept(main("val n = sync task<int> { val x = 2; val y = 7; "
            + "return 6 * y + (x - x); }; return n;"));
        accept(main("val x = 2147483647; val n = sync task<int> { return x + 1; }; return n;"));
        accept(main("val x = 40; do sync task { val x = 99; }; "
            + "val n = sync task<int> { return x + 2; }; return n;"));
        accept(main("val x = sync task<int> { do sync task { return; }; "
            + "return 6 * 7; }; return x;"));
        accept("pub susp fnc main(): int { val n = sync task<int> { return 40 + 2; }; return n; }");
        accept(main("val n = sync task<int> { return (3 + 4) * 6; }; return n;"));
        accept(main("val n = sync task<int> { return -9223372036854775808; }; return n;"));
        // Whitespace between keywords is valid, including CRLF/newlines.
        accept(main("do sync\\n task {};"
            + "val answer = sync\\t task<int> { return 40 + 2; }; return answer;")
            .replace("\\n", "\n").replace("\\t", "\t"));
        accept(main("val answer = sync\r\n task<int> { return 42; }; return answer;"));
        accept(main("val record_data = 40; val yield_safe = sync task<int> "
            + "{ return record_data + 2; }; return yield_safe;"));
        accept(main("return sync task<int> { return 40 + 2; };"));
        accept(main("val a = sync task<int> { return 1; }; "
            + "return a + sync task<int> { return 41; };"));
        var plan = accept(main("val n = sync task<int> { return 40 + 2; }; return n;"));
        if (!plan.typedOps().containsAll(List.of(
                AttoInlineTaskV2Compiler.MirOp.INLINE_TASK_ENTER,
                AttoInlineTaskV2Compiler.MirOp.INLINE_TASK_EXIT,
                AttoInlineTaskV2Compiler.MirOp.CHECKED_ARITHMETIC,
                AttoInlineTaskV2Compiler.MirOp.RETURN_INLINE,
                AttoInlineTaskV2Compiler.MirOp.RETURN_MAIN))) {
            throw new AssertionError("typed inline MIR operations missing");
        }

        reject(main("do sync task<int> {}; return 42;"), "E_TASK_RETURN_TYPE");
        reject(main("do sync task<bool> {}; return 42;"), "E_TASK_RETURN_TYPE");
        reject(main("do sync task<Future<int>> {}; return 42;"), "E_TASK_RETURN_TYPE");
        reject(main("do sync task<void> { return 42; }; return 42;"), "E_TASK_RETURN_TYPE");
        reject(main("do sync task { return 42; }; return 42;"), "E_TASK_RETURN_TYPE");
        reject(main("val n = sync task<void> {}; return 42;"), "E_TASK_RETURN_TYPE");
        reject(main("val n = sync task<bool> { return 42; }; return n;"), "E_TASK_RETURN_TYPE");
        reject(main("val n = sync task<Future<int>> { return 42; }; return n;"),
            "E_TASK_RETURN_TYPE");
        reject(main("val n = sync task<int> { }; return n;"), "E_TASK_RETURN_TYPE");
        reject(main("val n = sync task<int> { return; }; return n;"), "E_TASK_RETURN_TYPE");
        reject(main("val n = sync task<int> { return 42; val x = 1; }; return n;"),
            "E_ASYNC_UNREACHABLE");
        reject(main("do sync task { return; val x = 1; }; return 42;"),
            "E_ASYNC_UNREACHABLE");
        reject(main("sync task<int> { return 42; }; return 42;"), "E_SYNTAX");
        reject(main("sync task {}; return 42;"), "E_SYNTAX");
        reject(main("val n = sync task<int> { return 42; }; return missing;"),
            "E_ASYNC_UNKNOWN_LOCAL");
        reject(main("val n = sync task<int> { return missing; }; return n;"),
            "E_ASYNC_UNKNOWN_LOCAL");
        reject(main("val n = sync task<int> { val a = a; return 42; }; return n;"),
            "E_ASYNC_UNKNOWN_LOCAL");
        reject(main("val a = 41; val a = 42; do sync task {}; return a;"),
            "E_ASYNC_DUPLICATE_LOCAL");
        reject(main("do sync task { val a = 41; }; "
            + "val n = sync task<int> { return a; }; return n;"), "E_ASYNC_UNKNOWN_LOCAL");
        reject(main("val n = sync task<int> { await task<int> { return 42; }; return 1; }; "
            + "return n;"), "E_UNSUPPORTED_INLINE_EFFECT");
        reject(main("do sync task { using guard; }; return 42;"),
            "E_UNSUPPORTED_INLINE_EFFECT");
        reject(main("do sync task { defer cleanup; }; return 42;"),
            "E_UNSUPPORTED_INLINE_EFFECT");
        reject(main("do sync task { while true {}; }; return 42;"),
            "E_UNSUPPORTED_INLINE_EFFECT");
        reject(main("val class = sync task<int> { return 42; }; return class;"),
            "E_RESERVED_IDENTIFIER");
        reject(main("val System = sync task<int> { return 42; }; return System;"),
            "E_ASYNC_INVALID_IDENTIFIER");
        reject(main("val n = sync task<int> { return 42.0; }; return n;"),
            "E_ASYNC_UNKNOWN_TOKEN");
        reject(main("val n = sync task<int> { return 9223372036854775808; }; return n;"),
            "E_ASYNC_INTEGER_RANGE");
        reject(main("val n = sync task<int> { return 42; }; return n / 2;"),
            "E_ASYNC_UNKNOWN_TOKEN");
        reject(main("do sync task { val x = 5; }; return 42; /* inject */"),
            "E_ASYNC_UNKNOWN_TOKEN");
        reject(main("val n = sync task<int> { return 42; }; return n; } class Evil {"),
            "E_ASYNC_TRAILING_SOURCE");
        reject(main("val n = sync task<int> { return 42; }; return n;") + "\u0000",
            "E_ASYNC_UNKNOWN_TOKEN");
        reject(main("val n = sync task<int> { return 42; }; return n;")
            + " ".repeat(8193), "E_ASYNC_SOURCE_SIZE");
        StringBuilder many = new StringBuilder("pub fnc main(): int { ");
        for (int i = 0; i < 100; i++) many.append("do sync task {};");
        many.append("return 42; }");
        reject(many.toString(), "E_ASYNC_TOKEN_BUDGET");
        StringBuilder nesting = new StringBuilder("pub fnc main(): int { ");
        for (int i = 0; i < 18; i++) nesting.append("do sync task { ");
        nesting.append("return; ");
        for (int i = 0; i < 18; i++) nesting.append("} ");
        nesting.append("return 42; }");
        reject(nesting.toString(), "E_INLINE_NESTING_BUDGET");

        // Systematically guard the legacy reserved-word vocabulary against
        // a fallback into the newer typed frontend. The local names are
        // synthetic Java identifiers, but Atto syntax must still reject them.
        for (String keyword : AttoAsyncV1Compiler.FORBIDDEN_IDENTIFIERS) {
            if (!keyword.matches("[a-z_][a-z_0-9]*")) continue;
            reject(main("val " + keyword
                + " = sync task<int> { return 42; }; return 42;"),
                keyword.equals("sync") ? "E_RESERVED_SYNC"
                : keyword.equals("int") ? "E_ASYNC_INVALID_IDENTIFIER"
                : "E_RESERVED_IDENTIFIER");
        }
        // Malformed keyword boundaries must not be accepted as source.
        String commentGap = main("val n = sync/**/task<int> { return 42; }; return n;");
        String hyphenGap = main("val n = sync-task<int> { return 42; }; return n;");
        // A malformed token boundary never qualifies for the typed profile.
        reject(commentGap, "E_UNSUPPORTED_INLINE_PROFILE");
        reject(hyphenGap, "E_UNSUPPORTED_INLINE_PROFILE");
        // The public compiler still reports its exact lexical rejection.
        rejectIntegratedLexical(commentGap);
        rejectIntegratedLexical(hyphenGap);
        if (positives != 20 || negatives != 116)
            throw new AssertionError("inline hardened keyword/grammar probes omitted");
        System.out.println("ATTO_INLINE_V2_PASS positives=" + positives + " negatives=" + negatives);
    }
}
