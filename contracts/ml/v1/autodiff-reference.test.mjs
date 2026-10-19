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
  assert.throws(()=>fwd(g,1),/MICROBATCH_ID_OUT_OF_RANGE/);
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


test('reject unknown, hidden, accessor and spoofed configuration fields',()=>{
  const props={nodes:linear(),output:'loss',unexpected:'permit'};
  assert.throws(()=>new ActorAutodiffReference(props),/unknown or missing/);
  assert.throws(()=>new ActorAutodiffReference({nodes:linear(),output:'loss',maxInFlight:null}),/safe integer/);
  const access={output:'loss'};
  Object.defineProperty(access,'nodes',{enumerable:true,get(){throw new Error('EVALUATED_UNTRUSTED_GETTER');}});
  assert.throws(()=>new ActorAutodiffReference(access),/accessor/);
  const hidden={nodes:linear(),output:'loss'};
  Object.defineProperty(hidden,'extra',{enumerable:false,value:1});
  assert.throws(()=>new ActorAutodiffReference(hidden),/unknown or missing/);
  const sym=Symbol('extra');
  assert.throws(()=>new ActorAutodiffReference({nodes:linear(),output:'loss',[sym]:1}),/unknown or missing/);
});

test('reject unknown node fields and accessor or sparse graph arrays',()=>{
  const sp=linear();sp[0].unauthorized=true;
  assert.throws(()=>graph(sp),/unknown or missing/);
  const notDense=linear();delete notDense[0].inputs[0];
  assert.throws(()=>graph(notDense),/(bounded dense array|sparse\/accessor)/);
  const getter=linear();
  Object.defineProperty(getter[0],'bias',{enumerable:true,get(){throw new Error('UNSAFE_GETTER');}});
  assert.throws(()=>graph(getter),/accessor/);
  const matrix=linear();delete matrix[0].weights[1];
  assert.throws(()=>graph(matrix),/(bounded dense array|sparse\/accessor)/);
  const extra=linear();extra[1].weights=[[1]];
  assert.throws(()=>graph(extra),/unexpected parameters/);
});

test('reject oversized graphs and weights before allocating owned parameter copies',()=>{
  const graphNodes=[];
  let previous='$input';
  for(let i=0;i<128;i++){const id='step'+i;graphNodes.push({id,type:'relu',inputs:[previous]});previous=id;}
  graphNodes.push({id:'loss',type:'mse',inputs:[previous]});
  assert.throws(()=>graph(graphNodes),/bounded dense array/);
  const denseNodes=[];
  previous='$input';
  for(let i=0;i<17;i++){
    const id='layer'+i;
    denseNodes.push({id,type:'dense',inputs:[previous],weights:[Array(4096).fill(1)],bias:[0]});
    previous=id;
  }
  denseNodes.push({id:'loss',type:'mse',inputs:[previous]});
  assert.throws(()=>graph(denseNodes),/PARAMETER_BUDGET_EXCEEDED/);
});

test('strict DATA message validation rejects unknown, getter and hidden values',()=>{
  const g=graph();
  const beginMessage={runId:'runA',stepId:3,expectedMicrobatches:1};
  assert.throws(()=>g.beginStep({...beginMessage,callerRole:'admin'}),/unknown or missing/);
  begin(g);
  assert.throws(()=>g.forward({...{runId:'runA',stepId:3,microbatchId:0,input:[1,2],target:[2,3]},actorId:'other'}),/unknown or missing/);
  const forwardMessage={runId:'runA',stepId:3,microbatchId:0,input:[1,2]};
  Object.defineProperty(forwardMessage,'target',{get(){throw new Error('GETTER_RUN');},enumerable:true});
  assert.throws(()=>g.forward(forwardMessage),/accessor/);
  fwd(g);
  assert.throws(()=>g.backward({runId:'runA',stepId:3,microbatchId:0,skipGradients:true}),/unknown or missing/);
  assert.throws(()=>g.commit({runId:'runA',stepId:3,learningRate:0.1,authorize:true}),/unknown or missing/);
  assert.throws(()=>g.cancel({runId:'runA',stepId:3,recovery:'trusted'}),/unknown or missing/);
  back(g);
  assert.equal(commit(g).committedVersion,1);
});

