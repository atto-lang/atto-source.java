//! Strict v2 core trace safety checker.
//! This validates supplied events, NOT their provenance or completeness.
//! Runtime refinement claims require compiler/runtime instrumentation.
use serde::Deserialize;
use std::collections::{HashMap,HashSet,VecDeque};

#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
struct Trace {
    schema_version:String,
    complete:bool,
    capacity:usize,
    events:Vec<Event>,
}
#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
struct Event {
    seq:u64,
    kind:String,
    actor:String,
    #[serde(default,deserialize_with="nonnull_string")]
    worker:Option<String>,
    #[serde(default,deserialize_with="nonnull_string")]
    target:Option<String>,
    #[serde(default,deserialize_with="nonnull_string")]
    message_id:Option<String>,
}
fn nonnull_string<'de,D:serde::Deserializer<'de>>(d:D)->Result<Option<String>,D::Error>{
    String::deserialize(d).map(Some)
}
#[derive(Default)]
struct Actor {
    live:bool,
    carrier:Option<String>,
    waiting:bool,
    ready:bool,
    mail:VecDeque<String>,
}
#[derive(Debug,PartialEq,Eq)]
pub struct Report{pub events:usize,pub actors:usize,pub messages:usize}
#[derive(Debug,PartialEq,Eq)]
pub struct Violation{pub index:usize,pub reason:String}
impl std::fmt::Display for Violation{
    fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{
        write!(f,"core trace event {}: {}",self.index,self.reason)
    }
}
impl std::error::Error for Violation{}
fn fail(index:usize,msg:&str)->Violation{
    Violation{index,reason:msg.to_string()}
}
fn ident(s:&str)->bool{
    !s.is_empty()&&s.len()<=80&&s.bytes().all(|x|
        x.is_ascii_alphanumeric()||x==b'_'||x==b'-')
}
fn required<'a>(x:&'a Option<String>,i:usize,field:&str)->Result<&'a str,Violation>{
    x.as_deref().ok_or_else(||fail(i,field))
}
pub fn check(input:&str)->Result<Report,Violation>{
    if input.len()>16*1024*1024 {return Err(fail(0,"trace exceeds 16 MiB"))}
    let t:Trace=serde_json::from_str(input)
        .map_err(|_|fail(0,"invalid core trace JSON"))?;
    if t.schema_version!="2.0.0" {return Err(fail(0,"unsupported core trace schema"))}
    if t.capacity==0||t.capacity>65_536{return Err(fail(0,"invalid mailbox capacity"))}
    if t.events.len()>100_000 {return Err(fail(0,"core trace event budget exceeded"))}
    let mut actors=HashMap::<String,Actor>::new();
    let mut workers=HashMap::<String,String>::new();
    let mut messages=HashMap::<String,String>::new();
    let mut seen_received=HashSet::<String>::new();
    for (i,e) in t.events.iter().enumerate(){
        if e.seq!=i as u64{return Err(fail(i,"noncontiguous event sequence"))}
        if !ident(&e.actor){return Err(fail(i,"invalid actor id"))}
        let fields=match e.kind.as_str(){
            "spawn"|"terminate"|"io_complete"=>(false,false,false),
            "admit"|"receive"|"discard"=>(false,false,true),
            "send"=>(false,true,true),
            "acquire"|"release"|"suspend"|"resume"=>(true,false,false),
            _=>return Err(fail(i,"unknown core event kind")),
        };
        if (e.worker.is_some(),e.target.is_some(),e.message_id.is_some())!=fields{
            return Err(fail(i,"incorrect fields for core event kind"))
        }
        for v in [&e.worker,&e.target,&e.message_id].into_iter().flatten(){
            if !ident(v){return Err(fail(i,"invalid field identifier"))}
        }
        if e.kind=="spawn"{
            if actors.contains_key(&e.actor){return Err(fail(i,"duplicate or reused actor id"))}
            actors.insert(e.actor.clone(),Actor{live:true,..Actor::default()});
            continue;
        }
        let active=actors.get(&e.actor).is_some_and(|a|a.live);
        if !active{return Err(fail(i,"event from unknown or terminated actor"))}
        match e.kind.as_str(){
            "admit"|"send"=>{
                if e.kind=="send"&&actors.get(&e.actor).is_none_or(|a|a.carrier.is_none()||a.waiting){
                    return Err(fail(i,"send without active guest execution lease"))
                }
                let target=if e.kind=="admit"{e.actor.as_str()}
                    else{required(&e.target,i,"send missing target")?};
                if !actors.get(target).is_some_and(|a|a.live){
                    return Err(fail(i,"admission to unknown or terminated actor"))
                }
                let id=required(&e.message_id,i,"message ID missing")?;
                if messages.contains_key(id){return Err(fail(i,"duplicate message ID"))}
                if actors.get(target).is_some_and(|a|a.mail.len()>=t.capacity){
                    return Err(fail(i,"mailbox capacity exceeded"))
                }
                messages.insert(id.to_string(),target.to_string());
                actors.get_mut(target).expect("live").mail.push_back(id.to_string());
            }
            "acquire"=>{
                let worker=required(&e.worker,i,"worker missing")?;
                if workers.contains_key(worker){return Err(fail(i,"worker double leased"))}
                let a=actors.get_mut(&e.actor).expect("live");
                if a.carrier.is_some(){return Err(fail(i,"actor has duplicate carrier"))}
                if (a.waiting&&!a.ready)||(!a.waiting&&a.mail.is_empty()){
                    return Err(fail(i,"acquire without runnable work or ready continuation"))
                }
                a.carrier=Some(worker.to_string());
                workers.insert(worker.to_string(),e.actor.clone());
            }
            "receive"=>{
                let m=required(&e.message_id,i,"message missing")?;
                let a=actors.get_mut(&e.actor).expect("live");
                if a.carrier.is_none()||a.waiting{
                    return Err(fail(i,"receive without active execution lease"))
                }
                if a.mail.front().map(String::as_str)!=Some(m){
                    return Err(fail(i,"out-of-order, missing, or duplicate receive"))
                }
                a.mail.pop_front();
                if !seen_received.insert(m.to_string()){return Err(fail(i,"duplicate delivery"))}
            }
            "release"|"suspend"=>{
                let worker=required(&e.worker,i,"worker missing")?;
                let a=actors.get_mut(&e.actor).expect("live");
                if a.carrier.as_deref()!=Some(worker)
                    || workers.get(worker).map(String::as_str)!=Some(e.actor.as_str()){
                    return Err(fail(i,"carrier release by non-owner"))
                }
                if e.kind=="suspend" {
                    if a.waiting{return Err(fail(i,"second suspension without resume"))}
                    a.waiting=true;a.ready=false;
                }
                a.carrier=None;workers.remove(worker);
            }
            "io_complete"=>{
                let a=actors.get_mut(&e.actor).expect("live");
                if !a.waiting||a.ready{return Err(fail(i,"lost or duplicate IO completion"))}
                a.ready=true;
            }
            "resume"=>{
                let worker=required(&e.worker,i,"worker missing")?;
                let a=actors.get_mut(&e.actor).expect("live");
                if a.carrier.as_deref()!=Some(worker)||!a.waiting||!a.ready{
                    return Err(fail(i,"resume without ready continuation lease"))
                }
                a.waiting=false;a.ready=false;
            }
            "discard"=>{
                let m=required(&e.message_id,i,"message missing")?;
                let a=actors.get_mut(&e.actor).expect("live");
                if a.carrier.is_some(){return Err(fail(i,"discard while guest is executing"))}
                if !a.mail.iter().any(|x|x==m){return Err(fail(i,"discard of missing message"))}
                a.mail.retain(|x|x!=m);
            }
            "terminate"=>{
                let a=actors.get_mut(&e.actor).expect("live");
                if a.carrier.is_some()||!a.mail.is_empty(){
                    return Err(fail(i,"terminated with active lease or pending mail"))
                }
                // External cancellation may seal an actor waiting for I/O.
                a.live=false;a.waiting=false;a.ready=false;
            }
            _=>unreachable!("event kinds validated"),
        }
    }
    if t.complete&&(actors.values().any(|a|a.live||a.carrier.is_some()||!a.mail.is_empty())
            ||!workers.is_empty()){
        return Err(fail(t.events.len(),"complete core trace retains live work"))
    }
    Ok(Report{events:t.events.len(),actors:actors.len(),messages:messages.len()})
}
