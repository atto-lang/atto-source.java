package dev.oreslang.runtime.accel;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.DataOrientedLayout;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static dev.oreslang.parser.Token.Type.*;

/**
 * Opt-in compile-only source profile shared with the LLVM accelerator runner.
 * This produces a type-checked host graph plan, not executable guest actors.
 * User actor methods, arbitrary effects, unsupported stages and constructor
 * arguments are rejected until the full ActorGroup/borrow compiler exists.
 */
public final class PipelineSourceLowering {
    private PipelineSourceLowering() { }

    public record Stage(String name, String input, String output) { }
    public record Plan(List<Stage> stages, String entry, String gpuFunction,
                       DopKernelIR.OpenClArtifact kernel) {
        public Plan {
            stages = List.copyOf(stages);
            Objects.requireNonNull(entry);
            Objects.requireNonNull(gpuFunction);
            Objects.requireNonNull(kernel);
        }
    }

    public static Plan compile(String source, DataOrientedLayout.Plan geometry,
                               DopKernelIR.GpuTarget target) {
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(target);
        Cursor c = new Cursor(source);
        Stage[] stages = new Stage[3];
        String[] roles = {"CpuWorker", "GpuWorker", "CollectResultsWorker"};
        for (int i = 0; i < roles.length; ++i) {
            c.expect(DEFINE); c.expect(ACTOR);
            String name = c.ident();
            int role = -1;
            for (int j = 0; j < roles.length; j++) {
                if (roles[j].equals(name)) role = j;
            }
            if (role == -1 || stages[role] != null)
                throw c.error("duplicate or unsupported actor role");
            c.expect(IMPLEMENTS); c.name("StreamOperator"); c.expect(LT);
            String input = c.port(); c.expect(COMMA); String output = c.port();
            c.expect(GT); c.expect(AS); c.expect(END);
            stages[role] = new Stage(name, input, output);
        }
        if (!stages[0].input().equals("void") || !stages[0].output().equals("f32")
                || !stages[1].input().equals("f32") || !stages[1].output().equals("f32")
                || !stages[2].input().equals("f32") || !stages[2].output().equals("Results"))
            throw c.error("incompatible typed ActorStream ports");

        c.expect(GPU); c.expect(FNC);
        String gpuName = c.ident();
        if (!gpuName.equals("transform"))
            throw c.error("unsupported GPU stage kernel binding");
        c.expect(LPAREN); c.name("f32"); String param = c.ident();
        c.expect(RPAREN); c.expect(COLON); c.name("f32");
        c.expect(LBRACE); c.expect(RETURN); c.name(param);
        boolean scaled = c.take(STAR);
        float scale = scaled ? c.f32() : 1f;
        boolean plus = c.take(PLUS), minus = !plus && c.take(MINUS);
        float bias = (plus || minus) ? c.f32() : 0f;
        if (minus) bias = -bias;
        c.expect(SEMICOLON); c.expect(RBRACE);
        DopKernelIR.Op operation = scaled && (plus || minus)
                ? DopKernelIR.Op.SCALE_BIAS
                : scaled ? DopKernelIR.Op.SCALE : DopKernelIR.Op.BIAS;
        var kernel = DopKernelIR.lowerOpenCl(new DopKernelIR.Kernel(
                geometry, DopKernelIR.Element.F32, operation, scale, bias), target);

        c.expect(PUB); c.expect(FNC); String entry = c.ident();
        c.expect(LPAREN); c.expect(RPAREN); c.expect(COLON);
        c.name("ActorStream"); c.expect(LT); c.expect(VOID);
        c.expect(COMMA); c.name("Results"); c.expect(GT);
        c.expect(LBRACE); c.expect(RETURN);
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) c.name("pipe");
            c.expect(SPAWN); c.name(stages[i].name());
            c.expect(LPAREN); c.expect(RPAREN);
        }
        c.expect(SEMICOLON); c.expect(RBRACE); c.expect(EOF);
        return new Plan(List.of(stages), entry, gpuName, kernel);
    }

    private static final class Cursor {
        final List<Token> tokens;
        int index;
        Cursor(String source) {
            Objects.requireNonNull(source);
            if (source.length() > 65_536)
                throw new IllegalArgumentException("pipeline source exceeds 64 KiB");
            tokens = new Lexer(source).scan();
            if (tokens.size() > 4_096)
                throw new IllegalArgumentException("pipeline source exceeds token budget");
        }
        Token current() { return tokens.get(index); }
        boolean take(Token.Type type) {
            if (current().type() != type) return false;
            index++;
            return true;
        }
        void expect(Token.Type type) {
            if (!take(type)) throw error("expected " + type);
        }
        String ident() {
            if (current().type() != IDENT) throw error("expected identifier");
            String name = current().lexeme(); index++; return name;
        }
        void name(String expected) {
            if (current().type() != IDENT || !current().lexeme().equals(expected))
                throw error("expected " + expected);
            index++;
        }
        String port() { return take(VOID) ? "void" : ident(); }
        float f32() {
            boolean negative = take(MINUS);
            if (current().type() != INT && current().type() != FLOAT)
                throw error("expected finite f32");
            String literal = current().lexeme().replace("_", "");
            if (literal.length() > 128) throw error("f32 coefficient too long");
            index++;
            try {
                float value = Float.parseFloat(literal);
                if (value == 0f && new BigDecimal(literal).signum() != 0)
                    throw error("nonzero coefficient underflow");
                if (!Float.isFinite(value)) throw error("nonfinite coefficient");
                return negative ? -value : value;
            } catch (NumberFormatException e) {
                throw error("invalid f32 literal");
            }
        }
        IllegalArgumentException error(String message) {
            var t = current();
            return new IllegalArgumentException(
                "pipeline rejected at " + t.line() + ":" + t.column() + ": " + message);
        }
    }
}
