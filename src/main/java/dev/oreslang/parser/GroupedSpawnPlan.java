package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Non-executable typed constant preview. No runtime capability is minted here.
 * Dynamic expressions and nominal resolution require the normal typed HIR. */
public final class GroupedSpawnPlan {
    private GroupedSpawnPlan() {}
    public enum Kind { INT, BOOL, STRING, AFFINITY, POOL, POOL_SIZE }
    public record Value(String section, String key, Kind kind, Object value) {}
    public record Ceiling(long heap, long stack, long messages, int parallelism, boolean serial) {
        public Ceiling {
            if (heap <= 0 || stack <= 0 || messages <= 0 || parallelism <= 0)
                throw new IllegalArgumentException("E_SPAWN_POLICY");
        }
    }
    public record Preview(Ast.TypeRef actor, boolean awaited, List<Value> values) {
        public Preview { values = List.copyOf(values); }
    }
    /** Host-supplied analysis inputs, not a compiler-authenticated declaration. */
    public static Preview check(Parser.GroupedSpawnSyntax syntax,
            List<Parser.ActorInitField> schema, Ceiling ceiling) {
        Objects.requireNonNull(syntax); Objects.requireNonNull(ceiling);
        if (!syntax.arguments().isEmpty() || syntax.actorType().isBorrow() ||
                syntax.actorType().inferArguments())
            throw deny("E_SPAWN_POSITIONAL_UNSUPPORTED");
        var fields = new HashMap<String,Kind>();
        if (schema.size() > 64) throw deny("E_SPAWN_INIT_SCHEMA");
        for (var field : schema) {
            var type = field.type();
            if (type.inferArguments() || !type.arguments().isEmpty()) throw deny("E_SPAWN_INIT_TYPE");
            Kind kind = switch(type.name()) {
                case "int" -> Kind.INT; case "bool" -> Kind.BOOL; case "string" -> Kind.STRING;
                default -> throw deny("E_SPAWN_INIT_TYPE");
            };
            if (fields.putIfAbsent(field.name(),kind)!=null) throw deny("E_SPAWN_INIT_SCHEMA");
        }
        var seenSections = new HashSet<String>();
        var seenFields = new HashSet<String>();
        var result = new ArrayList<Value>();
        long parallelism = ceiling.serial() ? 1 : ceiling.parallelism();
        String affinity = "none";
        for (var section : syntax.sections()) {
            if (!List.of("schedule","resources","init").contains(section.name()))
                throw deny("E_SPAWN_OPTION_SECTION");
            if (!seenSections.add(section.name())) throw deny("E_SPAWN_OPTION_DUPLICATE");
            var seen = new HashSet<String>();
            for (var entry : section.entries()) {
                if (!seen.add(entry.key())) throw deny("E_SPAWN_OPTION_DUPLICATE");
                String key = entry.key();
                Ast.Expr expr = entry.value();
                Kind kind;
                Object value;
                switch(section.name()) {
                    case "resources" -> {
                        long limit = switch(key) {
                            case "heap_size" -> ceiling.heap();
                            case "stack_size" -> ceiling.stack();
                            case "mailbox_capacity" -> ceiling.messages();
                            default -> throw deny("E_SPAWN_OPTION_KEY");
                        };
                        kind=Kind.INT; value=positive(expr,limit);
                    }
                    case "schedule" -> {
                        switch(key) {
                            case "max_parallelism" -> {
                                if (expr instanceof Ast.NameExpr n && n.name().equals("pool_size")) {
                                    kind=Kind.POOL_SIZE; value="pool_size";
                                    if (ceiling.serial()) throw deny("E_SPAWN_POLICY");
                                    parallelism=ceiling.parallelism();
                                } else {
                                    kind=Kind.INT;
                                    parallelism=positive(expr,ceiling.serial()?1:ceiling.parallelism());
                                    value=parallelism;
                                }
                            }
                            case "affinity" -> {
                                kind=Kind.AFFINITY;
                                affinity=name(expr,List.of("none","sticky","pinned","dedicated"));
                                value=affinity;
                            }
                            case "pool" -> {
                                // Only the shared symbolic pool is recognized in this preview.
                                // Actual pool ownership/capability is not checked here.
                                kind=Kind.POOL; value=name(expr,List.of("shared"));
                            }
                            default -> throw deny("E_SPAWN_OPTION_KEY");
                        }
                    }
                    case "init" -> {
                        kind=fields.get(key);
                        if (kind==null || !seenFields.add(key)) throw deny("E_SPAWN_INIT_FIELDS");
                        if (kind==Kind.INT) value=integer(expr,0);
                        else if (expr instanceof Ast.LiteralExpr literal &&
                            ((kind==Kind.BOOL && literal.value() instanceof Boolean) ||
                             (kind==Kind.STRING && literal.value() instanceof String))) value=literal.value();
                        else throw deny("E_SPAWN_INIT_TYPE");
                    }
                    default -> throw deny("E_SPAWN_OPTION_SECTION");
                }
                result.add(new Value(section.name(),key,kind,value));
            }
        }
        if (seenFields.size()!=fields.size()) throw deny("E_SPAWN_INIT_FIELDS");
        if ((affinity.equals("pinned") || affinity.equals("dedicated")) && parallelism!=1)
            throw deny("E_SPAWN_POLICY");
        return new Preview(syntax.actorType(),syntax.awaited(),result);
    }
    private static String name(Ast.Expr expr,List<String> allowed) {
        if (expr instanceof Ast.NameExpr n && allowed.contains(n.name())) return n.name();
        throw deny("E_SPAWN_POLICY");
    }
    private static long positive(Ast.Expr expr,long limit) {
        long n=integer(expr,0);
        if (n<=0 || n>limit) throw deny("E_SPAWN_QUOTA");
        return n;
    }
    private static long integer(Ast.Expr expr,int depth) {
        if (depth>=128) throw deny("E_SPAWN_CONST_DEPTH");
        if (expr instanceof Ast.LiteralExpr l && l.value() instanceof Long n) return n;
        try {
            if (expr instanceof Ast.UnaryExpr u && u.operator().equals("-"))
                return Math.negateExact(integer(u.operand(),depth+1));
            if (expr instanceof Ast.BinaryExpr b) {
                long a=integer(b.left(),depth+1), c=integer(b.right(),depth+1);
                return switch(b.operator()) {
                    case "+" -> Math.addExact(a,c);
                    case "-" -> Math.subtractExact(a,c);
                    case "*" -> Math.multiplyExact(a,c);
                    default -> throw deny("E_SPAWN_CONST_UNSUPPORTED");
                };
            }
        } catch (ArithmeticException ex) { throw deny("E_SPAWN_CONST_OVERFLOW"); }
        throw deny("E_SPAWN_CONST_UNSUPPORTED");
    }
    private static IllegalArgumentException deny(String code) { return new IllegalArgumentException(code); }
}
