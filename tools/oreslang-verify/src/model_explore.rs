use std::collections::{HashMap,VecDeque};
use super::model::{Bounds,State,initial,invariant};
use super::model_steps::transitions;
#[derive(Debug)]
pub struct Report{pub states:usize,pub edges:usize}
#[derive(Debug)]
pub struct Error{pub reason:String,pub trace:Vec<String>}
impl std::fmt::Display for Error{
 fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{
  write!(f,"{}",self.reason)?;
  if !self.trace.is_empty(){write!(f,"\ncounterexample: {}",self.trace.join(" -> "))?}
  Ok(())
 }
}
impl std::error::Error for Error{}
fn trace(nodes:&[(State,Option<(usize,String)>)],mut i:usize)->Vec<String>{
 let mut path=vec![];
 while let Some((parent,event))=&nodes[i].1{path.push(event.clone());i = *parent}
 path.reverse();path
}
pub fn explore(b:Bounds)->Result<Report,Error>{
 if b.actors==0||b.messages==0||b.workers==0||b.capacity==0||b.limit==0{
  return Err(Error{reason:"model bounds must be positive".into(),trace:vec![]})
 }
 if b.actors>12||b.workers>12||b.messages>24{
  return Err(Error{reason:"model dimensions unsupported".into(),trace:vec![]})
 }
 let start=initial(b);
 let mut nodes=vec![(start.clone(),None)];
 let mut seen=HashMap::from([(start,0usize)]);
 let mut pending=VecDeque::from([0usize]);
 let mut edges=0;
 while let Some(i)=pending.pop_front(){
  if let Err(reason)=invariant(&nodes[i].0,b.capacity){
   return Err(Error{reason,trace:trace(&nodes,i)})
  }
  for (label,next) in transitions(&nodes[i].0,b.capacity){
   edges+=1;
   if seen.contains_key(&next){continue}
   if nodes.len()>=b.limit{
    let mut t=trace(&nodes,i);t.push(label);
    return Err(Error{reason:"STATE_LIMIT_REACHED: verification NOT exhaustive".into(),trace:t})
   }
   let new_index=nodes.len();
   seen.insert(next.clone(),new_index);
   nodes.push((next,Some((i,label))));
   pending.push_back(new_index);
  }
 }
 Ok(Report{states:nodes.len(),edges})
}
#[cfg(test)]
mod tests{
 use super::*;
 use crate::model::Status;
 #[test]fn exhaustive_small(){
  for b in [Bounds::default(),Bounds{actors:2,messages:2,workers:2,..Bounds::default()},Bounds{messages:3,capacity:2,..Bounds::default()}]{
   let r=explore(b).unwrap();assert!(r.states>2);assert!(r.edges>=r.states);
  }
 }
 #[test]fn fail_closed_on_budget(){
  let e=explore(Bounds{limit:2,..Bounds::default()}).unwrap_err();
  assert!(e.reason.contains("NOT exhaustive"));assert!(!e.trace.is_empty());
 }
 #[test]fn reject_duplicate_execution_lease(){
  let mut s=initial(Bounds::default());s.workers=vec![Some(0),Some(0)];
  assert!(invariant(&s,1).unwrap_err().contains("lease"));
 }
 #[test]fn reject_duplicate_mail(){
  let mut s=initial(Bounds{actors:2,..Bounds::default()});
  s.mail=vec![vec![0],vec![0]];s.status[0]=Status::Queued;
  assert!(invariant(&s,1).unwrap_err().contains("duplicate queued"));
 }
 #[test]fn reject_orphan_wakeup(){
  let mut s=initial(Bounds::default());s.ready[0]=true;
  assert!(invariant(&s,1).unwrap_err().contains("orphan"));
 }
 #[test]fn termination_seals_sends(){
  let s=initial(Bounds::default());
  let ended=transitions(&s,1).into_iter().find(|(e,_)|e=="end(0)").unwrap().1;
  assert!(!transitions(&ended,1).iter().any(|(e,_)|e.starts_with("send(")));
 }
}
