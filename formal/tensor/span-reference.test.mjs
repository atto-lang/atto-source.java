import test from 'node:test';
import assert from 'node:assert/strict';

// Independent host mathematical oracle ONLY. Compiled .ores guest is separately
// required by native-tensor-span.yml before claiming source-language evidence.
const min = -(1n << 63n), max = (1n << 63n) - 1n;
const fits = n => n >= min && n <= max;

// Contract formula uses in-range signed-i64 operations after guard checks.
function checked_span(n, start, count, stride) {
  if (![n,start,count,stride].every(fits)) throw Error('out-of-domain input');
  if (n < 0n || start < 0n || count < 0n || stride <= 0n) return false;
  if (start > n) return false;
  if (count === 0n) return true;
  if (start === n) return false;
  return count - 1n <= (n - 1n - start) / stride;
}

// Truly independent oracle in arbitrary precision: no division/rearrangement.
function model(n, start, count, stride) {
  if (![n,start,count,stride].every(fits)) throw Error('out-of-domain input');
  if (n < 0n || start < 0n || count < 0n || stride <= 0n || start > n) return false;
  return count === 0n || start + (count - 1n) * stride < n;
}

const samples = [
  [[8n,0n,8n,1n],true],[[8n,1n,3n,3n],true],[[8n,8n,0n,3n],true],
  [[0n,0n,0n,1n],true],[[max,max-1n,1n,1n],true],
  [[8n,7n,2n,1n],false],[[8n,8n,1n,1n],false],
  [[8n,1n,2n,0n],false],[[8n,1n,2n,-2n],false],
  [[max,1n,max,2n],false],[[-1n,0n,0n,1n],false],
  [[8n,-1n,1n,1n],false],[[8n,0n,-1n,1n],false],
  [[8n,9n,0n,1n],false],[[8n,0n,3n,4n],false],
  [[max,0n,max,1n],true],[[max,0n,max,2n],false],
  [[0n,0n,1n,1n],false],[[max,max,0n,max],true],
  [[max,max,1n,max],false],[[min,0n,0n,1n],false]
];

test('signed-i64 tensor span boundary vectors',()=>{
  for(const [values,want] of samples) {
    assert.equal(checked_span(...values),want,String(values));
    assert.equal(model(...values),want,String(values));
  }
});
test('randomized, reproducible checked-span/model equivalence',()=>{
  let seed=0x12345678abcdefn;
  const next=()=>{
    seed=(6364136223846793005n*seed+1442695040888963407n)&((1n<<64n)-1n);
    return seed >= (1n<<63n) ? seed-(1n<<64n) : seed;
  };
  const corners=[min,-2n,-1n,0n,1n,2n,3n,7n,8n,32n,max-1n,max];
  for(let i=0;i<20000;i++){
    const v=Array.from({length:4},(_,j)=>
      (i+j)%3===0 ? corners[Number((next() & 0x7fffffffffffffffn)%BigInt(corners.length))] : next());
    assert.equal(checked_span(...v),model(...v),'mismatch '+String(v));
  }
});
test('mutation controls reject past-end and overflow-disguised views',()=>{
  assert.equal(model(8n,7n,2n,1n),false);
  assert.equal(model(max,1n,max,2n),false);
  assert.equal(model(0n,0n,1n,1n),false);
});
