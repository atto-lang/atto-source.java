package dev.oreslang.parser;

public record Token(Type type, String lexeme, int line, int column) {
    public enum Type {
        IDENT,
        INT,
        FLOAT,
        IMAG,
        STRING,

        DEFINE, CLASS, MODULE, IMPORT, FROM, AS, EXTENDS, IMPLEMENTS,
        TRY, CATCH, FINALLY, END, FI, IF, DO, ELSE, THEN,
        NEW, DONE, AWAIT, ASYNC, DEF, FNC, ROUTINE, FOR, OF, YIELD, SUPER, ELSEIF, SWITCH, TYPE,
        INTERFACE, IMPL, ABSTRACT, VOID, STATIC, PUB, PRIVATE, RETURN, DEFER,
        VAL, CONST, LET, SELF, TRUE, FALSE, NULL, OBJ, ARR,

        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
        COMMA, DOT, SEMICOLON, COLON, QUESTION, AT,
        PLUS, MINUS, STAR, SLASH, PERCENT, PIPE, BANG,
        EQUAL, EQUAL_EQUAL, BANG_EQUAL,
        LT, LTE, GT, GTE,
        ARROW, FAT_ARROW,
        EOF
    }
}
