use serde_json::Value;
// Recursively reject duplicate object members before serde_json::Value can
// silently overwrite the first value. This is an OUTPUT-only semantic oracle;
// it must never be treated as proof that a guest actually ran.
use serde::de::{self, Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};

struct StrictValue(Value);
struct StrictVisitor;

impl<'de> Deserialize<'de> for StrictValue {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where D: Deserializer<'de> {
        deserializer.deserialize_any(StrictVisitor)
    }
}
impl<'de> Visitor<'de> for StrictVisitor {
    type Value = StrictValue;

    fn expecting(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("a JSON value without duplicate object keys")
    }
    fn visit_unit<E: de::Error>(self) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::Null))
    }
    fn visit_none<E: de::Error>(self) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::Null))
    }
    fn visit_bool<E: de::Error>(self, value: bool) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::Bool(value)))
    }
    fn visit_i64<E: de::Error>(self, value: i64) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::Number(value.into())))
    }
    fn visit_u64<E: de::Error>(self, value: u64) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::Number(value.into())))
    }
    fn visit_f64<E: de::Error>(self, value: f64) -> std::result::Result<Self::Value, E> {
        let number = serde_json::Number::from_f64(value)
            .ok_or_else(|| E::custom("non-finite JSON number"))?;
        Ok(StrictValue(Value::Number(number)))
    }
    fn visit_str<E: de::Error>(self, value: &str) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::String(value.to_owned())))
    }
    fn visit_string<E: de::Error>(self, value: String) -> std::result::Result<Self::Value, E> {
        Ok(StrictValue(Value::String(value)))
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A)
        -> std::result::Result<Self::Value, A::Error> {
        let mut items = Vec::new();
        while let Some(item) = seq.next_element::<StrictValue>()? {
            items.push(item.0);
        }
        Ok(StrictValue(Value::Array(items)))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A)
        -> std::result::Result<Self::Value, A::Error> {
        let mut fields = serde_json::Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if fields.contains_key(&key) {
                return Err(de::Error::custom(format!("duplicate JSON member: {key}")));
            }
            let value = map.next_value::<StrictValue>()?;
            fields.insert(key, value.0);
        }
        Ok(StrictValue(Value::Object(fields)))
    }
}

fn parse_strict_json(line: &str) -> std::result::Result<Value, serde_json::Error> {
    serde_json::from_str::<StrictValue>(line).map(|strict| strict.0)
}

use std::{error::Error, path::PathBuf, process::Command};

type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn execute(root: &std::path::Path, source: &std::path::Path) -> Result<String> {
    let output = Command::new(root.join("scripts/compiler.sh"))
        .arg(source)
        .current_dir(root)
        .output()?;
    if !output.status.success() {
        return Err(format!(
            "guest execution failed: {}",
            String::from_utf8_lossy(&output.stderr)
        )
        .into());
    }
    return Ok(String::from_utf8(output.stdout)?);
}

