package atto.compiler.asyncv1;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restrictive, statically generating .atto source compiler for completed-Future
 * pipelines and purely inline scalar sync-task examples. NOT a full parser,
 * suspending scheduler, cancellation, ownership checker
 * or production asynchronous frontend.
 */
public final class AttoAsyncV1Compiler {
    private static final Pattern TOKENS = Pattern.compile(
        "\\s+|\\|>|>>|->|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|[{}():;^+=|<>]");
    // Guest-sourced identifiers are also emitted as Java symbols. Reject both
    // Atto control keywords and Java reserved/contextual names *before* source
    // generation; never rely on javac to detect an unsafe generated symbol.
    // Shared with the typed inline frontend: never permit a source keyword
    // through the newer AST path after the legacy parser rejects it.
    static final Set<String> FORBIDDEN_IDENTIFIERS = Set.of(
        "task", "await", "sync", "susp", "suspends", "async", "fnc",
        "pub", "val", "let", "const", "return", "do", "done",
        "actor", "spawn", "defer", "match", "select", "raise",
        "panic", "recover", "using", "class", "new", "static",
        "final", "public", "private", "protected", "native", "void",
        "int", "long", "short", "byte", "float", "double", "boolean",
        "char", "true", "false", "null", "if", "else", "for",
        "while", "try", "catch", "throw", "throws", "switch", "case",
        "default", "break", "continue", "import", "package",
        "interface", "enum", "extends", "implements", "abstract",
        "this", "super", "instanceof", "assert", "synchronized",
        "volatile", "transient", "strictfp", "record", "sealed",
        "permits", "yield", "var", "goto", "_"
    );
    private AttoAsyncV1Compiler() {}

