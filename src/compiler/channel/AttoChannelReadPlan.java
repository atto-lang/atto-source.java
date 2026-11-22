package atto.compiler.channel;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded source preflight and typed admission for one Channel<int> read.
 * A read has Result<Option<int>, IoError> type, requires susp + io capability,
 * and must not be silently unwrapped/discarded.
 *
 * No NIO/JSPI guest ABI, scheduler, or general Atto frontend is implemented here.
 */
public final class AttoChannelReadPlan {
    public enum Syntax { PREFIX, POSTFIX, READCH, DECLARATION_FIRST, DIRECTIONAL_BINDING }
    public enum Type { CHECKED_RESULT_OPTION_INT, INT, OPTION_INT, VOID }
    public enum OutcomeTag { SOME, EOF, IO_ERROR }
    public record Context(boolean suspending, boolean ioGranted,
                          boolean activeBorrowCrossesSuspension, boolean constBinding) {}
    public record Read(Syntax syntax, String readableName, Type resultType,
                       boolean exactlyOnce, boolean nonparking, boolean cooperate) {
        public Read {
            Objects.requireNonNull(syntax);
            Objects.requireNonNull(readableName);
            Objects.requireNonNull(resultType);
        }
    }
    public record CheckedOutcome(OutcomeTag tag, int value, String error) {
        public CheckedOutcome { Objects.requireNonNull(tag); }
        public static CheckedOutcome some(int value) {
            return new CheckedOutcome(OutcomeTag.SOME, value, null);
        }
        public static CheckedOutcome eof() {
            return new CheckedOutcome(OutcomeTag.EOF, 0, null);
        }
        public static CheckedOutcome error(String reason) {
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("E_IO_ERROR");
            return new CheckedOutcome(OutcomeTag.IO_ERROR, 0, reason);
        }
        public boolean is_some() { return tag == OutcomeTag.SOME; }
        public boolean is_eof() { return tag == OutcomeTag.EOF; }
        public boolean is_error() { return tag == OutcomeTag.IO_ERROR; }
    }
    private static final String ID = "[A-Za-z_][A-Za-z_0-9]*";
    private static final Pattern PREFIX = Pattern.compile("(?:(?:return|\\^|val " + ID + " =|let " + ID + " =)\\s+)?<<\\s+(" + ID + ")");
    private static final Pattern READCH = Pattern.compile("readch\\s+(" + ID + ")");
    private static final Pattern DECLARATION = Pattern.compile("(?:val|let)\\s+" + ID + "\\s+<<\\s+(" + ID + ")");
    // Anchored source-slice candidates: the preflight is not an Atto parser.
    // Both postfix spellings become exactly the same typed Read as prefix <<.
    // The suffix must end the expression; c >> val item is a declaration,
    // while c >>> 1 and c > > are never read aliases.
    private static final String BIND_HEAD = "(?:val|let)\\s+(?:" + ID + "\\s+)?" + ID + "\\s*=";
    private static final Pattern POSTFIX = Pattern.compile(
        "(?:(?:return|\\^|" + BIND_HEAD + ")\\s+)?(?:\\(\\s*(" + ID +
        ")\\s*>>\\s*\\)|(" + ID + ")\\s*>>)");
    private static final Pattern DIRECTIONAL = Pattern.compile(
        "(" + ID + ")\\s*>>\\s*(?:val|let)\\s+" + ID);

    private AttoChannelReadPlan() {}

    public static Read parse_and_check(String source, String declaredReadable,
                                       Type expectedType, Context context) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(declaredReadable);
        Objects.requireNonNull(expectedType);
        Objects.requireNonNull(context);
        String text = source.trim();
        Matcher prefix = PREFIX.matcher(text);
        Matcher readch = READCH.matcher(text);
        Matcher declaration = DECLARATION.matcher(text);
        Matcher postfix = POSTFIX.matcher(text);
        Matcher directional = DIRECTIONAL.matcher(text);
        Syntax syntax;
        String name;
        if (prefix.matches()) { syntax = Syntax.PREFIX; name = prefix.group(1); }
        else if (readch.matches()) { syntax = Syntax.READCH; name = readch.group(1); }
        else if (postfix.matches()) {
            syntax = Syntax.POSTFIX;
            name = postfix.group(1) == null ? postfix.group(2) : postfix.group(1);
        } else if (directional.matches()) {
            syntax = Syntax.DIRECTIONAL_BINDING;
            name = directional.group(1);
        }
        else if (declaration.matches()) {
            syntax = Syntax.DECLARATION_FIRST; name = declaration.group(1);
        } else throw new IllegalArgumentException("E_IO_SYNTAX");
        if (!name.equals(declaredReadable))
            throw new IllegalArgumentException("E_IO_READABLE_CAPABILITY");
        if (!context.ioGranted()) throw new IllegalArgumentException("E_IO_PERMISSION");
        if (!context.suspending()) throw new IllegalArgumentException("E_IO_SUSPENDS");
        if (context.activeBorrowCrossesSuspension())
            throw new IllegalArgumentException("E_BORROW_AWAIT");
        if (context.constBinding() && (syntax == Syntax.DECLARATION_FIRST
                                       || syntax == Syntax.DIRECTIONAL_BINDING
                                       || text.startsWith("val ")))
            throw new IllegalArgumentException("E_CONST_EFFECT");
        if (expectedType == Type.VOID) throw new IllegalArgumentException("E_IO_DISCARD");
        if (expectedType != Type.CHECKED_RESULT_OPTION_INT)
            throw new IllegalArgumentException("E_IO_NO_IMPLICIT_UNWRAP");
        return new Read(syntax, name, expectedType, true, true, true);
    }
}
