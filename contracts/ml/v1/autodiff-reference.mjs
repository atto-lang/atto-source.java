// A deterministic CPU f64 reference model for differentiable actor DATA edges.
// This is NOT Oreslang source lowering, actual actor transport, or a GPU runtime.
// It makes correlation, gradient fan-in, parameter-version, and commit rules testable.
const VALID_ID = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const RESERVED_INPUT = '$input';

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
function cloneParams(nodes) {
  const out = {};
  for (const n of nodes) if (n.type === 'dense') {
    out[n.id] = {weights:n.weights.map(row=>row.slice()), bias:n.bias.slice()};
  }
  return out;
}
function compileNodes(specs, output) {
  if (!Array.isArray(specs) || !specs.length) throw new TypeError('nodes required');
  const seen = new Set([RESERVED_INPUT]);
  const nodes = [];
  for (const spec of specs) {
    if (!spec || Object.getPrototypeOf(spec) !== Object.prototype) throw new TypeError('plain node required');
    const {id, type, inputs} = spec;
    assertId(id, 'node id');
    if (seen.has(id)) throw new Error(`duplicate node ${id}`);
    if (!['dense','relu','add','mse'].includes(type)) throw new Error(`unknown operation ${type}`);
    if (!Array.isArray(inputs) || inputs.length !== (type === 'add' ? 2 : 1)) {
      throw new Error(`${id} invalid input arity`);
    }
    for (const source of inputs) {
      if (typeof source !== 'string' || !seen.has(source)) {
        throw new Error(`${id} references missing, forward, or cyclic input ${source}`);
      }
    }
    if (type === 'mse' && spec.id !== output) throw new Error('loss must be final graph output');
    if (type === 'dense') {
      if (!Array.isArray(spec.weights) || !spec.weights.length || spec.weights.length > 4096) {
        throw new TypeError(`${id} weights rows required`);
      }
      const weights = spec.weights.map((row,i)=>vector(row,`${id}.weights[${i}]`));
      const bias = vector(spec.bias,`${id}.bias`);
      if (bias.length !== weights.length) throw new RangeError(`${id} bias rows mismatch`);
      for (const w of weights) same(w,weights[0],`${id} weight matrix`);
      nodes.push({id,type,inputs:inputs.slice(),weights,bias});
    } else {
      nodes.push({id,type,inputs:inputs.slice()});
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
  #nodes; #modelVersion=0; #step=null; #maxInFlight; #committedIds=new Set();
  constructor({nodes, output, maxInFlight=8}) {
    this.#nodes=compileNodes(nodes, output);
    this.#maxInFlight=integer(maxInFlight,'maxInFlight',1);
  }
  get version() { return this.#modelVersion; }
  parameters() { return cloneParams(this.#nodes); }
  beginStep({runId,stepId,expectedMicrobatches}) {
    assertId(runId,'runId');
    integer(stepId,'stepId');
    integer(expectedMicrobatches,'expectedMicrobatches',1);
    if (expectedMicrobatches > 4096) throw new RangeError('microbatch count too large');
    if (this.#committedIds.has(JSON.stringify([runId,stepId]))) throw new Error('ALREADY_COMMITTED');
    if (this.#step) throw new Error('training step already active');
    this.#step = {runId,stepId,expectedMicrobatches,version:this.#modelVersion,
      frames:new Map(),completed:new Set(),gradients:new Map(),receipts:[]};
    return {runId,stepId,parameterVersion:this.#modelVersion};
  }
  #match(message) {
    if (!this.#step || !message || message.runId!==this.#step.runId ||
        message.stepId!==this.#step.stepId || this.#step.version!==this.#modelVersion) {
      throw new Error('STALE_STEP_OR_VERSION');
    }
    return this.#step;
  }
  forward({runId,stepId,microbatchId,input,target}) {
    const s=this.#match({runId,stepId});
    integer(microbatchId,'microbatchId');
    if (s.frames.has(microbatchId) || s.completed.has(microbatchId)) throw new Error('DUPLICATE_FORWARD');
    if (s.frames.size >= this.#maxInFlight) throw new Error('BACKPRESSURE');
    if (s.frames.size+s.completed.size>=s.expectedMicrobatches) throw new Error('TOO_MANY_MICROBATCHES');
    const activations=new Map([[RESERVED_INPUT,vector(input,'input')]]);
    const targetCopy=vector(target,'target');
    const receipts=[];
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
      activations.set(n.id,out);
      receipts.push({kind:'forward',node:n.id,microbatchId,parameterVersion:s.version});
    }
    s.frames.set(microbatchId,{activations,target:targetCopy});
    s.receipts.push(...receipts);
    return {loss:activations.get(this.#nodes.at(-1).id)[0],parameterVersion:s.version};
  }
  backward({runId,stepId,microbatchId}) {
    const s=this.#match({runId,stepId});
    integer(microbatchId,'microbatchId');
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
        receipts.push({kind:'backward',from:n.id,to:n.inputs[i],microbatchId,parameterVersion:s.version});
      });
    }
    // Compute all merged gradients without modifying committed state on failure.
    const merged=new Map(s.gradients);
    for (const [id,g] of localParamGrad) {
      const old=merged.get(id);
      const next=old ? {
        weights:g.weights.map((row,j)=>row.map((v,i)=>old.weights[j][i]+v)),
        bias:g.bias.map((v,i)=>old.bias[i]+v)
      } : g;
      next.weights.forEach(row=>finite(row,`${id} accumulated weight gradient`));
      finite(next.bias,`${id} accumulated bias gradient`);
      merged.set(id,next);
    }
    const dx=gradients.get(RESERVED_INPUT);
    if (!dx) throw new Error('NO_INPUT_GRADIENT');
    finite(dx,'input gradient');
    s.gradients=merged;
    s.frames.delete(microbatchId);
    s.completed.add(microbatchId);
    s.receipts.push(...receipts);
    return {inputGradient:dx.slice(),parameterVersion:s.version};
  }
  gradients() {
    if (!this.#step) throw new Error('NO_ACTIVE_STEP');
    const out={};
    for (const [id,g] of this.#step.gradients) out[id]={weights:g.weights.map(row=>row.slice()),bias:g.bias.slice()};
    return out;
  }
  trace() {
    if (!this.#step) throw new Error('NO_ACTIVE_STEP');
    return this.#step.receipts.map(r=>({...r}));
  }
  commit({runId,stepId,learningRate}) {
    const s=this.#match({runId,stepId});
    if(typeof learningRate!=='number'||!Number.isFinite(learningRate)||learningRate<=0)
      throw new RangeError('positive finite learningRate required');
    if(s.frames.size || s.completed.size!==s.expectedMicrobatches) throw new Error('INCOMPLETE_STEP');
    const divisor=s.expectedMicrobatches;
    // Validate all updated parameters BEFORE mutating any owner.
    const updates=new Map();
    for(const n of this.#nodes) if(n.type==='dense') {
      const g=s.gradients.get(n.id);
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
  cancel({runId,stepId}) { this.#match({runId,stepId}); this.#step=null; }
}
