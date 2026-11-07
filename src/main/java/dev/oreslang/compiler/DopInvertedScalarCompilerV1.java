package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit opt-in production-parser -> checked typed operation plan for the
 * canonical bounded two-i64 inverted struct v1 subset.
 *
 * This is NOT general Java/Graal guest dispatch or executable AOT lowering.
 * The only execution facility below is a host reference against the private
 * checked SoA storage ABI. Ordinary OresCompiler.parseAndTypeCheck retains its
 * fail-closed inverted declaration admission.
 */
public final class DopInvertedScalarCompilerV1 {
    private DopInvertedScalarCompilerV1() { }

    public enum Op { PUSH, WRITE, ERASE_SWAP, RETURN_READ }

    /** Typed/closed operation: no source expressions, callbacks or aliases escape. */
    public record Instruction(Op op, int index, String field, long first, long second) {
        public Instruction {
            Objects.requireNonNull(op, "DOP operation");
            Objects.requireNonNull(field, "DOP field");
        }
    }

    public static final class Plan {
        private final Ast.InvertedStructDecl schema;
        private final String owner;
        private final int capacity;
        private final List<Instruction> instructions;

        private Plan(Ast.InvertedStructDecl schema, String owner, int capacity,
                     List<Instruction> instructions) {
            this.schema = schema;
            this.owner = owner;
            this.capacity = capacity;
            this.instructions = List.copyOf(instructions);
        }

        public String owner() { return owner; }
        public String schemaName() { return schema.name(); }
        public int capacity() { return capacity; }
        public List<Instruction> instructions() { return instructions; }

