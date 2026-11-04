use oreslang_verify::{lint::lint,lexer::tokens,model::{initial,invariant,Bounds,Status},model_steps::transitions,model_explore::explore};
fn codes(src:&str,pedantic:bool)->Vec<&'static str>{
 lint(src,"fixture.ores",pedantic).into_iter().map(|d|d.code).collect()
}
#[test]fn lexical_skip_comments_and_strings(){
 let toks=tokens("/* defer /* await */ */ // defer\n\"await\" 'defer' `defer` x");
 assert_eq!(toks.len(),1);assert_eq!(toks[0].value,"x");
}
#[test]fn correct_line_columns(){
 let toks=tokens("ok\n  await");assert_eq!((toks[1].line,toks[1].col),(2,3));
}
#[test]fn defer_nested_in_loop(){
 assert_eq!(codes("async routine f(){ loop { if true { defer close(); } } }",false),vec!["ORES101"]);
}
#[test]fn outer_defer_safe(){
 assert!(codes("fnc f(){ defer close(); loop { work(); } }",false).is_empty());
}
#[test]fn sync_await_warns(){
 assert_eq!(codes("fnc f(){ if true { await work(); } }",false),vec!["ORES102"]);
}
#[test]fn async_await_safe(){
 assert!(codes("async routine f(){ await work(); }",false).is_empty());
}
#[test]fn nested_nonasync_context_warns(){
 assert_eq!(codes("async routine f(){ fnc g(){ await x(); } }",false),vec!["ORES102"]);
}
#[test]fn select_advisory_opt_in(){
 let src="async routine f(){ select { readch(ch) => 1; } }";
 assert!(codes(src,false).is_empty());assert_eq!(codes(src,true),vec!["ORES103"]);
}
#[test]fn match_is_not_proven_exhaustive(){
 assert_eq!(codes("fnc f(){ match b { true => 1; false => 2; } }",true),vec!["ORES104"]);
}
#[test]fn wildcard_match_no_advisory(){
 assert!(codes("fnc f(){ match b { _ => 1; } }",true).is_empty());
}
#[test]fn bounded_model_two_actors(){
 let r=explore(Bounds{actors:2,messages:2,workers:2,..Bounds::default()}).unwrap();
 assert!(r.states>50);assert!(r.edges>=r.states);
}
#[test]fn invalid_state_detected(){
 let mut s=initial(Bounds::default());s.status[0]=Status::Queued;
 assert!(invariant(&s,1).unwrap_err().contains("inconsistency"));
}
#[test]fn termination_seals_admission(){
 let s=initial(Bounds::default());
 let ended=transitions(&s,1).into_iter().find(|(a,_)|a=="end(0)").unwrap().1;
 assert!(!transitions(&ended,1).iter().any(|(a,_)|a.starts_with("send(")));
}
#[test]fn model_bound_is_not_success(){
 assert!(explore(Bounds{limit:2,..Bounds::default()}).unwrap_err().reason.contains("NOT exhaustive"));
}
