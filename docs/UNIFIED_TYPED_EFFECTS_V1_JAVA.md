# Compiler typed effects v1 (Java/GraalVM)

**Stage:** opt-in, compiler-owned **checked-AST** static analysis. Not a new AttoLang grammar mode, full-AOT proof, permission authority or hard-real-time certification.

`OresCompiler.analyzeEffectProfileV1(source, Profile.PURE|DETERMINISTIC|NO_ALLOC)` invokes the real Parser, TypeChecker and OwnershipChecker, then analyzes a strictly closed-world scalar module. Recognized safe primitives and direct static callable references are propagated through a finite worklist fixed point, including recursive call cycles. Numeric `+`/`-`/`*` retain a `panic` observation (checked arithmetic may trap); observational purity does not imply no exceptional control. Unknown member/native calls, imports, FFI, actor lifecycle, callback values, constructors with unverified cleanup, implicit heap paths, unsupported statements and unresolved calls raise `E_EFFECT_UNKNOWN` rather than forging empty rows. A confirmed explicit constructor allocation also triggers `E_HARD_RT_ALLOCATION` in the `NO_ALLOC` profile.

Current source ceiling: only `__root__` free functions with closed int/bool/void signatures and known statement/expression shapes. This slice does **not** resolve clock or file APIs into authenticated `io.clock`/`io.file.read` effects; it rejects those calls as unknown. Runtime policy is independently checked; this analysis does not change default guest execution.

Test target: `mvn -B -ntp -Dtest=CompilerTypedEffectV1Test test`. Real parser/type/ownership positive test plus model-only synthetic typed-AST negative cases. **No executed CI or guest run is claimed until the current SHA's test steps are inspected.**

Shared target schema: [atto-common-source typed effect contract](https://github.com/atto-lang/atto-common-source/blob/feat/typed-unified-effects-gate-v1-20261121/contracts/analysis/UNIFIED_TYPED_EFFECTS_V1.md). This Java checker does not yet serialize that schema. Next: source span/call path diagnostics, authenticated builtin identity for clock/files, typed constructor/FFI/drop summaries, AOT manifest/permission binding, and byte-identical conformance guests.