        /**
         * The plan is verified before publication. Only host reference
         * execution is exposed; this is not the Truffle guest call target.
         * try-with-resources closes and scrubs the bounded owning SoA store.
         */
        public long executeHostReference() {
            try (var columns = new InvertedSchemaStorageBridge(schema, capacity)) {
                for (Instruction op : instructions) {
                    switch (op.op()) {
                        case PUSH -> columns.push(op.first(), op.second());
                        case WRITE -> columns.write(
                                columns.handleAt(op.index()), op.field(), op.first());
                        case ERASE_SWAP -> columns.eraseSwap(columns.handleAt(op.index()));
                        case RETURN_READ -> {
                            return columns.get(columns.handleAt(op.index()), op.field());
                        }
                    }
                }
                throw new IllegalStateException("verified inverted plan missing terminal read");
            }
        }
    }

    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("DOP_INVERTED_V1_REJECTED: " + reason);
    }

    private static long scalar(Ast.Expr expr, String purpose) {
        if (expr instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long value) {
            return value;
        }
        // Parser represents the signed i64 minimum directly as a literal.
        // Other negative literals are strict unary operations. Admit only
        // one statically checked sign and never evaluate guest expressions,
        // silently wrap Long.MIN_VALUE, or widen into arbitrary BigInteger.
        if (expr instanceof Ast.UnaryExpr sign
                && (sign.operator().equals("-") || sign.operator().equals("+"))
                && sign.operand() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Long magnitude) {
            if (sign.operator().equals("+")) return magnitude;
            try {
                return Math.negateExact(magnitude);
            } catch (ArithmeticException overflow) {
                throw reject("signed i64 negation overflows for " + purpose);
            }
        }
        throw reject("expected static signed i64 for " + purpose);
    }

    private static int index(Ast.Expr expr) {
        long value = scalar(expr, "row index");
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw reject("row index outside Java checked dense range");
        }
        return (int) value;
    }

    private static Ast.CallExpr call(Ast.Expr expr, String owner) {
        if (!(expr instanceof Ast.CallExpr c) || c.typeArgumentsPresent()
                || c.origin() != Ast.CallOrigin.DIRECT
                || !(c.callee() instanceof Ast.MemberExpr member)
                || !(member.receiver() instanceof Ast.NameExpr recv)
                || !recv.name().equals(owner)) {
            throw reject("only direct calls on the bound inverted local owner are supported");
        }
        return c;
    }

    private static String field(Ast.Expr expr, Ast.InvertedStructDecl schema,
                                boolean requireWrite) {
        if (!(expr instanceof Ast.NameExpr name)) {
            throw reject("inverted field selector must be a static name");
        }
        for (Ast.InvertedColumnDecl column : schema.columns()) {
            if (column.name().equals(name.name())) {
                if (requireWrite && !column.mutable()) {
                    throw reject("immutable inverted field cannot be written: " + name.name());
                }
                return name.name();
            }
        }
        throw reject("unknown inverted field: " + name.name());
    }

    public static Plan lower(Ast.Program program) {
        Objects.requireNonNull(program, "DOP parser program");
        if (program.namespace() != null || !program.imports().isEmpty()
                || program.modules().size() != 1) {
            throw reject("only one standalone source module and no imports");
        }
        Ast.ModuleDecl module = program.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(module.name())
                || !module.annotations().isEmpty() || !module.contracts().isEmpty()
                || module.declarations().size() != 2
                || !(module.declarations().get(0) instanceof Ast.InvertedStructDecl schema)
                || !(module.declarations().get(1) instanceof Ast.FunctionDecl fn)) {
            throw reject("expected one inverted struct followed by one main function");
        }
        if (!"main".equals(fn.name()) || fn.kind() != Ast.CallableKind.FNC
                || !fn.parameters().isEmpty() || !fn.genericParameters().isEmpty()
                || !fn.genericSpecs().isEmpty() || !fn.annotations().isEmpty()
                || fn.async() || fn.suspends() || fn.generator() || fn.structural()
                || fn.nonLexical() || fn.trapped()
                || fn.actorKind() != Ast.ActorKind.NONE || !fn.effects().isEmpty()
                || fn.returnType() == null || !"int".equals(fn.returnType().name())
                || fn.returnType().inferArguments()
                || !fn.returnType().arguments().isEmpty()
                || fn.body().size() < 2 || fn.body().size() > 4096) {
            throw reject("only a synchronous scalar int main with a bounded body");
        }
        if (!(fn.body().getFirst() instanceof Ast.BindingStmt binding)
                || binding.kind() != Ast.BindingKind.LET
                || binding.declaredType() != null
                || binding.mutableReferent()
                || binding.inferredTypeName() != null
                || binding.name().isBlank() || binding.name().equals("_")
                || !(binding.initializer() instanceof Ast.NewExpr alloc)
                || !"InvertedArray".equals(alloc.type().name())
                || alloc.type().inferArguments()
                || alloc.type().arguments().size() != 1
                || !schema.name().equals(alloc.type().arguments().getFirst().name())
                || alloc.type().arguments().getFirst().inferArguments()
                || !alloc.type().arguments().getFirst().arguments().isEmpty()
                || alloc.arguments().size() != 1) {
            throw reject("expected one local let owner initialized by InvertedArray<Schema>");
        }
        long requested = scalar(alloc.arguments().getFirst(), "owner capacity");
        if (requested < 0 || requested > 4096) {
            throw reject("owner capacity exceeds bounded two-i64 prototype");
        }
        int capacity = (int) requested;
        String owner = binding.name();
        List<Instruction> code = new ArrayList<>();
        int staticLength = 0;
        for (int i = 1; i < fn.body().size(); i++) {
            Ast.Stmt statement = fn.body().get(i);
            boolean terminal = i == fn.body().size() - 1;
            Ast.Expr expression;
            if (terminal && statement instanceof Ast.ReturnStmt ret) {
                expression = ret.value();
            } else if (!terminal && statement instanceof Ast.ExprStmt exprStmt) {
                expression = exprStmt.expression();
            } else {
                throw reject("only owner operations followed by a terminal return are allowed");
            }
            Ast.CallExpr c = call(expression, owner);
            Ast.MemberExpr callee = (Ast.MemberExpr) c.callee();
            List<Ast.Expr> args = c.arguments();
            switch (callee.member()) {
                case "push" -> {
                    if (terminal || args.size() != 2 || staticLength >= capacity) {
                        throw reject("inverted push exceeds capacity or has invalid arity");
                    }
                    code.add(new Instruction(Op.PUSH, 0, "",
                            scalar(args.get(0), "first column"),
                            scalar(args.get(1), "second column")));
                    staticLength++;
                }
                case "set" -> {
                    if (terminal || args.size() != 3) {
                        throw reject("inverted set requires row, field, value");
                    }
                    int row = index(args.get(0));
                    String name = field(args.get(1), schema, true);
                    code.add(new Instruction(Op.WRITE, row, name,
                            scalar(args.get(2), "column value"), 0));
                }
                case "erase_swap" -> {
                    if (terminal || args.size() != 1) {
                        throw reject("inverted erase_swap requires one dense index");
                    }
                    code.add(new Instruction(Op.ERASE_SWAP, index(args.getFirst()), "", 0, 0));
                    // Row index is guarded at execution; static length changes
                    // only for an actually valid deletion index.
                    if (index(args.getFirst()) >= staticLength) {
                        throw reject("inverted erase index outside current length");
                    }
                    staticLength--;
                }
                case "get" -> {
                    if (!terminal || args.size() != 2) {
                        throw reject("only terminal get may return a scalar");
                    }
                    code.add(new Instruction(Op.RETURN_READ, index(args.get(0)),
                            field(args.get(1), schema, false), 0, 0));
                }
                default -> throw reject("unsupported inverted operation: " + callee.member());
            }
        }
        if (code.isEmpty() || code.getLast().op() != Op.RETURN_READ) {
            throw reject("inverted main must return a checked column read");
        }
        return new Plan(schema, owner, capacity, code);
    }
}
