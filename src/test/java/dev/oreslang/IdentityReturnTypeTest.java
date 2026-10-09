package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IdentityReturnTypeTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }
    private static void rejects(String source, String message) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(message), failure::getMessage);
    }

    @Test void exactParameterAndAliasAccepted() {
        accepts("""
                define module m
                  define class T as
                  end
                  pub fnc decorate(T v): v {
                    val original = v;
                    return original;
                  }
                end
                """);
    }

    @Test void replacementInstanceRejected() {
        rejects("""
                define module m
                  define class T as
                  end
                  pub fnc decorate(T v): v {
                    return new T();
                  }
                end
                """, "identity return mismatch");
    }

    @Test void replacementInOneBranchRejected() {
        rejects("""
                define module m
                  define class T as
                  end
                  pub fnc decorate(T v, bool flag): v {
                    if flag {
                      return v;
                    } else {
                      return new T();
                    } fi
                  }
                end
                """, "identity return mismatch");
    }

    @Test void transitiveIdentityFunctionCallAccepted() {
        accepts("""
                define module m
                  define class T as
                  end
                  pub fnc relay(T x): x { return x; }
                  pub fnc decorate(T v): v { return relay(v); }
                end
                """);
    }

    @Test void nominallyTypedHelperCannotForgeProof() {
        rejects("""
                define module m
                  define class T as
                  end
                  pub fnc replace(T x): T { return new T(); }
                  pub fnc decorate(T v): v { return replace(v); }
                end
                """, "identity return mismatch");
    }

    @Test void receiverIdentityIsStricterThanPolymorphicSelf() {
        accepts("""
                define module m
                  define class T as
                    pub same(): this { return self; }
                  end
                end
                """);
        rejects("""
                define module m
                  define class T as
                    pub same(): this { return new T(); }
                  end
                end
                """, "return value");
        rejects("""
                define module m
                  define class T as
                    pub choose(self other): this { return other; }
                  end
                end
                """, "identity return mismatch");
    }

    @Test void mutableDecoratorCanUpdateTheOriginalInstance() {
        accepts("""
                define module m
                  define class T as
                    pub val string foo = "old";
                  end
                  pub fnc decorate(T mut v): v {
                    v.foo = "bar";
                    return v;
                  }
                end
                """);
    }

    @Test void mutableParameterCannotBeReboundToAnotherInstance() {
        rejects("""
                define module m
                  define class T as
                  end
                  pub fnc decorate(T mut v): v {
                    v = new T();
                    return v;
                  }
                end
                """, "identity return rebind");
    }

    @Test void nestedExpressionCannotRebindOriginalInstance() {
        rejects("""
                define module m
                  define class T as
                  end
                  pub fnc decorate(T mut v): v {
                    val x = (v = new T());
                    return v;
                  }
                end
                """, "identity return rebind");
    }

    @Test void inheritedExactIdentityContractCannotBeWeakened() {
        rejects("""
                define module m
                  define class Base as
                    pub same(): this { return self; }
                  end
                  define class Child extends Base as
                    pub same(): self { return self; }
                  end
                end
                """, "cannot weaken inherited identity return");
    }

    @Test void inheritedExactIdentityContractMayBePreserved() {
        accepts("""
                define module m
                  define class Base as
                    pub same(): this { return self; }
                  end
                  define class Child extends Base as
                    pub same(): this { return self; }
                  end
                end
                """);
    }

    @Test void nominalReturnMayReplaceTheInstance() {
        accepts("""
                define module m
                  define class T as
                  end
                  pub fnc replace(T v): T { return new T(); }
                end
                """);
    }

    @Test void mutableDecoratorPreservesRuntimeReferenceIdentity() throws Exception {
        String program = """
                define class T as
                  pub val string foo = "old";
                end

                pub fnc decorate(T mut v): v {
                  v.foo = "bar";
                  return v;
                }

                pub fnc main(): void {
                  val T original = new T();
                  val T result = decorate(original);
                  stdio.println(original is result);
                  stdio.println(original.foo);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "identity-decorator.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("true\nbar\n", output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test void shadowedAliasDoesNotProveIdentity() {
        rejects("""
                define module m
                  define class T as
                  end
                  pub fnc decorate(T v): v {
                    if true {
                      val v = new T();
                      return v;
                    } fi
                    return v;
                  }
                end
                """, "identity return mismatch");
    }
}
