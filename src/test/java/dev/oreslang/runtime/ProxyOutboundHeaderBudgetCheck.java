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
        Map<String, List<String>> tooMany = new LinkedHashMap<>();
        tooMany.put("Connection", List.of("X-Remove"));
        var entries = new ArrayList<String>();
        for (int i = 0; i < 101; i++) entries.add("test-" + i);
        tooMany.put("X-Remove", entries);
        expectDenied(tooMany);
        Map<String, List<String>> malformedHopName = new LinkedHashMap<>();
        malformedHopName.put("Connection", List.of("X-Remove,"));
        expectDenied(malformedHopName);
        System.out.println("PASS prefilter header budgets and hop-by-hop validation");
    }
    private static void expectDenied(Map<String, List<String>> map) {
        try {
            ProxyOutboundLoopback.filtered(HttpHeaders.of(map, (name, value) -> true));
            throw new AssertionError("malformed or unbounded header set accepted");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }
}
