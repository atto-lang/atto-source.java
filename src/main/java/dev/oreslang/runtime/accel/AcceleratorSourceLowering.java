package dev.oreslang.runtime.accel;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.DataOrientedLayout;

import java.util.List;
import java.util.Objects;

import static dev.oreslang.parser.Token.Type.*;

/**
 * Strict, compile-only Oreslang accelerator source lowering (v0).
 *
 * This is an explicit compiler entry point, NOT an executable gpu/quantum
 * construct in the general Oreslang VM. Only the pure finite scalar GPU
 * transformation and terminal-gate quantum circuit grammars are admitted.
 * There is no CPU fallback for a requested GPU or quantum execution target.
 */
public final class AcceleratorSourceLowering {
    private AcceleratorSourceLowering() { }

    public record GpuProgram(String name, String parameter, DopKernelIR.Kernel kernel) {
        public GpuProgram {
            Objects.requireNonNull(name);
            Objects.requireNonNull(parameter);
            Objects.requireNonNull(kernel);
        }
        public DopKernelIR.OpenClArtifact emitOpenCl(DopKernelIR.GpuTarget target) {
            return DopKernelIR.lowerOpenCl(kernel, target);
        }
    }

    public record QuantumProgram(String name, QuantumCircuitIR.Circuit circuit) {
        public QuantumProgram {
            Objects.requireNonNull(name);
            Objects.requireNonNull(circuit);
        }
        public String emitOpenQasm3() { return QuantumCircuitIR.lowerOpenQasm3(circuit); }
    }

    public static GpuProgram compileGpu(String source, DataOrientedLayout.Plan layout) {
        Objects.requireNonNull(layout);
        Cursor p = new Cursor(source);
        p.expect(GPU);
        p.expect(FNC);
        String name = p.identifier();
        p.expect(LPAREN);
        p.expectName("f32");
        String parameter = p.identifier();
        p.expect(RPAREN);
        p.expect(COLON);
        p.expectName("f32");
        p.expect(LBRACE);
        p.expect(RETURN);
        p.expectName(parameter);
        float scale = 1f;
        float bias = 0f;
        boolean multiplied = false;
        boolean biased = false;
        if (p.take(STAR)) {
            scale = p.finiteF32();
            multiplied = true;
        }
        if (p.take(PLUS) || p.take(MINUS)) {
            boolean minus = p.previousType == MINUS;
            bias = p.finiteF32();
            if (minus) bias = -bias;
            biased = true;
        }
        p.expect(SEMICOLON);
        p.expect(RBRACE);
        p.expect(EOF);
        var op = multiplied && biased ? DopKernelIR.Op.SCALE_BIAS
                : multiplied ? DopKernelIR.Op.SCALE : DopKernelIR.Op.BIAS;
        return new GpuProgram(name, parameter, new DopKernelIR.Kernel(
                layout, DopKernelIR.Element.F32, op, scale, bias));
    }

    public static QuantumProgram compileQuantum(String source) {
        Cursor p = new Cursor(source);
        p.expect(QUANTUM);
        p.expect(FNC);
        String name = p.identifier();
        p.expect(LPAREN);
        p.expect(RPAREN);
        p.expect(COLON);
        p.expectName("Circuit");
        p.expect(LT);
        int qubits = p.nonnegativeInt();
        p.expect(COMMA);
        int bits = p.nonnegativeInt();
        p.expect(GT);
        p.expect(LBRACE);
        var circuit = new QuantumCircuitIR.Builder(qubits, bits);
        int operations = 0;
        while (!p.check(RBRACE) && !p.check(EOF)) {
            if (++operations > 1024) throw p.error("circuit gate budget exceeded");
            String gate = p.identifier();
            p.expect(LPAREN);
            int arg0 = p.nonnegativeInt();
            switch (gate) {
                case "x" -> circuit.x(arg0);
                case "h" -> circuit.h(arg0);
                case "cx" -> {
                    p.expect(COMMA);
                    circuit.cx(arg0, p.nonnegativeInt());
                }
                case "measure" -> {
                    p.expect(COMMA);
                    circuit.measure(arg0, p.nonnegativeInt());
                }
                default -> throw p.error("unsupported quantum gate or host effect: " + gate);
            }
            p.expect(RPAREN);
            p.expect(SEMICOLON);
        }
        p.expect(RBRACE);
        p.expect(EOF);
        return new QuantumProgram(name, circuit.build());
    }

    private static final class Cursor {
        private final List<Token> tokens;
        private int at;
        private Token.Type previousType;

        Cursor(String source) {
            Objects.requireNonNull(source);
            if (source.length() > 65_536)
                throw new IllegalArgumentException("accelerator source exceeds 64 KiB admission limit");
            tokens = new Lexer(source).scan();
            if (tokens.size() > 4_096)
                throw new IllegalArgumentException("accelerator source exceeds token admission limit");
        }
        Token peek() { return tokens.get(at); }
        boolean check(Token.Type kind) { return peek().type() == kind; }
        boolean take(Token.Type kind) {
            if (!check(kind)) return false;
            previousType = peek().type();
            at++;
            return true;
        }
        void expect(Token.Type kind) {
            if (!take(kind)) throw error("expected " + kind);
        }
        String identifier() {
            if (!check(IDENT)) throw error("expected identifier");
            String name = peek().lexeme();
            expect(IDENT);
            return name;
        }
        void expectName(String name) {
            if (!check(IDENT) || !peek().lexeme().equals(name))
                throw error("expected '" + name + "'");
            expect(IDENT);
        }
        float finiteF32() {
            boolean negative = take(MINUS);
            if (negative && !check(INT) && !check(FLOAT)) throw error("expected finite f32 constant");
            if (!check(INT) && !check(FLOAT)) throw error("expected finite f32 constant");
            String lexeme = peek().lexeme().replace("_", "");
            if (lexeme.length() > 128) throw error("f32 coefficient token too long");
            at++;
            try {
                float value = Float.parseFloat(lexeme);
                if (negative) value = -value;
                if (!Float.isFinite(value)) throw error("GPU scalar coefficient must be finite");
                if (value == 0f && new java.math.BigDecimal(lexeme).signum() != 0)
                    throw error("nonzero f32 coefficient underflowed to zero");
                return value;
            } catch (NumberFormatException e) {
                throw error("invalid f32 coefficient");
            }
        }
        int nonnegativeInt() {
            if (!check(INT)) throw error("expected unsigned integer literal");
            String lexeme = peek().lexeme().replace("_", "");
            at++;
            try { return Integer.parseInt(lexeme); }
            catch (NumberFormatException e) { throw error("invalid integer size/index"); }
        }
        IllegalArgumentException error(String message) {
            var token = peek();
            return new IllegalArgumentException(
                    "accelerator source rejected at " + token.line() + ":"
                            + token.column() + ": " + message
                            + " (found '" + token.lexeme() + "')");
        }
    }
}
