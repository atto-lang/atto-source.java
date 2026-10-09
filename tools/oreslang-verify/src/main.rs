use oreslang_verify::{lint::lint,model::Bounds,model_explore::explore};
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
fn visit(path:&Path,files:&mut Vec<std::path::PathBuf>)->Result<(),String>{
 if path.is_dir(){
  let entries=fs::read_dir(path).map_err(|e|e.to_string())?;
  for entry in entries{
   let p=entry.map_err(|e|e.to_string())?.path();
   if p.is_dir(){visit(&p,files)?}else if p.extension().is_some_and(|x|x=="ores"){files.push(p)}
  }
 }else if path.is_file(){files.push(path.to_path_buf())}
 else{return Err(format!("path not found: {}",path.display()))}
 Ok(())
}
fn run(args:&[String])->Result<(),String>{
 if args.len()<2{return Err("usage: oreslang-verify model [--actors N --messages N --workers N --capacity N --max-states N] | lint [--format json|text] [--pedantic] [--strict] PATH...".into())}
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
  "lint"=>{
   let mut pedantic=false;let mut strict=false;let mut json=false;let mut paths=vec![];
   let mut i=2;
   while i<args.len(){
    match args[i].as_str(){
     "--pedantic"=>pedantic=true,"--strict"=>strict=true,
     "--format"=>{
      let v=args.get(i+1).ok_or("missing --format value")?;
      match v.as_str(){"json"=>json=true,"text"=>json=false,_=>return Err("format must be json or text".into())}
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
   let mut issues=vec![];
   for p in files{
    let src=fs::read_to_string(&p).map_err(|e|format!("{}: {e}",p.display()))?;
    issues.extend(lint(&src,&p.to_string_lossy(),pedantic));
   }
   if json{
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
