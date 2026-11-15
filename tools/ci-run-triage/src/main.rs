//! Read-only, exact-attempt GitHub Actions triage frontend.
//! This is staged migration code. Do not substitute for the Python CI gate
//! until real Rust execution and full adversarial parity are verified.
#[path = "../../ci_run_triage_core.rs"]
mod core;

use core::{Job, Run, Step};
use serde::de::{self, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer};
use serde_json::{Map, Number, Value};
use std::collections::HashSet;
use std::fmt;
use std::process::{Command, Stdio};

// serde_json::Value alone silently accepts duplicate JSON member names.
// This recursive visitor rejects ambiguous JSON throughout the response.
struct StrictJson(Value);
impl<'de> Deserialize<'de> for StrictJson {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct StrictVisitor;
        impl<'de> Visitor<'de> for StrictVisitor {
            type Value = StrictJson;
            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str("JSON without duplicate object keys")
            }
            fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::Null))
            }
            fn visit_none<E: de::Error>(self) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::Null))
            }
            fn visit_bool<E: de::Error>(self, b: bool) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::Bool(b)))
            }
            fn visit_i64<E: de::Error>(self, n: i64) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::Number(Number::from(n))))
            }
            fn visit_u64<E: de::Error>(self, n: u64) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::Number(Number::from(n))))
            }
            fn visit_f64<E: de::Error>(self, n: f64) -> Result<Self::Value, E> {
                let number = Number::from_f64(n).ok_or_else(|| E::custom("nonfinite JSON number"))?;
                Ok(StrictJson(Value::Number(number)))
            }
            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::String(value.into())))
            }
            fn visit_string<E: de::Error>(self, value: String) -> Result<Self::Value, E> {
                Ok(StrictJson(Value::String(value)))
            }
            fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
                let mut out = Vec::new();
                while let Some(value) = seq.next_element::<StrictJson>()? {
                    if out.len() >= 100_000 { return Err(de::Error::custom("oversized JSON sequence")); }
                    out.push(value.0);
                }
                Ok(StrictJson(Value::Array(out)))
            }
            fn visit_map<A: MapAccess<'de>>(self, mut entries: A) -> Result<Self::Value, A::Error> {
                let mut out = Map::new();
                while let Some((key, value)) = entries.next_entry::<String, StrictJson>()? {
                    if out.contains_key(&key) {
                        return Err(de::Error::custom("duplicate JSON object member"));
                    }
                    if out.len() >= 100_000 { return Err(de::Error::custom("oversized JSON object")); }
                    out.insert(key, value.0);
                }
                Ok(StrictJson(Value::Object(out)))
            }
        }
        deserializer.deserialize_any(StrictVisitor)
    }
}