test('microbatch identifiers are contiguous bounded step-local IDs',()=>{
  const g=graph();begin(g,2);
  assert.throws(()=>fwd(g,2),/MICROBATCH_ID_OUT_OF_RANGE/);
  assert.throws(()=>fwd(g,Number.MAX_SAFE_INTEGER),/MICROBATCH_ID_OUT_OF_RANGE/);
  assert.throws(()=>back(g,2),/MICROBATCH_ID_OUT_OF_RANGE/);
  fwd(g,1);back(g,1);
  assert.throws(()=>commit(g),/INCOMPLETE_STEP/);
  fwd(g,0);back(g,0);
  assert.equal(commit(g).completedMicrobatches,2);
});

test('gradient fan-in with the identical source on two add ports is counted twice',()=>{
  const g=graph([{id:'twice',type:'add',inputs:['$input','$input']},{id:'loss',type:'mse',inputs:['twice']}]);
  begin(g);close(fwd(g,0,[3],[0]).loss,18);
  assert.deepEqual(back(g).inputGradient,[12]);
  const backEdges=g.trace().filter(x=>x.kind==='backward'&&x.from==='twice');
  assert.deepEqual(backEdges.map(x=>x.inputSlot),[0,1]);
  assert.deepEqual(backEdges.map(x=>x.to),['$input','$input']);
});

test('forward and backward edge receipts include full step and slot correlation',()=>{
  const g=graph();begin(g);
  const forward=fwd(g);const backward=back(g);
  for(const message of [forward,backward]){
    assert.equal(message.runId,'runA');
    assert.equal(message.stepId,3);
    assert.equal(message.microbatchId,0);
    assert.equal(message.parameterVersion,0);
  }
  for(const receipt of g.trace()){
    assert.equal(receipt.runId,'runA');
    assert.equal(receipt.stepId,3);
    assert.equal(receipt.microbatchId,0);
    assert.equal(receipt.parameterVersion,0);
    assert.equal(receipt.inputSlot,0);
  }
  const trace=g.trace();
  trace[0].node='forged';
  assert.notEqual(g.trace()[0].node,'forged');
});

test('a rejected forward overflow does not consume microbatch credit or mutate tape',()=>{
  const g=graph([{id:'sum',type:'add',inputs:['$input','$input']},{id:'loss',type:'mse',inputs:['sum']}]);
  begin(g);
  assert.throws(()=>fwd(g,0,[Number.MAX_VALUE],[0]),/overflow/);
  assert.deepEqual(g.trace(),[]);
  assert.equal(fwd(g,0,[1],[0]).loss,2);
  assert.deepEqual(back(g).inputGradient,[4]);
  assert.equal(commit(g).committedVersion,1);
});

test('backward overflow retains original forward tape until cancel',()=>{
  const g=graph([
    {id:'dense',type:'dense',inputs:['$input'],weights:[[Number.MAX_VALUE]],bias:[1]},
    {id:'loss',type:'mse',inputs:['dense']}
  ]);
  begin(g);
  close(fwd(g,0,[0],[-2]).loss,4.5);
  assert.throws(()=>back(g),/overflow/);
  assert.throws(()=>commit(g),/INCOMPLETE_STEP/);
  assert.equal(g.trace().filter(x=>x.kind==='forward').length,2);
  g.cancel({runId:'runA',stepId:3});
  assert.equal(g.version,0);
  begin(g); // cancelled step may be retried with safe input/target
  fwd(g,0,[0],[0]);back(g);
  assert.equal(commit(g).committedVersion,1);
});

