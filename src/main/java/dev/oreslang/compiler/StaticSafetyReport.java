package dev.oreslang.compiler;

import java.util.List;
import java.util.Objects;

/**
 * Stable advisory JSON envelope for opt-in source AST diagnostics.
 * This report contains no compiler-authenticated proof or verified locations.
 * Do not use it as an isolation, security, WCET or no-Zalgo certificate.
 */
public final class StaticSafetyReport {
    private StaticSafetyReport() { }

    private static final int MAX_FINDINGS = 10_000;
    private static final int MAX_FIELD_CHARS = 16_384;
    private static final int MAX_REPORT_CHARS = 8 * 1024 * 1024;

    public static String render(List<StaticSafetyAnalyzer.Finding> findings) {
        Objects.requireNonNull(findings, "findings");
        if (findings.size() > MAX_FINDINGS)
            throw new IllegalArgumentException("static safety report finding budget exceeded");
        StringBuilder output = new StringBuilder(256 + findings.size() * 160);
        output.append("{\"schema\":\"ores-static-safety/v0\",")
              .append("\"assurance\":\"advisory_unverified\",")
              .append("\"producer_authenticated\":false,")
              .append("\"proof_complete\":false,")
              .append("\"findings\":[");
        boolean first = true;
        for (StaticSafetyAnalyzer.Finding finding : findings) {
            Objects.requireNonNull(finding, "finding");
            if (!first) output.append(',');
            first = false;
            output.append('{');
            output.append("\"rule\":");
            quote(output, finding.rule());
            output.append(",\"confidence\":");
            quote(output, finding.confidence());
            output.append(",\"callable\":");
            quote(output, finding.callable());
            output.append(",\"message\":");
            quote(output, finding.message());
            output.append(",\"witness\":[");
            boolean firstWitness = true;
            for (String witness : finding.witness()) {
                if (!firstWitness) output.append(',');
                firstWitness = false;
                quote(output, witness);
            }
            output.append("]}");
            if (output.length() > MAX_REPORT_CHARS)
                throw new IllegalArgumentException("static safety report byte budget exceeded");
        }
        return output.append("]}").toString();
    }

    private static void quote(StringBuilder output, String value) {
        Objects.requireNonNull(value, "diagnostic string");
        if (value.length() > MAX_FIELD_CHARS)
            throw new IllegalArgumentException("static safety diagnostic field budget exceeded");
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        output.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4)
                            output.append(Character.forDigit((ch >>> shift) & 0xf, 16));
                    } else {
                        output.append(ch);
                    }
                }
            }
        }
        output.append('"');
    }
}