fn check_smoke(text: &str) -> Result<()> {
    let rows = text
        .lines()
        .map(parse_strict_json)
        .collect::<std::result::Result<Vec<_>, _>>()?;
    let logs: Vec<_> = rows.iter().filter(|r| r["signal"] == "log").collect();
    let spans: Vec<_> = rows.iter().filter(|r| r["signal"] == "span").collect();
    let metrics: Vec<_> = rows.iter().filter(|r| r["signal"] == "metric").collect();
    if spans.len() != 2 || logs.len() != 6 || metrics.len() != 1 {
        return Err(
            "incorrect signal counts (including repeated finish, filtered and after-finish events)"
                .into(),
        );
    }
    // The smoke program is expected to emit exactly nine records. Unknown
    // signals or unrecognized fields (especially sensitive attributes) must
    // not pass merely because the expected log/span/metric counts match.
    if rows.len() != 9 {
        return Err("unexpected telemetry record count".into());
    }
    const COMMON: &[&str] = &["schema", "signal", "timestamp_unix_ms", "service", "ores_trace_id"];
    const LOG: &[&str] = &["span_id", "severity", "body"];
    const SPAN: &[&str] = &["span_id", "parent_span_id", "name", "duration_ns", "outcome", "http_status"];
    const METRIC: &[&str] = &["name", "kind", "unit", "value"];
    for row in &rows {
        let signal = row["signal"].as_str().ok_or("missing telemetry signal")?;
        let signal_keys: &[&str] = match signal {
            "log" => LOG,
            "span" => SPAN,
            "metric" => METRIC,
            _ => return Err("unexpected telemetry signal".into()),
        };
        let object = row.as_object().ok_or("non-object telemetry record")?;
        if object.len() != COMMON.len() + signal_keys.len()
            || object.keys().any(|key| {
                !COMMON.contains(&key.as_str()) && !signal_keys.contains(&key.as_str())
            })
        {
            return Err("unexpected telemetry record field".into());
        }
    }
    if spans[0]["name"] != "child" || spans[0]["parent_span_id"] != spans[1]["span_id"] {
        return Err("invalid child correlation".into());
    }
    for row in &rows {
        if row["schema"] != "oreslang-otel.v1"
            || row["ores_trace_id"] != "ores-trace-test"
            || row["timestamp_unix_ms"].as_i64().unwrap_or(0) <= 1700000000000
        {
            return Err("invalid schema, trace or epoch timestamp".into());
        }
    }
    if spans
        .iter()
        .any(|r| r["duration_ns"].as_i64().unwrap_or(-1) < 0)
    {
        return Err("invalid span duration".into());
    }
    let controls: String = (0_u8..32).map(char::from).collect();
    let expected = [
        "quote\" slash\\ newline\n nul\0 tab\t emoji😀",
        controls.as_str(),
        "-9223372036854775808|9223372036854775807|0|-1|100",
        "visible warning",
        "visible all",
        "visible equal",
    ];
    for (log, body) in logs.iter().zip(expected) {
        if log["body"] != body {
            return Err(format!("incorrect log body: {}", log["body"]).into());
        }
    }
    if metrics[0]["value"].as_i64() != Some(1) {
        return Err("incorrect counter value".into());
    }
    return Ok(());
}


/**
 * Semantic oracle for the real native Oreslang guest:
 *   tests/span-finish-admission-retry.ores
 *
 * A deliberate >65,536-byte record must fail synchronously without
 * committing the live span's state. The same span then emits one valid
 * completion, and repeated finish/event calls cannot create duplicates.
 * This checks output only; the caller separately attests compiler execution.
 */
fn check_finish_admission_retry(text: &str) -> Result<()> {
    if text.len() > 4096 {
        return Err("span retry guest produced excessive output".into());
    }
    let lines: Vec<_> = text.lines().collect();
    if lines.len() != 1 {
        return Err("expected exactly one admitted completion after rejected enqueue".into());
    }
    let row = parse_strict_json(lines[0])?;
    let fields = [
        "schema", "signal", "timestamp_unix_ms", "service", "ores_trace_id",
        "span_id", "parent_span_id", "name", "duration_ns", "outcome", "http_status"
    ];
    let object = row.as_object().ok_or("span retry record is not an object")?;
    if object.len() != fields.len() || !fields.iter().all(|key| object.contains_key(*key)) {
        return Err("missing or unexpected span retry field".into());
    }
    if row["schema"] != "oreslang-otel.v1"
        || row["signal"] != "span"
        || row["service"] != "retry-test"
        || row["ores_trace_id"] != "ores-trace-retry"
        || row["parent_span_id"] != ""
        || row["name"] != "admission-retry"
        || row["outcome"] != "ok"
        || row["http_status"].as_i64() != Some(200)
    {
        return Err("wrong span retry identity or successful completion".into());
    }
    if !row["span_id"].as_str().is_some_and(|id| !id.is_empty()) {
        return Err("retry span has no generated id".into());
    }
    if row["timestamp_unix_ms"].as_i64().unwrap_or(0) <= 1700000000000
        || row["duration_ns"].as_i64().is_none_or(|ns| ns < 0)
    {
        return Err("invalid timestamp or elapsed duration for span retry".into());
    }
    Ok(())
}