    public static final class Reject extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Reject(String reason) { super(reason); }
    }
    private static final class Parser {
        private final List<String> tokens = new ArrayList<>();
        private int index;
        Parser(String source) {
            if (source == null || source.length() > 8192)
                throw new Reject("E_ASYNC_SOURCE_SIZE");
            Matcher matcher = TOKENS.matcher(source);
            int at = 0;
            while (at < source.length()) {
                matcher.region(at, source.length());
                if (!matcher.lookingAt())
                    throw new Reject("E_ASYNC_UNKNOWN_TOKEN at " + at);
                String token = matcher.group();
                if (!token.isBlank()) tokens.add(token);
                if (tokens.size() > 256) throw new Reject("E_ASYNC_TOKEN_BUDGET");
                at = matcher.end();
            }
        }
        String next() {
            if (index == tokens.size()) throw new Reject("E_ASYNC_INCOMPLETE");
            return tokens.get(index++);
        }
        void expect(String value) {
            String got = next();
            if (!got.equals(value))
                throw new Reject("E_ASYNC_UNEXPECTED_TOKEN"); // never echo untrusted guest identifiers
        }
        String ident() {
            String value = next();
            if (!value.matches("[a-z_][a-z_0-9]*"))
                throw new Reject("E_ASYNC_INVALID_IDENTIFIER");
            if (value.equals("sync"))
                throw new Reject("E_RESERVED_SYNC");
            if (FORBIDDEN_IDENTIFIERS.contains(value))
                throw new Reject("E_RESERVED_IDENTIFIER_" + value);
            return value;
        }
        long smallInt() {
            String value = next();
            if (!value.matches("[0-9]{1,19}"))
                throw new Reject("E_ASYNC_INTEGER_RANGE");
            try { return Long.parseLong(value); }
            catch (NumberFormatException e) { throw new Reject("E_ASYNC_INTEGER_RANGE"); }
        }
        boolean done() { return index == tokens.size(); }
        boolean peekIs(String token) {
            return index < tokens.size() && tokens.get(index).equals(token);
        }
    }
    public static String compile(String source) {
        // Preserve the previously verified literal-only and Future contracts.
        // The typed AST path is attempted only for standalone pure main sources
        // containing inline tasks, and only when legacy admission rejects.
        try {
            return compileLegacy(source);
        } catch (Reject legacy) {
            if (!AttoInlineTaskV2Compiler.candidate(source)) throw legacy;
            return AttoInlineTaskV2Compiler.compile(source);
        }
    }

    private static String compileLegacy(String source) {
        Parser p = new Parser(source);
        // The completed-Future pipeline requires a helper. Literal-only sync
        // tasks additionally admit a standalone main with no helper preamble.
        String fun = null;
        String param = null;
        long increment = 0;
        if (p.peekIs("fnc")) {
            p.expect("fnc");
            fun = p.ident();
            p.expect("("); p.expect("int");
            param = p.ident();
            p.expect(")"); p.expect(":"); p.expect("int");
            p.expect("{"); p.expect("^"); p.expect(param); p.expect("+");
            increment = p.smallInt();
            p.expect(";"); p.expect("}");
        }
        p.expect("pub");
        boolean suspendingMain = p.peekIs("susp");
        if (suspendingMain) p.expect("susp");
        p.expect("fnc"); p.expect("main");
        p.expect("("); p.expect(")"); p.expect(":"); p.expect("int");
        p.expect("{");
        // Admits only provably non-suspending literal inline bodies. A sync
        // task uses the current logical task and is permitted in a pure caller.
        // A value-producing sync task belongs in an expression context.
        // Void sync task statements require 'do' under atto-spec #129.
        if (p.peekIs("do")) {
            boolean discard = true;
            p.expect("do");
            p.expect("sync"); p.expect("task");
            String payload = "infer";
            if (p.peekIs("<")) {
                p.expect("<");
                // A do-sync statement's *payload* is void, not an arbitrary
                // discarded T. Fail before generating any guest artifact.
                if (discard && !p.peekIs("void"))
                    throw new Reject("E_TASK_RETURN_TYPE");
                payload = p.next();
                p.expect(">");
            }
            p.expect("{");
            boolean hasValue = false;
            long ignored = 0;
            if (p.peekIs("return")) {
                p.expect("return");
                if (p.peekIs(";")) {
                    p.expect(";");
                } else {
                    if (discard || payload.equals("void"))
                        throw new Reject("E_TASK_RETURN_TYPE");
                    ignored = p.smallInt();
                    p.expect(";");
                    hasValue = true;
                }
            }
            p.expect("}");
            if (p.peekIs(";")) p.expect(";");
            if (!payload.equals("infer") && !payload.equals(hasValue ? "int" : "void"))
                throw new Reject("E_TASK_RETURN_TYPE");
            p.expect("return"); long parentValue = p.smallInt();
            p.expect(";"); p.expect("}");
            if (!p.done()) throw new Reject("E_ASYNC_TRAILING_SOURCE");
            String body = "    atto_inline_task_0: {\n"
                + (hasValue ? "      Math.addExact(" + ignored + "L, 0L);\n" : "")
                + "      break atto_inline_task_0;\n"
                + "    }\n"
                + "    return " + parentValue + "L;\n";
            return compilePureInline(fun, param, increment, body);
        }
        if (p.peekIs("sync"))
            throw new Reject("E_SYNTAX");
        p.expect("val"); String future = p.ident();
        p.expect("=");
        if (p.peekIs("sync")) {
            p.expect("sync"); p.expect("task");
            if (p.peekIs("<")) {
                p.expect("<"); p.expect("int"); p.expect(">");
            }
            p.expect("{"); p.expect("return"); long value = p.smallInt();
            p.expect(";"); p.expect("}"); p.expect(";");
            boolean hasDiscard = p.peekIs("do");
            if (hasDiscard) {
                p.expect("do"); p.expect("sync"); p.expect("task");
                if (p.peekIs("<")) {
                    p.expect("<");
                    if (!p.peekIs("void"))
                        throw new Reject("E_TASK_RETURN_TYPE");
                    p.expect("void"); p.expect(">");
                }
                p.expect("{");
                if (p.peekIs("return")) {
                    p.expect("return");
                    if (!p.peekIs(";"))
                        throw new Reject("E_TASK_RETURN_TYPE");
                    p.expect(";");
                }
                p.expect("}");
                if (p.peekIs(";")) p.expect(";");
            }
            p.expect("return"); String called = p.ident();
            String parentReturn;
            if (p.peekIs("(")) {
                p.expect("("); String argument = p.ident(); p.expect(")");
                if (fun == null || !called.equals(fun))
                    throw new Reject("E_INLINE_CALL_MISMATCH");
                if (!argument.equals(future)) throw new Reject("E_INLINE_BINDER_MISMATCH");
                parentReturn = "    return " + fun + "(" + future + ");\n";
            } else {
                if (!called.equals(future)) throw new Reject("E_INLINE_BINDER_MISMATCH");
                parentReturn = "    return " + future + ";\n";
            }
            p.expect(";"); p.expect("}");
            if (!p.done()) throw new Reject("E_ASYNC_TRAILING_SOURCE");
            String body = "    long " + future + ";\n"
                + "    atto_inline_task_0: {\n"
                + "      " + future + " = " + value + "L;\n"
                + "      break atto_inline_task_0;\n"
                + "    }\n";
            if (hasDiscard) {
                body += "    atto_inline_task_1: {\n"
                    + "      break atto_inline_task_1;\n"
                    + "    }\n";
            }
            body += parentReturn;
            return compilePureInline(fun, param, increment, body);
        }
        if (!suspendingMain) throw new Reject("E_EFFECT");
        if (fun == null) throw new Reject("E_UNSUPPORTED_COMPLETED_FUTURE_PROFILE");
        p.expect("task");
        // The payload parameter is int, NOT Future<int>: task<int>
        // produces a Future<int>. Unparameterized legacy task remains
        // admitted only by this closed-world completed-Future profile.
        if (p.peekIs("<")) {
            p.expect("<"); p.expect("int"); p.expect(">");
        }
        p.expect("{");
        if (p.peekIs("return")) p.expect("return");
        else p.expect("^");
        long value = p.smallInt();
        p.expect(";"); p.expect("}"); p.expect(";");
        p.expect("^"); p.expect(future); p.expect("|>");
        p.expect("susp"); p.expect("|"); String binding = p.ident();
        p.expect("|"); p.expect("->"); p.expect("await");
        String awaited = p.ident();
        if (!binding.equals(awaited)) throw new Reject("E_AWAIT_BINDER_MISMATCH");
        p.expect("|>"); String invoked = p.ident();
        if (!fun.equals(invoked)) throw new Reject("E_PIPE_FUNCTION_MISMATCH");
        p.expect(">>"); p.expect("val"); String terminal = p.ident();
        p.expect(":"); p.expect("int"); p.expect(";");
        p.expect("}");
        if (!p.done()) throw new Reject("E_ASYNC_TRAILING_SOURCE");
        if (future.equals(terminal) || terminal.equals(binding))
            throw new Reject("E_ASYNC_DUPLICATE_LOCAL");
        // All three expressions are checked before generating code; no Atto
        // expression is evaluated by a runtime source interpreter.
        try { Math.addExact(value, increment); }
        catch (ArithmeticException e) { throw new Reject("E_ASYNC_INTEGER_RANGE"); }
        return "import java.util.concurrent.CompletableFuture;\n"
            + "public final class AttoAsyncV1Guest {\n"
            + "  private static long " + fun + "(long " + param + ") {\n"
            + "    return Math.addExact(" + param + ", " + increment + "L);\n"
            + "  }\n"
            + "  public static long execute() {\n"
            + "    CompletableFuture<Long> " + future
            + " = CompletableFuture.completedFuture(" + value + "L);\n"
            + "    long " + terminal + " = " + fun + "(" + future + ".join());\n"
            + "    return " + terminal + ";\n"
            + "  }\n"
            + "  public static void main(String[] args) {\n"
            + "    if (args.length != 0) throw new IllegalArgumentException();\n"
            + "    long result = execute();\n"
            + "    System.out.println(\"ATTO_ASYNC_V1_RESULT=\" + result);\n"
            + "    System.out.println(\"ATTO_ASYNC_SCOPE=COMPLETED_FUTURE_ONLY\");\n"
            + "    System.exit((int) result);\n"
            + "  }\n"
            + "}\n";
    }
    private static String compilePureInline(String fun, String param, int increment,
                                            String body) {
        // Labelled Java lexical scopes implement inline-local returns without
        // returning from the parent, allocating a Future or scheduling a child.
        return "public final class AttoAsyncV1Guest {\n"
            + (fun == null ? "" :
                "  private static long " + fun + "(long " + param + ") {\n"
                + "    return Math.addExact(" + param + ", " + increment + "L);\n"
                + "  }\n")
            + "  public static long execute() {\n"
            + body
            + "  }\n"
            + "  public static void main(String[] args) {\n"
            + "    if (args.length != 0) throw new IllegalArgumentException();\n"
            + "    long result = execute();\n"
            + "    System.out.println(\"ATTO_ASYNC_V1_RESULT=\" + result);\n"
            + "    System.out.println(\"ATTO_ASYNC_SCOPE=PURE_INLINE_TASK_ONLY\");\n"
            + "    System.exit((int) result);\n"
            + "  }\n"
            + "}\n";
    }
    // Validate the same opened file handle used to consume the guest source.
    // In particular a replaced symbolic-link source cannot bypass NOFOLLOW.
    private static String readSourceNoFollow(Path input) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(input,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > 8192) throw new Reject("E_ASYNC_SOURCE_SIZE");
            ByteBuffer bytes = ByteBuffer.allocate(8193);
            for (;;) {
                int n = channel.read(bytes);
                if (n < 0) break;
                if (!bytes.hasRemaining()) throw new Reject("E_ASYNC_SOURCE_SIZE");
                if (n == 0) throw new Reject("E_ASYNC_SOURCE_IO_STALL");
            }
            bytes.flip();
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes).toString();
        }
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new Reject("E_ASYNC_CLI_ARGUMENTS");
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (!input.toString().endsWith(".atto") ||
            !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(input) > 8192 || Files.exists(output, LinkOption.NOFOLLOW_LINKS))
            throw new Reject("E_ASYNC_SOURCE_OR_OUTPUT_ADMISSION");
        String generated = compile(readSourceNoFollow(input));
        Path temp = Files.createTempFile(output.getParent(), ".atto-async-v1-", ".tmp");
        try {
            Files.writeString(temp, generated, StandardCharsets.UTF_8);
            Files.createLink(output, temp); // atomic: never overwrite a planted path
        } finally {
            Files.deleteIfExists(temp);
        }
        System.out.println("ATTO_ASYNC_V1_COMPILED=PASS");
    }
}
