import test from 'node:test';
import assert from 'node:assert/strict';
import {ActorAutodiffReference} from './autodiff-reference.mjs';

const linear=()=>[
  {id:'dense',type:'dense',inputs:['$input'],weights:[[2,3],[-1,4]],bias:[0.5,-0.25]},
  {id:'loss',type:'mse',inputs:['dense']}
];
const graph=(nodes=linear(),maxInFlight=4)=>new ActorAutodiffReference({nodes,output:'loss',maxInFlight});
const begin=(g,n=1)=>g.beginStep({runId:'runA',stepId:3,expectedMicrobatches:n});
const fwd=(g,id=0,x=[1,2],y=[2,3])=>g.forward({runId:'runA',stepId:3,microbatchId:id,input:x,target:y});
const back=(g,id=0)=>g.backward({runId:'runA',stepId:3,microbatchId:id});
const commit=(g,learningRate=0.01)=>g.commit({runId:'runA',stepId:3,learningRate});
const close=(a,b,eps=1e-8)=>assert.ok(Math.abs(a-b)<=eps,`expected ${a} ~= ${b}`);

test('dense reverse mode returns correct gradients and input VJP',()=>{
  const g=graph(); begin(g); close(fwd(g).loss,28.15625);
  const {inputGradient}=back(g); close(inputGradient[0],9.25); close(inputGradient[1],34.5);
  const grad=g.gradients().dense;
  assert.deepEqual(grad.weights,[[6.5,13],[3.75,7.5]]);
  assert.deepEqual(grad.bias,[6.5,3.75]);
  assert.equal(commit(g).committedVersion,1);
});

test('finite-difference gradients agree with reverse mode',()=>{
  const base=linear(); const g=graph(base); begin(g); fwd(g); back(g);
  const exact=g.gradients().dense;
  function lossFor(nodes){const x=graph(nodes);begin(x);return fwd(x).loss;}
  const h=1e-5;
  for(let j=0;j<2;j++) for(let i=0;i<2;i++) {
    const p=linear(),m=linear();p[0].weights[j][i]+=h;m[0].weights[j][i]-=h;
    close((lossFor(p)-lossFor(m))/(2*h),exact.weights[j][i],1e-8);
  }
  for(let j=0;j<2;j++) {
    const p=linear(),m=linear();p[0].bias[j]+=h;m[0].bias[j]-=h;
    close((lossFor(p)-lossFor(m))/(2*h),exact.bias[j],1e-8);
  }
});

test('residual fan-out and fan-in sums both VJP contributions',()=>{
  const g=graph([
    {id:'branch',type:'dense',inputs:['$input'],weights:[[2]],bias:[0]},
    {id:'residual',type:'add',inputs:['branch','$input']},
    {id:'loss',type:'mse',inputs:['residual']}
  ]);
  begin(g); close(fwd(g,0,[3],[0]).loss,40.5);
  assert.deepEqual(back(g).inputGradient,[27]); // d(0.5*(3x)^2)/dx at x=3
  assert.deepEqual(g.gradients().branch.weights,[[27]]);
  const routes=g.trace().filter(x=>x.kind==='backward'&&x.from==='residual');
  assert.equal(routes.length,2);
});

test('ReLU zero derivative and branch gradient do not fabricate paths',()=>{
  const g=graph([
    {id:'negative',type:'dense',inputs:['$input'],weights:[[-1]],bias:[0]},
    {id:'relu',type:'relu',inputs:['negative']},
    {id:'loss',type:'mse',inputs:['relu']}
  ]);
  begin(g); fwd(g,0,[2],[1]);
  assert.deepEqual(back(g).inputGradient,[0]);
  assert.deepEqual(g.gradients().negative.weights,[[0]]);
});

test('microbatch gradients accumulate and optimizer commits one consistent version',()=>{
  const g=graph(undefined,1);begin(g,2);
  assert.equal(fwd(g,0).parameterVersion,0);
  assert.throws(()=>fwd(g,1),/BACKPRESSURE/);
  back(g,0);
  assert.equal(fwd(g,1,[2,1],[1,2]).parameterVersion,0);
  assert.throws(()=>commit(g),/INCOMPLETE_STEP/);
  back(g,1);
  const before=g.parameters().dense;
  const sum=g.gradients().dense;
  commit(g,0.1);
  const after=g.parameters().dense;
  close(after.weights[0][0],before.weights[0][0]-0.1*sum.weights[0][0]/2);
  close(after.bias[1],before.bias[1]-0.1*sum.bias[1]/2);
  assert.equal(g.version,1);
  assert.throws(()=>back(g,0),/STALE_STEP_OR_VERSION/);
});

