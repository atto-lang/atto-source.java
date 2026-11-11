//! Stage-one Rust classifier for GitHub Actions evidence. No API/network/export.
//! The existing Python triage is authoritative until the full Rust API adapter,
//! pagination guards and fixture parity have been verified on an executed runner.
#[derive(Clone, Copy, Debug)]
pub struct Run<'a> {
    pub id: u64, pub sha: &'a str, pub attempt: u64,
    pub status: &'a str, pub conclusion: Option<&'a str>,
}
#[derive(Clone, Copy, Debug)]
pub struct Step<'a> {
    pub name: Option<&'a str>, pub status: Option<&'a str>,
    pub conclusion: Option<&'a str>, pub start: Option<&'a str>,
    pub end: Option<&'a str>,
}
#[derive(Clone, Copy, Debug)]
pub struct Job<'a> {
    pub id: u64, pub sha: Option<&'a str>, pub run_id: Option<u64>,
    pub attempt: Option<u64>, pub status: Option<&'a str>,
    pub conclusion: Option<&'a str>,
    // None = absent/null steps; Some(&[]) = observed zero executed steps.
    pub steps: Option<&'a [Step<'a>]>,
}
#[derive(Debug, PartialEq, Eq)]
pub struct Evidence {
    pub category: &'static str, pub steps_observed: Option<usize>,
    pub failing_build_tests: usize, pub failing_steps: usize,
    pub reported_failures_without_timing: usize,
}
type Result<T> = std::result::Result<T, &'static str>;

