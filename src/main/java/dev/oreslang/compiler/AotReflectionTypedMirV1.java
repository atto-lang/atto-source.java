package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Narrow, explicitly opted-in typed MIR for a single guest-source declaration:
 *
 *   pub fnc main(): TypeDescriptor { return type_of<PublicNominal>(); }
 *
 * The ordinary parser is used for the entire source. Non-entry declarations
 * undergo the normal TypeChecker and OwnershipChecker. This MIR does NOT
 * implement the general TypeChecker intrinsic, a guest runtime TypeDescriptor,
 * native AOT code generation, or executable guest semantics. The ordinary
 * compilation path must continue to reject unlowered type_of calls.
 *
 * The compiler owns the metadata blob; the guest will eventually receive a
 * bounded read-only table index, never a Java reflection object or raw pointer.
 */
public final class AotReflectionTypedMirV1 {
    private static final int MAX_SOURCE_BYTES = 65_536;
    private static final int MAX_DECLARATIONS = 1_024;
    private static final String ENTRY_NAME = "main";
    private static final String VALUE_NAME = "TypeDescriptor";

    private AotReflectionTypedMirV1() { }

    public enum ValueType { TYPE_DESCRIPTOR }

    public sealed interface Instruction permits LoadTypeDescriptor, ReturnDescriptor {
        ValueType valueType();
    }

    /** Compiler-emitted constant reference, not a native address. */
    public record LoadTypeDescriptor(int tableIndex, String typeIdHex) implements Instruction {
        public LoadTypeDescriptor {
            if (tableIndex != 0 || typeIdHex == null ||
                    !typeIdHex.matches("[0-9a-f]{64}")) {
                throw invalid("invalid bounded type descriptor reference");
            }
        }
        @Override public ValueType valueType() { return ValueType.TYPE_DESCRIPTOR; }
    }

    public record ReturnDescriptor(int registerIndex) implements Instruction {
        public ReturnDescriptor {
            if (registerIndex != 0) throw invalid("invalid descriptor result register");
        }
        @Override public ValueType valueType() { return ValueType.TYPE_DESCRIPTOR; }
    }

    /** This is an internal typed compiler value, not a runtime-exposed handle. */
    public record TypeDescriptorType(String qualifiedNominal, String typeIdHex) {
        public TypeDescriptorType {
            if (qualifiedNominal == null || qualifiedNominal.isBlank() ||
                    typeIdHex == null || !typeIdHex.matches("[0-9a-f]{64}")) {
                throw invalid("invalid closed descriptor type");
            }
        }
    }

    /**
     * Compiler-issued, immutable typed plan. The private constructor prevents
     * callers from turning a self-authored descriptor blob into allegedly
     * checked MIR; only the guarded source lowering path can issue one.
     */
    public static final class Plan {
        private final String entryPoint;
        private final TypeDescriptorType resultType;
        private final byte[] descriptorTable;
        private final List<Instruction> instructions;

        private Plan(String entryPoint, TypeDescriptorType resultType,
                     byte[] descriptorTable, List<Instruction> instructions) {
            if (!ENTRY_NAME.equals(entryPoint) || resultType == null ||
                    descriptorTable == null || instructions == null) {
                throw invalid("invalid typed descriptor plan");
            }
            byte[] stable = descriptorTable.clone();
            List<Instruction> copied = List.copyOf(instructions);
            if (!copied.equals(List.of(
                    new LoadTypeDescriptor(0, resultType.typeIdHex()),
                    new ReturnDescriptor(0)))) {
                throw invalid("typed MIR sequence must load and return one descriptor");
            }
            var decoded = AotReflectionTableV1Reader.decode(stable);
            if (decoded.types().size() != 1 ||
                    !decoded.types().getFirst().resolvedSymbol().equals(
                            resultType.qualifiedNominal()) ||
                    !decoded.types().getFirst().typeIdHex().equals(
                            resultType.typeIdHex()) ||
                    decoded.findByTypeId(resultType.typeIdHex()).isEmpty()) {
                throw invalid("descriptor table and typed MIR identity disagree");
            }
            this.entryPoint = entryPoint;
            this.resultType = resultType;
            this.descriptorTable = stable;
            this.instructions = copied;
        }

        public String entryPoint() { return entryPoint; }
        public TypeDescriptorType resultType() { return resultType; }
        public byte[] descriptorTable() { return descriptorTable.clone(); }
        public List<Instruction> instructions() { return instructions; }
    }

