use crate::lexer::{tokens_checked,Token};
#[derive(Clone,Debug)]
pub struct Diagnostic{pub path:String,pub line:usize,pub column:usize,pub code:&'static str,pub severity:&'static str,pub message:&'static str}
#[derive(Clone,Copy)]struct Scope{loop_body:bool,async_context:Option<bool>,function_boundary:bool}
fn header(ts:&[Token],open:usize)->Vec<&str>{
 let mut start=open;let mut depth=0usize;
 while start>0{
  let t=ts[start-1].value.as_str();
  if depth==0&&(t==";"||t=="{"||t=="}"){break}
  if t==")"||t=="]"{depth+=1}
  if t=="("||t=="["{depth=depth.saturating_sub(1)}
  start-=1;
 }
 ts[start..open].iter().map(|t|t.value.as_str()).collect()
}
fn body(ts:&[Token],open:usize)->Vec<&str>{
 let mut depth=1;let mut result=vec![];
 for t in ts.iter().skip(open+1){
  if t.value=="{"{depth+=1}
  if t.value=="}"{depth-=1;if depth==0{break}}
  result.push(t.value.as_str());
 }
 result
}

pub fn lint(src:&str,path:&str,pedantic:bool)->Vec<Diagnostic>{
 match lint_checked(src,path,pedantic){
  Ok(findings)=>findings,
  Err(_)=>vec![Diagnostic{path:path.to_string(),line:1,column:1,code:"ORES000",severity:"error",message:"malformed source; lint analysis incomplete"}]
 }
}
pub fn lint_checked(src:&str,path:&str,pedantic:bool)->Result<Vec<Diagnostic>,String>{
 let ts=tokens_checked(src)?;let mut scopes:Vec<Scope>=vec![];let mut result=vec![];
 for (i,t) in ts.iter().enumerate(){
  let mut add=|code,severity,message|result.push(Diagnostic{
   path:path.into(),line:t.line,column:t.col,code,severity,message
  });
  match t.value.as_str(){
   "{"=>{
    let h=header(&ts,i);
    let loop_body=h.iter().any(|w|["loop","for","while"].contains(w));
    let func=h.iter().any(|w|["fnc","routine"].contains(w));
    let async_context=if func{Some(h.contains(&"async"))}else{scopes.last().and_then(|s|s.async_context)};
    if pedantic&&h.contains(&"select"){
     let words=body(&ts,i);
     if !words.contains(&"default")&&!words.contains(&"timeout"){
      add("ORES103","info","select lacks syntactic default/timeout; blocking may be intentional")
     }
    }
    if pedantic&&h.contains(&"match")&&!body(&ts,i).contains(&"_"){
     add("ORES104","info","match exhaustiveness requires typed checking");
    }
    scopes.push(Scope{loop_body,async_context,function_boundary:func});
   }
   "}"=>{scopes.pop();}
   "defer"=>{if scopes.iter().rev().take_while(|s|!s.function_boundary).any(|s|s.loop_body){
    add("ORES101","warning","defer is inside a loop; review cleanup lifetime");
   }}
   "await" if scopes.last().and_then(|s|s.async_context)==Some(false)=>{
    add("ORES102","warning","await appears inside a non-async function");
   }
   _=>{}
  }
 }
 Ok(result)
}