fn valid_sha(s:&str)->bool {
    s.len()==40 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn pending(s:&str)->bool {
    matches!(s,"queued"|"in_progress"|"waiting"|"pending"|"requested")
}
fn terminal(s:&str)->bool {
    matches!(s,"cancelled"|"skipped"|"timed_out"|"neutral"|"action_required"|"stale")
}
fn status_valid(s:&str)->bool {s=="completed" || pending(s)}
fn terminal_constant(s:&str)->&'static str {
    match s {"cancelled"=>"cancelled","skipped"=>"skipped","timed_out"=>"timed_out",
        "neutral"=>"neutral","action_required"=>"action_required","stale"=>"stale",
        _=>"unobserved_conclusion"}
}
fn purpose(name:Option<&str>)->&'static str {
    let Some(name)=name else{return "unknown"};
    let s=name.trim().to_ascii_lowercase();
    if s.is_empty(){return "unknown";}
    let before=s.strip_prefix("run ").unwrap_or(&s);
    let prep=["install","restore","download","fetch","prepare","bootstrap",
        "configure","initialize","set up","setup","provision","cache"];
    if ["set up job","complete job","checkout","checkout repository","checkout source",
        "checkout code","start containers","stop containers","initialize containers",
        "upload artifact","download artifact"].contains(&s.as_str())
        || s.starts_with("pre ") || s.starts_with("post ")
        || s.starts_with("set up ") || s.starts_with("setup ")
        || s.starts_with("run actions/checkout") || s.starts_with("run actions/cache")
        || s.starts_with("run actions/setup-") || s.starts_with("run actions/upload-artifact")
        || s.starts_with("run actions/download-artifact")
        || s.starts_with("run git checkout") || s.starts_with("run npm ci")
        || s.starts_with("run pip install") || s.starts_with("run mvn dependency:resolve")
        || prep.iter().any(|p| before==*p || before.starts_with(&format!("{p} ")))
    {return "housekeeping";}
    let tokens=["test","tests","testing","pytest","unittest","spec","build","compile",
        "compiler","gradle","maven","cargo","make","cmake","lint","check","validation","conformance"];
    if s.split(|c:char| !c.is_ascii_alphanumeric() && c!='_')
        .any(|part| tokens.contains(&part)){return "build_test";}
    "other_execution"
}
fn number(s:&str)->Option<i64> {
    if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()){return None;}
    s.parse().ok()
}
fn leap(y:i64)->bool {y%4==0 && (y%100!=0 || y%400==0)}
fn days_prior(y:i64)->i64 {let p=y-1;365*p+p/4-p/100+p/400}
// Strict timestamp parse; malformed times can NEVER establish executed evidence.
fn instant(s:&str)->Option<i128> {
    let b=s.as_bytes();
    if b.len()<20 || b.get(4)!=Some(&b'-') || b.get(7)!=Some(&b'-')
        || b.get(10)!=Some(&b'T') || b.get(13)!=Some(&b':')
        || b.get(16)!=Some(&b':'){return None;}
    let y=number(s.get(0..4)?)?;let m=number(s.get(5..7)?)?;
    let day=number(s.get(8..10)?)?;let h=number(s.get(11..13)?)?;
    let minute=number(s.get(14..16)?)?;let second=number(s.get(17..19)?)?;
    if y<1 || !(1..=12).contains(&m) || h>23 || minute>59 || second>59{return None;}
    let days=[31,if leap(y){29}else{28},31,30,31,30,31,31,30,31,30,31];
    if day<1 || day>days[(m-1) as usize]{return None;}
    let mut idx=19usize;let mut ns=0i64;
    if b.get(idx)==Some(&b'.'){
        idx+=1;let start=idx;
        while b.get(idx).is_some_and(u8::is_ascii_digit){idx+=1;}
        let decimal=s.get(start..idx)?;
        if decimal.is_empty() || decimal.len()>9{return None;}
        ns=number(decimal)?*10i64.pow((9-decimal.len()) as u32);
    }
    let offset=if b.get(idx)==Some(&b'Z') && idx+1==b.len(){0}
    else {
        if idx+6!=b.len() || !matches!(b.get(idx),Some(b'+')|Some(b'-'))
            || b.get(idx+3)!=Some(&b':'){return None;}
        let oh=number(s.get(idx+1..idx+3)?)?;let om=number(s.get(idx+4..idx+6)?)?;
        if oh>23 || om>59{return None;}
        let amount=oh*3600+om*60;
        if b[idx]==b'+'{amount}else{-amount}
    };
    let ordinal=days[..(m-1) as usize].iter().sum::<i64>()+day-1;
    let seconds=(days_prior(y)-days_prior(1970)+ordinal)*86400+h*3600+minute*60+second-offset;
    Some(i128::from(seconds)*1_000_000_000+i128::from(ns))
}
fn ran(step:&Step<'_>)->bool {
    step.status==Some("completed") &&
    matches!((step.start.and_then(instant),step.end.and_then(instant)),(Some(a),Some(b)) if a<=b)
}
fn evidence(category:&'static str,steps_observed:Option<usize>)->Evidence {
    Evidence{category,steps_observed,failing_build_tests:0,failing_steps:0,
        reported_failures_without_timing:0}
}
pub fn classify_job(job:&Job<'_>,run:&Run<'_>,strict_pr:bool)->Result<Evidence> {
    if job.id==0 || job.sha.is_some_and(|s| s!=run.sha) ||
        job.run_id.is_some_and(|n|n!=run.id) ||
        job.attempt.is_some_and(|n|n!=run.attempt)
        {return Err("job identity or attempt mismatch");}
    if strict_pr && (job.sha!=Some(run.sha)||job.run_id!=Some(run.id))
        {return Err("job exact-head/attempt provenance unverified");}
    if job.status.is_some_and(|s|!status_valid(s)){return Err("invalid job status");}
    let Some(status)=job.status else{return Ok(evidence("unobserved_job_status",None));};
    if pending(status){return Ok(evidence("pending",None));}
    let Some(conclusion)=job.conclusion else{return Ok(evidence("unobserved_conclusion",None));};
    if terminal(conclusion){return Ok(evidence(terminal_constant(conclusion),None));}
    if conclusion!="failure" && conclusion!="success"
        {return Ok(evidence("unobserved_conclusion",None));}
    let Some(steps)=job.steps else{return Ok(evidence(
        if conclusion=="failure"{"unobserved_failure"}else{"unobserved_success"},None));};
    let mut out=evidence("unobserved_conclusion",Some(steps.len()));
    if steps.is_empty(){out.category=if conclusion=="failure"
        {"observed_no_step_failure"}else{"success_without_step_evidence"};return Ok(out);}
    let mut setup=0;let mut unknown=0;let mut other=0;let mut successful=false;
    for s in steps {
        if s.status.is_some_and(|v|!status_valid(v)){return Err("invalid step status");}
        let executed=ran(s);let kind=purpose(s.name);
        if s.status==Some("completed") && matches!(s.conclusion,Some("failure"|"timed_out")){
            if executed {
                out.failing_steps+=1;
                match kind {"build_test"=>out.failing_build_tests+=1,
                    "housekeeping"=>setup+=1,"unknown"=>unknown+=1,_=>other+=1}
            }else{out.reported_failures_without_timing+=1;}
        }
        if executed && s.conclusion==Some("success") && kind!="housekeeping"{successful=true;}
    }
    out.category=if conclusion=="failure" {
        if out.failing_build_tests>0{"executed_build_test_failure"}
        else if other>0{"executed_step_failure_unclassified"}
        else if unknown>0{"failed_step_purpose_unknown"}
        else if setup>0{"setup_only_failure"}
        else if out.reported_failures_without_timing>0{"reported_failure_execution_unobserved"}
        else{"failure_without_completed_failing_step"}
    }else if out.failing_steps>0{"success_with_reported_failing_step"}
    else if out.reported_failures_without_timing>0{"success_with_unverified_step_failure"}
    else if successful{"executed_success"}
    else{"success_without_substantive_evidence"};
    Ok(out)
}
pub fn assess(run:&Run<'_>,jobs:&[Job<'_>],sha:&str,strict_pr:bool)->Result<(String,Vec<Evidence>)> {
    if !valid_sha(sha)||run.sha!=sha{return Err("run exact head SHA mismatch");}
    if run.id==0||run.attempt==0{return Err("invalid run identity/attempt");}
    if pending(run.status){return Ok(("pending".into(),Vec::new()));}
    if run.status!="completed"{return Err("unrecognized run status");}
    if let Some(c)=run.conclusion{if terminal(c){return Ok((terminal_constant(c).into(),Vec::new()));}}
    if !matches!(run.conclusion,Some("failure"|"success"))
        {return Ok(("unobserved_conclusion".into(),Vec::new()));}
    if jobs.is_empty(){return Ok(("no_jobs_recorded".into(),Vec::new()));}
    let outcomes=jobs.iter().map(|j|classify_job(j,run,strict_pr))
        .collect::<Result<Vec<_>>>()?;
    let present=|c:&str|outcomes.iter().any(|j|j.category==c);
    let c=if present("executed_build_test_failure"){"executed_build_test_failure"}
    else if present("executed_step_failure_unclassified"){"executed_step_failure_unclassified"}
    else if present("failed_step_purpose_unknown"){"failed_step_purpose_unknown"}
    else if present("unobserved_failure")||present("unobserved_job_status"){
        if run.conclusion==Some("failure"){"unobserved_failure"}else{"unobserved_success"}}
    else if present("setup_only_failure"){"setup_only_failure"}
    else if present("observed_no_step_failure"){"observed_no_step_failure"}
    else if present("reported_failure_execution_unobserved"){"reported_failure_execution_unobserved"}
    else if present("success_with_reported_failing_step"){"success_with_reported_failing_step"}
    else if present("success_with_unverified_step_failure"){"success_with_unverified_step_failure"}
    else if present("pending"){"pending"}
    else if run.conclusion==Some("failure"){"failure_without_completed_failing_step"}
    else if outcomes.iter().all(|j|j.category=="executed_success"){"executed_success"}
    else{"success_without_substantive_evidence"};
    Ok((c.into(),outcomes))
}

