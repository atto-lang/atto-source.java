package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

/** Finds `alias.member` references in an importer's syntax tree. */
final class ImportedMemberScan {
    private ImportedMemberScan() { }

    /** The first referenced member of {@code alias} that is in {@code names}, or null. */
    static String firstReference(Object node, String alias, Set<String> names) {
        if (node == null || names.isEmpty()) return null;
        if (node instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals(alias) && names.contains(member.member())) {
            return member.member();
        }
        if (node instanceof List<?> list) {
            for (Object element : list) {
                String found = firstReference(element, alias, names);
                if (found != null) return found;
            }
            return null;
        }
        if (!(node instanceof Record record) || !record.getClass().getName().startsWith("dev.oreslang.ast.")) {
            return null;
        }
        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                String found = firstReference(component.getAccessor().invoke(record), alias, names);
                if (found != null) return found;
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return null;
    }
}
