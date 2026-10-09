use super::model::{State,Status};
/// Explicit nondeterminism: sends, termination, I/O completions, carrier turns.
/// This is an abstract protocol, not the Java execution engine.
pub fn transitions(s:&State,cap:usize)->Vec<(String,State)>{
 let mut out=Vec::new();
 for a in 0..s.alive.len(){
  if !s.alive[a]{continue}
  for m in 0..s.status.len(){
   if s.status[m]==Status::New&&s.mail[a].len()<cap{
    let mut n=s.clone();n.mail[a].push(m);n.status[m]=Status::Queued;
    out.push((format!("send({a},{m})"),n));
   }
  }
  if s.waiting[a]&&!s.ready[a]{
   let mut n=s.clone();n.ready[a]=true;
   out.push((format!("io_complete({a})"),n));
  }
  // External shutdown is modeled only after the carrier releases its lease.
  // Cancellation while guest code runs needs a separate quiescence protocol.
  if s.workers.contains(&Some(a)){continue}
  let mut n=s.clone();n.alive[a]=false;
  for m in n.mail[a].drain(..){n.status[m]=Status::Discarded}
  n.waiting[a]=false;n.ready[a]=false;
  out.push((format!("end({a})"),n));
 }
 for w in 0..s.workers.len(){
  match s.workers[w]{
   None=>{
    for a in 0..s.alive.len(){
     if s.alive[a]&&!s.workers.contains(&Some(a))&&(s.ready[a]||(!s.waiting[a]&&!s.mail[a].is_empty())){
      let mut n=s.clone();n.workers[w]=Some(a);
      out.push((format!("acquire({w},{a})"),n));
     }
    }
   }
   Some(a)=>{
    let mut n=s.clone();n.workers[w]=None;
    out.push((format!("release({w})"),n));
    if !s.alive[a]{continue}
    if s.ready[a]{
     let mut n=s.clone();n.ready[a]=false;n.waiting[a]=false;
     out.push((format!("resume({w},{a})"),n));
    }else if !s.waiting[a] { if let Some(&m)=s.mail[a].first(){
     let mut n=s.clone();n.mail[a].remove(0);n.status[m]=Status::Handled;
     out.push((format!("receive({w},{a},{m})"),n));
    }}
    if !s.waiting[a]{
     let mut n=s.clone();n.waiting[a]=true;n.workers[w]=None;
     out.push((format!("await({w},{a})"),n));
    }
   }
  }
 }
 out
}