#[cfg(test)]
mod tests {
    use super::*;
    const SHA:&str="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const OTHER:&str="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    fn run(c:&'static str)->Run<'static>{Run{id:42,sha:SHA,attempt:2,status:"completed",conclusion:Some(c)}}
    fn step(name:Option<&'static str>,c:Option<&'static str>)->Step<'static>{
        Step{name,status:Some("completed"),conclusion:c,
            start:Some("2026-10-18T12:00:00Z"),end:Some("2026-10-18T12:00:03Z")}}
    fn job<'a>(c:&'a str,steps:Option<&'a [Step<'a>]>)->Job<'a>{
        Job{id:7,sha:Some(SHA),run_id:Some(42),attempt:Some(2),status:Some("completed"),
            conclusion:Some(c),steps}}
    fn cat(r:&Run<'_>,j:&[Job<'_>])->String {assess(r,j,SHA,true).unwrap().0}
    #[test]
    fn zero_steps_are_not_billing_or_compiler_failure(){
        assert_eq!(cat(&run("failure"),&[job("failure",Some(&[]))]),"observed_no_step_failure");
        assert_eq!(cat(&run("failure"),&[job("failure",None)]),"unobserved_failure");
    }
    #[test]
    fn housekeeping_is_not_a_compiler_verdict(){
        for name in ["Checkout","Run actions/checkout@v4","Set up Java","Cache dependencies",
                     "Install test dependencies","Prepare build workspace","Run npm ci"]{
            let xs=[step(Some(name),Some("failure"))];
            assert_eq!(cat(&run("failure"),&[job("failure",Some(&xs))]),"setup_only_failure");
        }
    }
    #[test]
    fn build_failure_requires_completed_timed_step(){
        for name in ["Run unit tests","Compile Oreslang","Build backend","Run conformance tests"]{
            let xs=[step(Some(name),Some("failure"))];
            assert_eq!(cat(&run("failure"),&[job("failure",Some(&xs))]),"executed_build_test_failure");
        }
        let mut s=step(Some("Run tests"),Some("failure"));s.end=None;
        assert_eq!(cat(&run("failure"),&[job("failure",Some(&[s]))]),"reported_failure_execution_unobserved");
        s.end=Some("bad time");
        assert_eq!(cat(&run("failure"),&[job("failure",Some(&[s]))]),"reported_failure_execution_unobserved");
        s.end=Some("2026-10-18T12:00:03Z");s.status=Some("queued");
        assert_eq!(cat(&run("failure"),&[job("failure",Some(&[s]))]),"failure_without_completed_failing_step");
    }
    #[test]
    fn timestamps_require_real_utc_order_and_offset(){
        assert_eq!(instant("2026-10-18T08:00:00-04:00"),instant("2026-10-18T12:00:00Z"));
        assert_eq!(instant("2026-10-18T12:00:00.12Z"),instant("2026-10-18T12:00:00.120Z"));
        for s in ["banana","2026-02-29T00:00:00Z","2026-99-99T12:00:00Z",
                  "2026-10-18T12:00:00","2026-10-18T25:00:00Z"]{
            assert!(instant(s).is_none(),"{s}");
        }
    }
    #[test]
    fn exact_head_run_and_job_identity_fail_closed(){
        assert!(assess(&run("failure"),&[],OTHER,true).is_err());
        let steps=[step(Some("Run tests"),Some("failure"))];
        let j=job("failure",Some(&steps));
        assert!(classify_job(&Job{sha:Some(OTHER),..j},&run("failure"),true).is_err());
        assert!(classify_job(&Job{attempt:Some(3),..j},&run("failure"),true).is_err());
        assert!(classify_job(&Job{run_id:None,..j},&run("failure"),true).is_err());
    }
    #[test]
    fn success_cannot_hide_failed_test_step(){
        let xs=[step(Some("Run tests"),Some("failure")),step(Some("Complete job"),Some("success"))];
        assert_eq!(cat(&run("success"),&[job("success",Some(&xs))]),"success_with_reported_failing_step");
        let ok=[step(Some("Checkout"),Some("success")),step(Some("Run tests"),Some("success"))];
        assert_eq!(cat(&run("success"),&[job("success",Some(&ok))]),"executed_success");
    }
    #[test]
    fn other_failure_is_not_called_compiler_failure(){
        let xs=[step(Some("Do something"),Some("failure"))];
        assert_eq!(cat(&run("failure"),&[job("failure",Some(&xs))]),"executed_step_failure_unclassified");
        let ys=[step(None,Some("failure"))];
        assert_eq!(cat(&run("failure"),&[job("failure",Some(&ys))]),"failed_step_purpose_unknown");
    }
    #[test]
    fn invalid_status_and_incomplete_observation_are_distinct(){
        let mut s=step(Some("Run tests"),Some("failure"));s.status=Some("weird");
        assert!(assess(&run("failure"),&[job("failure",Some(&[s]))],SHA,true).is_err());
        let mut r=run("failure");r.status="queued";
        assert_eq!(cat(&r,&[]),"pending");
        for terminal in ["cancelled","skipped","timed_out","neutral","action_required","stale"]{
            assert_eq!(cat(&run(terminal),&[]),terminal);
        }
    }
    #[test]
    fn mixed_jobs_preserve_individual_evidence(){
        let steps=[step(Some("Run tests"),Some("failure"))];
        let (category,jobs)=assess(&run("failure"),&[job("failure",None),job("failure",Some(&steps))],SHA,true).unwrap();
        assert_eq!(category,"executed_build_test_failure");
        assert_eq!(jobs[0].category,"unobserved_failure");
        assert_eq!(jobs[1].failing_build_tests,1);
    }
}