test('a bounded in-memory commit ledger refuses unsafe eviction and replay',()=>{
  const g=new ActorAutodiffReference({nodes:linear(),output:'loss',maxCommittedSteps:1});
  begin(g);fwd(g);back(g);commit(g);
  assert.throws(()=>begin(g),/ALREADY_COMMITTED/);
  assert.throws(()=>g.beginStep({runId:'different',stepId:4,expectedMicrobatches:1}),/REPLAY_LEDGER_EXHAUSTED/);
  assert.equal(g.version,1);
});

test('activation credit budget bounds aggregate outstanding forward tapes',()=>{
  const stages=[];let prev='$input';
  for(let i=0;i<60;i++){const id='relu'+i;stages.push({id,type:'relu',inputs:[prev]});prev=id;}
  stages.push({id:'loss',type:'mse',inputs:[prev]});
  const g=graph(stages,8);
  begin(g,8);
  for(let i=0;i<4;i++) fwd(g,i,Array(1000).fill(0.5),Array(1000).fill(0));
  // 4 * (1000 input + 1000 target + 60000 ReLU + 1 loss) > 262144? false (248004)
  assert.throws(()=>fwd(g,4,Array(1000).fill(0.5),Array(1000).fill(0)),/ACTIVATION_CREDIT_EXHAUSTED/);
  back(g,0);
  assert.doesNotThrow(()=>fwd(g,4,Array(1000).fill(0.5),Array(1000).fill(0)));
});

test('forward activations cannot mutate parameter snapshots after owner admission',()=>{
  const inputs=linear();const g=graph(inputs);
  const exported=g.parameters();
  inputs[0].weights[0][0]=-999;
  exported.dense.weights[0][0]=-999;
  begin(g);
  close(fwd(g).loss,28.15625);
  back(g);
  assert.equal(g.gradients().dense.weights[0][0],6.5);
});


test('gradient aggregation has canonical microbatch-id order, not arrival-order rounding',()=>{
  const config=[
    {id:'dense',type:'dense',inputs:['$input'],weights:[[0]],bias:[0]},
    {id:'loss',type:'mse',inputs:['dense']}
  ];
  function compute(order){
    const g=graph(config);
    begin(g,3);
    const targets=[-1e16,-1,1e16];
    for(const i of order){
      fwd(g,i,[0],[targets[i]]);
      back(g,i);
    }
    const grads=g.gradients();
    const version=commit(g).committedVersion;
    return {biasGradient:grads.dense.bias[0],weightGradient:grads.dense.weights[0][0],version,parameters:g.parameters()};
  }
  assert.deepEqual(compute([0,1,2]),compute([0,2,1]));
  assert.deepEqual(compute([1,2,0]),compute([2,0,1]));
});

test('reference trace and replay memory budgets reject oversized step declarations',()=>{
  const nodes=[];
  let from='$input';
  for(let i=0;i<80;i++){const id='h'+i;nodes.push({id,type:'relu',inputs:[from]});from=id;}
  nodes.push({id:'loss',type:'mse',inputs:[from]});
  const g=graph(nodes);
  assert.throws(()=>begin(g,205),/TRACE_BUDGET_EXCEEDED/); // 205 * 81 * 2 > 32768
  assert.doesNotThrow(()=>begin(g,200)); // 200 * 81 * 2 <= 32768
  assert.throws(()=>g.beginStep({runId:'runB',stepId:3,expectedMicrobatches:1}),/training step already active/);
});

test('an accumulated overflow rejects optimizer commit without partially updating parameters',()=>{
  const g=graph([
    {id:'dense',type:'dense',inputs:['$input'],weights:[[0]],bias:[0]},
    {id:'loss',type:'mse',inputs:['dense']}
  ]);
  const before=g.parameters();begin(g,2);
  for(let i=0;i<2;i++){
    fwd(g,i,[1e154],[-1e154]);
    back(g,i);
  }
  assert.throws(()=>g.gradients(),/overflow/);
  assert.throws(()=>commit(g),/overflow/);
  assert.deepEqual(g.parameters(),before);
  assert.equal(g.version,0);
  g.cancel({runId:'runA',stepId:3});
  assert.equal(g.version,0);
});
