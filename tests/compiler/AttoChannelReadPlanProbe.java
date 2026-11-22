package atto.compiler.channel;

import static atto.compiler.channel.AttoChannelReadPlan.*;

/** Exact checked outcome and conservative source preflight host-only witness. */
public final class AttoChannelReadPlanProbe {
    private static int checks;
    private static void ok(boolean yes, String what) {
        if (!yes) throw new AssertionError(what);
        checks++;
    }
    private static void reject(String error, Runnable run) {
        try { run.run(); }
        catch (RuntimeException e) {
            ok(e.getMessage() != null && e.getMessage().contains(error), "wrong error");
            return;
        }
        throw new AssertionError("accepted forbidden channel read: " + error);
    }
    private static final Context VALID = new Context(true, true, false, false);
    private static Read admit(String text) {
        return parse_and_check(text, "in_ch", Type.CHECKED_RESULT_OPTION_INT, VALID);
    }
    public static void main(String[] ignored) {
        Read a = admit("<< in_ch");
        ok(a.syntax() == Syntax.PREFIX && a.exactlyOnce(), "prefix single read");
        ok(a.nonparking() && a.cooperate(), "nonparking cooperative plan");
        ok(admit("return << in_ch").syntax() == Syntax.PREFIX, "return checked expression");
        ok(admit("val result = << in_ch").syntax() == Syntax.PREFIX, "value read binding");
        ok(admit("val result << in_ch").syntax() == Syntax.DECLARATION_FIRST,
           "declaration-first read");
        ok(admit("readch in_ch").syntax() == Syntax.READCH, "explicit readch alias");

        Read postfix = admit("(in_ch >>)");
        ok(postfix.syntax() == Syntax.POSTFIX, "checked terminal postfix");
        ok(postfix.resultType() == a.resultType(), "same Result<Option<int>,IoError> type");
        ok(postfix.exactlyOnce() && postfix.nonparking() && postfix.cooperate(),
           "postfix uses same single-read cooperative semantics");
        ok(admit("in_ch >>").syntax() == Syntax.POSTFIX, "bare postfix expression");
        ok(admit("return (in_ch >>)").syntax() == Syntax.POSTFIX, "parenthesized return");
        ok(admit("return in_ch >>").syntax() == Syntax.POSTFIX, "bare return");
        ok(admit("^ (in_ch >>)").syntax() == Syntax.POSTFIX, "caret return");
        ok(admit("val result = (in_ch >>)").syntax() == Syntax.POSTFIX,
           "name-first initializer");
        ok(admit("val int answer = (in_ch >>)").syntax() == Syntax.POSTFIX,
           "type-first initializer retains checked outcome");
        ok(admit("in_ch >> val item").syntax() == Syntax.DIRECTIONAL_BINDING,
           "directional immutable binder");
        ok(admit("in_ch >> let item").syntax() == Syntax.DIRECTIONAL_BINDING,
           "directional mutable binder");
        reject("E_IO_SYNTAX", () -> admit("in_ch > >"));
        reject("E_IO_SYNTAX", () -> admit("in_ch >>> 1"));
        reject("E_IO_SYNTAX", () -> admit("in_ch >> 1"));
        reject("E_IO_SYNTAX", () -> admit("1 >> 2"));
        reject("E_IO_SYNTAX", () -> admit("in_ch >> const item"));
        reject("E_IO_SYNTAX", () -> admit("in_ch >> val item >> val again"));
        reject("E_IO_SYNTAX", () -> admit("val result = (in_ch > >)"));
        reject("E_IO_READABLE_CAPABILITY", () -> admit("return (foreign >>)"));
        reject("E_IO_PERMISSION", () -> parse_and_check("(in_ch >>)", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(true, false, false, false)));
        reject("E_IO_SUSPENDS", () -> parse_and_check("in_ch >> val item", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(false, true, false, false)));
        reject("E_BORROW_AWAIT", () -> parse_and_check("(in_ch >>)", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(true, true, true, false)));
        reject("E_CONST_EFFECT", () -> parse_and_check("in_ch >> val item", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(true, true, false, true)));
        reject("E_IO_NO_IMPLICIT_UNWRAP", () -> parse_and_check("val int answer = (in_ch >>)",
            "in_ch", Type.INT, VALID));
        reject("E_IO_DISCARD", () -> parse_and_check("(in_ch >>)", "in_ch",
            Type.VOID, VALID));

        ok(CheckedOutcome.some(0).is_some() && CheckedOutcome.some(0).value() == 0,
           "zero payload is Some, not EOF");
        ok(CheckedOutcome.eof().is_eof(), "EOF separate from integer zero");
        ok(CheckedOutcome.error("closed").is_error(), "error separate from EOF");
        reject("E_IO_SYNTAX", () -> admit("sink << 7"));
        reject("E_IO_SYNTAX", () -> admit("<< 17"));
        reject("E_IO_SYNTAX", () -> admit("total += (<< in_ch)"));
        reject("E_IO_READABLE_CAPABILITY", () -> admit("<< foreign"));
        reject("E_IO_PERMISSION", () -> parse_and_check("<< in_ch", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(true, false, false, false)));
        reject("E_IO_SUSPENDS", () -> parse_and_check("<< in_ch", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(false, true, false, false)));
        reject("E_BORROW_AWAIT", () -> parse_and_check("<< in_ch", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(true, true, true, false)));
        reject("E_CONST_EFFECT", () -> parse_and_check("val item << in_ch", "in_ch",
            Type.CHECKED_RESULT_OPTION_INT, new Context(true, true, false, true)));
        reject("E_IO_NO_IMPLICIT_UNWRAP", () -> parse_and_check("<< in_ch", "in_ch",
            Type.INT, VALID));
        reject("E_IO_NO_IMPLICIT_UNWRAP", () -> parse_and_check("readch in_ch", "in_ch",
            Type.OPTION_INT, VALID));
        reject("E_IO_DISCARD", () -> parse_and_check("<< in_ch", "in_ch",
            Type.VOID, VALID));
        System.out.println("ATTO_CHANNEL_READ_PREHIR_PASS checks=" + checks);
    }
}
