use oreslang_verify::{
    lexer::tokens_checked,lint::{lint,lint_checked},
    model::{initial,invariant,Bounds,Status},model_steps::transitions,
    trace,typed_ir,
};
use serde_json::json;

#[test]fn unfinished_lexical_structures_are_errors(){
    for s in ["fnc f() { /* forgotten */ /*", "fnc f() { \"oops", "fnc f() { `oops",
              "fnc f() { while(true) {", "fnc f() { x ]; }", "fnc f() { [) }"]{
        assert!(tokens_checked(s).is_err(),"should reject: {s}");
        assert!(lint_checked(s,"bad.ores",false).is_err());
        assert_eq!(lint(s,"bad.ores",false)[0].code,"ORES000");
    }
}
#[test]fn valid_unicode_and_nested_comment(){
    let s="/* nested /* ignore } ] */ ok */ async routine café() { await go(); }";
    assert!(tokens_checked(s).is_ok());
    assert!(lint_checked(s,"unicode.ores",false).unwrap().is_empty());
}
#[test]fn suspended_actor_must_not_consume_unrelated_mail(){
    let mut state=initial(Bounds::default());
    state.status[0]=Status::Queued;
    state.mail[0].push(0);
    state.waiting[0]=true;
    invariant(&state,1).unwrap();
    let actions=transitions(&state,1);
    assert!(!actions.iter().any(|(a,_)|a.starts_with("acquire(")));
    assert!(!actions.iter().any(|(a,_)|a.starts_with("receive(")));
    let ready=actions.into_iter().find(|(a,_)|a=="io_complete(0)").unwrap().1;
    let acquired=transitions(&ready,1).into_iter().find(|(a,_)|a=="acquire(0,0)").unwrap().1;
    assert!(!transitions(&acquired,1).iter().any(|(a,_)|a.starts_with("receive(")));
    assert!(transitions(&acquired,1).iter().any(|(a,_)|a=="resume(0,0)"));
}
#[test]fn suspended_actor_lease_is_invalid(){
    let mut s=initial(Bounds::default());
    s.waiting[0]=true;
    s.workers[0]=Some(0);
    assert!(invariant(&s,1).unwrap_err().contains("suspended"));
}
fn ir_node(id:u32,op:&str,successors:Vec<u32>)->serde_json::Value{
    json!({"id":id,"line":id+1,"column":1,"op":op,"successors":successors})
}
fn ir_program(nodes:Vec<serde_json::Value>)->String{
    json!({"schema_version":"0.1.0","path":"fixture.ores","functions":[{
       "name":"f","actor_kind":"untrusted","allows_error":false,"entry":0,
       "nodes":nodes,"matches":[]}
    ]}).to_string()
}
#[test]fn select_mutable_borrow_is_detected(){
    let mut node=ir_node(1,"select",vec![2]);
    node["borrowed_mut_across_suspend"]=json!(true);
    let findings=typed_ir::analyze(&ir_program(vec![
        ir_node(0,"entry",vec![1]),node,ir_node(2,"return",vec![])] )).unwrap();
    assert!(findings.iter().any(|f|f.code=="ORES213"));
}
#[test]fn awaited_failure_must_be_handled(){
    let mut node=ir_node(1,"await",vec![2]);node["may_throw"]=json!(true);
    let findings=typed_ir::analyze(&ir_program(vec![ir_node(0,"entry",vec![1]),node,ir_node(2,"return",vec![])])).unwrap();
    assert!(findings.iter().any(|f|f.code=="ORES214"));
}
#[test]fn terminal_edges_and_invalid_suspend_metadata_fail_closed(){
    assert!(typed_ir::analyze(&ir_program(vec![ir_node(0,"return",vec![0])])).is_err());
    let mut node=ir_node(0,"entry",vec![]);
    node["borrowed_mut_across_suspend"]=json!(true);
    assert!(typed_ir::analyze(&ir_program(vec![node])).is_err());
}
#[test]fn cycle_scan_finds_defer_only_on_real_cycle(){
    let nodes=vec![ir_node(0,"entry",vec![1]),
        ir_node(1,"defer",vec![2]),
        ir_node(2,"branch",vec![1,3]),
        ir_node(3,"return",vec![])];
    let findings=typed_ir::analyze(&ir_program(nodes)).unwrap();
    assert_eq!(findings.iter().filter(|f|f.code=="ORES212").count(),1);
}
#[test]fn long_acyclic_cfg_does_not_false_alarm(){
    let n=2500u32;
    let nodes=(0..n).map(|i|{
       let op=if i==n-1{"return"}else if i==n/2{"defer"}else{"other"};
       ir_node(i,op,if i==n-1{vec![]}else{vec![i+1]})
    }).collect();
    let findings=typed_ir::analyze(&ir_program(nodes)).unwrap();
    assert!(!findings.iter().any(|f|f.code=="ORES212"));
}
#[test]fn explicit_null_in_trace_event_rejected(){
    let t=json!({"schema_version":"1.0.0","complete":false,"events":[
       {"seq":0,"kind":"spawn","actor":"a","message_id":null}
    ]});
    assert!(trace::check(&t.to_string()).is_err());
}
#[test]fn bounded_json_inputs_fail_before_deserialization(){
    let large=" ".repeat(16*1024*1024+1);
    assert!(trace::check(&large).unwrap_err().reason.contains("budget"));
    assert!(typed_ir::analyze(&large).unwrap_err().contains("size"));
}
