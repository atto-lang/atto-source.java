package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HOST_ONLY: synthetic signatures, NEVER AMD-signed endorsement verification.
 * Neither success here nor an unverified policy match can admit a TenantVM.
 */
final class SevSnpReportV1Test {
    private static byte[] fill(int n, int value) {
        byte[] data = new byte[n];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static SevSnpReportV1.Policy policy() {
        return new SevSnpReportV1.Policy(
                fill(64, 9), fill(48, 7), new SevSnpReportV1.TcbFloor(1, 1, 1, 1),
                SevSnpReportV1.Signer.VCEK, 2, 5);
    }

    private static byte[] report() {
        byte[] report = new byte[SevSnpReportV1.REPORT_BYTES];
        report[0] = 3;
        report[0x34] = 1;
        System.arraycopy(fill(64, 9), 0, report, SevSnpReportV1.REPORT_DATA_OFFSET, 64);
        System.arraycopy(fill(48, 7), 0, report, SevSnpReportV1.MEASUREMENT_OFFSET, 48);
        report[0x180] = 1;
        report[0x181] = 1;
        report[0x186] = 1;
        report[0x187] = 1;
        return report;
    }

    @Test
    void rawReportPolicyIsNotAnAmdAttestation() {
        byte[] report = report();
        var p = policy();
        assertEquals(SevSnpReportV1.Result.UNVERIFIED_POLICY_MATCH,
                SevSnpReportV1.inspect_unverified_report(report, p));
        assertEquals(SevSnpReportV1.Result.REJECTED_SIZE,
                SevSnpReportV1.inspect_unverified_report(Arrays.copyOf(report, 1000), p));

        report[0x0a] = 8;
        assertEquals(SevSnpReportV1.Result.REJECTED_DEBUG,
                SevSnpReportV1.inspect_unverified_report(report, p));
        report[0x0a] = 0;
        report[0x30] = 1;
        assertEquals(SevSnpReportV1.Result.REJECTED_VMPL,
                SevSnpReportV1.inspect_unverified_report(report, p));
        report[0x30] = 0;
        report[0x34] = 2;
        assertEquals(SevSnpReportV1.Result.REJECTED_ALGORITHM,
                SevSnpReportV1.inspect_unverified_report(report, p));
        report[0x34] = 1;
        report[0x48] = 1;
        assertEquals(SevSnpReportV1.Result.REJECTED_SIGNER,
                SevSnpReportV1.inspect_unverified_report(report, p));
        report[0x48] = 0;
        report[0x187] = 0;
        assertEquals(SevSnpReportV1.Result.REJECTED_TCB,
                SevSnpReportV1.inspect_unverified_report(report, p));
        report[0x187] = 1;
        report[0x90] ^= 1;
        assertEquals(SevSnpReportV1.Result.REJECTED_MEASUREMENT,
                SevSnpReportV1.inspect_unverified_report(report, p));
        report[0x90] ^= 1;
        report[0x50] ^= 1;
        assertEquals(SevSnpReportV1.Result.REJECTED_REPORT_DATA,
                SevSnpReportV1.inspect_unverified_report(report, p));
        assertEquals(SevSnpReportV1.Result.UNVERIFIED_POLICY_MATCH,
                SevSnpReportV1.inspect_unverified_report(report(), p));
    }

    private static void derToAmdSignature(byte[] der, byte[] report) {
        int cursor = 2;
        assertEquals(0x30, der[0] & 255);
        for (int offset : new int[] { 0x2a0, 0x2e8 }) {
            assertEquals(0x02, der[cursor++] & 255);
            int count = der[cursor++] & 255;
            byte[] encoded = Arrays.copyOfRange(der, cursor, cursor + count);
            cursor += count;
            byte[] big = new BigInteger(1, encoded).toByteArray();
            for (int i = 0; i < 48; ++i) {
                report[offset + i] = i < big.length ? big[big.length - 1 - i] : 0;
            }
        }
    }

    @Test
    void p384SignaturePrimitiveRejectsTamperingAndWrongKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        var signer = generator.generateKeyPair();
        var wrong = generator.generateKeyPair();
        byte[] report = report();
        var signature = Signature.getInstance("SHA384withECDSA");
        signature.initSign(signer.getPrivate());
        signature.update(report, 0, SevSnpReportV1.SIGNED_BYTES);
        derToAmdSignature(signature.sign(), report);
        assertTrue(SevSnpReportV1.verify_signature_only(report, signer.getPublic()));
        assertFalse(SevSnpReportV1.verify_signature_only(report, wrong.getPublic()));
        report[0x50] ^= 1;
        assertFalse(SevSnpReportV1.verify_signature_only(report, signer.getPublic()));
        report[0x50] ^= 1;
        report[0x2a0 + 60] = 1;
        assertFalse(SevSnpReportV1.verify_signature_only(report, signer.getPublic()));
        report[0x2a0 + 60] = 0;
        report[0x400] = 1;
        assertFalse(SevSnpReportV1.verify_signature_only(report, signer.getPublic()));
    }

    @Test
    void reportDataTranscriptIsStableAndDependsOnEveryInput() {
        byte[] nonce = fill(32, 9);
        byte[] identity = fill(32, 3);
        byte[] spki = new byte[] { 0x30, 3, 1, 2, 3 }; // synthetic SPKI placeholder
        byte[] result = SevSnpReportV1.compute_bound_report_data(nonce, identity, spki);
        assertEquals(64, result.length);
        assertEquals(
                "f122440e23e256756d7eae6eb32372deee5ffe37683811dd05b5321cf3800ccba025dc18d8bd0d52aa24eb70fe7787a4fe2cba19a8e3357828c24e6c7804b839",
                HexFormat.of().formatHex(result));
        nonce[0] ^= 1;
        assertFalse(Arrays.equals(result, SevSnpReportV1.compute_bound_report_data(nonce, identity, spki)));
        nonce[0] ^= 1;
        identity[0] ^= 1;
        assertFalse(Arrays.equals(result, SevSnpReportV1.compute_bound_report_data(nonce, identity, spki)));
        identity[0] ^= 1;
        spki[4] ^= 1;
        assertFalse(Arrays.equals(result, SevSnpReportV1.compute_bound_report_data(nonce, identity, spki)));
        assertThrows(IllegalArgumentException.class,
                () -> SevSnpReportV1.compute_bound_report_data(nonce, identity, new byte[0]));
    }
}
