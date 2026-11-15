// A deterministic CPU f64 reference model for differentiable actor DATA edges.
// This is NOT Oreslang source lowering, actual actor transport, or a GPU runtime.
// It makes correlation, gradient fan-in, parameter-version, and commit rules testable.
const VALID_ID = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const RESERVED_INPUT = '$input';
// Conservative limits for the host reference, not production actor resource quotas.
const MAX_NODES = 128;
const MAX_PARAMETER_SCALARS = 65536;
const MAX_ACTIVATION_SCALARS = 65536;
const MAX_INFLIGHT_ACTIVATION_SCALARS = 262144;
const MAX_MICROBATCHES = 4096;
const MAX_LEDGER_ENTRIES = 4096;
const MAX_TRACE_RECEIPTS = 32768;
function record(value, required, optional=[], name='record') {
  if (!value || typeof value!=='object' || Array.isArray(value) ||
      (Object.getPrototypeOf(value)!==Object.prototype &&
       Object.getPrototypeOf(value)!==null)) throw new TypeError(name+' must be a plain record');
  const keys=Reflect.ownKeys(value);
  const allowed=new Set([...required,...optional]);
  if (keys.some(k=>typeof k!=='string'||!allowed.has(k)) ||
      required.some(k=>!Object.hasOwn(value,k))) throw new TypeError(name+' unknown or missing fields');
  for (const k of keys) {
    const d=Object.getOwnPropertyDescriptor(value,k);
    if(!d||!('value' in d)||!d.enumerable) throw new TypeError(name+' accessor/hidden field forbidden');
  }
}
function dataArray(value,name,max) {
  if (!Array.isArray(value)||Object.getPrototypeOf(value)!==Array.prototype ||
      value.length<1||value.length>max||Reflect.ownKeys(value).length!==value.length+1) {
    throw new TypeError(name+' must be a bounded dense array');
  }
  const out=[];
  for(let i=0;i<value.length;i++) {
    const d=Object.getOwnPropertyDescriptor(value,String(i));
    if(!d||!('value' in d)||!d.enumerable) throw new TypeError(name+' sparse/accessor array forbidden');
    out.push(d.value);
  }
  return out;
}

