use oreslang_verify::{lint::lint_checked,model::Bounds,model_explore::explore};
use std::{env,fs,path::Path,process::ExitCode};
fn json_quote(s:&str)->String{
 let mut out=String::from("\"");
 for c in s.chars(){
  match c{
   '"'=>out.push_str("\\\""),'\\'=>out.push_str("\\\\"),
   '\n'=>out.push_str("\\n"),'\r'=>out.push_str("\\r"),'\t'=>out.push_str("\\t"),
   c if c.is_control()=>out.push_str(&format!("\\u{:04x}",c as u32)),
   c=>out.push(c)
  }
 }
 out.push('"');out
}
/// Bounded local file traversal: no symlink cycles, aliases, huge source inputs.
fn visit_inner(path:&Path,files:&mut Vec<std::path::PathBuf>,depth:usize)->Result<(),String>{
 if depth>64{return Err(format!("directory depth exceeded: {}",path.display()))}
 let meta=fs::symlink_metadata(path).map_err(|e|format!("{}: {e}",path.display()))?;
 if meta.file_type().is_symlink(){return Err(format!("symlink input refused: {}",path.display()))}
 if meta.is_dir(){
  for entry in fs::read_dir(path).map_err(|e|format!("{}: {e}",path.display()))?{
   let p=entry.map_err(|e|e.to_string())?.path();
   if p.is_dir()||p.extension().is_some_and(|x|x=="ores"){
    visit_inner(&p,files,depth+1)?;
   }
  }
 }else if meta.is_file(){
  if path.extension().is_none_or(|x|x!="ores"){
   return Err(format!("lint requires an .ores source file: {}",path.display()))
  }
  if meta.len()>4*1024*1024{return Err(format!("Oreslang source exceeds 4 MiB: {}",path.display()))}
  if files.len()>=20_000{return Err("Oreslang lint file limit (20000) exceeded".into())}
  files.push(path.to_path_buf());
 }else{return Err(format!("unsupported input type: {}",path.display()))}
 Ok(())
}
fn visit(path:&Path,files:&mut Vec<std::path::PathBuf>)->Result<(),String>{
 visit_inner(path,files,0)
}
fn run(args:&[String])->Result<(),String>{
 if args.len()<2{return Err("usage: oreslang-verify model [--actors N --messages N --workers N --capacity N --max-states N] | trace TRACE.json | select-model [--arms N] | ir PROGRAM.json [--strict] | lint [--format json|text|sarif] [--pedantic] [--strict] PATH...".into())}
 match args[1].as_str(){
  "model"=>{
   let mut b=Bounds::default();let mut i=2;
   while i<args.len(){
    let field=&args[i];let n=args.get(i+1).ok_or_else(||format!("missing value for {field}"))?
      .parse::<usize>().map_err(|_|format!("invalid integer for {field}"))?;
    match field.as_str(){
     "--actors"=>b.actors=n,"--messages"=>b.messages=n,"--workers"=>b.workers=n,
     "--capacity"=>b.capacity=n,"--max-states"=>b.limit=n,
     _=>return Err(format!("unknown model flag: {field}"))
    }
    i+=2;
   }
   let r=explore(b).map_err(|e|e.to_string())?;
   println!("BOUNDED MODEL PASS: {} states, {} transitions (actors={}, messages={}, workers={}, capacity={})",
    r.states,r.edges,b.actors,b.messages,b.workers,b.capacity);
  }
  "trace-core"=>{
   if args.len()!=3{return Err("usage: oreslang-verify trace-core CORE_TRACE.json".into())}
   let input=fs::read_to_string(&args[2]).map_err(|e|e.to_string())?;
   let r=oreslang_verify::core_trace::check(&input).map_err(|e|e.to_string())?;
   println!("CORE TRACE OK: {} events, {} actors, {} messages",r.events,r.actors,r.messages);
  }
  "trace"=>{
   if args.len()!=3{return Err("usage: oreslang-verify trace TRACE.json".into())}
   let input=fs::read_to_string(&args[2]).map_err(|e|e.to_string())?;
   let r=oreslang_verify::trace::check(&input).map_err(|e|e.to_string())?;
   println!("TRACE OK: {} events, {} actors, {} messages ({} received)",r.events,r.actors,r.messages_sent,r.messages_received);
  }
  "select-model"=>{
   let mut arms=3u8;let mut limit=100_000usize;let mut i=2;
   while i<args.len(){
    let flag=&args[i];let value=args.get(i+1).ok_or("missing model option value")?
      .parse::<usize>().map_err(|_|format!("invalid integer for {flag}"))?;
    match flag.as_str(){
      "--arms"=>arms=u8::try_from(value).map_err(|_|"arms exceed supported range")?,
      "--max-states"=>limit=value,
      _=>return Err(format!("unknown select-model flag: {flag}")),
    }
    i+=2;
   }
   let r=oreslang_verify::select_model::explore(arms,limit)?;
   println!("SELECT MODEL PASS: {} reachable states, {} transitions, {} arms",r.states,r.transitions,arms);
  }
  "ir"=>{
   let path=args.get(2).ok_or("usage: oreslang-verify ir PROGRAM.json [--strict]")?;
   if args.len()>4||(args.len()==4&&args[3]!="--strict"){
    return Err("usage: oreslang-verify ir PROGRAM.json [--strict]".into())
   }
   let input=fs::read_to_string(path).map_err(|e|e.to_string())?;
   let findings=oreslang_verify::typed_ir::analyze(&input)?;
   for f in &findings{
    println!("{}:{}:{}: {} [{}] {}",path,f.line,f.column,f.code,f.function,f.message);
   }
   println!("typed IR: {} diagnostic(s)",findings.len());
   if args.len()==4&&!findings.is_empty(){
    return Err("strict typed-IR analysis has findings".into())
   }
  }
  "lint"=>{
   let mut pedantic=false;let mut strict=false;let mut format="text";let mut paths=vec![];
   let mut i=2;
   while i<args.len(){
    match args[i].as_str(){
     "--pedantic"=>pedantic=true,"--strict"=>strict=true,
     "--format"=>{
      let v=args.get(i+1).ok_or("missing --format value")?;
      match v.as_str(){"json"|"text"|"sarif"=>format=v,_=>return Err("format must be json, text or sarif".into())}
      i+=1;
     }
     arg if arg.starts_with('-')=>return Err(format!("unknown flag: {arg}")),
     arg=>paths.push(arg.to_string())
    }
    i+=1;
   }
   if paths.is_empty(){return Err("lint requires at least one file or directory".into())}
   let mut files=vec![];
   for p in paths{visit(Path::new(&p),&mut files)?}
   files.sort();files.dedup();
   if files.is_empty(){return Err("no Oreslang source files selected".into())}
   let mut issues=vec![];
   for p in files{
    let src=fs::read_to_string(&p).map_err(|e|format!("{}: {e}",p.display()))?;
    issues.extend(lint_checked(&src,&p.to_string_lossy(),pedantic).map_err(|e|format!("{}: {e}",p.display()))?);
   }
   if format=="sarif"{
    use serde_json::json;
    let results:Vec<_>=issues.iter().map(|d|json!({
      "ruleId":d.code,
      "level":match d.severity{"error"=>"error","warning"=>"warning",_=>"note"},
      "message":{"text":d.message},
      "locations":[{"physicalLocation":{
         "artifactLocation":{"uri":d.path.replace('\\',"/")},
         "region":{"startLine":d.line,"startColumn":d.column}
      }}]
    })).collect();
    let sarif=json!({"$schema":"https://json.schemastore.org/sarif-2.1.0.json",
       "version":"2.1.0","runs":[{"tool":{"driver":{"name":"oreslang-verify"}},"results":results}]});
    println!("{sarif}");
   }else if format=="json"{
    let parts:Vec<String>=issues.iter().map(|d|format!(
     "{{\"path\":{},\"line\":{},\"column\":{},\"code\":{},\"severity\":{},\"message\":{}}}",
     json_quote(&d.path),d.line,d.column,json_quote(d.code),json_quote(d.severity),json_quote(d.message))).collect();
    println!("[{}]",parts.join(","));
   }else{
    for d in &issues{println!("{}:{}:{}: {} {}: {}",d.path,d.line,d.column,d.severity,d.code,d.message)}
    println!("Oreslang lint: {} advisory finding(s)",issues.len());
   }
   if strict&&issues.iter().any(|d|d.severity=="warning"){return Err("strict lint: warnings detected".into())}
  }
  other=>return Err(format!("unknown command: {other}"))
 }
 Ok(())
}
fn main()->ExitCode{
 let args:Vec<String>=env::args().collect();
 match run(&args){Ok(())=>ExitCode::SUCCESS,Err(e)=>{eprintln!("{e}");ExitCode::FAILURE}}
}
