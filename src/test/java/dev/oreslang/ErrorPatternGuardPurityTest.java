package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Error-domain match guards are observational predicates: they cannot mutate
 * state, call arbitrary user code or trigger exceptional/suspending control
 * flow. Generic async match guards retain their established semantics.
 */
final class ErrorPatternGuardPurityTest {
    private static final String SOURCE = """
            define class Error as
            end
            fnc sideEffect(): bool {
              return true;
            }
            """;

    @Test
    void sideEffectingCallableCannotRunDuringOrderedErrorDispatch() {
        String bad = SOURCE + """
                fnc classify(Error error): int {
                  match error over
                    on Error e when sideEffect() -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void nestedUserCallableIsNotMadePureByBooleanShortCircuit() {
        String bad = SOURCE + """
                fnc classify(Error error, bool enabled): int {
                  match error over
                    on Error e when enabled && sideEffect() -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void errorValueMatchHasExactlyTheSameGuardPurityBoundary() {
        String bad = SOURCE + """
                fnc classify(Error error): int {
                  return match error over {
                    on Error e when sideEffect() { return 1; }
                    on Error rest { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void safeBooleanPredicatesRemainLegalForBothMatchForms() {
        String good = SOURCE + """
                fnc classify(Error error, int attempts, bool enabled): int {
                  match error over
                    on Error e when enabled && attempts > 0 -> { return 1; }
                    on Error remaining -> { return 0; }
                  end
                }

                fnc classifyValue(Error error, int attempts, bool enabled): int {
                  return match error over {
                    on Error e when enabled && attempts > 0 { return 1; }
                    on Error remaining { return 0; }
                  };
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(good)));
    }

    @Test
    void nonErrorValueMatchRetainsExistingSuspendableAndCallableGuardSemantics() {
        // This change must not invalidate ValueMatchSuspensionTest's existing
        // async guard continuation contract. The Error-domain restriction is
        // deliberately narrower than a blanket match-expression policy.
        String valid = SOURCE + """
                fnc classify(bool selected): int {
                  return match selected over {
                    on true when sideEffect() { return 1; }
                    on _ { return 0; }
                  };
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void inheritedErrorSubjectAlsoUsesPureGuardAdmission() {
        String invalid = SOURCE + """
                define class IOError extends Error as
                end
                fnc classify(IOError failure): int {
                  match failure over
                    on IOError io when sideEffect() -> { return 1; }
                    on IOError rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void explicitErrorArmCannotBypassPolicyThroughBroadAnyScrutinee() {
        String invalid = SOURCE + """
                fnc classify(Any unknown): int {
                  match unknown over
                    on Error error when sideEffect() -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void broadAnyWildcardGuardBeforeExplicitErrorArmIsRejected() {
        String bad = SOURCE + """
                fnc classify(Any unknown): int {
                  match unknown over
                    on _ when sideEffect() -> { return 9; }
                    on Error error -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void broadAnyValueMatchWildcardGuardBeforeErrorArmIsRejected() {
        String bad = SOURCE + """
                fnc classify(Any unknown): int {
                  return match unknown over {
                    on _ when sideEffect() { return 9; }
                    on Error error { return 1; }
                    on _ { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void broadAnyMatchWithoutAnErrorArmKeepsExistingCallableGuardSemantics() {
        String valid = SOURCE + """
                fnc classify(Any unknown): int {
                  match unknown over
                    on _ when sideEffect() -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void recursiveOptionEqualityCannotRunInsideErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, Option<int> left, Option<int> right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void nestedResultEqualityCannotRunInsideValueMatchErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, Result<int, int> left, Result<int, int> right): int {
                  return match error over {
                    on Error e when left == right { return 1; }
                    on Error rest { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void nonErrorOptionEqualityRetainsExistingValueMatchSemantics() {
        String valid = SOURCE + """
                fnc classify(bool flag, Option<int> left, Option<int> right): int {
                  return match flag over {
                    on true when left == right { return 1; }
                    on _ { return 0; }
                  };
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void recursiveListEqualityCannotRunInsideErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, List<int> left, List<int> right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void broadAnyPreErrorGuardRejectsRecursiveEquality() {
        String invalid = SOURCE + """
                fnc classify(Any unknown, Option<int> left, Option<int> right): int {
                  match unknown over
                    on _ when left == right -> { return 9; }
                    on Error error -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void typedGuardBindingRemainsAvailableToRhsEqualityComparison() {
        String valid = SOURCE + """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                fnc classify(Error error, Animal animal): int {
                  match error over
                    on Error e when animal is type Dog dog && dog == animal -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void errorGuardPatternTestCannotWalkNestedOptionPayload() {
        String invalid = SOURCE + """
                fnc classify(Error error, Option<int> candidate): int {
                  match error over
                    on Error e when candidate matches Some(_) -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void errorValueMatchAlsoRejectsRecursivePatternGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, Option<int> candidate): int {
                  return match error over {
                    on Error e when candidate matches Some(_) { return 1; }
                    on Error rest { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void nonErrorValueMatchRetainsPatternGuardCapability() {
        String valid = SOURCE + """
                fnc classify(bool enabled, Option<int> candidate): int {
                  return match enabled over {
                    on true when candidate matches Some(_) { return 1; }
                    on _ { return 0; }
                  };
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void structuralTypeTestCannotTraverseFieldsInsideErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, Any candidate): int {
                  match error over
                    on Error e when candidate is type { amount: int } -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void nominalTypeTestRemainsAllowedInsideErrorGuard() {
        String valid = SOURCE + """
                fnc classify(Error error, Error candidate): int {
                  match error over
                    on Error e when candidate is type Error -> { return 1; }
                    on Error rest -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void ordinaryIntegerEqualityCannotInheritAnErasedBigintBound() {
        String invalid = SOURCE + """
                fnc classify(Error error, int left, int right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void arbitraryPrecisionBigintEqualityCannotRunInsideErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, bigint left, bigint right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void stringEqualityCannotProveConstantTimeErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, string left, string right): int {
                  return match error over {
                    on Error e when left == right { return 1; }
                    on Error other { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void arbitraryPrecisionDecimalEqualityCannotProveConstantTimeErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, decimal left, decimal right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void nominalErrorIdentityEqualityRemainsAvailable() {
        String valid = SOURCE + """
                fnc classify(Error error, Error candidate): int {
                  match error over
                    on Error e when candidate == error -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void booleanEqualityRemainsAvailableInsideErrorGuard() {
        String valid = SOURCE + """
                fnc classify(Error error, bool left, bool right): int {
                  return match error over {
                    on Error e when left == right { return 1; }
                    on Error other { return 0; }
                  };
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void stringOrderingCannotScanUnboundedTextInErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, string left, string right): int {
                  match error over
                    on Error e when left < right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality/type checks"), failure.getMessage());
    }

    @Test
    void decimalOrderingCannotRunInErrorValueGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, decimal left, decimal right): int {
                  return match error over {
                    on Error e when left >= right { return 1; }
                    on Error other { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality/type checks"), failure.getMessage());
    }

    @Test
    void broadAnyGuardBeforeErrorArmCannotCompareStrings() {
        String invalid = SOURCE + """
                fnc classify(Any unknown, string left, string right): int {
                  match unknown over
                    on _ when left > right -> { return 2; }
                    on Error err -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality/type checks"), failure.getMessage());
    }

    @Test
    void floatOrderingRemainsAdmittedInsideErrorGuard() {
        String valid = SOURCE + """
                fnc classify(Error error, float left, float right): int {
                  match error over
                    on Error e when left < right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void nonErrorStringOrderingRetainsExistingSemantics() {
        String valid = SOURCE + """
                fnc classify(bool enabled, string left, string right): int {
                  match enabled over
                    on true when left < right -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void errorReceiverSelfWildcardGuardCannotCallUserCode() {
        String invalid = """
                define class Error as
                  pub classify(): int {
                    match self over
                      on _ when sideEffect() -> { return 1; }
                      on _ -> { return 0; }
                    end
                  }
                end
                fnc sideEffect(): bool {
                  return true;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void errorDescendantReceiverSelfValueMatchCannotCallUserCode() {
        String invalid = SOURCE + """
                define class IOError extends Error as
                  pub classify(): int {
                    return match self over {
                      on _ when sideEffect() { return 1; }
                      on _ { return 0; }
                    };
                  }
                end
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void nonErrorReceiverSelfStillSupportsExistingCallableGuards() {
        String valid = SOURCE + """
                define class Ordinary as
                  pub classify(): int {
                    match self over
                      on _ when sideEffect() -> { return 1; }
                      on _ -> { return 0; }
                    end
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void optionalCastNestedInsideNominalTestCannotAllocateDuringErrorDispatch() {
        String invalid = SOURCE + """
                fnc classify(Error error, Error candidate): int {
                  match error over
                    on Error e when (candidate as? Error) is type Option<Error> -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void errorValueGuardAlsoRejectsNestedAllocatingOptionalCast() {
        String invalid = SOURCE + """
                fnc classify(Error error, Error candidate): int {
                  return match error over {
                    on Error e when (candidate as? Error) is type Option<Error> { return 1; }
                    on Error other { return 0; }
                  };
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }

    @Test
    void ordinaryMatchStillAllowsOptionalCastNestedInGuardTypeTest() {
        String valid = SOURCE + """
                fnc classify(bool selected, Error candidate): int {
                  match selected over
                    on true when (candidate as? Error) is type Option<Error> -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void mixedComplexFloatEqualityCannotAllocateInsideErrorGuard() {
        String invalid = SOURCE + """
                fnc classify(Error error, complex left, float right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(invalid)));
        assertTrue(failure.getMessage().contains("bounded equality"), failure.getMessage());
    }

    @Test
    void homogeneousComplexEqualityRemainsAdmittedInsideErrorGuard() {
        String valid = SOURCE + """
                fnc classify(Error error, complex left, complex right): int {
                  match error over
                    on Error e when left == right -> { return 1; }
                    on Error other -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void ordinaryMatchRetainsMixedComplexFloatEquality() {
        String valid = SOURCE + """
                fnc classify(bool enabled, complex left, float right): int {
                  match enabled over
                    on true when left == right -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void errorReceiverSelfIdentityEqualityIsBoundedAndAccepted() {
        String valid = SOURCE + """
                define class IOError extends Error as
                  pub classify(): int {
                    match self over
                      on _ when self == self -> { return 1; }
                      on _ -> { return 0; }
                    end
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void errorReceiverSelfEqualityWithNominalCandidateIsAccepted() {
        String valid = SOURCE + """
                define class IOError extends Error as
                  pub classify(IOError candidate): int {
                    return match self over {
                      on _ when self == candidate { return 1; }
                      on _ { return 0; }
                    };
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
    }

    @Test
    void guardDivisionCannotSmuggleAnExceptionalArithmeticOperation() {
        String bad = SOURCE + """
                fnc classify(Error error, int divisor): int {
                  match error over
                    on Error e when (10 / divisor) > 1 -> { return 1; }
                    on Error remaining -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(bad)));
        assertTrue(failure.getMessage().contains("match guard must be pure/read-only"),
                failure.getMessage());
    }
}