function assertId(value, what) {
  if (typeof value !== 'string' || !VALID_ID.test(value)) {
    throw new TypeError(`${what} must be a nonempty canonical identifier`);
  }
}
function integer(value, what, min = 0) {
  if (!Number.isSafeInteger(value) || value < min) {
    throw new RangeError(`${what} must be a safe integer >= ${min}`);
  }
  return value;
}
function vector(value, what) {
  if (!Array.isArray(value) || Object.getPrototypeOf(value) !== Array.prototype ||
      value.length < 1 || value.length > 4096 || Reflect.ownKeys(value).length !== value.length + 1) {
    throw new TypeError(`${what} must be a dense, nonempty f64 vector`);
  }
  return Array.from({length:value.length}, (_,i) => {
    const d = Object.getOwnPropertyDescriptor(value, String(i));
    if (!d || !('value' in d) || !d.enumerable || typeof d.value !== 'number' || !Number.isFinite(d.value)) {
      throw new TypeError(`${what} contains invalid/nonfinite/accessor element`);
    }
    return d.value;
  });
}
function same(a, b, what) {
  if (a.length !== b.length) throw new RangeError(`${what} shape mismatch`);
}
function addTo(map, id, grad) {
  if (!map.has(id)) { map.set(id, grad.slice()); return; }
  const existing = map.get(id);
  same(existing, grad, 'gradient fan-in');
  for (let i=0;i<grad.length;i++) existing[i] += grad[i];
}
function finite(values, what) {
  if (values.some(x => !Number.isFinite(x))) throw new RangeError(`${what} overflow/nonfinite result`);
}
// Stable microbatch-id order avoids nondeterministic f64 accumulation under
// out-of-order actor delivery. This is a reference oracle, not a fast reducer.
function aggregateGradients(step) {
  const acc=new Map();
  for(const batchId of [...step.gradientsByMicrobatch.keys()].sort((a,b)=>a-b)) {
    for(const [id,g] of step.gradientsByMicrobatch.get(batchId)) {
      const old=acc.get(id);
      const next=old ? {
        weights:g.weights.map((row,j)=>row.map((v,i)=>old.weights[j][i]+v)),
        bias:g.bias.map((v,i)=>old.bias[i]+v)
      } : {weights:g.weights.map(row=>row.slice()),bias:g.bias.slice()};
      next.weights.forEach(row=>finite(row,id+' accumulated weight gradient'));
      finite(next.bias,id+' accumulated bias gradient');
      acc.set(id,next);
    }
  }
  return acc;
}
function cloneParams(nodes) {
  const out = {};
  for (const n of nodes) if (n.type === 'dense') {
    out[n.id] = {weights:n.weights.map(row=>row.slice()), bias:n.bias.slice()};
  }
  return out;
}
function compileNodes(specs, output) {
  const records=dataArray(specs,'nodes',MAX_NODES);
  let parameterScalars=0;
  const seen = new Set([RESERVED_INPUT]);
  const nodes = [];
  for (const spec of records) {
    record(spec,['id','type','inputs'],['weights','bias'],'node');
    const {id, type, inputs} = spec;
    assertId(id, 'node id');
    if (seen.has(id)) throw new Error(`duplicate node ${id}`);
    if (!['dense','relu','add','mse'].includes(type)) throw new Error(`unknown operation ${type}`);
    const sources=dataArray(inputs,id+' inputs',2);
    if(sources.length !== (type === 'add' ? 2 : 1)) throw new Error(`${id} invalid input arity`);
    for (const source of sources) {
      if (typeof source !== 'string' || !seen.has(source)) {
        throw new Error(`${id} references missing, forward, or cyclic input ${source}`);
      }
    }
    if (type === 'mse' && spec.id !== output) throw new Error('loss must be final graph output');
    if (type === 'dense') {
      if (!Object.hasOwn(spec,'weights')||!Object.hasOwn(spec,'bias')) throw new TypeError(`${id} missing weights/bias`);
      const rows=dataArray(spec.weights,id+' weight rows',4096);
      // Budget before copying an adversarially large weight matrix.
      const firstRow=vector(rows[0],`${id}.weights[0]`);
      if(parameterScalars+rows.length*(firstRow.length+1)>MAX_PARAMETER_SCALARS)
        throw new RangeError('PARAMETER_BUDGET_EXCEEDED');
      const weights=[firstRow,...rows.slice(1).map((row,i)=>vector(row,`${id}.weights[${i+1}]`))];
      const bias=vector(spec.bias,`${id}.bias`);
      if (bias.length !== weights.length) throw new RangeError(`${id} bias rows mismatch`);
      for (const w of weights) same(w,weights[0],`${id} weight matrix`);
      parameterScalars+=weights.length*weights[0].length+bias.length;
      if(parameterScalars>MAX_PARAMETER_SCALARS) throw new RangeError('PARAMETER_BUDGET_EXCEEDED');
      nodes.push({id,type,inputs:sources,weights,bias});
    } else {
      if(Object.hasOwn(spec,'weights')||Object.hasOwn(spec,'bias')) throw new TypeError(`${id} unexpected parameters`);
      nodes.push({id,type,inputs:sources});
    }
    seen.add(id);
  }
  if (nodes.at(-1).id !== output || nodes.at(-1).type !== 'mse') {
    throw new Error('final output must be an mse loss actor');
  }
  const used = new Set([output]);
  for (const n of [...nodes].reverse()) if (used.has(n.id)) for (const src of n.inputs) used.add(src);
  if (nodes.some(n=>!used.has(n.id))) throw new Error('unused actor not connected to loss');
  return nodes;
}

