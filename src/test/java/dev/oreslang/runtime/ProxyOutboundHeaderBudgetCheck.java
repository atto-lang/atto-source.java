package dev.oreslang.runtime;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Header budgets cover received bytes, even fields stripped from forwarding. */
public final class ProxyOutboundHeaderBudgetCheck {
    public static void main(String[] args) {
        Map<String, List<String>> normal = new LinkedHashMap<>();
        normal.put("Connection", List.of("X-Remove"));
        normal.put("X-Remove", List.of("hidden"));
        normal.put("Set-Cookie", List.of("a=1", "b=2"));
        var result = ProxyOutboundLoopback.filtered(HttpHeaders.of(normal, (name, value) -> true));
        if (result.size() != 2 || result.stream().anyMatch(f -> !f.getKey().equalsIgnoreCase("Set-Cookie")))
            throw new AssertionError("hop filtering or repeated cookies regressed");
        Map<String, List<String>> strippedOversize = new LinkedHashMap<>();
        strippedOversize.put("Connection", List.of("X-Remove"));
        strippedOversize.put("X-Remove", List.of("b".repeat(32769)));
        expectDenied(strippedOversize);
        // Count fields *before* stripping, not just the list we forward.
        Map<String, List<String>> tooMany = new LinkedHashMap<>();
        tooMany.put("Connection", List.of("X-Remove"));
        var entries = new ArrayList<String>();
        for (int i = 0; i < 101; i++) entries.add("test-" + i);
        tooMany.put("X-Remove", entries);
        expectDenied(tooMany);
        Map<String, List<String>> malformedHopName = new LinkedHashMap<>();
        malformedHopName.put("Connection", List.of("X-Remove,"));
        expectDenied(malformedHopName);
        Map<String, List<String>> tooManyConnectionOptions = new LinkedHashMap<>();
        var options = new ArrayList<String>();
        for (int i = 0; i < 101; i++) options.add("X" + i);
        tooManyConnectionOptions.put("Connection", List.of(String.join(",", options)));
        expectDenied(tooManyConnectionOptions);
        ProxyOutboundLoopback.validateFinalStatus(200);
        ProxyOutboundLoopback.validateFinalStatus(599);
        expectStatusDenied(101);
        expectStatusDenied(600);
        System.out.println("PASS prefilter header budgets, hop-by-hop validation and final-status gate");
    }

    private static void expectStatusDenied(int status) {
        try {
            ProxyOutboundLoopback.validateFinalStatus(status);
            throw new AssertionError("unsupported upstream status accepted: " + status);
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }

    private static void expectDenied(Map<String, List<String>> map) {
        try {
            ProxyOutboundLoopback.filtered(HttpHeaders.of(map, (name, value) -> true));
            throw new AssertionError("malformed or unbounded header set accepted");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }
}
