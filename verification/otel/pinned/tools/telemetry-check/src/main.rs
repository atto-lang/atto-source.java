use serde::de::{self, MapAccess, Visitor};
use serde::Deserialize;
use serde_json::Value;
use std::{error::Error, fmt, path::PathBuf, process::Command};

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


// serde_json::Value alone overwrites duplicate object keys. That can hide
// ambiguous records from a downstream consumer with different duplicate-key
// precedence. Reject ambiguous top-level fields before schema validation.
// Nested JSON objects are not part of any telemetry.v1 field type and the
// existing exact-field/type schema rejects them separately.
struct UniqueRecord(Value);

impl<'de> Deserialize<'de> for UniqueRecord {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        struct UniqueFields;
        impl<'de> Visitor<'de> for UniqueFields {
            type Value = UniqueRecord;

            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("JSON telemetry object with unique field names")
            }

            fn visit_map<M>(self, mut map: M) -> std::result::Result<Self::Value, M::Error>
            where
                M: MapAccess<'de>,
            {
                let mut fields = serde_json::Map::new();
                while let Some((key, value)) = map.next_entry::<String, Value>()? {
                    if fields.contains_key(&key) {
                        return Err(de::Error::custom(format!("duplicate telemetry field: {key}")));
                    }
                    fields.insert(key, value);
                }
                Ok(UniqueRecord(Value::Object(fields)))
            }
        }
        deserializer.deserialize_map(UniqueFields)
    }
}

fn parse_unique_json_line(line: &str) -> Result<Value> {
    let mut decoder = serde_json::Deserializer::from_str(line);
    let record = UniqueRecord::deserialize(&mut decoder)?;
    decoder.end()?;
    Ok(record.0)
}


fn check_record_schema(row: &Value) -> Result<()> {
    const COMMON: &[&str] = &[
        "schema",
        "signal",
        "timestamp_unix_ms",
        "service",
        "ores_trace_id",
    ];
    const LOG: &[&str] = &["span_id", "severity", "body"];
    const SPAN: &[&str] = &[
        "span_id",
        "parent_span_id",
        "name",
        "duration_ns",
        "outcome",
        "http_status",
    ];
    const METRIC: &[&str] = &["name", "kind", "unit", "value"];

    let object = row.as_object().ok_or("non-object telemetry record")?;
    let signal = row["signal"].as_str().ok_or("missing telemetry signal")?;
    let fields: &[&str] = match signal {
        "log" => LOG,
        "span" => SPAN,
        "metric" => METRIC,
        _ => return Err("unknown telemetry signal".into()),
    };
    // Count + full membership prevents both missing and unexpected fields.
    if object.len() != COMMON.len() + fields.len()
        || !COMMON.iter().chain(fields).all(|key| object.contains_key(*key))
    {
        return Err("missing or unexpected telemetry field".into());
    }

    let required_string = |key: &str, allow_empty: bool| -> Result<()> {
        match row.get(key).and_then(Value::as_str) {
            Some(value) if allow_empty || !value.is_empty() => Ok(()),
            _ => Err(format!("invalid telemetry string field: {key}").into()),
        }
    };
    let integer = |key: &str| -> Result<i64> {
        row.get(key)
            .and_then(Value::as_i64)
            .ok_or_else(|| format!("invalid telemetry int64 field: {key}").into())
    };

    if row["schema"] != "oreslang-otel.v1" {
        return Err("unexpected telemetry schema".into());
    }
    required_string("service", false)?;
    required_string("ores_trace_id", false)?;
    if integer("timestamp_unix_ms")? <= 0 {
        return Err("invalid epoch timestamp".into());
    }

    match signal {
        "log" => {
            required_string("span_id", true)?;
            required_string("severity", false)?;
            required_string("body", true)?;
        }
        "span" => {
            required_string("span_id", false)?;
            required_string("parent_span_id", true)?;
            required_string("name", false)?;
            required_string("outcome", false)?;
            if integer("duration_ns")? < 0 {
                return Err("negative span duration".into());
            }
            let status = integer("http_status")?;
            if !(0..=599).contains(&status) {
                return Err("invalid HTTP status".into());
            }
        }
        "metric" => {
            required_string("name", false)?;
            required_string("kind", false)?;
            required_string("unit", false)?;
            let kind = row["kind"].as_str().ok_or("invalid metric kind")?;
            if !["counter", "gauge", "histogram"].contains(&kind) {
                return Err("unknown metric kind".into());
            }
            let value = integer("value")?;
            if kind == "counter" && value < 0 {
                return Err("negative counter delta".into());
            }
        }
        _ => unreachable!(),
    }
    Ok(())
}