// Messages are represented by method arguments; callers cannot mutate owned tape/weights.
export class ActorAutodiffReference {
  #nodes; #modelVersion=0; #step=null; #maxInFlight; #maxCommittedSteps; #committedIds=new Set();
  constructor(config) {
    record(config,['nodes','output'],['maxInFlight','maxCommittedSteps'],'config');
    this.#nodes=compileNodes(config.nodes,config.output);
    this.#maxInFlight=integer(Object.hasOwn(config,'maxInFlight')?config.maxInFlight:8,'maxInFlight',1);
    if(this.#maxInFlight>MAX_MICROBATCHES) throw new RangeError('maxInFlight too large');
    this.#maxCommittedSteps=integer(Object.hasOwn(config,'maxCommittedSteps')?config.maxCommittedSteps:MAX_LEDGER_ENTRIES,'maxCommittedSteps',1);
    if(this.#maxCommittedSteps>MAX_LEDGER_ENTRIES) throw new RangeError('maxCommittedSteps too large');
  }
  get version() { return this.#modelVersion; }
  parameters() { return cloneParams(this.#nodes); }
  beginStep(message) {
    record(message,['runId','stepId','expectedMicrobatches'],[],'beginStep');
    const {runId,stepId,expectedMicrobatches}=message;
    assertId(runId,'runId');
    integer(stepId,'stepId');
    integer(expectedMicrobatches,'expectedMicrobatches',1);
    if (expectedMicrobatches > MAX_MICROBATCHES) throw new RangeError('microbatch count too large');
    const edgeCount=this.#nodes.reduce((n,node)=>n+node.inputs.length,0);
    if(expectedMicrobatches*edgeCount*2>MAX_TRACE_RECEIPTS) throw new RangeError('TRACE_BUDGET_EXCEEDED');
    if (this.#committedIds.has(JSON.stringify([runId,stepId]))) throw new Error('ALREADY_COMMITTED');
    if (this.#step) throw new Error('training step already active');
    // Never evict replay tokens: refuse new steps once this in-memory ledger is full.
    if(this.#committedIds.size>=this.#maxCommittedSteps) throw new Error('REPLAY_LEDGER_EXHAUSTED');
    this.#step = {runId,stepId,expectedMicrobatches,version:this.#modelVersion,
      frames:new Map(),completed:new Set(),gradientsByMicrobatch:new Map(),receipts:[],activeActivationScalars:0};
    return {runId,stepId,parameterVersion:this.#modelVersion};
  }
  #match(message) {
    if (!this.#step || !message || message.runId!==this.#step.runId ||
        message.stepId!==this.#step.stepId || this.#step.version!==this.#modelVersion) {
      throw new Error('STALE_STEP_OR_VERSION');
    }
    return this.#step;
  }
  forward(message) {
    record(message,['runId','stepId','microbatchId','input','target'],[],'forward');
    const {runId,stepId,microbatchId,input,target}=message;
    const s=this.#match(message);
    integer(microbatchId,'microbatchId');
    if(microbatchId>=s.expectedMicrobatches) throw new RangeError('MICROBATCH_ID_OUT_OF_RANGE');
    if (s.frames.has(microbatchId) || s.completed.has(microbatchId)) throw new Error('DUPLICATE_FORWARD');
    if (s.frames.size >= this.#maxInFlight) throw new Error('BACKPRESSURE');
    if (s.frames.size+s.completed.size>=s.expectedMicrobatches) throw new Error('TOO_MANY_MICROBATCHES');
    const activations=new Map([[RESERVED_INPUT,vector(input,'input')]]);
    const targetCopy=vector(target,'target');
    const receipts=[];
    let activationScalars=activations.get(RESERVED_INPUT).length+targetCopy.length;
    if(activationScalars>MAX_ACTIVATION_SCALARS) throw new RangeError('ACTIVATION_BUDGET_EXCEEDED');
    for (const n of this.#nodes) {
      const ins=n.inputs.map(id=>activations.get(id));
      let out;
      if (n.type==='dense') {
        same(ins[0],n.weights[0],`${n.id} input`);
        out=n.weights.map((w,j)=>w.reduce((a,x,k)=>a+x*ins[0][k],n.bias[j]));
      } else if(n.type==='relu') out=ins[0].map(x=>Math.max(0,x));
      else if(n.type==='add') { same(ins[0],ins[1],`${n.id} add`); out=ins[0].map((x,i)=>x+ins[1][i]); }
      else { same(ins[0],targetCopy,`${n.id} target`); out=[ins[0].reduce((a,x,i)=>a+0.5*(x-targetCopy[i])**2,0)]; }
      finite(out,`${n.id} forward`);
      activationScalars+=out.length;
      if(activationScalars>MAX_ACTIVATION_SCALARS) throw new RangeError('ACTIVATION_BUDGET_EXCEEDED');
      activations.set(n.id,out);
      for(let slot=0;slot<n.inputs.length;slot++) receipts.push({
        kind:'forward',runId,stepId,node:n.id,from:n.inputs[slot],inputSlot:slot,
        microbatchId,parameterVersion:s.version
      });
    }
    if (s.activeActivationScalars+activationScalars>MAX_INFLIGHT_ACTIVATION_SCALARS)
      throw new Error('ACTIVATION_CREDIT_EXHAUSTED');
    s.frames.set(microbatchId,{activations,target:targetCopy,activationScalars});
    s.activeActivationScalars+=activationScalars;
    s.receipts.push(...receipts);
    return {loss:activations.get(this.#nodes.at(-1).id)[0],runId,stepId,microbatchId,parameterVersion:s.version};
  }
  backward(message) {
    record(message,['runId','stepId','microbatchId'],[],'backward');
    const {runId,stepId,microbatchId}=message;
    const s=this.#match(message);
    integer(microbatchId,'microbatchId');
    if(microbatchId>=s.expectedMicrobatches) throw new RangeError('MICROBATCH_ID_OUT_OF_RANGE');
    if (s.completed.has(microbatchId)) throw new Error('DUPLICATE_BACKWARD');
    const frame=s.frames.get(microbatchId);
    if (!frame) throw new Error('MISSING_FORWARD');
    const gradients=new Map([[this.#nodes.at(-1).id,[1]]]);
    const localParamGrad=new Map();
    const receipts=[];
    for (const n of [...this.#nodes].reverse()) {
      const gout=gradients.get(n.id);
      if (!gout) continue; // Unused branch: no reverse obligation.
      const ins=n.inputs.map(id=>frame.activations.get(id));
      let gins;
      if (n.type==='dense') {
        same(gout,n.bias,`${n.id} upstream gradient`);
        gins=[n.weights[0].map((_,i)=>n.weights.reduce((a,row,j)=>a+row[i]*gout[j],0))];
        localParamGrad.set(n.id,{
          weights:n.weights.map((row,j)=>row.map((_,i)=>gout[j]*ins[0][i])),
          bias:gout.slice()
        });
      } else if (n.type==='relu') {
        same(gout,ins[0],`${n.id} relu gradient`);
        gins=[gout.map((x,i)=>ins[0][i]>0?x:0)];
      } else if (n.type==='add') {
        same(gout,ins[0],`${n.id} add gradient`);
        gins=[gout.slice(),gout.slice()];
      } else {
        if(gout.length!==1) throw new RangeError('loss gradient must be scalar');
        gins=[ins[0].map((x,i)=>gout[0]*(x-frame.target[i]))];
      }
      gins.forEach((grad,i)=>{
        finite(grad,`${n.id} backward`);
        addTo(gradients,n.inputs[i],grad);
        receipts.push({kind:'backward',runId,stepId,from:n.id,to:n.inputs[i],inputSlot:i,microbatchId,parameterVersion:s.version});
      });
    }
    // Check individual VJPs before publishing this microbatch's contribution.
    for(const [id,g] of localParamGrad) {
      g.weights.forEach(row=>finite(row,id+' weight gradient'));
      finite(g.bias,id+' bias gradient');
    }
    const dx=gradients.get(RESERVED_INPUT);
    if (!dx) throw new Error('NO_INPUT_GRADIENT');
    finite(dx,'input gradient');
    s.gradientsByMicrobatch.set(microbatchId,localParamGrad);
    s.frames.delete(microbatchId);
    s.activeActivationScalars-=frame.activationScalars;
    s.completed.add(microbatchId);
    s.receipts.push(...receipts);
    return {inputGradient:dx.slice(),runId,stepId,microbatchId,parameterVersion:s.version};
  }
  gradients() {
    if (!this.#step) throw new Error('NO_ACTIVE_STEP');
    const out={};
    for (const [id,g] of aggregateGradients(this.#step)) out[id]={weights:g.weights.map(row=>row.slice()),bias:g.bias.slice()};
    return out;
  }
  trace() {
    if (!this.#step) throw new Error('NO_ACTIVE_STEP');
    return this.#step.receipts.map(r=>({...r}));
  }
  commit(message) {
    record(message,['runId','stepId','learningRate'],[],'commit');
    const {runId,stepId,learningRate}=message;
    const s=this.#match(message);
    if(typeof learningRate!=='number'||!Number.isFinite(learningRate)||learningRate<=0)
      throw new RangeError('positive finite learningRate required');
    if(s.frames.size || s.completed.size!==s.expectedMicrobatches) throw new Error('INCOMPLETE_STEP');
    const divisor=s.expectedMicrobatches;
    const allGradients=aggregateGradients(s);
    // Validate all updated parameters BEFORE mutating any owner.
    const updates=new Map();
    for(const n of this.#nodes) if(n.type==='dense') {
      const g=allGradients.get(n.id);
      if (!g) throw new Error(`MISSING_GRADIENT ${n.id}`);
      const weights=n.weights.map((row,j)=>row.map((x,i)=>x-learningRate*g.weights[j][i]/divisor));
      const bias=n.bias.map((x,i)=>x-learningRate*g.bias[i]/divisor);
      weights.forEach(row=>finite(row,`${n.id} weights`)); finite(bias,`${n.id} bias`);
      updates.set(n.id,{weights,bias});
    }
    for(const n of this.#nodes) if(updates.has(n.id)) Object.assign(n,updates.get(n.id));
    this.#committedIds.add(JSON.stringify([runId,stepId]));
    this.#modelVersion++;
    this.#step=null;
    return {committedVersion:this.#modelVersion,completedMicrobatches:divisor};
  }
  cancel(message) {
    record(message,['runId','stepId'],[],'cancel');
    this.#match(message);
    this.#step=null;
  }
}
