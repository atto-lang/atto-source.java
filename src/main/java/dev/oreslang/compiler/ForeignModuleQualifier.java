package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the declaration signatures of an imported module so they can be
 * type-checked in the importer's scope (typed linked IR v1, stage 2).
 *
 * <p>Inside its own source, a module refers to its sibling classes, interfaces
 * and type aliases by short name ({@code Command}). The importer binds the
 * module under a local alias, so the same nominal is only reachable as
 * {@code alias.Command}. This class qualifies every such short name in
 * parameter, return, field, parent and generic-default types. Generic
 * parameters shadow module names exactly as they do in the source.
 *
 * <p>Callable and constructor bodies are not rewritten: linked bodies are
 * never re-checked by the importer; their own unit already checked them.
 */
final class ForeignModuleQualifier {
    private final String alias;
    private final Set<String> nominals;
    private final Map<String, String> namespaceAliases;

    private ForeignModuleQualifier(String alias, Set<String> nominals,
                                   Map<String, String> namespaceAliases) {
        this.alias = alias;
        this.nominals = nominals;
        this.namespaceAliases = namespaceAliases;
    }

    /** Names of the nominal declarations a module owns. */
    static Set<String> nominalNames(Ast.ModuleDecl module) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Ast.Decl declaration : module.declarations()) {
            // Type aliases stay short: linked classes resolve their field
            // aliases privately, and a public signature naming a module alias
            // is not linkable (the importer keeps it opaque).
            if (declaration instanceof Ast.ClassDecl klass) names.add(klass.name());
            else if (declaration instanceof Ast.InterfaceDecl iface) names.add(iface.name());
        }
        return Set.copyOf(names);
    }

    static Ast.ModuleDecl qualify(String alias, Ast.ModuleDecl module) {
        return qualify(alias, module, Map.of());
    }

    static Ast.ModuleDecl qualify(String alias, Ast.ModuleDecl module,
                                  Map<String, String> namespaceAliases) {
        ForeignModuleQualifier qualifier =
                new ForeignModuleQualifier(alias, nominalNames(module), namespaceAliases);
        List<Ast.Decl> declarations = new ArrayList<>(module.declarations().size());
        for (Ast.Decl declaration : module.declarations()) {
            declarations.add((Ast.Decl) qualifier.rewrite(declaration, Set.of()));
        }
        return new Ast.ModuleDecl(module.name(), module.annotations(), module.contracts(),
                declarations);
    }

    static Ast.ClassDecl qualify(String alias, Ast.ModuleDecl owner, Ast.ClassDecl klass) {
        return (Ast.ClassDecl) new ForeignModuleQualifier(alias, nominalNames(owner), Map.of())
                .rewrite(klass, Set.of());
    }

    private Ast.TypeRef qualifyType(Ast.TypeRef ref, Set<String> generics) {
        if (ref == null) return null;
        List<Ast.TypeRef> arguments = new ArrayList<>(ref.arguments().size());
        boolean changed = false;
        for (Ast.TypeRef argument : ref.arguments()) {
            Ast.TypeRef rewritten = qualifyType(argument, generics);
            changed |= rewritten != argument;
            arguments.add(rewritten);
        }
        String name = ref.name();
        if (nominals.contains(name) && !generics.contains(name)) {
            name = alias + "." + name;
            changed = true;
        } else {
            int separator = name.indexOf('.');
            if (separator >= 0) {
                String replacement = namespaceAliases.get(name.substring(0, separator));
                if (replacement != null) {
                    name = replacement + name.substring(separator);
                    changed = true;
                }
            }
        }
        return changed ? new Ast.TypeRef(name, arguments, ref.inferArguments()) : ref;
    }

    @SuppressWarnings("unchecked")
    private Object rewrite(Object node, Set<String> generics) {
        if (node == null) return null;
        if (node instanceof Ast.TypeRef ref) return qualifyType(ref, generics);
        if (node instanceof Ast.Stmt || node instanceof Ast.Expr) return node;
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                Object rewritten = rewrite(element, generics);
                changed |= rewritten != element;
                out.add(rewritten);
            }
            return changed ? out : list;
        }
        if (!(node instanceof Record record) || !record.getClass().getName().startsWith("dev.oreslang.ast.")) {
            return node;
        }
        RecordComponent[] components = record.getClass().getRecordComponents();
        Set<String> scope = withGenerics(record, components, generics);
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean changed = false;
        try {
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                types[i] = component.getType();
                Object value = component.getAccessor().invoke(record);
                Object rewritten = isBody(component) ? value : rewrite(value, scope);
                changed |= rewritten != value;
                values[i] = rewritten;
            }
            if (!changed) return record;
            Constructor<?> canonical = record.getClass().getDeclaredConstructor(types);
            canonical.setAccessible(true);
            return canonical.newInstance(values);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "cannot qualify linked declaration " + record.getClass().getSimpleName(), failure);
        }
    }

    private static boolean isBody(RecordComponent component) {
        String name = component.getName();
        return name.equals("body") || name.equals("initializer") || name.equals("annotations");
    }

    private static Set<String> withGenerics(Record record, RecordComponent[] components, Set<String> outer)
            {
        for (RecordComponent component : components) {
            if (!component.getName().equals("genericParameters")) continue;
            try {
                Object value = component.getAccessor().invoke(record);
                if (!(value instanceof List<?> list) || list.isEmpty()) return outer;
                HashSet<String> scope = new HashSet<>(outer);
                for (Object element : list) {
                    if (element instanceof String name) scope.add(name);
                    else if (element instanceof Ast.GenericParameter parameter) scope.add(parameter.name());
                }
                return Set.copyOf(scope);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
        return outer;
    }
}
