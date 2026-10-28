// Independent signed-64 reference oracle; HOST ONLY, not guest execution.
// BigInt avoids the JavaScript Number precision ceiling.
import test from "node:test";
import assert from "node:assert/strict";

const MAX=(1n<<63n)-1n;
const n=x=>BigInt(x);
function admit(x) {
  const [rows,cols,rs,cs,bytes,capacity,writable]=x.map((v,i)=>i===6?v:n(v));
  if ([rows,cols,rs,cs,capacity].some(v=>v<0n) || bytes<=0n) return false;
  if ([rows,cols,rs,cs,bytes,capacity].some(v=>v>MAX)) return false;
  if (rows===0n||cols===0n) return true;
  if (writable && ((rows>1n&&rs===0n)||(cols>1n&&cs===0n))) return false;
  // Reference oracle uses unbounded BigInt; bounds are checked on each
  // intermediate expression, not merely the final answer.
  const rspan=(rows-1n)*rs,cspan=(cols-1n)*cs;
  if (rspan>MAX||cspan>MAX) return false;
  if(writable&&rows>1n&&cols>1n) {
    if(cols*cs>MAX||rs<cols*cs) return false;
  }
  const sum=rspan+cspan;
  if(sum>=MAX) return false;
  const required=(sum+1n)*bytes;
  return required<=MAX && required<=capacity;
}
function exact(x) {
  const [rows,cols,rs,cs,bytes,capacity]=x.map((v,i)=>i===6?v:n(v));
  if(!admit(x)||cs!==1n||rs!==cols) return false;
  if(rows===0n||cols===0n) return capacity===0n;
  const elements=rows*cols;
  return elements<=MAX&&elements*bytes<=MAX&&elements*bytes===capacity;
}
function broadcasts(a,b) {
  if([...a,...b].some(v=>n(v)<0n||n(v)>MAX))return false;
  return a.every((v,i)=>n(v)===n(b[i])||n(v)===1n||n(b[i])===1n);
}

test("guest fixture positive and negative vectors match BigInt independent oracle",()=>{
  assert.equal(admit([3,4,4,1,8,96,true]),true);
  assert.equal(exact([3,4,4,1,8,96,true]),true);
  assert.equal(admit([3,4,8,1,4,80,true]),true);
  assert.equal(exact([3,4,8,1,4,80,true]),false);
  assert.equal(admit([3,4,0,0,8,8,false]),true);
  assert.equal(admit([3,4,0,0,8,8,true]),false);
  assert.equal(admit([2,2,1,1,8,24,true]),false);
  assert.equal(admit([3,4,4,1,8,95,true]),false);
  assert.equal(admit([MAX,3,2,1,8,96,true]),false);
  assert.equal(exact([0,4,4,1,8,0,true]),true);
  assert.equal(admit([-1,4,4,1,8,96,true]),false);
  assert.equal(broadcasts([3,1],[1,4]),true);
  assert.equal(broadcasts([0,3],[1,3]),true);
  assert.equal(broadcasts([3,4],[5,4]),false);
});

test("all small shapes/strides and capacities obey bounded interval reference",()=>{
  let iterations=0;
  for(let rows=0;rows<=4;rows++)
  for(let cols=0;cols<=4;cols++)
  for(let rs=0;rs<=6;rs++)
  for(let cs=0;cs<=6;cs++)
  for(const writable of [false,true])
  for(const cap of [0,4,8,32,96,256]){
    const x=[rows,cols,rs,cs,4,cap,writable];
    const actual=admit(x);
    if(actual&&rows>0&&cols>0) {
      let positions=[];
      for(let r=0;r<rows;r++)for(let c=0;c<cols;c++)positions.push(r*rs+c*cs);
      assert(positions.every(p=>p*4+4<=cap));
      if(writable)assert.equal(new Set(positions).size,positions.length);
    }
    iterations++;
  }
  assert.equal(iterations,14700);
});

test("overflow at every arithmetic boundary fails closed",()=>{
  assert.equal(admit([MAX,2,2,1,8,MAX,false]),false);
  assert.equal(admit([2,MAX,1,2,8,MAX,false]),false);
  assert.equal(admit([2,2,MAX,MAX,8,MAX,false]),false);
  assert.equal(admit([1,1,0,0,MAX,MAX,false]),true);
  assert.equal(admit([2,1,MAX,0,1,MAX,false]),false); // last-index + 1 overflow
  assert.equal(exact([MAX,MAX,MAX,1,8,MAX,true]),false);
  assert.equal(admit([0,MAX,0,0,8,0,false]),true);
});

test("non-overlapping writable row-major subviews are accepted",()=>{
  assert.equal(admit([2,3,4,1,8,56,true]),true);
  assert.equal(admit([2,3,4,2,8,72,true]),false); // possible overlap
  assert.equal(admit([1,4,0,2,8,56,true]),true);
  assert.equal(admit([4,1,2,0,8,56,true]),true);
});

test("broadcast rules are shape-only, never implied memory transfer",()=>{
  assert.equal(broadcasts([2,3],[1,3]),true);
  assert.equal(broadcasts([0,3],[1,3]),true);
  assert.equal(broadcasts([0,3],[2,3]),false);
  assert.equal(broadcasts([-1,3],[1,3]),false);
  assert.equal(broadcasts([MAX,1],[1,MAX]),true);
});
