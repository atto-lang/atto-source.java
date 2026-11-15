use oreslang_verify::{trace,typed_ir,select_model};
const BASE:&str=r#"{
 "schema_version":"0.1.0","path":"demo.ores",
 "functions":[{
  "name":"f","actor_kind":"shared","allows_error":false,"entry":0,
  "nodes":[
   {"id":0,"line":1,"column":1,"op":"entry","successors":[1]},
   {"id":1,"line":2,"column":1,"op":"call","may_throw":false,"successors":[2]},
   {"id":2,"line":3,"column":1,"op":"return","successors":[]}
  ],
  "matches":[{"line":2,"column":1,"closed_variants":["Some","None"],
   "arms":[{"variants":["Some"]},{"variants":["None"]}]}]
 }]}"#;
fn codes(ir:&str)->Vec<&'static str>{
 typed_ir::analyze(ir).unwrap().iter().map(|f|f.code).collect()
}
#[test]fn typed_match_exact_exhaustiveness(){assert!(codes(BASE).is_empty())}
#[test]fn typed_match_missing_case(){
 let json=BASE.replace(r#",{"variants":["None"]}"#,r#""#);
 assert_eq!(codes(&json),vec!["ORES218"]);
}
#[test]fn typed_match_guard_does_not_prove_coverage(){
 let json=BASE.replace(r#"{"variants":["None"]}"#,r#"{"variants":["None"],"guarded":true}"#);
 assert_eq!(codes(&json),vec!["ORES218"]);
}
#[test]fn typed_match_unreachable_arm(){
 let json=BASE.replace(r#"{"variants":["None"]}"#,r#"{"variants":["Some"]}"#);
 assert_eq!(codes(&json),vec!["ORES217","ORES218"]);
}
#[test]fn reject_untrusted_type_domain(){
 let json=BASE.replace(r#""closed_variants":["Some","None"]"#,r#""closed_variants":["Some","None","Some"]"#);
 assert!(typed_ir::analyze(&json).unwrap_err().contains("duplicate type-domain"));
}
#[test]fn reject_malformed_successor(){
 let json=BASE.replace(r#""successors":[2]"#,r#""successors":[999]"#);
 assert!(typed_ir::analyze(&json).unwrap_err().contains("dangling successor"));
}
#[test]fn detect_borrow_crossing_await(){
 let json=BASE.replace(r#""op":"call","may_throw":false"#,r#""op":"await","borrowed_mut_across_suspend":true"#);
 assert_eq!(codes(&json),vec!["ORES213"]);
}
#[test]fn detect_unhandled_effect(){
 let json=BASE.replace(r#""may_throw":false"#,r#""may_throw":true"#);
 assert_eq!(codes(&json),vec!["ORES214"]);
}
#[test]fn detect_untrusted_blocking(){
 let json=BASE.replace(r#""actor_kind":"shared""#,r#""actor_kind":"untrusted""#)
  .replace(r#""may_throw":false"#,r#""blocking_call":true"#);
 assert_eq!(codes(&json),vec!["ORES215"]);
}
#[test]fn detect_select_loser_leak(){
 let json=BASE.replace(r#""op":"call""#,r#""op":"select""#);
 assert_eq!(codes(&json),vec!["ORES216"]);
}
#[test]fn detect_registered_defer_on_cfg_cycle(){
 let json=BASE.replace(r#""op":"call","may_throw":false,"successors":[2]"#,
                       r#""op":"defer","successors":[1,2]"#);
 assert_eq!(codes(&json),vec!["ORES212"]);
}
#[test]fn detect_unreachable_cfg_node(){
 let json=BASE.replace(r#""successors":[1]"#,r#""successors":[2]"#);
 assert_eq!(codes(&json),vec!["ORES211"]);
}
#[test]fn trace_accepts_existing_v1_protocol(){
 let input=r#"{"schema_version":"1.0.0","complete":true,"events":[
  {"seq":0,"kind":"spawn","actor":"alice"},
  {"seq":1,"kind":"spawn","actor":"bob"},
  {"seq":2,"kind":"send","actor":"alice","target":"bob","message_id":"m1"},
  {"seq":3,"kind":"receive","actor":"bob","message_id":"m1"},
  {"seq":4,"kind":"terminate","actor":"alice"},
  {"seq":5,"kind":"terminate","actor":"bob"}]}"#;
 assert_eq!(trace::check(input).unwrap().messages_sent,1);
}
#[test]fn trace_rejects_post_termination_receive(){
 let input=r#"{"schema_version":"1.0.0","complete":false,"events":[
  {"seq":0,"kind":"spawn","actor":"a"},
  {"seq":1,"kind":"terminate","actor":"a"},
  {"seq":2,"kind":"receive","actor":"a","message_id":"m1"}]}"#;
 assert_eq!(trace::check(input).unwrap_err().index,2);
}
#[test]fn select_bounded_safety(){
 let r=select_model::explore(3,100_000).unwrap();
 assert!(r.states>10);assert!(r.transitions>=r.states);
}
