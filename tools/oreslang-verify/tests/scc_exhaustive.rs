use oreslang_verify::typed_ir;
use serde_json::json;
use std::collections::{HashSet,VecDeque};

fn is_reachable(edges:&[Vec<usize>],start:usize,target:usize,require_edge:bool)->bool{
    let mut todo=VecDeque::new();
    let mut visited=HashSet::new();
    if require_edge{todo.extend(edges[start].iter().copied());}
    else{todo.push_back(start);}
    while let Some(node)=todo.pop_front(){
        if node==target{return true}
        if !visited.insert(node){continue}
        todo.extend(edges[node].iter().copied());
    }
    false
}

fn as_ir(edges:&[Vec<usize>])->String{
    let nodes:Vec<_>=edges.iter().enumerate().map(|(id,successors)|json!({
        "id":id as u32,"line":id+1,"column":1,"op":"defer",
        "successors":successors.iter().map(|x|*x as u32).collect::<Vec<_>>()
    })).collect();
    json!({
      "schema_version":"0.1.0","path":"graph.ores","functions":[{
        "name":"cycle_check","actor_kind":"none","allows_error":true,"entry":0,
        "nodes":nodes,"matches":[]
      }]
    }).to_string()
}
#[test]
fn all_three_node_directed_graphs_match_independent_reachability_oracle(){
    // 2^(3*3) = 512 graph structures, including cross edges, diamonds,
    // self loops, disconnected SCCs and unreachable cycles.
    for mask in 0u16..512 {
        let mut graph=vec![vec![];3];
        for (i, neighbors) in graph.iter_mut().enumerate().take(3) {
            for j in 0..3 {
                if mask & (1 << (i*3+j)) != 0 {neighbors.push(j);}
            }
        }
        let findings=typed_ir::analyze(&as_ir(&graph)).expect("valid test graph");
        let flagged:HashSet<usize>=findings.iter().filter(|f|f.code=="ORES212")
            .map(|f|f.line-1).collect();
        let expected:HashSet<usize>=(0..3)
            .filter(|&id|is_reachable(&graph,0,id,false)&&is_reachable(&graph,id,id,true))
            .collect();
        assert_eq!(flagged,expected,"mismatch on graph bitmask {mask}");
    }
}
#[test]
fn scc_cross_edge_order_is_not_a_proof_of_cycle(){
    let graph=vec![vec![1,2],vec![2],vec![]];
    let issues=typed_ir::analyze(&as_ir(&graph)).unwrap();
    assert!(!issues.iter().any(|f|f.code=="ORES212"));
}
#[test]
fn only_reachable_cycles_may_register_defer(){
    let graph=vec![vec![1],vec![],vec![2]];
    let issues=typed_ir::analyze(&as_ir(&graph)).unwrap();
    assert!(!issues.iter().any(|f|f.code=="ORES212"));
    assert!(issues.iter().any(|f|f.code=="ORES211"&&f.line==3));
}
