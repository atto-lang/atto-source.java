use oreslang_verify::{core_trace,lint::lint_checked,model::{initial,invariant,Bounds,Status},model_steps::transitions,typed_ir};
use serde_json::{json,Value};

fn event(kind:&str,actor:&str)->Value{
    json!({"kind":kind,"actor":actor})
}
fn run(mut events:Vec<Value>,complete:bool)->Result<core_trace::Report,core_trace::Violation>{
    for (i,e) in events.iter_mut().enumerate(){e["seq"]=json!(i);}
    core_trace::check(&json!({"schema_version":"2.0.0","complete":complete,"capacity":2,"events":events}).to_string())
}
fn fixture()->Vec<Value>{
    vec![
        event("spawn","alpha"),
        json!({"kind":"admit","actor":"alpha","message_id":"m1"}),
        json!({"kind":"acquire","actor":"alpha","worker":"carrier1"}),
        json!({"kind":"receive","actor":"alpha","message_id":"m1"}),
        json!({"kind":"suspend","actor":"alpha","worker":"carrier1"}),
        event("io_complete","alpha"),
        json!({"kind":"acquire","actor":"alpha","worker":"carrier2"}),
        json!({"kind":"resume","actor":"alpha","worker":"carrier2"}),
        json!({"kind":"release","actor":"alpha","worker":"carrier2"}),
        event("terminate","alpha"),
    ]
}
#[test]fn valid_core_trace_and_cancellation(){
    assert_eq!(run(fixture(),true).unwrap().events,10);
    let e=vec![event("spawn","alpha"),json!({"kind":"admit","actor":"alpha","message_id":"m"}),
        json!({"kind":"acquire","actor":"alpha","worker":"w"}),
        json!({"kind":"receive","actor":"alpha","message_id":"m"}),
        json!({"kind":"suspend","actor":"alpha","worker":"w"}),
        event("terminate","alpha")];
    assert!(run(e,true).is_ok());
}
#[test]fn reject_double_carrier_lease_for_actor(){
    let mut e=fixture();
    e.insert(3,json!({"kind":"acquire","actor":"alpha","worker":"carrier2"}));
    assert!(run(e,true).unwrap_err().reason.contains("duplicate carrier"));
}
#[test]fn reject_double_worker_lease_across_actors(){
    let e=vec![event("spawn","alpha"),event("spawn","beta"),
       json!({"kind":"admit","actor":"alpha","message_id":"m1"}),
       json!({"kind":"admit","actor":"beta","message_id":"m2"}),
       json!({"kind":"acquire","actor":"alpha","worker":"w"}),
       json!({"kind":"acquire","actor":"beta","worker":"w"})];
    assert!(run(e,false).unwrap_err().reason.contains("double leased"));
}
#[test]fn forbid_receive_while_suspended(){
    let mut e=fixture();
    e.insert(5,json!({"kind":"receive","actor":"alpha","message_id":"m2"}));
    assert!(run(e,false).is_err());
}
#[test]fn forbid_unsignalled_and_double_completion(){
    let mut e=fixture();
    e.insert(5,event("io_complete","alpha"));
    assert!(run(e,true).unwrap_err().reason.contains("duplicate IO"));
    let mut e=fixture();
    e.insert(1,event("io_complete","alpha"));
    assert!(run(e,true).unwrap_err().reason.contains("IO completion"));
}
#[test]fn reject_resume_before_completion(){
    let mut e=fixture();
    e.remove(5);
    assert!(run(e,true).is_err());
}
#[test]fn reject_termination_while_worker_leased(){
    let mut e=fixture(); e.insert(4,event("terminate","alpha"));
    assert!(run(e,false).unwrap_err().reason.contains("active lease"));
}
#[test]fn reject_termination_with_queued_mail(){
    let e=vec![event("spawn","alpha"),json!({"kind":"admit","actor":"alpha","message_id":"m"}),
       event("terminate","alpha")];
    assert!(run(e,false).unwrap_err().reason.contains("pending mail"));
}
#[test]fn enforce_mail_fifo(){
    let e=vec![event("spawn","alpha"),
      json!({"kind":"admit","actor":"alpha","message_id":"m1"}),
      json!({"kind":"admit","actor":"alpha","message_id":"m2"}),
      json!({"kind":"acquire","actor":"alpha","worker":"w"}),
      json!({"kind":"receive","actor":"alpha","message_id":"m2"})];
    assert!(run(e,false).unwrap_err().reason.contains("out-of-order"));
}
#[test]fn reject_duplicate_message_and_null_fields(){
    let e=vec![event("spawn","alpha"),
      json!({"kind":"admit","actor":"alpha","message_id":"m1"}),
      json!({"kind":"admit","actor":"alpha","message_id":"m1"})];
    assert!(run(e,false).unwrap_err().reason.contains("duplicate message"));
    let e=vec![json!({"kind":"spawn","actor":"alpha","worker":null})];
    assert!(run(e,false).is_err());
}
#[test]fn reject_incomplete_complete_flag(){
    let e=vec![event("spawn","alpha")];
    assert!(run(e,true).unwrap_err().reason.contains("retains live"));
    assert!(run(vec![event("spawn","alpha")],false).is_ok());
}
#[test]fn refuse_receiving_mail_before_carrier(){
    let e=vec![event("spawn","alpha"),
      json!({"kind":"admit","actor":"alpha","message_id":"m"}),
      json!({"kind":"receive","actor":"alpha","message_id":"m"})];
    assert!(run(e,false).unwrap_err().reason.contains("execution lease"));
}
#[test]fn model_rejects_terminated_actor_holding_carrier(){
    let mut s=initial(Bounds::default());
    s.alive[0]=false;s.workers[0]=Some(0);
    assert!(invariant(&s,1).unwrap_err().contains("terminated actor retains"));
}
#[test]fn model_never_offers_terminate_during_execution(){
    let mut s=initial(Bounds::default());
    s.status[0]=Status::Queued;s.mail[0].push(0);
    s.workers[0]=Some(0);
    assert!(!transitions(&s,1).iter().any(|(e,_)|e=="end(0)"));
}
#[test]fn function_boundaries_stop_outer_loop_defer_warnings(){
    let inside="fnc outer() { loop { fnc inner() { defer cleanup(); } } }";
    assert!(lint_checked(inside,"test.ores",false).unwrap().is_empty());
    let inner="fnc outer() { loop { fnc inner() { while(true) { defer cleanup(); } } } }";
    let found=lint_checked(inner,"test.ores",false).unwrap();
    assert_eq!(found.iter().filter(|f|f.code=="ORES101").count(),1);
}
#[test]fn typed_ir_rejects_duplicate_edges_and_wrong_select_facts(){
    let base=json!({"schema_version":"0.1.0","path":"x.ores","functions":[{
       "name":"f","actor_kind":"none","allows_error":false,"entry":0,
       "nodes":[{"id":0,"op":"entry","line":1,"column":1,"successors":[1,1]},
                {"id":1,"op":"return","line":2,"column":1,"successors":[]}]}]});
    assert!(typed_ir::analyze(&base.to_string()).unwrap_err().contains("duplicate CFG successor"));
    let mut v=base;
    v["functions"][0]["nodes"][0]["successors"]=json!([1]);
    v["functions"][0]["nodes"][0]["select_has_cancellation"]=json!(true);
    assert!(typed_ir::analyze(&v.to_string()).unwrap_err().contains("non-select"));
}


