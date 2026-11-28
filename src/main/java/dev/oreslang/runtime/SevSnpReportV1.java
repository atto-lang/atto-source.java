package dev.oreslang.runtime;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;
import java.util.Objects;

/**
 * Raw AMD SEV-SNP report parser and a signature-only verification primitive.
 * Neither a matching report nor a valid signature with a caller-supplied key
 * authenticates AMD hardware: endorsements, pinned AMD roots, VCEK/VLEK TCB
 * extension/chip identity, revocation and trusted key release are NOT wired.
 * This helper MUST NOT authorize confidential TenantVM admission or secrets.
 */
public final class SevSnpReportV1 {
    private SevSnpReportV1() {}
    public static final int REPORT_BYTES = 0x4a0;
    public static final int SIGNED_BYTES = 0x2a0;
    public static final int REPORT_DATA_OFFSET = 0x50;
    public static final int MEASUREMENT_OFFSET = 0x90;
    private static final int SIGNATURE_R_OFFSET = 0x2a0;
    private static final int SIGNATURE_S_OFFSET = 0x2e8;
    private static final int SIGNATURE_ELEMENT_BYTES = 72;

    public enum Signer { VCEK, VLEK }
    public enum Result {
        UNVERIFIED_POLICY_MATCH, REJECTED_SIZE, REJECTED_VERSION,
        REJECTED_ALGORITHM, REJECTED_SIGNER, REJECTED_VMPL, REJECTED_DEBUG,
        REJECTED_REPORT_DATA, REJECTED_MEASUREMENT, REJECTED_TCB, REJECTED_POLICY
    }
    public record TcbFloor(int bootloader, int tee, int snp, int microcode) {
        public TcbFloor {
            if ((bootloader | tee | snp | microcode) < 0 ||
                bootloader > 255 || tee > 255 || snp > 255 || microcode > 255) {
                throw new IllegalArgumentException("invalid TCB floor");
            }
        }
    }
    public record Policy(byte[] expected_report_data, byte[] expected_measurement,
                         TcbFloor minimum_tcb, Signer signer,
                         int min_version, int max_version) {
        public Policy {
            expected_report_data = Objects.requireNonNull(expected_report_data).clone();
            expected_measurement = Objects.requireNonNull(expected_measurement).clone();
            Objects.requireNonNull(minimum_tcb);
            Objects.requireNonNull(signer);
            if (expected_report_data.length != 64 || expected_measurement.length != 48 ||
                    all_zero(expected_report_data) || all_zero(expected_measurement) ||
                    (minimum_tcb.bootloader() == 0 && minimum_tcb.tee() == 0 &&
                     minimum_tcb.snp() == 0 && minimum_tcb.microcode() == 0) ||
                    min_version < 2 || max_version > 5 || min_version > max_version) {
                throw new IllegalArgumentException("invalid SNP policy");
            }
        }
        @Override public byte[] expected_report_data() { return expected_report_data.clone(); }
        @Override public byte[] expected_measurement() { return expected_measurement.clone(); }
    }
    private static int u8(byte b) { return b & 0xff; }
    private static long u32(byte[] b, int pos) {
        return Integer.toUnsignedLong(u8(b[pos]) | u8(b[pos+1]) << 8 |
                u8(b[pos+2]) << 16 | u8(b[pos+3]) << 24);
    }
    private static long u64(byte[] b, int pos) {
        return u32(b, pos) | u32(b, pos + 4) << 32;
    }
    private static boolean all_zero(byte[] b) {
        int n = 0;
        for (byte value : b) n |= value;
        return n == 0;
    }
    /** Policy match from raw *untrusted* bytes, not proof of signed endorsement. */
    public static Result inspect_unverified_report(byte[] report, Policy policy) {
        Objects.requireNonNull(policy, "policy");
        if (report == null || report.length != REPORT_BYTES) return Result.REJECTED_SIZE;
        long version = u32(report, 0);
        if (version < policy.min_version() || version > policy.max_version())
            return Result.REJECTED_VERSION;
        if (u32(report, 0x34) != 1) return Result.REJECTED_ALGORITHM;
        if (u32(report, 0x30) != 0) return Result.REJECTED_VMPL;
        long signer = u32(report, 0x48);
        if ((signer & 3) != policy.signer().ordinal() || (signer & ~0xffL) != 0)
            return Result.REJECTED_SIGNER;
        if ((u64(report, 0x08) & (1L << 19)) != 0) return Result.REJECTED_DEBUG;
        if (!MessageDigest.isEqual(Arrays.copyOfRange(report, REPORT_DATA_OFFSET,
                        REPORT_DATA_OFFSET + 64), policy.expected_report_data))
            return Result.REJECTED_REPORT_DATA;
        if (!MessageDigest.isEqual(Arrays.copyOfRange(report, MEASUREMENT_OFFSET,
                        MEASUREMENT_OFFSET + 48), policy.expected_measurement))
            return Result.REJECTED_MEASUREMENT;
        TcbFloor floor = policy.minimum_tcb();
        if (u8(report[0x180]) < floor.bootloader || u8(report[0x181]) < floor.tee ||
                u8(report[0x186]) < floor.snp || u8(report[0x187]) < floor.microcode)
            return Result.REJECTED_TCB;
        return Result.UNVERIFIED_POLICY_MATCH;
    }
    /** Host-side transcript binding; never independently authenticates a TEE. */
    public static byte[] compute_bound_report_data(byte[] nonce, byte[] image_identity,
                                                   byte[] ephemeral_spki_der) {
        if (nonce == null || nonce.length != 32 || all_zero(nonce) ||
                image_identity == null || image_identity.length != 32 || all_zero(image_identity) ||
                ephemeral_spki_der == null || ephemeral_spki_der.length == 0 ||
                ephemeral_spki_der.length > 4096) {
            throw new IllegalArgumentException("invalid SNP transcript binding");
        }
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update("atto-sev-snp-report-data-v1".getBytes(StandardCharsets.US_ASCII));
            sha512.update(nonce);
            sha512.update(image_identity);
            int n = ephemeral_spki_der.length;
            sha512.update(new byte[] { (byte)(n >>> 24), (byte)(n >>> 16),
                    (byte)(n >>> 8), (byte)n });
            sha512.update(ephemeral_spki_der);
            return sha512.digest();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-512 not supported", unavailable);
        }
    }

    /**
     * Verify signed bytes with a PRE-AUTHENTICATED secp384r1 key.
     * Key validity/AMD provenance is NOT established here. Never grant access
     * solely from this boolean, even for a cryptographically valid signature.
     */
    public static boolean verify_signature_only(byte[] report, PublicKey authenticated_key) {
        if (report == null || report.length != REPORT_BYTES ||
                !(authenticated_key instanceof ECPublicKey ec) || u32(report, 0x34) != 1)
            return false;
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp384r1"));
            ECParameterSpec target = params.getParameterSpec(ECParameterSpec.class);
            ECParameterSpec actual = ec.getParams();
            if (!target.getCurve().equals(actual.getCurve()) ||
                    !target.getGenerator().equals(actual.getGenerator()) ||
                    !target.getOrder().equals(actual.getOrder()) ||
                    target.getCofactor() != actual.getCofactor()) return false;
            for (int i = 48; i < SIGNATURE_ELEMENT_BYTES; ++i) {
                if (report[SIGNATURE_R_OFFSET + i] != 0 ||
                        report[SIGNATURE_S_OFFSET + i] != 0) return false;
            }
            for (int i = SIGNATURE_S_OFFSET + SIGNATURE_ELEMENT_BYTES;
                    i < report.length; ++i) if (report[i] != 0) return false;
            byte[] r = der_integer(report, SIGNATURE_R_OFFSET);
            byte[] s = der_integer(report, SIGNATURE_S_OFFSET);
            int seq_size = r.length + s.length;
            if (seq_size > 127) return false;
            byte[] der = new byte[2 + seq_size];
            der[0] = 0x30; der[1] = (byte) seq_size;
            System.arraycopy(r, 0, der, 2, r.length);
            System.arraycopy(s, 0, der, 2 + r.length, s.length);
            Signature verifier = Signature.getInstance("SHA384withECDSA");
            verifier.initVerify(authenticated_key);
            verifier.update(report, 0, SIGNED_BYTES);
            return verifier.verify(der);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException invalid) {
            return false;
        }
    }
    private static byte[] der_integer(byte[] little_endian, int offset) {
        byte[] unsigned = new byte[48];
        for (int i = 0; i < 48; ++i) unsigned[47 - i] = little_endian[offset + i];
        byte[] value = new BigInteger(1, unsigned).toByteArray();
        byte[] der = new byte[2 + value.length];
        der[0] = 0x02;
        der[1] = (byte) value.length;
        System.arraycopy(value, 0, der, 2, value.length);
        return der;
    }
}