fn parse_json(body: &str) -> Result<Value, String> {
    if body.len() > 8 * 1024 * 1024 { return Err("oversized GitHub API response".into()); }
    serde_json::from_str::<StrictJson>(body).map(|v| v.0)
        .map_err(|_| "invalid or ambiguous GitHub API JSON".into())
}
fn obj<'a>(v: &'a Value) -> Result<&'a Map<String, Value>, String> {
    v.as_object().ok_or_else(|| "invalid GitHub object shape".into())
}
fn positive(v: &Value) -> Result<u64, String> {
    v.as_u64().filter(|n| *n > 0)
        .ok_or_else(|| "invalid positive integer".into())
}
fn optional_positive(v: Option<&Value>) -> Result<Option<u64>, String> {
    match v {None | Some(Value::Null) => Ok(None), Some(v) => positive(v).map(Some)}
}
fn optional_string<'a>(v: Option<&'a Value>) -> Result<Option<&'a str>, String> {
    match v {None | Some(Value::Null) => Ok(None),
        Some(Value::String(s)) => Ok(Some(s)),
        _ => Err("invalid string field".into())}
}
fn string<'a>(v: Option<&'a Value>) -> Result<&'a str, String> {
    optional_string(v)?.ok_or_else(|| "missing required string".into())
}
fn positive_field(o: &Map<String, Value>, field: &str) -> Result<u64, String> {
    positive(o.get(field).ok_or_else(|| "missing integer field".to_string())?)
}
fn valid_sha(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn valid_repo(s: &str) -> bool {
    let parts: Vec<_> = s.split('/').collect();
    parts.len() == 2 && parts.iter().all(|p| {
        p.as_bytes().first().is_some_and(u8::is_ascii_alphanumeric)
            && p.bytes().all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(&c))
    })
}
fn parse_run<'a>(v: &'a Value) -> Result<Run<'a>, String> {
    let o = obj(v)?;
    Ok(Run {id:positive_field(o,"id")?,sha:string(o.get("head_sha"))?,
        attempt:positive_field(o,"run_attempt")?,
        status:string(o.get("status"))?, conclusion:optional_string(o.get("conclusion"))?})
}
fn parse_step<'a>(v: &'a Value) -> Result<Step<'a>, String> {
    let o = obj(v)?;
    Ok(Step {name:optional_string(o.get("name"))?,status:optional_string(o.get("status"))?,
        conclusion:optional_string(o.get("conclusion"))?,
        start:optional_string(o.get("started_at"))?,end:optional_string(o.get("completed_at"))?})
}
struct ParsedJob<'a> {
    id: u64, sha: Option<&'a str>, run_id: Option<u64>, attempt: Option<u64>,
    status: Option<&'a str>, conclusion: Option<&'a str>,
    steps: Option<Vec<Step<'a>>>,
}
impl<'a> ParsedJob<'a> {
    fn as_model(&self) -> Job<'_> {
        Job {id:self.id,sha:self.sha,run_id:self.run_id,attempt:self.attempt,
            status:self.status,conclusion:self.conclusion,steps:self.steps.as_deref()}
    }
}
fn parse_job<'a>(v: &'a Value) -> Result<ParsedJob<'a>, String> {
    let o=obj(v)?;
    let steps = match o.get("steps") {
        None | Some(Value::Null) => None,
        Some(Value::Array(a)) => Some(a.iter().map(parse_step).collect::<Result<Vec<_>,_>>()?),
        _ => return Err("invalid steps collection".into()),
    };
    Ok(ParsedJob {id:positive_field(o,"id")?,
        sha:optional_string(o.get("head_sha"))?,
        run_id:optional_positive(o.get("run_id"))?,
        attempt:optional_positive(o.get("run_attempt"))?,
        status:optional_string(o.get("status"))?,
        conclusion:optional_string(o.get("conclusion"))?,steps})
}
fn check_pr(pr: &Value, expected_sha: &str, number: u64) -> Result<(), String> {
    let o=obj(pr)?;
    if positive_field(o,"number")? != number || string(o.get("state"))? != "open" {
        return Err("PR identity unavailable or not open".into());
    }
    let head=obj(o.get("head").ok_or("missing PR head")?)?;
    if string(head.get("sha"))? != expected_sha {
        return Err("PR current head SHA mismatch".into());
    }
    Ok(())
}
fn check_run(run: &Value, id: u64, sha: &str) -> Result<(), String> {
    let parsed=parse_run(run)?;
    if parsed.id != id || parsed.sha != sha {
        return Err("initial GitHub run identity or head SHA mismatch".into());
    }
    Ok(())
}
fn run_identity_stable(a: &Value, b: &Value) -> Result<(), String> {
    let aa=obj(a)?;let bb=obj(b)?;
    for key in ["id","head_sha","run_attempt","status","conclusion"] {
        if aa.get(key)!=bb.get(key) {return Err("GitHub run changed during inspection".into());}
    }
    Ok(())
}
fn scoped_jobs<F>(repo: &str, id: u64, attempt: u64, fetch: &mut F)
    -> Result<Vec<Value>, String>
where F: FnMut(&str)->Result<Value,String> {
    let mut jobs=Vec::new();let mut expected:Option<usize>=None;
    let mut seen=HashSet::<u64>::new();
    for page in 1..=100 {
        let mut path=format!("repos/{repo}/actions/runs/{id}/attempts/{attempt}/jobs?per_page=100");
        if page>1 {path.push_str(&format!("&page={page}"));}
        let json=fetch(&path)?;
        let o=obj(&json)?;
        let total=o.get("total_count").and_then(Value::as_u64)
            .filter(|n| *n<=10_000).ok_or("invalid job total count")? as usize;
        if expected.is_some_and(|old|old!=total) {
            return Err("job pagination total changed".into());
        }
        expected=Some(total);
        let batch=o.get("jobs").and_then(Value::as_array).ok_or("missing jobs array")?;
        if jobs.len()>total || batch.len()!=100.min(total-jobs.len()) {
            return Err("job pagination incomplete or invalid page length".into());
        }
        for j in batch {
            let id=positive_field(obj(j)?,"id")?;
            if !seen.insert(id) {return Err("job pagination duplicate ID".into());}
        }
        jobs.extend(batch.iter().cloned());
        if jobs.len()==total {return Ok(jobs);}
    }
    Err("job pagination limit exceeded".into())
}
fn inspect<F>(repo: &str, id: u64, sha: &str, pr: Option<u64>, mut fetch: F)
    -> Result<Value,String>
