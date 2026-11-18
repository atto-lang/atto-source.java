//! Source-free reference of optional/named/rest call-item binding.
//! This is NOT Java parser, typechecker, guest execution, or AOT evidence.
use std::collections::HashSet;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Value {
    Int(i64),
    Text(&'static str),
    None,
    Pack(Vec<Value>),
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Argument {
    Value(Value),
    Omit,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Formal {
    pub name: &'static str,
    pub optional: bool,
    pub rest: bool,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Item {
    pub argument: Argument,
    pub label: Option<&'static str>,
    pub source_index: usize,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Slot {
    Omitted,
    Value(Value),
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Plan {
    pub slots: Vec<Slot>,
    pub evaluation_order: Vec<usize>,
}

/// Never evaluate an expression here; record its original source position.
pub fn bind(formals: &[Formal], items: &[Item]) -> Result<Plan, &'static str> {
    if formals.iter().take(formals.len().saturating_sub(1)).any(|f| f.rest) {
        return Err("rest must be final");
    }
    let mut names = HashSet::new();
    for f in formals {
        if !names.insert(f.name) {
            return Err("duplicate formal");
        }
    }
    let has_rest = formals.last().is_some_and(|f| f.rest);
    let fixed = formals.len() - usize::from(has_rest);
    let mut assigned = vec![None; formals.len()];
    let mut rest = Vec::new();
    let mut evaluation_order = Vec::new();
    let mut saw_named = false;
    let mut next_positional = 0usize;
    for (index, item) in items.iter().enumerate() {
        if item.source_index != index {
            return Err("source item indexes must be contiguous");
        }
        let slot = if let Some(label) = item.label {
            saw_named = true;
            formals.iter().position(|f| f.name == label).ok_or("unknown label")?
        } else {
            if saw_named {
                return Err("positional after named");
            }
            let slot = next_positional;
            next_positional += 1;
            slot
        };
        if slot >= fixed {
            if !has_rest || matches!(&item.argument, Argument::Omit) {
                return Err("invalid extra/rest argument");
            }
            if item.label.is_some() {
                if assigned[fixed].is_some() || !rest.is_empty() {
                    return Err("invalid rest pack");
                }
                if let Argument::Value(Value::Pack(values)) = &item.argument {
                    assigned[fixed] = Some(Slot::Value(Value::Pack(values.clone())));
                } else {
                    return Err("named rest requires typed pack representation");
                }
            } else if let Argument::Value(value) = &item.argument {
                if assigned[fixed].is_some() {
                    return Err("invalid rest pack");
                }
                rest.push(value.clone());
            }
            evaluation_order.push(item.source_index);
            continue;
        }
        if assigned[slot].is_some() {
            return Err("duplicate slot");
        }
        match &item.argument {
            Argument::Omit => {
                if !formals[slot].optional {
                    return Err("required slot omitted");
                }
                assigned[slot] = Some(Slot::Omitted);
            }
            Argument::Value(value) => {
                assigned[slot] = Some(Slot::Value(value.clone()));
                evaluation_order.push(item.source_index);
            }
        }
    }
    for slot in 0..fixed {
        if assigned[slot].is_none() {
            if !formals[slot].optional {
                return Err("missing required");
            }
            assigned[slot] = Some(Slot::Omitted);
        }
    }
    if has_rest && assigned[fixed].is_none() {
        assigned[fixed] = Some(Slot::Value(Value::Pack(rest)));
    }
    Ok(Plan {
        slots: assigned.into_iter().map(|s| s.expect("validated formal")).collect(),
        evaluation_order,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(name: &'static str, optional: bool, rest: bool) -> Formal {
        Formal { name, optional, rest }
    }
    fn sig() -> Vec<Formal> {
        vec![f("a", false, false), f("b", true, false), f("tags", false, true)]
    }
    fn v(label: Option<&'static str>, value: Value, source_index: usize) -> Item {
        Item { argument: Argument::Value(value), label, source_index }
    }
    fn omit(label: Option<&'static str>, source_index: usize) -> Item {
        Item { argument: Argument::Omit, label, source_index }
    }

    #[test]
    fn omitted_slot_does_not_evaluate() {
        let p = bind(&sig(), &[v(None, Value::Int(3), 0), omit(None, 1),
            v(None, Value::Text("once"), 2)]).unwrap();
        assert_eq!(p.slots, vec![Slot::Value(Value::Int(3)), Slot::Omitted,
            Slot::Value(Value::Pack(vec![Value::Text("once")]))]);
        assert_eq!(p.evaluation_order, [0, 2]);
    }
    #[test]
    fn named_reordering_retains_source_order() {
        let p = bind(&[f("a", false, false), f("b", true, false), f("c", false, false)],
            &[v(Some("c"), Value::Int(3), 0), v(Some("a"), Value::Int(1), 1)]).unwrap();
        assert_eq!(p.slots, vec![Slot::Value(Value::Int(1)), Slot::Omitted, Slot::Value(Value::Int(3))]);
        assert_eq!(p.evaluation_order, [0, 1]);
    }
    #[test]
    fn named_rest_pack_keeps_original_origin() {
        let p = bind(&sig(), &[v(Some("tags"), Value::Pack(vec![Value::Text("x"),
            Value::Text("y")]), 0), v(Some("a"), Value::Int(5), 1)]).unwrap();
        assert_eq!(p.slots[2], Slot::Value(Value::Pack(vec![Value::Text("x"), Value::Text("y")])));
        assert_eq!(p.evaluation_order, [0, 1]);
    }
    #[test]
    fn no_rest_arguments_is_empty_pack() {
        let p = bind(&sig(), &[v(None, Value::Int(3), 0)]).unwrap();
        assert_eq!(p.slots[1], Slot::Omitted);
        assert_eq!(p.slots[2], Slot::Value(Value::Pack(vec![])));
    }
    #[test]
    fn explicit_none_is_a_real_evaluated_value() {
        let p = bind(&sig(), &[v(None, Value::Int(3), 0), v(None, Value::None, 1)]).unwrap();
        assert_eq!(p.slots[1], Slot::Value(Value::None));
        assert_eq!(p.evaluation_order, [0, 1]);
    }
    #[test]
    fn original_invalid_call_cases_still_reject() {
        let bad = [
            vec![omit(None, 0)],
            vec![v(None, Value::Int(1), 0), v(Some("a"), Value::Int(2), 1)],
            vec![v(Some("b"), Value::Int(1), 0), v(None, Value::Int(2), 1)],
            vec![v(Some("unknown"), Value::Int(1), 0)],
            vec![v(None, Value::Int(1), 0), omit(None, 1), omit(None, 2)],
            vec![v(None, Value::Int(1), 1)],
        ];
        for items in bad {
            assert!(bind(&sig(), &items).is_err(), "{items:?}");
        }
    }
    #[test]
    fn rest_must_be_final() {
        assert_eq!(bind(&[f("tags", false, true), f("a", false, false)], &[]),
            Err("rest must be final"));
    }
    #[test]
    fn duplicate_formal_fails_closed() {
        assert_eq!(bind(&[f("a", false, false), f("a", true, false)], &[]),
            Err("duplicate formal"));
    }
    #[test]
    fn duplicate_named_rest_fails_even_when_empty() {
        assert_eq!(bind(&sig(), &[v(Some("tags"), Value::Pack(vec![]), 0),
            v(Some("tags"), Value::Pack(vec![]), 1), v(Some("a"), Value::Int(1), 2)]),
            Err("invalid rest pack"));
    }
    #[test]
    fn named_rest_requires_typed_pack() {
        for val in [Value::None, Value::Int(4), Value::Text("not pack")] {
            assert_eq!(bind(&sig(), &[v(Some("tags"), val, 0), v(Some("a"), Value::Int(1), 1)]),
                Err("named rest requires typed pack representation"));
        }
    }
    #[test]
    fn named_rest_cannot_be_omitted() {
        assert!(bind(&sig(), &[v(Some("a"), Value::Int(1), 0), omit(Some("tags"), 1)]).is_err());
    }
    #[test]
    fn explicit_none_cannot_be_rebound() {
        assert_eq!(bind(&sig(), &[v(Some("a"), Value::Int(1), 0),
            v(Some("b"), Value::None, 1), v(Some("b"), Value::Int(2), 2)]),
            Err("duplicate slot"));
    }
    #[test]
    fn named_optional_omission_is_not_evaluated() {
        let p = bind(&sig(), &[v(Some("a"), Value::Int(1), 0), omit(Some("b"), 1)]).unwrap();
        assert_eq!(p.slots[1], Slot::Omitted);
        assert_eq!(p.evaluation_order, [0]);
    }
    #[test]
    fn rest_only_formals_support_named_and_positional() {
        let s = [f("tags", false, true)];
        let a = bind(&s, &[v(None, Value::Int(1), 0), v(None, Value::Int(2), 1)]).unwrap();
        let b = bind(&s, &[v(Some("tags"), Value::Pack(vec![Value::Int(1),
            Value::Int(2)]), 0)]).unwrap();
        assert_eq!(a.slots, b.slots);
        assert_eq!(a.evaluation_order, [0, 1]);
        assert_eq!(b.evaluation_order, [0]);
    }
    #[test]
    fn empty_signature_accepts_only_empty_call() {
        assert!(bind(&[], &[]).unwrap().slots.is_empty());
        assert!(bind(&[], &[v(None, Value::Int(1), 0)]).is_err());
    }
    #[test]
    fn source_index_must_be_contiguous() {
        assert_eq!(bind(&sig(), &[v(None, Value::Int(1), 1)]),
            Err("source item indexes must be contiguous"));
    }
}
