// Host-only independent arithmetic oracle. This is not guest execution.
import test from 'node:test';
import assert from 'node:assert/strict';

const MAX=(1n<<63n)-1n, MIN=-(1n<<63n);
const within=x=>typeof x==='bigint' && x>=MIN && x<=MAX;
function preflight({element_bytes:e,length:n,alignment:a,byte_length:b}) {
  if (![e,n,a,b].every(within)) return false;
  if(e<=0n||n<0n||a<=0n||b<0n) return false;
  if(n===0n) return b===0n;
  if(e>MAX/n) return false;
  return b===e*n;
}
// Independent spec evaluates the exact product in unbounded integers, THEN
// checks the claimed bytes fit the signed-i64 ABI; no modulo or wrapping.
function specification({element_bytes:e,length:n,alignment:a,byte_length:b}) {
  if(![e,n,a,b].every(within)||e<=0n||n<0n||a<=0n||b<0n) return false;
  const product=e*n;
  return product<=MAX && b===product;
}
const layout=(e,n,a,b)=>({element_bytes:BigInt(e),length:BigInt(n),alignment:BigInt(a),byte_length:BigInt(b)});

test('signed i64 boundary and overflow vectors',()=>{
  const cases=[
    [layout(8,3,8,24),true], [layout(8,3,8,1),false],
    [layout(8,0,8,0),true], [layout(8,0,8,1),false],
    [layout(MAX,1,8,MAX),true], [layout(MAX,2,8,1),false],
    [layout(1,MAX,1,MAX),true], [layout(2,MAX,1,0),false],
    [layout(1,MIN,8,0),false], [layout(0,1,8,0),false],
    [layout(-1,1,8,0),false], [layout(8,3,0,24),false],
    [layout(8,3,-1,24),false], [layout(8,3,3,24),true],
    [layout(1,0,1,0),true], [layout(MAX,0,8,0),true],
    [layout(1,1,1,1),true], [layout(1,1,1,2),false]
  ];
  for(const [v,expected] of cases){
    assert.equal(preflight(v),expected,JSON.stringify(v,(_,x)=>typeof x==='bigint'?x.toString():x));
    assert.equal(specification(v),expected);
  }
});

test('exhaustive bounded domain against unbounded arithmetic oracle',()=>{
  let count=0;
  for(let e=-2n;e<=128n;e++) for(let n=-2n;n<=128n;n++) {
    const exact=e*n;
    for(const b of [-1n,0n,1n,exact,exact+1n,MAX]) {
      const v=layout(e,n,8,b);
      assert.equal(preflight(v),specification(v),`e=${e},n=${n},bytes=${b}`);
      count++;
    }
  }
  assert.ok(count>100000);
});

test('seeded 64-bit edge perturbations agree with independent oracle',()=>{
  let seed=0x8877665544332211n;
  const next=()=>{ seed=(seed*6364136223846793005n+1442695040888963407n)&((1n<<64n)-1n);return seed>>1n;};
  for(let i=0;i<12000;i++){
    const e=next(), n=next();
    const variants=[0n,1n,MAX,e*n,(e*n)&MAX, e>0n?MAX/e:0n];
    const v=layout(e,n,8,variants[i%variants.length]);
    assert.equal(preflight(v),specification(v),`seed iteration ${i}`);
  }
});

test('two intentionally defective implementations must be caught',()=>{
  const unchecked=v=>v.element_bytes>0n&&v.length>=0n&&v.byte_length>=0n&&v.alignment>0n;
  const wrapped=v=>v.element_bytes>0n&&v.length>=0n&&v.alignment>0n&&v.byte_length===((v.element_bytes*v.length)&MAX);
  const wrong=layout(8,3,8,1), overflow=layout(MAX,2,8,MAX-1n);
  assert.equal(specification(wrong),false);
  assert.equal(unchecked(wrong),true);
  assert.equal(specification(overflow),false);
  assert.equal(wrapped(overflow),true);
});