test('duplicates, unknown messages, excessive batches, and stale step fail closed',()=>{
  const g=graph();begin(g);
  assert.throws(()=>back(g),/MISSING_FORWARD/);
  fwd(g);
  assert.throws(()=>fwd(g),/DUPLICATE_FORWARD/);
  assert.throws(()=>fwd(g,1),/TOO_MANY_MICROBATCHES/);
  assert.throws(()=>g.backward({runId:'runB',stepId:3,microbatchId:0}),/STALE_STEP_OR_VERSION/);
  back(g);
  assert.throws(()=>back(g),/DUPLICATE_BACKWARD/);
  commit(g);
  assert.throws(()=>commit(g),/STALE_STEP_OR_VERSION/);
});

test('cancel discards partial gradient updates without modifying parameters',()=>{
  const g=graph();const before=g.parameters();begin(g,2);fwd(g);back(g);
  g.cancel({runId:'runA',stepId:3});
  assert.deepEqual(g.parameters(),before);assert.equal(g.version,0);
  begin(g);fwd(g);back(g);commit(g);
  assert.equal(g.version,1);
});

test('invalid graph edges, mismatched shapes, and nonfinite tensors fail closed',()=>{
  assert.throws(()=>graph([{id:'loss',type:'mse',inputs:['notCreated']}]),/missing/);
  assert.throws(()=>graph([{id:'x',type:'relu',inputs:['x']},{id:'loss',type:'mse',inputs:['x']}]),/cyclic/);
  assert.throws(()=>graph([{id:'x',type:'relu',inputs:['$input']},{id:'x',type:'mse',inputs:['x']}]),/duplicate/);
  const g=graph();begin(g);
  assert.throws(()=>fwd(g,0,[1],[2,3]),/shape mismatch/);
  assert.throws(()=>fwd(g,0,[NaN,2]),/nonfinite/);
  fwd(g);back(g);
  assert.throws(()=>commit(g,Infinity),/learningRate/);
  assert.equal(g.version,0);
});

test('caller mutation cannot change captured activations or owned parameters',()=>{
  const nodes=linear();const g=graph(nodes);nodes[0].weights[0][0]=900;
  begin(g);const x=[1,2],t=[2,3];const result=fwd(g,0,x,t);
  x[0]=999;t[0]=999;
  close(result.loss,28.15625);
  assert.deepEqual(back(g).inputGradient,[9.25,34.5]);
  const exported=g.parameters();exported.dense.weights[0][0]=900;
  assert.equal(g.parameters().dense.weights[0][0],2);
});

test('a real SGD learning loop lowers loss across forty committed steps',()=>{
  const g=graph([
    {id:'dense',type:'dense',inputs:['$input'],weights:[[0]],bias:[0]},
    {id:'loss',type:'mse',inputs:['dense']}
  ]);
  let first;
  for(let stepId=0;stepId<40;stepId++) {
    g.beginStep({runId:'learning',stepId,expectedMicrobatches:1});
    const loss=g.forward({runId:'learning',stepId,microbatchId:0,input:[1],target:[2]}).loss;
    if(stepId===0) first=loss;
    else assert.ok(loss<first);
    g.backward({runId:'learning',stepId,microbatchId:0});
    g.commit({runId:'learning',stepId,learningRate:0.2});
  }
  assert.equal(g.version,40);
  close(g.parameters().dense.weights[0][0]+g.parameters().dense.bias[0],2,1e-7);
});

test('committed step identity is fenced against replay; aborted work can retry',()=>{
  const g=graph();begin(g);fwd(g);back(g);commit(g);
  assert.throws(()=>begin(g),/ALREADY_COMMITTED/);
  g.beginStep({runId:'runB',stepId:3,expectedMicrobatches:1});
  g.cancel({runId:'runB',stepId:3});
  assert.doesNotThrow(()=>g.beginStep({runId:'runB',stepId:3,expectedMicrobatches:1}));
  g.cancel({runId:'runB',stepId:3});
});

test('overflowing proposed optimizer update never partially mutates parameters',()=>{
  const g=graph();begin(g);fwd(g);back(g);
  const old=g.parameters();
  assert.throws(()=>commit(g,Number.MAX_VALUE),/overflow\/nonfinite/);
  assert.deepEqual(g.parameters(),old);
  assert.equal(g.version,0);
  assert.equal(commit(g).committedVersion,1);
});

test('unused graph stages are refused rather than silently skipping parameter updates',()=>{
  assert.throws(()=>graph([
    {id:'orphan',type:'dense',inputs:['$input'],weights:[[1]],bias:[0]},
    {id:'loss',type:'mse',inputs:['$input']}
  ]),/unused actor/);
});
