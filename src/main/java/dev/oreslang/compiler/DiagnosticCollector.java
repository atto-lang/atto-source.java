package dev.oreslang.compiler;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Resource-bounded compiler-owned primary-error store.
 *
 * This is infrastructure for parser/type/ownership recovery, NOT recovery
 * itself. Frontends must report real independent root identities and source
 * spans. In particular, callers must not manufacture multiple errors by
 * splitting one compiler exception string.
 */
public final class DiagnosticCollector {
    public static final int DEFAULT_MAX_ERRORS = 5;
    public static final int HARD_MAX_ERRORS = 1000;
    public static final int MAX_RELATED = 8;
    private static final int MAX_MESSAGE_CHARS = 2048;
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9-]{2,63}");

    public enum Phase { LEX, PARSE, IMPORT, TYPE, EFFECT, OWNERSHIP, CHECK, LOWERING, LINK, ARTIFACT }

    /** Compiler coordinates are 1-based with a half-open end. */
    public record Span(String unitId, int line, int column, int endLine, int endColumn) {
        public Span {
            Objects.requireNonNull(unitId, "unitId");
            if (unitId.isBlank() || unitId.length() > 1024)
                throw new IllegalArgumentException("invalid source-unit identifier");
            if (line < 1 || column < 1 || endLine < 1 || endColumn < 1
                    || endLine < line || (endLine == line && endColumn < column))
                throw new IllegalArgumentException("invalid half-open source range");
        }
    }

    public record Related(Span span, String message) {
        public Related {
            Objects.requireNonNull(span, "span");
            message = bounded(message);
        }
    }

    /** Root cause IDs are compiler-node/symbol identities, never error text. */
    public record Diagnostic(
            String code, Phase phase, Span primary, String message,
            String rootCauseId, List<Related> related, String help) {
        public Diagnostic {
            Objects.requireNonNull(code, "code");
            if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("invalid error code");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(primary, "primary");
            if (rootCauseId == null || rootCauseId.isBlank() || rootCauseId.length() > 256)
                throw new IllegalArgumentException("compiler root-cause ID required");
            message = bounded(message);
            help = help == null ? "" : bounded(help);
            related = related == null ? List.of() : List.copyOf(related);
            if (related.size() > MAX_RELATED)
                throw new IllegalArgumentException("too many related source ranges");
        }
    }

    private static String bounded(String value) {
        Objects.requireNonNull(value, "diagnostic text");
        // A parser diagnostic may quote attacker-controlled source. Remove
        // terminal controls and bidi override characters before presentation.
        StringBuilder safe = new StringBuilder(Math.min(MAX_MESSAGE_CHARS, value.length()));
        for (int i = 0; i < value.length() && safe.length() < MAX_MESSAGE_CHARS; i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || ch == 0x202e || ch == 0x202d
                    || ch == 0x202a || ch == 0x202b || ch == 0x202c
                    || ch == 0x2066 || ch == 0x2067
                    || ch == 0x2068 || ch == 0x2069) {
                safe.append('?');
            } else {
                safe.append(ch);
            }
        }
        if (value.length() > MAX_MESSAGE_CHARS) {
            safe.setLength(Math.min(safe.length(), MAX_MESSAGE_CHARS - 3));
            safe.append("...");
        }
        return safe.toString();
    }

    private static final Comparator<Diagnostic> SOURCE_ORDER = Comparator
            .comparing((Diagnostic d) -> d.primary().unitId())
            .thenComparingInt(d -> d.primary().line())
            .thenComparingInt(d -> d.primary().column())
            .thenComparing(Diagnostic::code)
            .thenComparing(Diagnostic::rootCauseId);

    private final int requestedMaxErrors;
    private final int effectiveMaxErrors;
    private final TreeSet<Diagnostic> ordered = new TreeSet<>(SOURCE_ORDER);
    // Root identities are scoped to source units, never global message strings.
    private final Map<String, Diagnostic> retainedRoots = new HashMap<>();
    private static String rootKey(Diagnostic d) {
        return d.primary().unitId() + "\\0" + d.rootCauseId();
    }
    private boolean mayHaveMore;

    /**
     * Zero means unlimited by user choice, still restricted to HARD_MAX_ERRORS.
     * Negative values and requests exceeding the protective hard cap are invalid.
     */
    public DiagnosticCollector(int maxErrors) {
        if (maxErrors < 0 || maxErrors > HARD_MAX_ERRORS)
            throw new IllegalArgumentException("--max-errors must be from 0 to " + HARD_MAX_ERRORS);
        this.requestedMaxErrors = maxErrors;
        this.effectiveMaxErrors = maxErrors == 0 ? HARD_MAX_ERRORS : maxErrors;
    }

    public DiagnosticCollector() { this(DEFAULT_MAX_ERRORS); }

    public static int parseMaxErrors(String input) {
        if (input == null || !input.matches("0|[1-9][0-9]*") || input.length() > 4)
            throw new IllegalArgumentException("invalid --max-errors");
        try {
            int n = Integer.parseInt(input);
            if (n > HARD_MAX_ERRORS) throw new IllegalArgumentException("excessive --max-errors");
            return n;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid --max-errors", error);
        }
    }

    /**
     * Retains at most the earliest N independent diagnostics by canonical
     * source location, regardless of insertion order. Duplicates with the
     * same compiler root-cause ID do not consume the error budget.
     *
     * A caller may stop discovery at its own computational budget and must
     * then invoke markDiscoveryIncomplete(). This collector never claims an
     * exact total of undetected errors.
     */
    public boolean add(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        String key = rootKey(diagnostic);
        if (retainedRoots.containsKey(key)) return false;
        if (ordered.size() == effectiveMaxErrors) {
            mayHaveMore = true;
            if (SOURCE_ORDER.compare(diagnostic, ordered.last()) >= 0) return false;
            Diagnostic removed = ordered.pollLast();
            retainedRoots.remove(rootKey(removed));
        }
        if (!ordered.add(diagnostic)) return false;
        retainedRoots.put(key, diagnostic);
        return true;
    }

    public void markDiscoveryIncomplete() { mayHaveMore = true; }
    public boolean mayHaveMore() { return mayHaveMore; }
    public boolean atLimit() { return ordered.size() == effectiveMaxErrors; }
    public int requestedMaxErrors() { return requestedMaxErrors; }
    public int effectiveMaxErrors() { return effectiveMaxErrors; }
    public List<Diagnostic> diagnostics() { return List.copyOf(ordered); }
}