fn integers(root: &std::path::Path) -> Result<()> {
    let mut values = vec![
        i64::MIN,
        i64::MAX,
        0,
        -1,
        1,
        9007199254740992,
        9007199254740993,
    ];
    let mut power = 10_i64;
    for exponent in 1..=18 {
        values.extend([power - 1, power, power + 1, -power - 1, -power, -power + 1]);
        if exponent < 18 {
            power *= 10;
        }
    }
    let mut state = 0x123456789abcdef0_u64;
    for _ in 0..100 {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        values.push(state as i64);
    }
    let mut source =
        String::from("import module otel from \"../src/otel\";\npub routine main(): void {\n");
    for value in &values {
        let literal = if *value == i64::MIN {
            "(-9223372036854775807 - 1)".to_owned()
        } else {
            value.to_string()
        };
        source.push_str(&format!("  stdio.println(otel.integer({literal}));\n"));
    }
    source.push_str("  return;\n}\n");
    std::fs::create_dir_all(root.join(".cache"))?;
    let path = root.join(".cache/integer-parity.ores");
    std::fs::write(&path, source)?;
    let result = execute(root, &path)?;
    let lines: Vec<_> = result.lines().collect();
    if lines.len() != values.len() {
        return Err("missing integer parity output".into());
    }
    for (actual, value) in lines.into_iter().zip(&values) {
        if actual != value.to_string() {
            return Err(format!("integer parity: expected {value}, received {actual}").into());
        }
    }
    println!(
        "PASS: {} exact int64 boundaries and deterministic random samples",
        values.len()
    );
    return Ok(());
}