fn check_smoke(text: &str) -> Result<()> {
    let rows = text
        .lines()
        .map(parse_unique_json_line)
        .collect::<Result<Vec<_>>>()?;
    if rows.len() != 9 || rows.iter().any(|row| !row.is_object()) {
        return Err("expected exactly nine telemetry records".into());
    }
    for row in &rows {
        check_record_schema(row)?;
    }
    let logs: Vec<_> = rows.iter().filter(|r| r["signal"] == "log").collect();
    let spans: Vec<_> = rows.iter().filter(|r| r["signal"] == "span").collect();
    let metrics: Vec<_> = rows.iter().filter(|r| r["signal"] == "metric").collect();
    if spans.len() != 2 || logs.len() != 6 || metrics.len() != 1 {
        return Err(
            "incorrect signal counts (including repeated finish, filtered and after-finish events)"
                .into(),
        );
    }
    if spans[0]["name"] != "child"
        || spans[0]["parent_span_id"] != spans[1]["span_id"]
        || spans[0]["span_id"] == spans[1]["span_id"]
        || spans[1]["parent_span_id"] != ""
        || spans[0]["outcome"] != "ok"
        || spans[1]["outcome"] != "ok"
        || spans[0]["http_status"] != 0
        || spans[1]["http_status"] != 200
    {
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
    let severities = ["INFO", "INFO", "INFO", "WARN", "INFO", "INFO"];
    for ((log, body), severity) in logs.iter().zip(expected).zip(severities) {
        if log["severity"] != severity {
            return Err("incorrect log severity".into());
        }
        if log["body"] != body {
            return Err(format!("incorrect log body: {}", log["body"]).into());
        }
    }
    if metrics[0]["name"] != "requests"
        || metrics[0]["kind"] != "counter"
        || metrics[0]["unit"] != "1"
        || metrics[0]["value"].as_i64() != Some(1)
    {
        return Err("incorrect counter value".into());
    }
    return Ok(());
}


fn check_span_lifecycle(text: &str) -> Result<()> {
    let lines: Vec<&str> = text.lines().collect();
    if lines.len() != 3 || lines[2] != "LIFECYCLE_OK" {
        return Err("span lifecycle guest did not finish with exact receipt and two JSONL rows".into());
    }
    let event = parse_unique_json_line(lines[0])?;
    let span = parse_unique_json_line(lines[1])?;
    check_record_schema(&event)?;
    check_record_schema(&span)?;
    if event["signal"] != "log"
        || event["ores_trace_id"] != "ores-trace-lifecycle"
        || event["span_id"] != "root-lifecycle"
        || event["severity"] != "INFO"
        || event["body"] != "before-finish"
        || span["signal"] != "span"
        || span["ores_trace_id"] != "ores-trace-lifecycle"
        || span["span_id"] != "root-lifecycle"
        || span["parent_span_id"] != ""
        || span["name"] != "lifecycle"
        || span["duration_ns"].as_i64() != Some(0)
        || span["outcome"] != "ok"
        || span["http_status"].as_i64() != Some(200)
        || event["service"] != "test"
        || span["service"] != "test"
    {
        return Err("span lifecycle guest produced an unexpected event, finish or duration".into());
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
        // Output-only parity check: a caller must independently attest compiler
        // revision, execution command, exit status, artifact hash, and target.
        // Bound input before UTF-8 decoding even for pipes and special files.
        use std::io::Read;
        let mut bytes = Vec::new();
        std::fs::File::open(&args[2])?
            .take(1024 * 1024 + 1)
            .read_to_end(&mut bytes)?;
        if bytes.len() > 1024 * 1024 {
            return Err("captured guest stdout exceeds 1 MiB".into());
        }
        check_smoke(&String::from_utf8(bytes)?)?;
        println!(
            "PASS: captured guest smoke semantics (output only; compiler provenance NOT attested)"
        );
        return Ok(());
    }
    if args.len() != 1 {
        return Err("usage: checker [--check-smoke-output GUEST_STDOUT_FILE]".into());
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
    check_span_lifecycle(&execute(&root, &root.join("tests/span-lifecycle.ores"))?)?;
    println!("PASS: native span lifecycle, future-start clamp, frozen elapsed and one-time finish");
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
    fn smoke_rejects_unrecognized_extra_telemetry_records() {
        use serde_json::json;
        let base = json!({"schema":"oreslang-otel.v1", "ores_trace_id":"ores-trace-test",
                          "timestamp_unix_ms":1700000000001_i64, "service":"test"});
        let controls: String = (0_u8..32).map(char::from).collect();
        let bodies = [
            "quote\" slash\\ newline\n nul\0 tab\t emoji😀",
            controls.as_str(),
            "-9223372036854775808|9223372036854775807|0|-1|100",
            "visible warning",
            "visible all",
            "visible equal",
        ];
        let mut rows = Vec::new();
        for (index, body) in bodies.into_iter().enumerate() {
            let mut row = base.clone();
            row["signal"] = json!("log");
            if index == 3 {
                row["severity"] = json!("WARN");
            }
            row["body"] = json!(body);
            if index != 3 {
                row["severity"] = json!("INFO");
            }
            row["span_id"] = json!("root-id");
            rows.push(row);
        }
        for (name, id, parent) in [("child", "child-id", "root-id"), ("request", "root-id", "")] {
            let mut row = base.clone();
            row["signal"] = json!("span");
            row["name"] = json!(name);
            row["span_id"] = json!(id);
            row["parent_span_id"] = json!(parent);
            row["duration_ns"] = json!(1);
            row["outcome"] = json!("ok");
            row["http_status"] = json!(if name == "child" { 0 } else { 200 });
            rows.push(row);
        }
        let mut metric = base.clone();
        metric["signal"] = json!("metric");
        metric["value"] = json!(1);
        metric["name"] = json!("requests");
        metric["kind"] = json!("counter");
        metric["unit"] = json!("1");
        rows.push(metric);
        let encode = |rows: &[serde_json::Value]| {
            rows.iter()
                .map(ToString::to_string)
                .collect::<Vec<_>>()
                .join("\n")
        };
        assert!(super::check_smoke(&encode(&rows)).is_ok());

        // Same nine valid-looking records, with one tampered invariant each.
        // These negative controls must reject the precise semantic corruption
        // rather than passing because JSON parsing or counts failed.
        let mut corrupt_trace = rows.clone();
        corrupt_trace[0]["ores_trace_id"] = json!("other-trace");
        assert!(super::check_smoke(&encode(&corrupt_trace)).is_err());
        let mut corrupt_parent = rows.clone();
        corrupt_parent[6]["parent_span_id"] = json!("wrong-parent");
        assert!(super::check_smoke(&encode(&corrupt_parent)).is_err());
        let mut corrupt_duration = rows.clone();
        corrupt_duration[6]["duration_ns"] = json!(-1);
        assert!(super::check_smoke(&encode(&corrupt_duration)).is_err());
        let mut corrupt_metric = rows.clone();
        corrupt_metric[8]["value"] = json!(2);
        assert!(super::check_smoke(&encode(&corrupt_metric)).is_err());
        let mut corrupt_log = rows.clone();
        corrupt_log[0]["body"] = json!("unexpected");
        assert!(super::check_smoke(&encode(&corrupt_log)).is_err());
        let mut corrupt_timestamp = rows.clone();
        corrupt_timestamp[0]["timestamp_unix_ms"] = json!(0);
        assert!(super::check_smoke(&encode(&corrupt_timestamp)).is_err());

        let mut unknown = base;
        unknown["signal"] = json!("unexpected");
        rows.push(unknown);
        assert!(super::check_smoke(&encode(&rows)).is_err());
    }

    #[test]
    fn duplicate_fields_fail_closed_even_with_json_escape_aliases() {
        // Value deserialization alone would silently select the last body.
        let repeated = r#"{"signal":"log","body":"first","body":"second"}"#;
        assert!(serde_json::from_str::<serde_json::Value>(repeated).is_ok());
        assert!(super::parse_unique_json_line(repeated).is_err());

        // Both spellings decode to the identical JSON field name.
        let escaped = r#"{"signal":"log","body":"first","bo\u0064y":"second"}"#;
        assert!(super::parse_unique_json_line(escaped).is_err());
        let unrelated = r#"{"signal":"log","body":"the word body is not a key"}"#;
        assert!(super::parse_unique_json_line(unrelated).is_ok());

        // The line decoder must consume exactly one complete object.
        assert!(super::parse_unique_json_line("{} {}").is_err());
        assert!(super::parse_unique_json_line("[]").is_err());
        assert!(super::parse_unique_json_line("42").is_err());
    }

    #[test]
    fn span_lifecycle_oracle_rejects_late_emission_and_duplicate_finish() {
        use serde_json::json;
        let event = json!({
            "schema":"oreslang-otel.v1", "signal":"log",
            "timestamp_unix_ms":1700000000001_i64, "service":"test",
            "ores_trace_id":"ores-trace-lifecycle",
            "span_id":"root-lifecycle", "severity":"INFO",
            "body":"before-finish"
        });
        let span = json!({
            "schema":"oreslang-otel.v1", "signal":"span",
            "timestamp_unix_ms":1700000000002_i64, "service":"test",
            "ores_trace_id":"ores-trace-lifecycle",
            "span_id":"root-lifecycle", "parent_span_id":"",
            "name":"lifecycle", "duration_ns":0, "outcome":"ok",
            "http_status":200
        });
        let good = format!("{event}\n{span}\nLIFECYCLE_OK");
        assert!(super::check_span_lifecycle(&good).is_ok());
        assert!(super::check_span_lifecycle(&format!("{event}\n{span}\n{span}\nLIFECYCLE_OK")).is_err());
        assert!(super::check_span_lifecycle(&format!("{event}\n{span}\n{event}\nLIFECYCLE_OK")).is_err());
        assert!(super::check_span_lifecycle(&format!("{event}\n{span}\nBAD_SECOND_FINISH")).is_err());
        let mut bad_span = span;
        bad_span["duration_ns"] = json!(1);
        assert!(super::check_span_lifecycle(&format!("{event}\n{bad_span}\nLIFECYCLE_OK")).is_err());
    }

    #[test]
    fn malformed_or_incomplete_records_fail_closed() {
        assert!(super::check_smoke("{broken}").is_err());
        assert!(super::check_smoke("").is_err());
        assert!(super::check_smoke("{}").is_err());
    }

    #[test]
    fn record_contract_rejects_missing_extra_or_mistyped_fields() {
        use serde_json::json;
        let valid = json!({
            "schema": "oreslang-otel.v1",
            "signal": "log",
            "timestamp_unix_ms": 1700000000001_i64,
            "service": "test",
            "ores_trace_id": "ores-trace-test",
            "span_id": "",
            "severity": "INFO",
            "body": ""
        });
        assert!(super::check_record_schema(&valid).is_ok());

        for field in [
            "schema",
            "signal",
            "timestamp_unix_ms",
            "service",
            "ores_trace_id",
            "span_id",
            "severity",
            "body",
        ] {
            let mut missing = valid.clone();
            missing.as_object_mut().unwrap().remove(field);
            assert!(
                super::check_record_schema(&missing).is_err(),
                "missing {field} was accepted"
            );
        }
        let mut extra = valid.clone();
        extra["credentials"] = json!("must not be emitted");
        assert!(super::check_record_schema(&extra).is_err());
        let mut wrong_type = valid.clone();
        wrong_type["timestamp_unix_ms"] = json!("1700000000001");
        assert!(super::check_record_schema(&wrong_type).is_err());
        wrong_type["timestamp_unix_ms"] = json!(1700000000001_i64);
        wrong_type["body"] = json!({"nested": "unexpected"});
        assert!(super::check_record_schema(&wrong_type).is_err());

        let mut span = json!({
            "schema":"oreslang-otel.v1", "signal":"span",
            "timestamp_unix_ms":1700000000001_i64, "service":"test",
            "ores_trace_id":"ores-trace-test", "span_id":"child",
            "parent_span_id":"parent", "name":"work",
            "duration_ns":0, "outcome":"ok", "http_status":0
        });
        assert!(super::check_record_schema(&span).is_ok());
        span["duration_ns"] = json!(-1);
        assert!(super::check_record_schema(&span).is_err());
        span["duration_ns"] = json!(0);
        span["http_status"] = json!(700);
        assert!(super::check_record_schema(&span).is_err());

        let mut metric = json!({
            "schema":"oreslang-otel.v1", "signal":"metric",
            "timestamp_unix_ms":1700000000001_i64, "service":"test",
            "ores_trace_id":"ores-trace-test", "name":"requests",
            "kind":"counter", "unit":"1", "value":1
        });
        assert!(super::check_record_schema(&metric).is_ok());
        metric["value"] = json!(-1);
        assert!(super::check_record_schema(&metric).is_err());
        metric["value"] = json!(1);
        metric["kind"] = json!("unrecognized");
        assert!(super::check_record_schema(&metric).is_err());
    }

    #[test]
    fn metric_i64_endpoints_preserve_signed_counter_authority() {
        use serde_json::json;
        let mut metric = json!({
            "schema":"oreslang-otel.v1", "signal":"metric",
            "timestamp_unix_ms":i64::MAX, "service":"test",
            "ores_trace_id":"edge-identity", "name":"signed-count",
            "kind":"counter", "unit":"1", "value":i64::MAX
        });
        assert!(super::check_record_schema(&metric).is_ok());
        metric["value"] = json!(-1);
        assert!(super::check_record_schema(&metric).is_err());
        metric["kind"] = json!("gauge");
        assert!(super::check_record_schema(&metric).is_ok());
        metric["value"] = json!(i64::MIN);
        assert!(super::check_record_schema(&metric).is_ok());
        metric["value"] = json!("9223372036854775808");
        assert!(super::check_record_schema(&metric).is_err());
    }

    #[test]
    fn span_http_and_duration_boundaries_are_not_clamped() {
        use serde_json::json;
        let mut span = json!({
            "schema":"oreslang-otel.v1", "signal":"span",
            "timestamp_unix_ms":1, "service":"test",
            "ores_trace_id":"span-edge", "span_id":"s",
            "parent_span_id":"", "name":"edge",
            "duration_ns":i64::MAX, "outcome":"ok", "http_status":599
        });
        assert!(super::check_record_schema(&span).is_ok());
        span["http_status"] = json!(600);
        assert!(super::check_record_schema(&span).is_err());
        span["http_status"] = json!(-1);
        assert!(super::check_record_schema(&span).is_err());
        span["http_status"] = json!(0);
        span["duration_ns"] = json!(i64::MIN);
        assert!(super::check_record_schema(&span).is_err());
        span["duration_ns"] = json!(0);
        assert!(super::check_record_schema(&span).is_ok());
        span["timestamp_unix_ms"] = json!(0);
        assert!(super::check_record_schema(&span).is_err());
    }

    #[test]
    fn decoded_json_field_names_cannot_hide_duplicate_schema_or_signal() {
        for raw in [
            r#"{"schema":"oreslang-otel.v1","sch\u0065ma":"forged"}"#,
            r#"{"signal":"log","sign\u0061l":"metric"}"#,
            r#"{"ores_trace_id":"x","ores_trace_\u0069d":"y"}"#,
        ] {
            assert!(super::parse_unique_json_line(raw).is_err(), "accepted duplicate: {raw}");
        }
        // Independent field spelling is not falsely collapsed by the decoder.
        assert!(super::parse_unique_json_line(
            r#"{"schema":"one","Schema":"two"}"#
        ).is_ok());
    }

    #[test]
    fn numeric_timestamps_cannot_be_replaced_with_float_string_or_boolean() {
        use serde_json::json;
        let mut log = json!({
            "schema":"oreslang-otel.v1", "signal":"log",
            "timestamp_unix_ms":1, "service":"test",
            "ores_trace_id":"trace", "span_id":"", "severity":"INFO", "body":""
        });
        assert!(super::check_record_schema(&log).is_ok());
        for wrong in [json!(1.5), json!("1"), json!(true), json!(null), json!(-1)] {
            log["timestamp_unix_ms"] = wrong;
            assert!(super::check_record_schema(&log).is_err());
        }
        log["timestamp_unix_ms"] = json!(i64::MAX);
        assert!(super::check_record_schema(&log).is_ok());
    }
}