    /**
     * Parse guest source, validate exactly one limited reflection-return entry,
     * run production semantic admission on all other declarations, and lower
     * one checked nominal into an immutable typed descriptor constant.
     *
     * This deliberately does not call normal TypeChecker on the entry itself:
     * that checker correctly rejects type_of until general HIR/backend lowering
     * exists. Consequently, this method is only an internal MIR *prototype*;
     * it must not be exposed as a normal guest compilation or execution path.
     */
    public static Plan lowerSource(String source, String packageIdentityClaim) {
        if (source == null || source.length() > MAX_SOURCE_BYTES ||
                source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw invalid("source exceeds typed reflection MIR budget");
        }
        if (packageIdentityClaim == null || packageIdentityClaim.isBlank() ||
                packageIdentityClaim.length() > 512) {
            throw invalid("bounded package identity claim required");
        }

        Ast.Program parsed = Parser.parse(source);
        if (parsed.namespace() != null || !parsed.imports().isEmpty() ||
                parsed.modules().size() != 1) {
            throw invalid("requires one import-free, namespace-free source module");
        }
        Ast.ModuleDecl module = parsed.modules().getFirst();
        if (!Parser.ROOT_MODULE.equals(module.name()) ||
                !module.annotations().isEmpty() || !module.contracts().isEmpty() ||
                module.declarations().isEmpty() ||
                module.declarations().size() > MAX_DECLARATIONS) {
            throw invalid("requires bounded root module without contracts or annotations");
        }
        Ast.FunctionDecl entry = null;
        List<Ast.Decl> declarations = new ArrayList<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn) {
                if (entry != null || !ENTRY_NAME.equals(fn.name())) {
                    throw invalid("only one main reflection entry is supported");
                }
                entry = fn;
            } else if (decl instanceof Ast.ClassDecl klass) {
                if (klass.name().equals(VALUE_NAME)) {
                    throw invalid("guest cannot shadow compiler TypeDescriptor type");
                }
                declarations.add(klass);
            } else {
                throw invalid("unsupported declaration in reflection MIR subset");
            }
        }
        if (entry == null || entry.kind() != Ast.CallableKind.FNC ||
                entry.visibility() != Ast.Visibility.PUBLIC ||
                entry.actorKind() != Ast.ActorKind.NONE ||
                entry.async() || entry.suspends() || entry.generator() ||
                entry.structural() || entry.nonLexical() || entry.trapped() ||
                !entry.genericParameters().isEmpty() ||
                !entry.genericSpecs().isEmpty() ||
                !entry.parameters().isEmpty() || !entry.annotations().isEmpty() ||
                !entry.effects().isEmpty() ||
                entry.returnType() == null ||
                !VALUE_NAME.equals(entry.returnType().name()) ||
                entry.returnType().inferArguments() ||
                !entry.returnType().arguments().isEmpty() ||
                entry.body().size() != 1 ||
                !(entry.body().getFirst() instanceof Ast.ReturnStmt returned)) {
            throw invalid("main must be a pure zero-argument descriptor-return entry");
        }
        Ast.Expr returnedExpression = returned.value();
        if (!(returnedExpression instanceof Ast.CallExpr call) ||
                call.origin() != Ast.CallOrigin.DIRECT ||
                !(call.callee() instanceof Ast.NameExpr callee) ||
                !callee.name().equals("type_of") ||
                !call.typeArgumentsPresent() ||
                call.typeArguments().size() != 1 ||
                !call.arguments().isEmpty()) {
            throw invalid("main must return exactly type_of<Nominal>()");
        }
        Ast.TypeRef nominal = call.typeArguments().getFirst();
        if (nominal.inferArguments() || !nominal.arguments().isEmpty() ||
                !nominal.name().matches("[A-Za-z][A-Za-z0-9_]{0,127}")) {
            throw invalid("reflection return must use one concrete nominal symbol");
        }

        // The unlowered special entry is intentionally not laundered into
        // normal guest type checking; only the other declarations are checked.
        Ast.Program declarationProgram = new Ast.Program(null, List.of(), List.of(
                new Ast.ModuleDecl(module.name(), List.of(), List.of(), declarations)));
        Ast.Program checkedDeclarations = OresCompiler.analyze(declarationProgram);
        String qualified = Parser.ROOT_MODULE + "." + nominal.name();
        byte[] bytes = AotReflectionSourceRootsV1.lowerChecked(
                checkedDeclarations, packageIdentityClaim, List.of(qualified)).bytes();
        var table = AotReflectionTableV1Reader.decode(bytes);
        if (table.types().size() != 1 ||
                !qualified.equals(table.types().getFirst().resolvedSymbol())) {
            throw invalid("resolved reflection root has no exact descriptor");
        }
        String typeId = table.types().getFirst().typeIdHex();
        return new Plan(ENTRY_NAME, new TypeDescriptorType(qualified, typeId),
                bytes, List.of(new LoadTypeDescriptor(0, typeId), new ReturnDescriptor(0)));
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("AOT_REFLECTION_MIR_UNSUPPORTED: " + reason);
    }
}
