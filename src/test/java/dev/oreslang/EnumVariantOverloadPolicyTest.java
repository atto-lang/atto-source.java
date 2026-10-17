package dev.oreslang;

import org.junit.jupiter.api.Test;

/** CI entrypoint for the standalone, release-active compiler-policy oracle. */
final class EnumVariantOverloadPolicyTest {
    @Test
    void soundSameArityEnumAndVariantSpecialization() {
        EnumVariantOverloadPolicySmoke.main(new String[0]);
    }
}
