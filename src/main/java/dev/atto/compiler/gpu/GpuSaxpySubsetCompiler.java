package dev.atto.compiler.gpu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * G2 restricted AttoLang gpu fnc saxpy -> OpenCL C source emitter.
 * Standalone compiler experiment: NOT the production AttoLang parser/typechecker,
 * a host launch, a provider, a device binary, GPU execution or CUDA parity.
 * Reject unknown source before artifact creation. No CPU implementation exists.
 */
public final class GpuSaxpySubsetCompiler {
    private GpuSaxpySubsetCompiler() { }

    private static final int SOURCE_BUDGET = 65536;
    private static final int TOKEN_BUDGET = 1024;
    private static final Set<String> RESERVED = Set.of(
            "gpu", "fnc", "pub", "const", "mut", "return", "if", "await",
            "let", "void", "u64", "f32", "GpuArray", "actor", "critical",
            "suspends", "class", "struct", "rt", "new", "launch");
    private static final String OPENCL = """
            #pragma OPENCL FP_CONTRACT OFF
            __kernel void attolang_saxpy_v1(
                const float scale, __global const float* x,
                __global const float* y, __global float* out, const ulong n) {
                const ulong i = get_global_id(0);
                if (i >= n) return;
                out[i] = scale * x[i] + y[i];
            }
            """;
    private record Token(String text, int offset) { }

    private static IllegalArgumentException invalid(String reason, int offset) {
        return new IllegalArgumentException(
                "GPU-KERNEL-G2-001: " + reason + " at byte " + offset);
    }
    private static boolean start(char c) {
        return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static boolean ident(char c) { return start(c) || digit(c); }

    private static List<Token> lex(String input) {
        if (input.getBytes(StandardCharsets.UTF_8).length > SOURCE_BUDGET)
            throw invalid("source exceeds 64KiB limit", 0);
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < input.length();) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f') { i++; continue; }
            if (input.startsWith("//", i)) {
                int end = input.indexOf('\n', i);
                if (end < 0) break;
                i = end;
                continue;
            }
            if (input.startsWith("/*", i)) {
                int end = input.indexOf("*/", i + 2);
                if (end < 0) throw invalid("unterminated comment", i);
                i = end + 2;
                continue;
            }
            int begin = i;
            if (start(c)) {
                while (i < input.length() && ident(input.charAt(i))) i++;
                if (i - begin > 128) throw invalid("identifier exceeds 128 bytes", begin);
            } else if (digit(c)) {
                while (i < input.length() && digit(input.charAt(i))) i++;
            } else if (input.startsWith("=>", i) || input.startsWith(">=", i)) {
                i += 2;
            } else if ("(){}[]<>,;.+=*".indexOf(c) >= 0) {
                i++;
            } else {
                throw invalid("unsupported device character", begin);
            }
            tokens.add(new Token(input.substring(begin, i), begin));
            if (tokens.size() > TOKEN_BUDGET) throw invalid("token budget exceeded", begin);
        }
        tokens.add(new Token("<end>", input.length()));
        return tokens;
    }
    private static final class Parser {
        private final List<Token> tokens;
        private int pos;
        private String scale, x, y, out, n, index;
        Parser(String source) { tokens = lex(source); }
        private Token peek() { return tokens.get(pos); }
        private boolean is(String text) { return peek().text().equals(text); }
        private void take(String expected) {
            if (!is(expected))
                throw invalid("expected '" + expected + "', got '" + peek().text() + "'", peek().offset());
            pos++;
        }
        private String name() {
            Token t = peek();
            String value = t.text();
            if (value.isEmpty() || !start(value.charAt(0)) || RESERVED.contains(value))
                throw invalid("invalid or reserved device binding name", t.offset());
            pos++;
            return value;
        }
        void parse() {
            if (is("pub")) take("pub");
            take("gpu"); take("fnc"); take("saxpy"); take("(");
            take("f32"); scale = name(); take(",");
            take("GpuArray"); take("<"); take("f32"); take(">"); x = name(); take(",");
            take("GpuArray"); take("<"); take("f32"); take(">"); y = name(); take(",");
            take("GpuArray"); take("<"); take("f32"); take(">"); take("mut"); out = name(); take(",");
            take("u64"); n = name(); take(")"); take("=>"); take("void"); take("{");
            take("const"); take("u64"); index = name(); take("=");
            take("gpu"); take("."); take("global_id"); take("("); take("0"); take(")"); take(";");
            take("if"); take(index); take(">="); take(n); take("{"); take("return"); take(";"); take("}");
            take(out); take("["); take(index); take("]"); take("=");
            take(scale); take("*"); take(x); take("["); take(index); take("]");
            take("+"); take(y); take("["); take(index); take("]"); take(";");
            if (is("return")) { take("return"); take(";"); }
            take("}"); take("<end>");
            if (new HashSet<>(List.of(scale, x, y, out, n, index)).size() != 6)
                throw invalid("device bindings must be distinct", tokens.get(tokens.size() - 1).offset());
        }
    }
    public static String lowerOpenCl(String source) {
        if (source == null) throw invalid("null device source", 0);
        new Parser(source).parse();
        return OPENCL;
    }
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException(
                    "usage: java dev.atto.compiler.gpu.GpuSaxpySubsetCompiler input.atto output.cl");
            Path input = Path.of(args[0]);
            Path output = Path.of(args[1]);
            if (!input.toString().endsWith(".atto") || !output.toString().endsWith(".cl"))
                throw invalid("expected input .atto and output .cl", 0);
            if (Files.size(input) > SOURCE_BUDGET) throw invalid("source exceeds 64KiB limit", 0);
            String artifact = lowerOpenCl(Files.readString(input, StandardCharsets.UTF_8));
            Files.writeString(output, artifact, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("G2_JAVA_SOURCE_TO_OPENCL_C_ONLY kernel=attolang_saxpy_v1 bytes=" +
                    artifact.getBytes(StandardCharsets.UTF_8).length);
        } catch (Exception failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        }
    }
}
