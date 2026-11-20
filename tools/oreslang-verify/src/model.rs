#[derive(Clone,Copy,Debug,PartialEq,Eq,Hash)]
pub enum Status{New,Queued,Handled,Discarded}
#[derive(Clone,Debug,PartialEq,Eq,Hash)]
pub struct State{pub alive:Vec<bool>,pub workers:Vec<Option<usize>>,pub mail:Vec<Vec<usize>>,pub status:Vec<Status>,pub waiting:Vec<bool>,pub ready:Vec<bool>}
#[derive(Clone,Copy,Debug)]
pub struct Bounds{pub actors:usize,pub messages:usize,pub workers:usize,pub capacity:usize,pub limit:usize}
impl Default for Bounds{fn default()->Self{Self{actors:1,messages:2,workers:2,capacity:1,limit:100_000}}}
pub fn initial(b:Bounds)->State{State{alive:vec![true;b.actors],workers:vec![None;b.workers],mail:vec![vec![];b.actors],status:vec![Status::New;b.messages],waiting:vec![false;b.actors],ready:vec![false;b.actors]}}
pub fn invariant(s:&State,cap:usize)->Result<(),String>{
 let n=s.alive.len();
 if s.mail.len()!=n||s.waiting.len()!=n||s.ready.len()!=n{return Err("invalid dimensions".into())}
 let mut leases=vec![false;n];
 for a in s.workers.iter().flatten(){if *a>=n{return Err("invalid actor".into())}
  if leases[*a]{return Err("duplicate execution lease".into())}leases[*a]=true;}
 let mut in_mail=vec![false;s.status.len()];
 for (a,&has_lease) in leases.iter().enumerate(){
  if !s.alive[a] && has_lease {return Err("terminated actor retains worker lease".into())}
  if s.waiting[a] && !s.ready[a] && has_lease {
   return Err("suspended actor holds a carrier before I/O completion".into());
  }
  if s.mail[a].len()>cap{return Err("mailbox capacity exceeded".into())}
  if !s.alive[a]&&(!s.mail[a].is_empty()||s.waiting[a]||s.ready[a]){return Err("work after termination".into())}
  if s.ready[a]&&!s.waiting[a]{return Err("orphan continuation".into())}
  for &m in &s.mail[a]{if m>=in_mail.len(){return Err("invalid message".into())}
   if in_mail[m]{return Err("duplicate queued message".into())}in_mail[m]=true;}
 }
 for (m,st) in s.status.iter().enumerate(){if in_mail[m]!=(*st==Status::Queued){return Err("mailbox/status inconsistency".into())}}
 Ok(())
}
