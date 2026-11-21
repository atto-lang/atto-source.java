package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Conservative v3 closed-literal preflight. No guest evaluation or call-site
 * omission is enabled here. Raw TypeRef names are not a nominal identity proof:
 * only certain builtin source spellings can be rejected before semantic resolve.
 *
 * A future checked-signature stage must separately prove builtin Option identity,
 * alias normalization, ownership, effects, ABI version and backend support.
 */
public final class StagedDefaultTypeV3 {
    private StagedDefaultTypeV3() { }

    private enum Scalar { INTEGER, BOOLEAN, STRING, OTHER }
    private record Known(Scalar scalar, int width, boolean unsigned, boolean option) { }

    private static Known known(Ast.TypeRef ref) {
        if (ref == null || ref.inferArguments()) return null;
        if (ref.name().equals("Option")) {
            if (ref.arguments().size() != 1) return null;
            Known inner = known(ref.arguments().getFirst());
            return inner == null ? new Known(Scalar.OTHER, 0, false, true)
                    : new Known(inner.scalar(), inner.width(), inner.unsigned(), true);
        }
        if (!ref.arguments().isEmpty()) return null;
        return switch (ref.name()) {
            case "i8" -> new Known(Scalar.INTEGER, 8, false, false);
            case "u8" -> new Known(Scalar.INTEGER, 8, true, false);
            case "i16" -> new Known(Scalar.INTEGER, 16, false, false);
            case "u16" -> new Known(Scalar.INTEGER, 16, true, false);
            case "i32" -> new Known(Scalar.INTEGER, 32, false, false);
            case "u32" -> new Known(Scalar.INTEGER, 32, true, false);
            case "int", "i64" -> new Known(Scalar.INTEGER, 64, false, false);
            case "uint", "u64" -> new Known(Scalar.INTEGER, 64, true, false);
            case "bool", "Bool", "boolean" -> new Known(Scalar.BOOLEAN, 0, false, false);
            case "string", "String" -> new Known(Scalar.STRING, 0, false, false);
            default -> null;
        };
    }

    /** Returns a closed constant only when preflight can prove a builtin shape.
     * null means unresolved type, NOT approval to lower/evaluate a default.
     */
    public static DefaultCallShapeV3.Constant verifyKnownBuiltin(
            Ast.TypeRef declared, Ast.TypeRef literal) {
        if (literal == null) throw new IllegalArgumentException("default literal missing");
        Known k = known(declared);
        if (k == null) return null;
        if (declared.name().equals("Option") && declared.arguments().size() == 1
                && declared.arguments().getFirst().name().equals("Option")
                && (literal.isIntLiteral() || literal.isBoolLiteral() || literal.isStringLiteral())) {
            throw new IllegalArgumentException(
                    "nested Option scalar default needs explicit checked Some chain");
        }
        if (literal.name().equals("None") && literal.arguments().isEmpty()
                && !literal.inferArguments()) {
            if (!k.option()) throw new IllegalArgumentException("None default requires Option<T>");
            return new DefaultCallShapeV3.Constant(DefaultCallShapeV3.ConstantKind.NONE, null);
        }
        if (literal.isIntLiteral()) {
            if (k.scalar() != Scalar.INTEGER)
                throw new IllegalArgumentException("integer default does not match declared type " + declared.name());
            BigInteger n;
            try {
                n = new BigInteger(literal.name().substring("$int$".length()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed integer default", e);
            }
            BigInteger min = k.unsigned() ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(k.width()-1).negate();
            BigInteger max = BigInteger.ONE.shiftLeft(k.unsigned()?k.width():k.width()-1).subtract(BigInteger.ONE);
            if (n.compareTo(min) < 0 || n.compareTo(max) > 0)
                throw new IllegalArgumentException("integer default overflows " + declared.name());
            // Current pure plan's integer witness is long. Even valid u64
            // numbers above signed i64 require a future wide-integer ABI.
            try {
                return new DefaultCallShapeV3.Constant(DefaultCallShapeV3.ConstantKind.INTEGER,
                        n.longValueExact());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("unsigned default needs wide-integer ABI proof", e);
            }
        }
        if (literal.isBoolLiteral()) {
            if (k.scalar() != Scalar.BOOLEAN)
                throw new IllegalArgumentException("boolean default does not match declared type " + declared.name());
            return new DefaultCallShapeV3.Constant(DefaultCallShapeV3.ConstantKind.BOOLEAN,
                    literal.boolLiteralValue());
        }
        if (literal.isStringLiteral()) {
            if (k.scalar() != Scalar.STRING)
                throw new IllegalArgumentException("string default does not match declared type " + declared.name());
            return new DefaultCallShapeV3.Constant(DefaultCallShapeV3.ConstantKind.STRING,
                    literal.stringLiteralValue());
        }
        throw new IllegalArgumentException("default must be a closed literal");
    }

    /** Early rejection of obvious source-local mismatches and overflow.
     * The regular unlowered-default guard MUST still reject this program.
     */
    public static void preflight(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    preflight(fn.annotations(), fn.parameters());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    if (klass.constructor() != null)
                        preflight(klass.constructor().annotations(), klass.constructor().parameters());
                    for (Ast.MethodDecl method : klass.methods())
                        preflight(method.annotations(), method.parameters());
                }
            }
        }
    }

    private static void preflight(List<Ast.Annotation> annotations, List<Ast.Param> formals) {
        Map<String, Ast.Param> byName = new HashMap<>();
        for (Ast.Param formal : formals) byName.putIfAbsent(formal.name(), formal);
        for (Ast.Annotation annotation : annotations) {
            if (!annotation.name().equals("DefaultArgVals")) continue;
            for (Ast.TypeRef argument : annotation.arguments()) {
                if (!argument.isNamedParameter()) continue;
                Ast.Param formal = byName.get(argument.namedParameterName());
                if (formal != null) verifyKnownBuiltin(formal.type(), argument.namedParameterValue());
            }
        }
    }
}
