package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Host-only, offline AMD VCEK endorsement verification.
 *
 * ARK DER SHA-256 pin must be an authenticated deployment policy value
 * obtained independently from AMD, NOT from the guest/report/cert bundle.
 * Requires an issuer CRL; unavailable/revoked/expired certificates reject.
 * Intentionally does NOT permit VLEK or Turin structVersion=1 in v1.
 *
 * This cannot authorize confidential TenantVM admission or key release.
 * Host report collection, TLS key possession, product-specific root pin
 * management, all-issuer revocation and genuine SNP hardware tests remain
 * separate prerequisites.
 */
public final class SevSnpVcekEndorsement {
    private SevSnpVcekEndorsement() {}

    public enum Outcome {
        UNVERIFIED_INPUT, UNSUPPORTED_SIGNER, UNTRUSTED_ROOT,
        INVALID_CERTIFICATES, MISSING_REVOCATION_EVIDENCE,
        CERTIFICATE_CHAIN_REJECTED, REPORT_POLICY_REJECTED,
        TCB_MISMATCH, CHIP_ID_MISMATCH, REPORT_SIGNATURE_REJECTED,
        ENDORSEMENT_CRYPTOGRAPHY_VALID
    }

    public record Bundle(byte[] ark_der, byte[] ask_der, byte[] vcek_der,
                         byte[] issuer_crl_der, byte[] root_issuer_crl_der,
                         byte[] expected_ark_sha256) {
        public Bundle {
            ark_der = clone_optional(ark_der);
            ask_der = clone_optional(ask_der);
            vcek_der = clone_optional(vcek_der);
            issuer_crl_der = clone_optional(issuer_crl_der);
            root_issuer_crl_der = clone_optional(root_issuer_crl_der);
            expected_ark_sha256 = clone_optional(expected_ark_sha256);
        }
        @Override public byte[] ark_der() { return clone_optional(ark_der); }
        @Override public byte[] ask_der() { return clone_optional(ask_der); }
        @Override public byte[] vcek_der() { return clone_optional(vcek_der); }
        @Override public byte[] issuer_crl_der() { return clone_optional(issuer_crl_der); }
        @Override public byte[] root_issuer_crl_der() { return clone_optional(root_issuer_crl_der); }
        @Override public byte[] expected_ark_sha256() { return clone_optional(expected_ark_sha256); }

        /** Compatibility: missing ASK-revocation CRL deliberately fails closed. */
        public Bundle(byte[] ark_der, byte[] ask_der, byte[] vcek_der,
                      byte[] issuer_crl_der, byte[] expected_ark_sha256) {
            this(ark_der, ask_der, vcek_der, issuer_crl_der, null, expected_ark_sha256);
        }
    }

    public static Outcome verify_vcek_offline(
            byte[] report, SevSnpReportV1.Policy policy, Bundle endorsements) {
        Objects.requireNonNull(policy, "policy");
        if (report == null || report.length != SevSnpReportV1.REPORT_BYTES ||
                endorsements == null) return Outcome.UNVERIFIED_INPUT;
        if (policy.signer() != SevSnpReportV1.Signer.VCEK ||
                (unsigned_le32(report, 0x48) & 3) != 0)
            return Outcome.UNSUPPORTED_SIGNER;
        if (!valid_root_pin(endorsements))
            return Outcome.UNTRUSTED_ROOT;
        try {
            var factory = CertificateFactory.getInstance("X.509");
            X509Certificate root = parse_cert(factory, endorsements.ark_der);
            X509Certificate ask = parse_cert(factory, endorsements.ask_der);
            X509Certificate vcek = parse_cert(factory, endorsements.vcek_der);
            if (root.getBasicConstraints() < 0 || ask.getBasicConstraints() < 0)
                return Outcome.INVALID_CERTIFICATES;
            if (endorsements.issuer_crl_der == null ||
                    endorsements.issuer_crl_der.length == 0 ||
                    endorsements.root_issuer_crl_der == null ||
                    endorsements.root_issuer_crl_der.length == 0)
                return Outcome.MISSING_REVOCATION_EVIDENCE;
            X509CRL crl = (X509CRL) factory.generateCRL(
                    new ByteArrayInputStream(endorsements.issuer_crl_der));
            X509CRL rootCrl = (X509CRL) factory.generateCRL(
                    new ByteArrayInputStream(endorsements.root_issuer_crl_der));
            var now = new java.util.Date();
            if (crl.getNextUpdate() == null || !crl.getNextUpdate().after(now) ||
                    rootCrl.getNextUpdate() == null || !rootCrl.getNextUpdate().after(now))
                return Outcome.MISSING_REVOCATION_EVIDENCE;
            // X509 path is leaf VCEK -> ASK -> authenticated/pinned ARK root.
            // Enable platform PKIX CRL checking (not certificate-only checks).
            // The trust-anchor pin is independent of this supplied bundle.
            CertPath path = factory.generateCertPath(List.of(vcek, ask));
            var params = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
            params.setRevocationEnabled(true);
            params.addCertStore(CertStore.getInstance("Collection",
                    new CollectionCertStoreParameters(List.of(crl, rootCrl))));
            CertPathValidator.getInstance("PKIX").validate(path, params);
            if (SevSnpReportV1.inspect_unverified_report(report, policy) !=
                    SevSnpReportV1.Result.UNVERIFIED_POLICY_MATCH)
                return Outcome.REPORT_POLICY_REJECTED;
            if (!tcb_matches(vcek, report)) return Outcome.TCB_MISMATCH;
            if (!chip_id_matches(vcek, report)) return Outcome.CHIP_ID_MISMATCH;
            if (!SevSnpReportV1.verify_signature_only(report, vcek.getPublicKey()))
                return Outcome.REPORT_SIGNATURE_REJECTED;
            // CRYPTOGRAPHY_VALID != TEE_ATTESTED. Never use as an admission.
            return Outcome.ENDORSEMENT_CRYPTOGRAPHY_VALID;
        } catch (java.security.cert.CRLException invalid) {
            return Outcome.MISSING_REVOCATION_EVIDENCE;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException invalid) {
            return Outcome.CERTIFICATE_CHAIN_REJECTED;
        }
    }

