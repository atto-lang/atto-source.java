//! Diagnostics for a versioned *compiler-supplied* typed control-flow graph.
 //! The JSON exporter has to derive facts from admitted AST/type/effect analysis.
 //! Caller-provided IR is untrusted: success proves nothing about its source.
use serde::Deserialize;
use std::collections::{BTreeSet,HashMap,HashSet,VecDeque};
#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Program{
    pub schema_version:String,
    pub path:String,
    pub functions:Vec<Function>,
}
#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Function{
    pub name:String,
    pub actor_kind:String,
    pub allows_error:bool,
    pub entry:u32,
    pub nodes:Vec<Node>,
    #[serde(default)]pub matches:Vec<MatchCheck>,
}
#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Node{
    pub id:u32,pub line:usize,pub column:usize,
    pub op:String,
    pub successors:Vec<u32>,
    #[serde(default)]pub may_throw:bool,
    #[serde(default)]pub handles_error:bool,
    #[serde(default)]pub borrowed_mut_across_suspend:bool,
    #[serde(default)]pub blocking_call:bool,
    #[serde(default)]pub select_has_cancellation:bool,
}
#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MatchCheck{
    pub line:usize,pub column:usize,
    /// Complete finite variants, obtained from a closed type only.
    pub closed_variants:Vec<String>,
    pub arms:Vec<Arm>,
}
#[derive(Debug,Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Arm{
    pub variants:Vec<String>,
    #[serde(default)]pub guarded:bool,
    #[serde(default)]pub wildcard:bool,
}
#[derive(Debug,PartialEq,Eq)]
pub struct Finding{pub code:&'static str,pub function:String,pub line:usize,pub column:usize,pub message:String}
fn finding(code:&'static str,f:&Function,line:usize,column:usize,msg:impl Into<String>)->Finding{
    Finding{code,function:f.name.clone(),line,column,message:msg.into()}
}
fn valid_graph(fun:&Function)->Result<(HashMap<u32,&Node>,HashSet<u32>),String>{
    let mut by_id=HashMap::new();
    for node in &fun.nodes{
        if node.line==0||node.column==0{return Err(format!("{}: invalid source location",fun.name))}
        if by_id.insert(node.id,node).is_some(){
            return Err(format!("{}: duplicate CFG node {}",fun.name,node.id))
        }
        if !["entry","exit","await","select","readch","writech","yield","call","defer","return","throw","branch","other"]
            .contains(&node.op.as_str()){
            return Err(format!("{}: unsupported operation {}",fun.name,node.op))
        }
    }
    if !by_id.contains_key(&fun.entry){return Err(format!("{}: missing entry node",fun.name))}
    for node in &fun.nodes{
        if ["return","throw","exit"].contains(&node.op.as_str())&&!node.successors.is_empty(){
            return Err(format!("{}: terminal CFG node {} has successors",fun.name,node.id))
        }
        if node.borrowed_mut_across_suspend && !["await","select","readch","writech","yield"].contains(&node.op.as_str()){
            return Err(format!("{}: suspension borrow fact on non-suspending node {}",fun.name,node.id))
        }
        if node.successors.iter().any(|id|!by_id.contains_key(id)){
            return Err(format!("{}: dangling successor at {}",fun.name,node.id))
        }
    }
    let mut seen=HashSet::new();
    let mut queue=VecDeque::from([fun.entry]);
    while let Some(id)=queue.pop_front(){
        if !seen.insert(id){continue}
        for &next in &by_id[&id].successors{queue.push_back(next)}
    }
    Ok((by_id,seen))
}
fn cyclic_nodes(graph:&HashMap<u32,&Node>,reachable:&HashSet<u32>)->HashSet<u32>{
    // Exact graph reachability for every node; determines SCC membership
    // (a node participates in a cycle iff it can reach itself).
    let mut cyclic=HashSet::new();
    for &start in reachable{
        let mut visited=HashSet::new();
        let mut todo:Vec<u32>=graph[&start].successors.clone();
        while let Some(next)=todo.pop(){
            if next==start{cyclic.insert(start);break}
            if visited.insert(next){todo.extend(&graph[&next].successors)}
        }
    }
    cyclic
}
pub fn analyze(text:&str)->Result<Vec<Finding>,String>{
    if text.len()>16*1024*1024{return Err("typed IR size exceeds 16 MiB limit".into())}
    let program:Program=serde_json::from_str(text).map_err(|e|format!("bad typed IR: {e}"))?;
    if program.schema_version!="0.1.0"{return Err("unsupported typed IR schema version".into())}
    if program.path.is_empty()||program.functions.len()>10000{
        return Err("invalid file or function count".into())
    }
    let mut out=vec![];
    for fun in &program.functions{
        if !["none","shared","private","untrusted"].contains(&fun.actor_kind.as_str()){
            return Err(format!("{}: invalid actor kind",fun.name))
        }
        if fun.nodes.len()>20000{return Err(format!("{}: CFG too large",fun.name))}
        let (graph,reach)=valid_graph(fun)?;
        let cycles=cyclic_nodes(&graph,&reach);
        for node in &fun.nodes{
            if !reach.contains(&node.id){
                out.push(finding("ORES211",fun,node.line,node.column,"unreachable CFG node"));
                continue
            }
            if node.op=="defer"&&cycles.contains(&node.id){
                out.push(finding("ORES212",fun,node.line,node.column,
                    "defer is registered on a reachable cycle; confirm per-iteration cleanup semantics"));
            }
            if ["await","select","readch","writech","yield"].contains(&node.op.as_str())&&node.borrowed_mut_across_suspend{
                out.push(finding("ORES213",fun,node.line,node.column,
                    "mutable borrow is live across suspension"));
            }
            if node.may_throw&&!node.handles_error&&!fun.allows_error{
                out.push(finding("ORES214",fun,node.line,node.column,
                    "possible unhandled throwing call in non-throwing function"));
            }
            if fun.actor_kind=="untrusted"&&node.blocking_call{
                out.push(finding("ORES215",fun,node.line,node.column,
                    "blocking call on untrusted actor carrier"));
            }
            if node.op=="select"&&!node.select_has_cancellation{
                out.push(finding("ORES216",fun,node.line,node.column,
                    "select path lacks verified losing-arm deregistration"));
            }
        }
        for case in &fun.matches{
            if case.line==0||case.column==0{return Err("invalid match location".into())}
            if case.closed_variants.is_empty(){
                return Err(format!("{}: no finite closed variant domain",fun.name))
            }
            let domain:BTreeSet<_>=case.closed_variants.iter().cloned().collect();
            if domain.len()!=case.closed_variants.len(){
                return Err(format!("{}: duplicate type-domain variant",fun.name))
            }
            let mut covered=BTreeSet::new();
            for (i,arm) in case.arms.iter().enumerate(){
                let variants=if arm.wildcard{
                    if !arm.variants.is_empty(){return Err("wildcard arm must have empty variants".into())}
                    domain.clone()
                }else{
                    let set:BTreeSet<_>=arm.variants.iter().cloned().collect();
                    if set.is_empty()||set.len()!=arm.variants.len()||!set.is_subset(&domain){
                        return Err(format!("{}: invalid match-arm variant",fun.name))
                    }
                    set
                };
                if variants.is_subset(&covered){
                    out.push(finding("ORES217",fun,case.line,case.column,
                        format!("match arm {i} is unreachable (already covered)")));
                }
                // Guarded patterns do not establish unconditional coverage.
                if !arm.guarded{covered.extend(variants)}
            }
            if covered!=domain{
                let missing:Vec<_>=domain.difference(&covered).cloned().collect();
                out.push(finding("ORES218",fun,case.line,case.column,
                    format!("non-exhaustive closed match: missing {}",missing.join(", "))));
            }
        }
    }
    Ok(out)
}
