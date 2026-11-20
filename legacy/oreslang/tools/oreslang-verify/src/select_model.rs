//! Finite model of select arm admission and atomic winner election.
//! Checks safety across all action orders. No fairness/liveness claim.
use std::collections::{HashMap,VecDeque};
#[derive(Clone,Debug,Hash,PartialEq,Eq)]
pub struct SelectState{
 pub registered:u8,pub closed:u8,pub winner:Option<u8>,
 pub cancelled:bool,pub wake_queued:bool,pub notified:bool,
}
#[derive(Clone,Debug)]
pub struct SelectReport{pub states:usize,pub transitions:usize}
pub fn initial()->SelectState{
 SelectState{registered:0,closed:0,winner:None,cancelled:false,wake_queued:false,notified:false}
}
pub fn invariant(s:&SelectState,arms:u8)->Result<(),String>{
 if !(1..=6).contains(&arms){return Err("select model requires 1 to 6 arms".into())}
 let mask=(1u8<<arms)-1;
 if (s.registered|s.closed)&!mask!=0{return Err("unknown select arm".into())}
 if s.registered&s.closed!=0{return Err("closed arm still registered".into())}
 if s.cancelled&&s.winner.is_some(){return Err("cancelled select has winner".into())}
 if (s.cancelled||s.winner.is_some())&&s.registered!=0{
  return Err("terminal select retained losing arms".into())
 }
 if let Some(win)=s.winner{
  if win>=arms{return Err("winner out of range".into())}
  if s.closed & (1u8<<win)!=0{return Err("select winner was already closed".into())}
  if !s.wake_queued&&!s.notified{return Err("lost committed winner notification".into())}
 }
 if (s.wake_queued||s.notified)&&s.winner.is_none(){
  return Err("wake without committed winner".into())
 }
 if s.wake_queued&&s.notified{return Err("duplicate wakeup".into())}
 Ok(())
}
pub fn transitions(s:&SelectState,arms:u8)->Vec<(String,SelectState)>{
 let mut next=vec![];
 let terminal=s.winner.is_some()||s.cancelled;
 if !terminal{
  for arm in 0..arms{
   let bit=1u8<<arm;
   if s.registered&bit==0&&s.closed&bit==0{
    let mut n=s.clone();n.registered|=bit;
    next.push((format!("register({arm})"),n));
   }
   if s.registered&bit!=0{
    let mut n=s.clone();n.winner=Some(arm);n.registered=0;n.wake_queued=true;
    next.push((format!("offer_commit({arm})"),n));
   }
   if s.closed&bit==0{
    let mut n=s.clone();n.closed|=bit;n.registered&=!bit;
    if n.closed==(1u8<<arms)-1{n.cancelled=true;n.registered=0}
    next.push((format!("close({arm})"),n));
   }
  }
  let mut n=s.clone();n.cancelled=true;n.registered=0;
  next.push(("cancel".into(),n));
 }else if s.wake_queued{
  let mut n=s.clone();n.wake_queued=false;n.notified=true;
  next.push(("notify".into(),n));
 }
 next
}
pub fn explore(arms:u8,limit:usize)->Result<SelectReport,String>{
 if !(1..=6).contains(&arms)||limit==0{return Err("invalid select model bounds".into())}
 let start=initial();
 let mut nodes=vec![(start.clone(),None::<(usize,String)>)];
 let mut seen=HashMap::from([(start,0usize)]);
 let mut pending=VecDeque::from([0usize]);
 let mut transitions_count=0;
 while let Some(i)=pending.pop_front(){
  if let Err(msg)=invariant(&nodes[i].0,arms){
   let mut actions=vec![];let mut at=i;
   while let Some((prev,action))=&nodes[at].1{
    actions.push(action.clone());at = *prev;
   }
   actions.reverse();
   return Err(format!("{msg}; counterexample: {}",actions.join(" -> ")))
  }
  for (action,n) in transitions(&nodes[i].0,arms){
   transitions_count+=1;
   if seen.contains_key(&n){continue}
   if nodes.len()>=limit{return Err("STATE_LIMIT_REACHED: select exploration NOT exhaustive".into())}
   let idx=nodes.len();seen.insert(n.clone(),idx);
   nodes.push((n,Some((i,action))));pending.push_back(idx);
  }
 }
 Ok(SelectReport{states:nodes.len(),transitions:transitions_count})
}
#[cfg(test)]
mod tests{
 use super::*;
 #[test]fn all_interleavings_of_two_and_three_arms(){
  for arms in [2,3]{let r=explore(arms,100000).unwrap();assert!(r.states>10)}
 }
 #[test]fn catch_stale_loser_registration(){
  let s=SelectState{registered:2,winner:Some(0),..initial()};
  assert!(invariant(&s,2).unwrap_err().contains("losing"));
 }
 #[test]fn catch_double_notify(){
  let s=SelectState{winner:Some(0),notified:true,wake_queued:true,..initial()};
  assert!(invariant(&s,2).unwrap_err().contains("duplicate"));
 }
 #[test]fn reject_lost_winner_notification(){
  let s=SelectState{winner:Some(0),..initial()};
  assert!(invariant(&s,2).unwrap_err().contains("lost committed"));
 }
 #[test]fn reject_closed_select_winner(){
  let s=SelectState{winner:Some(0),closed:1,wake_queued:true,..initial()};
  assert!(invariant(&s,2).unwrap_err().contains("already closed"));
 }
 #[test]fn stale_state_budget_fails_closed(){
  assert!(explore(3,2).unwrap_err().contains("NOT exhaustive"));
 }
}
