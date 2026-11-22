package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Negative-only host tests: no genuine AMD endorsements or hardware. */
final class SevSnpVcekEndorsementTest {
    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte)value);
        return bytes;
    }
    private static SevSnpReportV1.Policy policy() {
        return new SevSnpReportV1.Policy(fill(64,9),fill(48,7),
                new SevSnpReportV1.TcbFloor(1,1,1,1),
                SevSnpReportV1.Signer.VCEK,2,5);
    }
    private static byte[] report() {
        byte[] bytes = new byte[SevSnpReportV1.REPORT_BYTES];
        bytes[0]=3;
        bytes[0x34]=1;
        System.arraycopy(fill(64,9),0,bytes,0x50,64);
        System.arraycopy(fill(48,7),0,bytes,0x90,48);
        bytes[0x180]=bytes[0x181]=bytes[0x186]=bytes[0x187]=1;
        return bytes;
    }

    @Test
    void fakeClaimsNeverAuthenticateAmdRoot() throws Exception {
        byte[] raw=report();
        var policy=policy();
        assertEquals(SevSnpReportV1.Result.UNVERIFIED_POLICY_MATCH,
                SevSnpReportV1.inspect_unverified_report(raw,policy));
        assertEquals(SevSnpVcekEndorsement.Outcome.UNVERIFIED_INPUT,
                SevSnpVcekEndorsement.verify_vcek_offline(null,policy,null));
        var empty=new SevSnpVcekEndorsement.Bundle(null,null,null,null,null);
        assertEquals(SevSnpVcekEndorsement.Outcome.UNTRUSTED_ROOT,
                SevSnpVcekEndorsement.verify_vcek_offline(raw,policy,empty));

        byte[] fakeRoot=new byte[]{0x30,0x01,0};
        var badPin=new SevSnpVcekEndorsement.Bundle(
                fakeRoot,null,null,null,fill(32,7));
        assertEquals(SevSnpVcekEndorsement.Outcome.UNTRUSTED_ROOT,
                SevSnpVcekEndorsement.verify_vcek_offline(raw,policy,badPin));

        // Even a pin that agrees with a fabricated root does not turn its
        // malformed bytes into an accepted vendor certificate.
        var matchingPin=new SevSnpVcekEndorsement.Bundle(
                fakeRoot,null,null,null,MessageDigest.getInstance("SHA-256").digest(fakeRoot));
        assertEquals(SevSnpVcekEndorsement.Outcome.CERTIFICATE_CHAIN_REJECTED,
                SevSnpVcekEndorsement.verify_vcek_offline(raw,policy,matchingPin));
    }

    @Test
    void vlekIsNotSilentlyRoutedThroughVcek() {
        byte[] raw=report();
        var empty=new SevSnpVcekEndorsement.Bundle(null,null,null,null,null);
        var vlekPolicy=new SevSnpReportV1.Policy(fill(64,9),fill(48,7),
                new SevSnpReportV1.TcbFloor(1,1,1,1),
                SevSnpReportV1.Signer.VLEK,2,5);
        assertEquals(SevSnpVcekEndorsement.Outcome.UNSUPPORTED_SIGNER,
                SevSnpVcekEndorsement.verify_vcek_offline(raw,vlekPolicy,empty));
        raw[0x48]=1;
        assertEquals(SevSnpVcekEndorsement.Outcome.UNSUPPORTED_SIGNER,
                SevSnpVcekEndorsement.verify_vcek_offline(raw,policy(),empty));
    }

    @Test
    void bundleClonesSensitiveIdentityAndCertificateBytes() {
        byte[] root=new byte[]{1,2,3};
        byte[] pin=fill(32,9);
        var bundle=new SevSnpVcekEndorsement.Bundle(root,null,null,null,pin);
        root[0]=0;
        pin[0]=0;
        assertEquals(1,bundle.ark_der()[0]);
        assertEquals(9,bundle.expected_ark_sha256()[0]);
        bundle.ark_der()[1]=0;
        assertEquals(2,bundle.ark_der()[1]);
    }
}