where F: FnMut(&str)->Result<Value,String> {
    if !valid_repo(repo) || id==0 || !valid_sha(sha) || pr==Some(0) {
        return Err("invalid exact repository/run/PR inputs".into());
    }
    let pr_path=pr.map(|n|format!("repos/{repo}/pulls/{n}"));
    if let (Some(n),Some(path))=(pr,pr_path.as_ref()) {
        check_pr(&fetch(path)?,sha,n)?;
    }
    let run_path=format!("repos/{repo}/actions/runs/{id}");
    let first=fetch(&run_path)?;
    check_run(&first,id,sha)?;
    let run=parse_run(&first)?;
    let attempts=run.attempt;
    let all=scoped_jobs(repo,id,attempts,&mut fetch)?;
    if pr.is_some() {
        for value in &all {
            let o=obj(value)?;
            if positive_field(o,"run_id")? != id
                || string(o.get("head_sha"))? != sha
                || optional_positive(o.get("run_attempt"))?.is_some_and(|a|a!=attempts) {
                return Err("job exact-head/attempt provenance unverified".into());
            }
        }
    }
    let last=fetch(&run_path)?;
    run_identity_stable(&first,&last)?;
    if let (Some(n),Some(path))=(pr,pr_path.as_ref()) {
        check_pr(&fetch(path)?,sha,n)?;
    }
    let parsed=all.iter().map(parse_job).collect::<Result<Vec<_>,_>>()?;
    let models=parsed.iter().map(|p| p.as_model()).collect::<Vec<_>>();
    let (category,details)=core::assess(&run,&models,sha,pr.is_some())
        .map_err(|_|"invalid or inconsistent run/job evidence".to_string())?;
    let safe_jobs=details.iter().zip(&parsed).map(|(d,j)|serde_json::json!({
        "job_id":j.id,"classification":d.category,"steps_observed":d.steps_observed,
        "completed_failing_steps":d.failing_steps,
        "completed_build_test_failures":d.failing_build_tests,
        "reported_failures_without_timing":d.reported_failures_without_timing,
        "purpose_confidence":"HEURISTIC_ONLY"
    })).collect::<Vec<_>>();
    Ok(serde_json::json!({
        "repository":repo,"run_id":id,"head_sha":sha,"run_attempt":attempts,
        "pr_number":pr,"pr_head_verified":pr.is_some(),
        "job_provenance_verified":pr.is_some()&&!all.is_empty(),
        "category":category,"job_count":all.len(),"jobs":safe_jobs
    }))
}
fn get_json(path: &str) -> Result<Value,String> {
    // Linux CI platform. timeout fails closed if GitHub API stalls.
    // Never log gh stderr/stdout on failure, since they may contain secrets.
    let output=Command::new("timeout").args(["--signal=KILL","20s","gh","api",
        "--hostname","github.com","--method","GET",path])
        .stdin(Stdio::null()).output()
        .map_err(|_|"GitHub API process unavailable".to_string())?;
    if !output.status.success() {
        return Err("GitHub API request unavailable or timed out".into());
    }
    if output.stdout.len()>8*1024*1024 {
        return Err("oversized GitHub API response".into());
    }
    let body=std::str::from_utf8(&output.stdout).map_err(|_|"non-UTF8 GitHub API response")?;
    parse_json(body)
}
fn usage()->Result<(String,u64,String,Option<u64>),String>{
    let args=std::env::args().skip(1).collect::<Vec<_>>();
    if args.len()!=3 && args.len()!=5 {
        return Err("expected repository run_id exact_sha [--pr PR_NUMBER]".into());
    }
    if args.len()==5 && args[3]!="--pr" {return Err("unknown argument".into());}
    let id=args[1].parse::<u64>().map_err(|_|"invalid run ID")?;
    let pr=if args.len()==5 {Some(args[4].parse::<u64>().map_err(|_|"invalid PR ID")?)}else{None};
    if !valid_repo(&args[0])||id==0||!valid_sha(&args[2])||pr==Some(0){
        return Err("invalid repository/run/PR arguments".into());
    }
    Ok((args[0].clone(),id,args[2].clone(),pr))
}
fn main(){
    let result=usage().and_then(|(repo,id,sha,pr)|inspect(&repo,id,&sha,pr,get_json));
    match result {
        Ok(report)=>{
            println!("{}",report);
            std::process::exit(if report["category"]=="executed_success"{0}else{1});
        }
        Err(_)=>{
            // Error text is intentionally generic: no API data or credentials.
            eprintln!("CI_TRIAGE_INCONCLUSIVE: invalid/unavailable exact-head CI evidence");
            std::process::exit(2);
        }
    }
}
#[cfg(test)]
mod tests{
    use super::*;
    const SHA:&str="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    fn run()->Value{
        serde_json::json!({"id":42,"status":"completed","conclusion":"failure",
            "head_sha":SHA,"run_attempt":2})
    }
    fn job(id:u64)->Value {
        serde_json::json!({"id":id,"run_id":42,"head_sha":SHA,"run_attempt":2,
            "status":"completed","conclusion":"failure","steps":[]})
    }
    fn fetch_single(path:&str)->Result<Value,String> {
        if path.ends_with("pulls/17"){return Ok(serde_json::json!({
            "number":17,"state":"open","head":{"sha":SHA}}));}
        if path.contains("jobs?"){return Ok(serde_json::json!({"total_count":1,"jobs":[job(1)]}));}
        Ok(run())
    }
    #[test]
    fn exact_attempt_and_pr_head_verified(){
        let r=inspect("org/repo",42,SHA,Some(17),fetch_single).unwrap();
        assert_eq!(r["category"],"observed_no_step_failure");
        assert_eq!(r["job_provenance_verified"],true);
        assert_eq!(r["run_attempt"],2);
    }
    #[test]
    fn read_only_attempt_path_and_order(){
        let mut paths=Vec::new();
        let result=inspect("org/repo",42,SHA,Some(17),|path|{
            paths.push(path.to_string());fetch_single(path)
        });
        assert!(result.is_ok());
        assert_eq!(paths,vec![
            "repos/org/repo/pulls/17",
            "repos/org/repo/actions/runs/42",
            "repos/org/repo/actions/runs/42/attempts/2/jobs?per_page=100",
            "repos/org/repo/actions/runs/42",
            "repos/org/repo/pulls/17"]);
    }
    #[test]
    fn duplicate_keys_are_rejected_recursively(){
        for text in [r#"{"a":1,"a":2}"#,r#"{"nested":{"a":1,"a":3}}"#]{
            assert!(parse_json(text).is_err());
        }
        assert!(parse_json(r#"{"a":[1,true,null]}"#).is_ok());
    }
    #[test]
    fn malformed_types_denied(){
        for payload in [r#"{"status":[]}"#,r#"{"id":true}"#]{
            let v=parse_json(payload).unwrap();
            assert!(parse_run(&v).is_err());
        }
        let v=serde_json::json!({"id":1,"status":"completed","conclusion":"failure","steps":"bad"});
        assert!(parse_job(&v).is_err());
    }
    #[test]
    fn pagination_detects_racing_total_and_duplicate_ids(){
        let r=inspect("org/repo",42,SHA,None,|path|{
            if path.contains("jobs?"){
                if path.contains("page=2"){return Ok(serde_json::json!({"total_count":102,"jobs":[job(1),job(2)]}));}
                return Ok(serde_json::json!({"total_count":101,"jobs":(1..=100).map(job).collect::<Vec<_>>()}));
            }
            Ok(run())
        });
        assert!(r.is_err());
        let r2=inspect("org/repo",42,SHA,None,|path|{
            if path.contains("jobs?"){
                if path.contains("page=2"){return Ok(serde_json::json!({"total_count":101,"jobs":[job(1)]}));}
                return Ok(serde_json::json!({"total_count":101,"jobs":(1..=100).map(job).collect::<Vec<_>>()}));
            }
            Ok(run())
        });
        assert!(r2.is_err());
    }
    #[test]
    fn complete_pagination_101_jobs(){
        let r=inspect("org/repo",42,SHA,None,|path|{
            if path.contains("jobs?"){
                if path.contains("page=2"){return Ok(serde_json::json!({"total_count":101,"jobs":[job(101)]}));}
                return Ok(serde_json::json!({"total_count":101,"jobs":(1..=100).map(job).collect::<Vec<_>>()}));
            }
            Ok(run())
        }).unwrap();
        assert_eq!(r["job_count"],101);
        assert_eq!(r["category"],"observed_no_step_failure");
    }
    #[test]
    fn run_changed_and_stale_pr_rejected(){
        let mut count=0;
        let x=inspect("org/repo",42,SHA,None,|path|{
            if path.contains("jobs?"){return Ok(serde_json::json!({"total_count":1,"jobs":[job(1)]}));}
            count+=1;
            let mut r=run();
            if count==2{r["run_attempt"]=serde_json::json!(3);}
            Ok(r)
        });
        assert!(x.is_err());
        let mut count=0;
        let y=inspect("org/repo",42,SHA,Some(17),|path|{
            if path.ends_with("pulls/17"){
                count+=1;let mut p=serde_json::json!({"number":17,"state":"open","head":{"sha":SHA}});
                if count==2{p["head"]["sha"]=serde_json::json!("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");}
                return Ok(p);
            }
            fetch_single(path)
        });
        assert!(y.is_err());
    }
    #[test]
    fn invalid_inputs_never_fetch(){
        let forbidden=|_:&str|->Result<Value,String>{panic!("unexpected API request")};
        assert!(inspect("../repo",42,SHA,None,forbidden).is_err());
        assert!(inspect("org/repo",0,SHA,None,forbidden).is_err());
        assert!(inspect("org/repo",42,"bad",None,forbidden).is_err());
    }
}
