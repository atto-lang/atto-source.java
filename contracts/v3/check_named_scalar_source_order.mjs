// Source-free reference oracle for named scalar argument evaluation.
// Not proof of Java/Graal, LLVM or native Oreslang execution.
import assert from 'node:assert/strict';

function bind(formals, items) {
    assert.equal(formals.length, items.length, 'full scalar arity required');
    assert.ok(formals.length > 0);
    assert.equal(new Set(formals).size, formals.length, 'unique formals');
    const formalToSource = Array(formals.length).fill(-1);
    let positional = 0;
    let namedSeen = false;
    for (let source = 0; source < items.length; source++) {
        const item = items[source];
        if (!item || item.omitted || item.spread || typeof item.evaluate !== 'function') {
            throw new Error('unsupported omitted, spread or invalid source expression');
        }
        const named = item.label !== null && item.label !== undefined;
        if (!named && namedSeen) throw new Error('positional after named');
        if (named) namedSeen = true;
        const formal = named ? formals.indexOf(item.label) : positional++;
        if (formal < 0 || formal >= formals.length) throw new Error('unknown formal label');
        if (formalToSource[formal] !== -1) throw new Error('duplicate formal binding');
        formalToSource[formal] = source;
    }
    if (formalToSource.some(index => index === -1)) throw new Error('missing formal');
    const sourceToFormal = Array(items.length).fill(-1);
    formalToSource.forEach((source, formal) => { sourceToFormal[source] = formal; });
    return Object.freeze({formalToSource: Object.freeze(formalToSource),
                          sourceToFormal: Object.freeze(sourceToFormal)});
}

function execute(plan, items, callee) {
    const values = Array(items.length);
    // No guest effect is evaluated before the complete plan has been checked.
    for (let source = 0; source < items.length; source++) {
        values[plan.sourceToFormal[source]] = items[source].evaluate();
    }
    return callee(...values);
}

let cases = 0;
function test(name, callback) {
    callback();
    cases++;
    process.stdout.write('PASS ' + name + '\\n');
}
function val(label, symbol, value, effects) {
    return {label, evaluate: () => { effects.push(symbol); return value; }};
}

test('two-arg BA43', () => {
    const effects = [];
    const items = [val('right','B',3,effects),val('left','A',4,effects)];
    assert.equal(execute(bind(['left','right'],items),items,(l,r)=>l*10+r),43);
    assert.deepEqual(effects,['B','A']);
});
test('three-arg BCA423', () => {
    const effects = [];
    const items = [val('b','B',2,effects),val('c','C',3,effects),val('a','A',4,effects)];
    const plan = bind(['a','b','c'],items);
    assert.deepEqual(plan.formalToSource,[2,0,1]);
    assert.deepEqual(plan.sourceToFormal,[1,2,0]);
    assert.equal(execute(plan,items,(a,b,c)=>a*100+b*10+c),423);
    assert.deepEqual(effects,['B','C','A']);
});
test('exception aborts later guest effects', () => {
    const effects = [];
    const items = [val('right','B',3,effects),val('left','A',4,effects)];
    const plan = bind(['left','right'],items);
    const failed = [{label:'right',evaluate:()=>{effects.push('X');throw Error('stop');}}, items[1]];
    assert.throws(()=>execute(plan,failed,(a,b)=>a+b),/stop/);
    assert.deepEqual(effects,['X']);
});
for (const [name, items, pattern] of [
    ['duplicate', [{label:'left',evaluate:()=>1},{label:'left',evaluate:()=>2}],/duplicate/],
    ['unknown', [{label:'wrong',evaluate:()=>1},{label:'left',evaluate:()=>2}],/unknown/],
    ['positional after named', [{label:'right',evaluate:()=>1},{label:null,evaluate:()=>2}],/positional/],
    ['omission', [{label:'right',evaluate:()=>1},{label:'left',omitted:true,evaluate:()=>2}],/unsupported/]
]) {
    test(name + ' rejects before guest effects', () => {
        assert.throws(()=>bind(['left','right'],items), pattern);
    });
}
test('bounded large permutation plan', () => {
    const n = 1000;
    const formals = Array.from({length:n},(_,i)=>'p'+i);
    const effects = [];
    const items = formals.slice().reverse().map((x,i)=>val(x,String(i),i,effects));
    const plan = bind(formals,items);
    assert.equal(execute(plan,items,(...values)=>values[0]),999);
    assert.equal(effects.length,n);
});
console.log('NAMED_SCALAR_SOURCE_ORDER_REFERENCE_PASS: '+cases+' independent oracle cases');