#[test]fn mailbox_capacity_is_a_checked_core_invariant(){
 let events=vec![event("spawn","alpha"),
   json!({"kind":"admit","actor":"alpha","message_id":"m1"}),
   json!({"kind":"admit","actor":"alpha","message_id":"m2"}),
   json!({"kind":"admit","actor":"alpha","message_id":"m3"})];
 assert!(run(events,false).unwrap_err().reason.contains("mailbox capacity"));
 let bad=json!({"schema_version":"2.0.0","capacity":0,"complete":true,"events":[]});
 assert!(core_trace::check(&bad.to_string()).unwrap_err().reason.contains("capacity"));
}
#[test]fn guest_send_requires_execution_lease(){
 let events=vec![event("spawn","alpha"),event("spawn","beta"),
   json!({"kind":"send","actor":"alpha","target":"beta","message_id":"m1"})];
 assert!(run(events,false).unwrap_err().reason.contains("active guest execution lease"));
 let events=vec![event("spawn","alpha"),event("spawn","beta"),
   json!({"kind":"admit","actor":"alpha","message_id":"work"}),
   json!({"kind":"acquire","actor":"alpha","worker":"w"}),
   json!({"kind":"send","actor":"alpha","target":"beta","message_id":"m1"}),
   json!({"kind":"receive","actor":"alpha","message_id":"work"}),
   json!({"kind":"release","actor":"alpha","worker":"w"}),
   json!({"kind":"discard","actor":"beta","message_id":"m1"}),
   event("terminate","alpha"),event("terminate","beta")];
 assert!(run(events,true).is_ok());
}

#[test]fn core_v2_json_schema_matches_monitor_event_vocabulary(){
 let doc:serde_json::Value=serde_json::from_str(include_str!("../schema/core-trace-v2.schema.json")).unwrap();
 assert_eq!(doc["properties"]["schema_version"]["const"],"2.0.0");
 assert_eq!(doc["properties"]["capacity"]["minimum"],1);
 assert_eq!(doc["properties"]["capacity"]["maximum"],65536);
 let kinds:std::collections::BTreeSet<_>=doc["properties"]["events"]["items"]["oneOf"].as_array().unwrap()
   .iter().map(|v|v["properties"]["kind"]["const"].as_str().unwrap()).collect();
 let expected:std::collections::BTreeSet<_>=["spawn","admit","send","acquire","receive","release",
    "suspend","io_complete","resume","discard","terminate"].into_iter().collect();
 assert_eq!(kinds,expected);
}
