# Native Oreslang tests — 2026-11-01 incremental batch

**40 newly authored Oreslang test cases across 20 repositories** (two cases per repository), applied to the pre-existing review branches `test/native-std-harness-20261031`. These are native `.ores` files, not host-language tests. All 20 test files were read back after writing: **132 unique TestCase registrations** are present in these 20 files, including 92 previously authored registrations.

| Repository | Draft PR | Added names | Cumulative cases |
| --- | --- | --- | ---: |
| `oreslang-http-routing` | [#31](https://github.com/ores-truffle-oreslang/oreslang-http-routing/pull/31) | `rejects_empty_capture_name`<br>`rejects_nonterminal_wildcard` | 5 |
| `oreslang-linked-queue` | [#15](https://github.com/ores-truffle-oreslang/oreslang-linked-queue/pull/15) | `negative_key_enqueue_is_atomic`<br>`repeated_clear_is_idempotent` | 5 |
| `oreslang-otel` | [#15](https://github.com/ores-truffle-oreslang/oreslang-otel/pull/15) | `warning_alias_has_same_rank`<br>`high_severity_passes_lower_threshold` | 5 |
| `oreslang-rpc` | [#17](https://github.com/ores-truffle-oreslang/oreslang-rpc/pull/17) | `zero_wire_field_number_is_invalid`<br>`duplicate_message_field_name_is_invalid` | 5 |
| `oreslang-ssr` | [#25](https://github.com/ores-truffle-oreslang/oreslang-ssr/pull/25) | `untrusted_style_attribute_is_denied`<br>`inert_aria_attribute_escapes_ampersand` | 5 |
| `postgres-orm.ores` | [#14](https://github.com/ores-truffle-oreslang/postgres-orm.ores/pull/14) | `not_null_column_is_explicit`<br>`nullable_column_omits_not_null` | 5 |
| `oreslang-web-browser.ores` | [#14](https://github.com/ores-truffle-oreslang/oreslang-web-browser.ores/pull/14) | `trusted_same_tenant_open_is_admitted`<br>`tab_limit_is_fail_closed` | 5 |
| `oreslang-sg-stategraph.ores` | [#7](https://github.com/ores-truffle-oreslang/oreslang-sg-stategraph.ores/pull/7) | `duplicate_node_name_is_rejected`<br>`missing_start_edge_is_rejected` | 5 |
| `oreslang-data-science` | [#21](https://github.com/ores-truffle-oreslang/oreslang-data-science/pull/21) | `absolute_zero_is_exact`<br>`clamp_preserves_interior_value` | 5 |
| `oreslang-logic.ores` | [#5](https://github.com/ores-truffle-oreslang/oreslang-logic.ores/pull/5) | `empty_disjunction_is_false`<br>`equivalence_requires_matching_truth` | 5 |
| `oreslang-hpc-finance` | [#19](https://github.com/ores-truffle-oreslang/oreslang-hpc-finance/pull/19) | `constant_series_mean_is_exact`<br>`negative_count_is_rejected` | 4 |
| `oreslang-serialization-and-validation` | [#48](https://github.com/ores-truffle-oreslang/oreslang-serialization-and-validation/pull/48) | `valid_utf8_four_byte_scalar`<br>`rejects_truncated_multibyte_scalar` | 4 |
| `oreslang-orie-ieor` | [#4](https://github.com/ores-truffle-oreslang/oreslang-orie-ieor/pull/4) | `knapsack_mismatched_inputs_rejected`<br>`knapsack_zero_capacity_has_zero_value` | 4 |
| `oreslang-forward-proxy-demo.ores` | [#32](https://github.com/ores-truffle-oreslang/oreslang-forward-proxy-demo.ores/pull/32) | `prefix_rejects_relative_request`<br>`trailing_slash_requires_real_subpath` | 4 |
| `oreslang-reverse-proxy-demo.ores` | [#34](https://github.com/ores-truffle-oreslang/oreslang-reverse-proxy-demo.ores/pull/34) | `relative_config_path_is_denied`<br>`backslash_config_path_is_denied` | 4 |
| `oreslang-platform-conformance` | [#269](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/269) | `option_some_zero_is_not_absent`<br>`result_error_fallback_preserves_type` | 4 |
| `oreslang-source.java` | [#2395](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2395) | `machine_safe_ascii_is_unchanged`<br>`machine_multiple_newlines_are_escaped` | 30 |
| `oreslang-source.llvm` | [#1252](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1252) | `large_opposite_sign_sum_is_exact`<br>`large_negative_opposite_sign_sum_is_exact` | 12 |
| `oreslang-source.net` | [#279](https://github.com/ores-truffle-oreslang/oreslang-source.net/pull/279) | `large_opposite_sign_sum_is_exact`<br>`large_negative_opposite_sign_sum_is_exact` | 12 |
| `oreslang-compilers-parity-testing` | [#15](https://github.com/ores-truffle-oreslang/oreslang-compilers-parity-testing/pull/15) | `result_is_error_without_implicit_unwrap`<br>`some_zero_is_not_none` | 4 |

## Test design
Positive and negative pairs cover HTTP route capture/wildcard validation, queue atomicity, OpenTelemetry severity thresholds, duplicate RPC field identity, HTML escaping/unsafe attributes, PostgreSQL SQL DDL types, browser tenant and resource caps, StateGraph node identity and entry edges, scalar math, logical identities, finance count admission, UTF-8 validity, 0/1 knapsack limits, forward/reverse proxy URI/path constraints, portable Option/Result semantics, Oreslang machine-field escaping, and signed 64-bit precision on LLVM/.NET.

## Evidence and restrictions
- **40 authored, 0 compiler-backed passes verified in this incremental batch**. GitHub file read-back plus a textual registration/unique-name/function-presence check cannot prove compilation or runtime behavior.
- Hosted Java/Graal recognizes canonical `ores.std/testing`; source-level import registration is not native guest execution. LLVM/.NET must separately demonstrate the module and guest result receipts.
- Only real exact-head GitHub Actions jobs with assigned runners and non-empty executed step sets can count as CI execution. A failed `steps=[]` workflow is runner admission failure, not a failing test body.
- Require PASS/FAIL event identity and SUMMARY consistency, expected nonzero exit on failure, and negative-case evidence.
- No private-source mirror to another organization without explicit policy authorization. Respect the existing cross-organization transfer hold and require independent review before merging.
- The separate Rust-host tests in the Rust/Cranelift compiler PRs must not inflate this native-Oreslang TestCase count.
