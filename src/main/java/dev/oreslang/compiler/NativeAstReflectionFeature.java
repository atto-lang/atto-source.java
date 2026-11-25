package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.lang.reflect.RecordComponent;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/** Keeps the record shapes used by the source linker and AST shape guard available in Native Image. */
public final class NativeAstReflectionFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        for (Class<?> type : Ast.class.getDeclaredClasses()) {
            if (!type.isRecord()) continue;
            RuntimeReflection.register(type);
            RuntimeReflection.registerAllRecordComponents(type);
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] signature = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                RuntimeReflection.register(components[i].getAccessor());
                signature[i] = components[i].getType();
            }
            try {
                RuntimeReflection.register(type.getDeclaredConstructor(signature));
            } catch (NoSuchMethodException missing) {
                throw new IllegalStateException("AST record has no canonical constructor: " + type, missing);
            }
        }
    }
}
