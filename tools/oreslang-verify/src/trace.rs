//! Checks the existing actor trace v1.0 JSON contract against a deterministic
//! protocol monitor. A passing trace is evidence only for the *recorded* events.
use serde::{Deserialize, Deserializer};
fn non_null_optional<'de, D: Deserializer<'de>>(d: D) -> Result<Option<String>, D::Error> {
    String::deserialize(d).map(Some)
}
use std::collections::{HashMap,HashSet};
#[derive(Deserialize,Debug)]
#[serde(deny_unknown_fields)]
pub struct Trace {
    pub schema_version:String,
    pub complete:bool,
    pub events:Vec<Event>,
}
#[derive(Deserialize,Debug)]
#[serde(deny_unknown_fields)]
pub struct Event{
    pub seq:u64,
    pub kind:String,
    pub actor:String,
    #[serde(default, deserialize_with = "non_null_optional")]
    pub target:Option<String>,
    #[serde(default, deserialize_with = "non_null_optional")]
    pub message_id:Option<String>,
    #[serde(default, deserialize_with = "non_null_optional")]
    pub resource:Option<String>,
}
#[derive(Debug,PartialEq,Eq)]
pub struct Summary{pub events:usize,pub actors:usize,pub messages_sent:usize,pub messages_received:usize}
#[derive(Debug)]
pub struct Violation{pub index:usize,pub reason:String}
impl std::fmt::Display for Violation{
    fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{
        write!(f,"trace event {}: {}",self.index,self.reason)
    }
}
impl std::error::Error for Violation{}
fn fail(index:usize,reason:impl Into<String>)->Violation{
    Violation{index,reason:reason.into()}
}
fn ident(s:&str)->bool{
    !s.is_empty()&&s.len()<=80&&s.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'_'||c==b'-')
}
fn actor_ident(s:&str)->bool{
    s.len()<=80&&s.bytes().next().is_some_and(|c|c.is_ascii_lowercase())
        &&s.bytes().all(|c|c.is_ascii_lowercase()||c.is_ascii_digit()||c==b'_'||c==b'-')
}
fn required<'a>(value:&'a Option<String>,index:usize,field:&str)->Result<&'a str,Violation>{
    value.as_deref().ok_or_else(||fail(index,format!("missing {field}")))
}
fn fields(event:&Event,index:usize)->Result<(),Violation>{
    let(kind,msg,target,res)=(
        event.kind.as_str(),event.message_id.is_some(),
        event.target.is_some(),event.resource.is_some());
    let valid=match kind{
        "spawn"|"terminate"=>!msg&&!target&&!res,
        "admit"|"receive"=>msg&&!target&&!res,
        "send"=>msg&&target&&!res,
        "exclusive_acquire"|"exclusive_release"=>!msg&&!target&&res,
        _=>return Err(fail(index,"unknown event kind")),
    };
    if !valid{return Err(fail(index,"missing or unexpected event-specific fields"))}
    if !actor_ident(&event.actor){return Err(fail(index,"invalid actor identifier"))}
    for value in [&event.message_id,&event.target,&event.resource].into_iter().flatten(){
        if !ident(value){return Err(fail(index,"invalid identifier"))}
    }
    Ok(())
}
pub fn check(input:&str)->Result<Summary,Violation>{
    if input.len()>16*1024*1024{return Err(fail(0,"trace exceeds 16 MiB input budget"))}
    let trace:Trace=serde_json::from_str(input).map_err(|e|fail(0,format!("invalid trace JSON: {e}")))?;
    if trace.schema_version!="1.0.0"{return Err(fail(0,"unsupported trace schema version"))}
    if trace.events.len()>100_000{return Err(fail(0,"trace event budget exceeded"))}
    let mut live=HashSet::new();
    let mut retired=HashSet::new();
    let mut messages=HashMap::<String,String>::new();
    let mut received=HashSet::new();
    let mut owners=HashMap::<String,String>::new();
    for (i,e) in trace.events.iter().enumerate(){
        if e.seq!=i as u64{return Err(fail(i,"noncontiguous sequence"))}
        fields(e,i)?;
        if e.kind=="spawn" {
            if live.contains(&e.actor)||retired.contains(&e.actor){
                return Err(fail(i,"duplicate actor spawn"))
            }
            live.insert(e.actor.clone());
            continue;
        }
        if !live.contains(&e.actor){return Err(fail(i,"event from unknown/terminated actor"))}
        match e.kind.as_str(){
            "admit"|"send"=>{
                let message=required(&e.message_id,i,"message_id")?;
                let target=if e.kind=="admit"{&e.actor}
                    else{e.target.as_ref().expect("checked fields")};
                if !live.contains(target){return Err(fail(i,"target is not live"))}
                if messages.insert(message.to_owned(),target.clone()).is_some(){
                    return Err(fail(i,"duplicate message identifier"))
                }
            }
            "receive"=>{
                let m=required(&e.message_id,i,"message_id")?;
                if messages.get(m)!=Some(&e.actor)||!received.insert(m.to_owned()){
                    return Err(fail(i,"unsent, wrong-target, or duplicate receive"))
                }
            }
            "exclusive_acquire"=>{
                let resource=required(&e.resource,i,"resource")?;
                if owners.insert(resource.to_owned(),e.actor.clone()).is_some(){
                    return Err(fail(i,"exclusive resource double-acquired"))
                }
            }
            "exclusive_release"=>{
                let resource=required(&e.resource,i,"resource")?;
                if owners.get(resource)!=Some(&e.actor){
                    return Err(fail(i,"release by non-owner"))
                }
                owners.remove(resource);
            }
            "terminate"=>{
                if owners.values().any(|owner|owner==&e.actor){
                    return Err(fail(i,"actor terminated owning exclusive resource"))
                }
                live.remove(&e.actor);
                retired.insert(e.actor.clone());
            }
            _=>unreachable!("checked event kind"),
        }
    }
    let i=trace.events.len();
    if trace.complete{
        if !live.is_empty(){return Err(fail(i,"complete trace retains live actors"))}
        if !owners.is_empty(){return Err(fail(i,"complete trace retains exclusive leases"))}
        if messages.len()!=received.len(){return Err(fail(i,"complete trace retains undelivered messages"))}
    }
    Ok(Summary{events:i,actors:live.len()+retired.len(),
        messages_sent:messages.len(),messages_received:received.len()})
}
#[cfg(test)]
mod tests{
    use super::*;
    const VALID:&str=r#"{"schema_version":"1.0.0","complete":true,"events":[
      {"seq":0,"kind":"spawn","actor":"a"},
      {"seq":1,"kind":"admit","actor":"a","message_id":"m1"},
      {"seq":2,"kind":"receive","actor":"a","message_id":"m1"},
      {"seq":3,"kind":"exclusive_acquire","actor":"a","resource":"lock"},
      {"seq":4,"kind":"exclusive_release","actor":"a","resource":"lock"},
      {"seq":5,"kind":"terminate","actor":"a"}]}"#;
    #[test]fn valid_real_contract(){assert_eq!(check(VALID).unwrap().messages_received,1)}
    #[test]fn wrong_sequence(){assert!(check(&VALID.replace("\"seq\":2","\"seq\":5")).is_err())}
    #[test]fn duplicate_ingress(){let s=VALID.replace("\"seq\":2,\"kind\":\"receive\"","\"seq\":2,\"kind\":\"admit\"");assert!(check(&s).is_err())}
    #[test]fn missing_messages_fail_complete(){assert!(check(&VALID.replace("\"complete\":true","\"complete\":false")).is_ok());let s=VALID.replace("\"seq\":2,\"kind\":\"receive\"","\"seq\":2,\"kind\":\"admit\"");assert!(check(&s).is_err())}
    #[test]fn malformed_optional_fields_rejected(){assert!(check(&VALID.replace("\"kind\":\"spawn\"","\"kind\":\"spawn\",\"resource\":\"x\"")).is_err())}
    #[test]fn premature_release_fails(){assert!(check(&VALID.replace("\"kind\":\"exclusive_acquire\"","\"kind\":\"exclusive_release\"")).is_err())}
    #[test]fn unknown_future_version_rejected(){assert!(check(&VALID.replace("1.0.0","2.0.0")).is_err())}
}
