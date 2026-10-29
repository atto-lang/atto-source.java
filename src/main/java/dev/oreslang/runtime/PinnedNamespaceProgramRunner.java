package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.PackageNamespaceLinker;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host-injected closed, pinned namespace graph execution for Java/Graal.
 *
 * This is an executable *in-memory* guest entry point. Only a caller which has
 * already verified manifest/lockfile ownership and immutable package pins may
 * supply this graph; the runner does not discover or trust filesystem packages.
 * The ordinary filesystem runner still fails closed for import ns.
 */
public final class PinnedNamespaceProgramRunner {
    private PinnedNamespaceProgramRunner() { }

    public record Result(IncrementalCompiler.BuildResult build, int exitStatus) { }

    public static Result run(
            Map<String, String> sources,
            Map<String, String> sourceOwners,
            Map<String, Map<String, String>> pinnedAliases,
            String entrySource,
            IsolatePolicy policy,
            ExecutionProfile profile) {
        return run(sources, sourceOwners, pinnedAliases, entrySource, policy, profile, null, null);
    }

    public static Result run(
            Map<String, String> sources,
            Map<String, String> sourceOwners,
            Map<String, Map<String, String>> pinnedAliases,
            String entrySource,
            IsolatePolicy policy,
            ExecutionProfile profile,
            OutputStream out,
            OutputStream err) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(sourceOwners, "sourceOwners");
        Objects.requireNonNull(pinnedAliases, "pinnedAliases");
        Objects.requireNonNull(entrySource, "entry source");
        Objects.requireNonNull(policy, "isolate policy");
        Objects.requireNonNull(profile, "execution profile");

        // Never attempt JIT/AOT before all source units and package identities
        // pass compile-time ownership, public export and type checking.
        IncrementalCompiler.PinnedBuildResult verified =
                new IncrementalCompiler().compilePinned(
                        sources, Map.of(), sourceOwners, pinnedAliases);
        IncrementalCompiler.BuildResult build = verified.build();
        if (!build.units().containsKey(entrySource))
            throw new IllegalArgumentException("entry source was not supplied in pinned package graph");

        List<String> ids = new ArrayList<>(build.units().keySet());
        ids.sort(String::compareTo);
        Context.Builder builder = policy.restrictedContextBuilder(profile, Set.of());
        if (out != null) builder.out(out);
        if (err != null) builder.err(err);

        try (Context context = builder.build()) {
            Map<String, Value> executables = new LinkedHashMap<>();
            for (String id : ids) {
                IncrementalCompiler.CompiledUnit unit = build.units().get(id);
                Source guest = Source.newBuilder(OresLanguage.ID, unit.sourceText(), id)
                        .mimeType(OresLanguage.MIME_TYPE).buildLiteral();
                try (OresLanguage.PrecheckedProgramScope ignored =
                             OresLanguage.openPrecheckedProgram(id, unit.sourceText(), unit.program())) {
                    executables.put(id, context.parse(guest));
                }
            }

            // Register only function slots from the compiler's verified package
            // graph. No directory-based fallback, wildcard or runtime merging.
            for (String importer : ids) {
                Value executable = executables.get(importer);
                Map<String, PackageNamespaceLinker.NamespaceImport> selections =
                        verified.namespaceGraph().importsBySource()
                                .getOrDefault(importer, Map.of());
                List<String> bindings = new ArrayList<>(selections.keySet());
                bindings.sort(String::compareTo);
                for (String binding : bindings) {
                    PackageNamespaceLinker.NamespaceImport selected = selections.get(binding);
                    for (PackageNamespaceLinker.Export member : selected.target().exports()) {
                        executable.execute(
                                OresEvalRootNode.REGISTER_NAMESPACE_MEMBER_COMMAND,
                                binding, member.name(), member.arity(), member.sourceUnitId());
                    }
                }
            }

            // Link all checked units before invoking any initializer. The
            // package graph adds all namespace sources to initialization deps.
            for (String id : ids)
                executables.get(id).execute(OresEvalRootNode.LINK_ONLY_COMMAND);
            for (List<String> group : build.initializationGroups())
                for (String id : group)
                    executables.get(id).execute(OresEvalRootNode.INIT_ONLY_COMMAND);

            Value status = executables.get(entrySource).execute(OresEvalRootNode.MAIN_ONLY_COMMAND);
            if (status == null || status.isNull()) return new Result(build, 0);
            if (!status.fitsInInt()) {
                throw new IllegalArgumentException("Oreslang main() exit status must be an int");
            }
            int exit = status.asInt();
            if (exit < 0 || exit > 255) {
                throw new IllegalArgumentException("Oreslang process exit status must be in 0..255");
            }
            return new Result(build, exit);
        }
    }
}