    private static X509Certificate parse_cert(CertificateFactory factory, byte[] der)
            throws GeneralSecurityException {
        if (der == null || der.length == 0 || der.length > 65536)
            throw new java.security.cert.CertificateException("missing/oversized cert");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean valid_root_pin(Bundle bundle) {
        if (bundle.ark_der == null || bundle.ark_der.length == 0 ||
                bundle.ark_der.length > 65536 || bundle.expected_ark_sha256 == null ||
                bundle.expected_ark_sha256.length != 32 ||
                all_zero(bundle.expected_ark_sha256)) return false;
        try {
            return MessageDigest.isEqual(
                    MessageDigest.getInstance("SHA-256").digest(bundle.ark_der),
                    bundle.expected_ark_sha256);
        } catch (GeneralSecurityException error) { return false; }
    }

    private static boolean tcb_matches(X509Certificate vcek, byte[] report) throws IOException {
        return integer_extension_matches(vcek, "1.3.6.1.4.1.3704.1.1", 0) &&
                integer_extension_matches(vcek, "1.3.6.1.4.1.3704.1.3.1", unsigned(report[0x180])) &&
                integer_extension_matches(vcek, "1.3.6.1.4.1.3704.1.3.2", unsigned(report[0x181])) &&
                integer_extension_matches(vcek, "1.3.6.1.4.1.3704.1.3.3", unsigned(report[0x186])) &&
                integer_extension_matches(vcek, "1.3.6.1.4.1.3704.1.3.8", unsigned(report[0x187]));
    }
    private static boolean integer_extension_matches(
            X509Certificate cert, String oid, int expected) throws IOException {
        byte[] value = get_extension_content(cert, oid);
        if (value == null || value.length < 3 || value.length > 16 ||
                value[0] != 2) return false;
        byte[] integer = read_der_content(value, 0x02);
        if (integer.length < 1 || integer.length > 2 ||
                (integer[0] & 0x80) != 0) return false;
        int decoded = 0;
        for (byte b : integer) decoded = (decoded << 8) | unsigned(b);
        return decoded == expected;
    }
    private static boolean chip_id_matches(X509Certificate cert, byte[] report)
            throws IOException {
        byte[] content = get_extension_content(cert, "1.3.6.1.4.1.3704.1.4");
        if (content == null) return false;
        // Family19h VCEK HWID appears either as raw 64 bytes or wrapped DER.
        if (content.length != 64) content = read_der_content(content, 0x04);
        return content.length == 64 && !all_zero(content) &&
                MessageDigest.isEqual(content, Arrays.copyOfRange(report, 0x1a0, 0x1e0));
    }
    private static byte[] get_extension_content(X509Certificate cert, String oid)
            throws IOException {
        byte[] wrapped = cert.getExtensionValue(oid);
        return wrapped == null ? null : read_der_content(wrapped, 0x04);
    }
    private static byte[] read_der_content(byte[] data, int tag) throws IOException {
        if (data.length < 2 || unsigned(data[0]) != tag)
            throw new IOException("invalid DER tag");
        int pos = 1, len = unsigned(data[pos++]);
        if ((len & 0x80) != 0) {
            int bytes = len & 0x7f;
            if (bytes == 0 || bytes > 2 || pos + bytes > data.length)
                throw new IOException("invalid DER length");
            len = 0;
            for (int i = 0; i < bytes; ++i) len = (len << 8) | unsigned(data[pos++]);
        }
        if (len < 0 || pos + len != data.length)
            throw new IOException("DER length/trailing bytes");
        return Arrays.copyOfRange(data, pos, pos + len);
    }
    private static int unsigned(byte value) { return value & 0xff; }
    private static long unsigned_le32(byte[] a, int i) {
        return (long) unsigned(a[i]) | (long) unsigned(a[i+1]) << 8 |
                (long) unsigned(a[i+2]) << 16 | (long) unsigned(a[i+3]) << 24;
    }
    private static byte[] clone_optional(byte[] b) { return b == null ? null : b.clone(); }
    private static boolean all_zero(byte[] data) {
        int nonzero = 0;
        for (byte b : data) nonzero |= b;
        return nonzero == 0;
    }
}