fn run() -> Result<()> {
    let args: Vec<_> = std::env::args_os().collect();
    if args.len() == 3 && args[1].to_str() == Some("--check-smoke-output") {
        // Only semantic output validation, never proof of the compiler process.
        use std::io::Read;
        let mut bytes = Vec::new();
        std::fs::File::open(&args[2])?
            .take(1024 * 1024 + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() > 1024 * 1024 {
            return Err("smoke capture exceeds 1 MiB".into());
        }
        check_smoke(&String::from_utf8(bytes)?)?;
        println!("PASS: smoke JSONL semantics; OUTPUT_ONLY_NOT_GUEST_ATTESTED");
        return Ok(());
    }
    if args.len() == 3 && args[1].to_str() == Some("--check-finish-retry-output") {
        // This mode validates ONLY externally captured output; never claim
        // compiler provenance or real guest execution from a file alone.
        use std::io::Read;
        let mut bytes = Vec::new();
        std::fs::File::open(&args[2])?
            .take(4097)
            .read_to_end(&mut bytes)?;
        if bytes.len() > 4096 {
            return Err("span retry capture exceeds 4096 bytes".into());
        }
        check_finish_admission_retry(&String::from_utf8(bytes)?)?;
        println!("PASS: span finish retry semantics; OUTPUT_ONLY_NOT_GUEST_ATTESTED");
        return Ok(());
    }
    if args.len() != 1 {
        return Err("usage: checker [--check-smoke-output GUEST_JSONL | --check-finish-retry-output GUEST_JSONL]".into());
    }
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_path_buf();
    check_smoke(&execute(&root, &root.join("tests/smoke.ores"))?)?;
    println!(
        "PASS: native spans, correlation, idempotence, JSON escaping, int64 and severity gating"
    );
    check_finish_admission_retry(&execute(
        &root, &root.join("tests/span-finish-admission-retry.ores")
    )?)?;
    println!("PASS: native span finish admission retry; oversized enqueue did not end span");
    integers(&root)?;
    return Ok(());
}

fn main() {
    if let Err(error) = run() {
        eprintln!("FAIL: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn strict_json_decoder_rejects_duplicate_keys_at_every_nesting_level() {
        for bad in [
            r#"{"signal":"span","signal":"span"}"#,
            r#"{"signal":"span","signal":"log"}"#,
            r#"{"a":{"trace":"first","trace":"second"}}"#,
            r#"{"a":[{"x":1,"x":2}]}"#,
            r#"{"\u0073ignal":"span","signal":"span"}"#,
        ] {
            assert!(super::parse_strict_json(bad).is_err(), "accepted duplicate key: {bad}");
        }
        for good in [
            r#"{"a":1,"b":{"a":2},"c":[{"a":3},{"a":4}]}"#,
            r#"{"escaped":"quote \" and \\ slash","null":null,"boolean":true}"#,
            r#"{"i64_min":-9223372036854775808,"i64_max":9223372036854775807}"#,
        ] {
            assert!(super::parse_strict_json(good).is_ok(), "rejected valid JSON: {good}");
        }
    }

    #[test]
    fn span_finish_retry_oracle_accepts_one_valid_completion_and_rejects_tampering() {
        use serde_json::{json, Value};
        let good = json!({
            "schema":"oreslang-otel.v1", "signal":"span",
            "timestamp_unix_ms":1700000000001_i64,
            "service":"retry-test", "ores_trace_id":"ores-trace-retry",
            "span_id":"generated-id", "parent_span_id":"",
            "name":"admission-retry", "duration_ns":20_i64,
            "outcome":"ok", "http_status":200
        });
        let check = |v: Value| super::check_finish_admission_retry(&v.to_string());
        assert!(check(good.clone()).is_ok(), "single accepted span record");
        for (key, value) in [
            ("schema", json!("other")),
            ("signal", json!("log")),
            ("service", json!("wrong")),
            ("ores_trace_id", json!("other")),
            ("span_id", json!("")),
            ("outcome", json!("oversized")),
            ("http_status", json!(503)),
            ("duration_ns", json!(-1)),
            ("timestamp_unix_ms", json!(0)),
        ] {
            let mut damaged = good.clone();
            damaged[key] = value;
            assert!(check(damaged).is_err(), "invalid field {key}");
        }
        let mut extra = good.clone();
        extra["body"] = json!("data must not be leaked");
        assert!(check(extra).is_err());
        assert!(super::check_finish_admission_retry("").is_err());
        assert!(super::check_finish_admission_retry("{}").is_err());
        assert!(super::check_finish_admission_retry(
            &format!("{}\n{}", good, good)).is_err(), "no duplicate completions");
        assert!(super::check_finish_admission_retry(
            &"x".repeat(4097)).is_err(), "strict capture budget");
    }

    #[test]
    fn malformed_or_incomplete_records_fail_closed() {
        assert!(super::check_smoke("{broken}").is_err());
        assert!(super::check_smoke("").is_err());
        assert!(super::check_smoke("{}").is_err());
    }

    #[test]
    fn rejects_unexpected_signal_and_undeclared_fields_in_otherwise_valid_output() {
        use serde_json::{json, Value};
        let controls: String = (0_u8..32).map(char::from).collect();
        let bodies = [
            "quote\" slash\\ newline\n nul\0 tab\t emoji😀".to_owned(),
            controls,
            "-9223372036854775808|9223372036854775807|0|-1|100".to_owned(),
            "visible warning".to_owned(),
            "visible all".to_owned(),
            "visible equal".to_owned(),
        ];
        let mut rows: Vec<Value> = bodies.iter().map(|body| json!({
            "schema":"oreslang-otel.v1", "signal":"log", "timestamp_unix_ms":1700000000001_i64,
            "service":"test", "ores_trace_id":"ores-trace-test",
            "span_id":"parent", "severity":"INFO", "body":body
        })).collect();
        rows.push(json!({
            "schema":"oreslang-otel.v1", "signal":"span", "timestamp_unix_ms":1700000000001_i64,
            "service":"test", "ores_trace_id":"ores-trace-test", "span_id":"child",
            "parent_span_id":"parent", "name":"child", "duration_ns":1, "outcome":"ok", "http_status":0
        }));
        rows.push(json!({
            "schema":"oreslang-otel.v1", "signal":"span", "timestamp_unix_ms":1700000000001_i64,
            "service":"test", "ores_trace_id":"ores-trace-test", "span_id":"parent",
            "parent_span_id":"", "name":"request", "duration_ns":2, "outcome":"ok", "http_status":200
        }));
        rows.push(json!({
            "schema":"oreslang-otel.v1", "signal":"metric", "timestamp_unix_ms":1700000000001_i64,
            "service":"test", "ores_trace_id":"ores-trace-test", "name":"requests",
            "kind":"counter", "unit":"1", "value":1
        }));
        let render = |records: &[Value]| records.iter().map(Value::to_string)
            .collect::<Vec<_>>().join("\n");
        assert!(super::check_smoke(&render(&rows)).is_ok(), "valid strict fixture");
        rows.push(json!({
            "schema":"oreslang-otel.v1", "signal":"unknown",
            "timestamp_unix_ms":1700000000001_i64, "service":"test",
            "ores_trace_id":"ores-trace-test"
        }));
        assert!(super::check_smoke(&render(&rows)).is_err());
        rows.pop();
        rows[0]["credential"] = json!("should be rejected");
        assert!(super::check_smoke(&render(&rows)).is_err());
    }
}
