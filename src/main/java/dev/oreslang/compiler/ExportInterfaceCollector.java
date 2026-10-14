package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Extracts public declaration contracts, not guest function bodies.
 * Input MUST have already passed trusted type/ownership admission and
 * annotation expansion. The result is an interface prototype, not yet
 * suitable for importing native ABI layouts or external cache trust.
 */
public final class ExportInterfaceCollector {
    private ExportInterfaceCollector() { }

    public static ExportInterfaceCodec.InterfaceFile collect(
            Ast.Program checked, ExportInterfaceCodec.Header header) {
        Objects.requireNonNull(checked);
        Objects.requireNonNull(header);
        String namespace = checked.namespace() == null ? "" : checked.namespace();
        if (!namespace.equals(header.namespace()))
            throw new IllegalArgumentException("interface namespace does not match checked source");
        List<ExportInterfaceCodec.Entry> entries = new ArrayList<>();
        for (Ast.ModuleDecl module : checked.modules()) {
            String owner = module.name();
            StringBuilder info = new StringBuilder();
            types(info, module.contracts());
            annotations(info, module.annotations());
            entries.add(new ExportInterfaceCodec.Entry(
                    owner, ExportInterfaceCodec.Kind.MODULE, owner, info.toString()));
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                    StringBuilder sig = new StringBuilder();
                    field(sig, fn.kind().name());
                    field(sig, fn.actorKind().name());
                    flags(sig, fn.async(), fn.suspends(), fn.generator(), fn.structural(),
                            fn.nonLexical(), fn.trapped());
                    strings(sig, fn.genericParameters());
                    params(sig, fn.parameters());
                    requiredType(sig, fn.returnType());
                    annotations(sig, fn.annotations());
                    entries.add(new ExportInterfaceCodec.Entry(owner,
                            ExportInterfaceCodec.Kind.FUNCTION, fn.name(), sig.toString()));
                } else if (decl instanceof Ast.TypeAliasDecl alias
                        && alias.visibility() == Ast.Visibility.PUBLIC) {
                    StringBuilder sig = new StringBuilder();
                    strings(sig, alias.genericParameters());
                    requiredType(sig, alias.target());
                    entries.add(new ExportInterfaceCodec.Entry(owner,
                            ExportInterfaceCodec.Kind.TYPE, alias.name(), sig.toString()));
                } else if (decl instanceof Ast.InterfaceDecl iface
                        && iface.visibility() == Ast.Visibility.PUBLIC) {
                    StringBuilder sig = new StringBuilder();
                    flags(sig, iface.moduleContract());
                    strings(sig, iface.genericParameters());
                    types(sig, iface.parents());
                    List<String> members = new ArrayList<>();
                    for (Ast.InterfaceMember member : iface.members()) {
                        StringBuilder part = new StringBuilder();
                        if (member instanceof Ast.InterfaceFunctionDecl fn) {
                            field(part, "fnc");
                            field(part, fn.name());
                            flags(part, fn.structural());
                            strings(part, fn.genericParameters());
                            params(part, fn.parameters());
                            requiredType(part, fn.returnType());
                        } else if (member instanceof Ast.InterfaceFieldDecl f) {
                            field(part, "field");
                            field(part, f.name());
                            requiredType(part, f.type());
                        }
                        members.add(part.toString());
                    }
                    stringsSorted(sig, members);
                    entries.add(new ExportInterfaceCodec.Entry(owner,
                            ExportInterfaceCodec.Kind.INTERFACE, iface.name(), sig.toString()));
                } else if (decl instanceof Ast.ClassDecl klass
                        && klass.visibility() == Ast.Visibility.PUBLIC) {
                    StringBuilder sig = new StringBuilder();
                    field(sig, klass.actorKind().name());
                    flags(sig, klass.isAbstract());
                    strings(sig, klass.genericParameters());
                    types(sig, klass.parents());
                    types(sig, klass.interfaces());
                    if (klass.constructor() != null) {
                        field(sig, klass.constructor().visibility().name());
                        if (klass.constructor().visibility() == Ast.Visibility.PUBLIC) {
                            params(sig, klass.constructor().parameters());
                            annotations(sig, klass.constructor().annotations());
                        }
                    } else {
                        // Synthesized constructors currently consume all
                        // positional fields. Do NOT leak private field layouts
                        // into an exported descriptor until an opaque ABI hash
                        // and native layout contract are implemented.
                        for (Ast.FieldDecl f : klass.fields()) {
                            if (f.visibility() != Ast.Visibility.PUBLIC) {
                                throw new IllegalArgumentException(
                                        "synthetic constructor with private fields has no safe public interface");
                            }
                        }
                        field(sig, "synthesized-constructor");
                        for (Ast.FieldDecl f : klass.fields()) requiredType(sig, f.type());
                    }
                    List<String> members = new ArrayList<>();
                    for (Ast.FieldDecl f : klass.fields()) {
                        if (f.visibility() != Ast.Visibility.PUBLIC) continue;
                        StringBuilder part = new StringBuilder();
                        field(part, "field");
                        field(part, f.name());
                        field(part, f.bindingKind().name());
                        flags(part, f.mutableReferent());
                        requiredType(part, f.type());
                        annotations(part, f.annotations());
                        members.add(part.toString());
                    }
                    for (Ast.MethodDecl m : klass.methods()) {
                        if (m.visibility() != Ast.Visibility.PUBLIC) continue;
                        StringBuilder part = new StringBuilder();
                        field(part, "method");
                        field(part, m.name());
                        flags(part, m.isStatic(), m.isAbstract(), m.async(), m.suspends(), m.structural());
                        requiredTypeOrAbsent(part, m.explicitReceiverType());
                        strings(part, m.genericParameters());
                        params(part, m.parameters());
                        requiredType(part, m.returnType());
                        annotations(part, m.annotations());
                        members.add(part.toString());
                    }
                    stringsSorted(sig, members);
                    entries.add(new ExportInterfaceCodec.Entry(owner,
                            ExportInterfaceCodec.Kind.CLASS, klass.name(), sig.toString()));
                } else if (decl instanceof Ast.FieldDecl f && f.visibility() == Ast.Visibility.PUBLIC) {
                    StringBuilder sig = new StringBuilder();
                    field(sig, f.bindingKind().name());
                    flags(sig, f.mutableReferent());
                    requiredType(sig, f.type());
                    annotations(sig, f.annotations());
                    entries.add(new ExportInterfaceCodec.Entry(owner,
                            ExportInterfaceCodec.Kind.BINDING, f.name(), sig.toString()));
                }
            }
        }
        return ExportInterfaceCodec.encode(header, entries);
    }

    private static void field(StringBuilder dst, String item) {
        Objects.requireNonNull(item);
        dst.append(item.length()).append(':').append(item);
    }

    private static void flags(StringBuilder dst, boolean... values) {
        for (boolean b : values) dst.append(b ? '1' : '0');
        dst.append(';');
    }

    private static void strings(StringBuilder dst, List<String> values) {
        dst.append(values.size()).append('[');
        for (String value : values) field(dst, value);
        dst.append(']');
    }

    private static void stringsSorted(StringBuilder dst, List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        strings(dst, copy);
    }

    private static void types(StringBuilder dst, List<Ast.TypeRef> values) {
        dst.append(values.size()).append('[');
        for (Ast.TypeRef value : values) requiredType(dst, value);
        dst.append(']');
    }

    private static void params(StringBuilder dst, List<Ast.Param> values) {
        dst.append(values.size()).append('[');
        for (Ast.Param p : values) {
            field(dst, p.name());
            flags(dst, p.mutable(), p.structural());
            requiredType(dst, p.type());
        }
        dst.append(']');
    }

    private static void annotations(StringBuilder dst, List<Ast.Annotation> values) {
        dst.append(values.size()).append('[');
        for (Ast.Annotation a : values) {
            field(dst, a.name());
            types(dst, a.arguments());
        }
        dst.append(']');
    }

    private static void requiredType(StringBuilder dst, Ast.TypeRef type) {
        if (type == null) throw new IllegalArgumentException(
                "exported declaration has unresolved or inferred type");
        appendType(dst, type, 0);
    }

    private static void requiredTypeOrAbsent(StringBuilder dst, Ast.TypeRef type) {
        if (type == null) field(dst, "(no receiver)");
        else requiredType(dst, type);
    }

    private static void appendType(StringBuilder dst, Ast.TypeRef type, int depth) {
        if (depth > 128) throw new IllegalArgumentException("type signature too deeply nested");
        field(dst, type.name());
        flags(dst, type.inferArguments());
        dst.append(type.arguments().size()).append('(');
        for (Ast.TypeRef child : type.arguments()) appendType(dst, child, depth + 1);
        dst.append(')');
    }
}
