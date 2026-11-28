// Pure reference validation for shape/stride bounds. Not a compiler or allocator.
export function validateTensorDescriptor(d) {
  if(!d||Object.getPrototypeOf(d)!==Object.prototype) throw new TypeError('descriptor record required');
  const keys=['dtype','shape','strides','offset','storageScalars','ownership','device'];
  if(Reflect.ownKeys(d).length!==keys.length||keys.some(k=>!Object.hasOwn(d,k))) throw new TypeError('descriptor keys');
  for(const k of keys){const p=Object.getOwnPropertyDescriptor(d,k);if(!p||!('value' in p)||!p.enumerable)throw new TypeError('descriptor accessor');}
  if(!['f32','f64','i32','i64','bool'].includes(d.dtype))throw new TypeError('dtype');
  if(!['owned','borrowed_read','borrowed_mut','frozen_shared','moved'].includes(d.ownership))throw new TypeError('ownership');
  if(!['cpu','gpu'].includes(d.device))throw new TypeError('device');
  for(const k of ['offset','storageScalars'])if(!Number.isSafeInteger(d[k])||d[k]<0)throw new RangeError(k);
  function array(v,name){if(!Array.isArray(v)||Object.getPrototypeOf(v)!==Array.prototype||v.length>16||Reflect.ownKeys(v).length!==v.length+1)throw new TypeError(name);return v.map((_,i)=>{const p=Object.getOwnPropertyDescriptor(v,String(i));if(!p||!('value'in p)||!p.enumerable)throw new TypeError(name);return p.value;});}
  const shape=array(d.shape,'shape'),strides=array(d.strides,'strides');
  if(shape.length!==strides.length)throw new RangeError('rank mismatch');
  for(const x of shape)if(!Number.isInteger(x)||x<0||x>2147483647)throw new RangeError('shape dimension');
  for(const x of strides)if(!Number.isSafeInteger(x))throw new RangeError('stride');
  let elements=1n;
  for(const x of shape)elements*=BigInt(x);
  if(elements>BigInt(Number.MAX_SAFE_INTEGER))throw new RangeError('element count overflow');
  if(elements>0n){
    let low=BigInt(d.offset),high=low;
    for(let i=0;i<shape.length;i++){
      const span=BigInt(shape[i]-1)*BigInt(strides[i]);
      if(span<0n)low+=span;else high+=span;
    }
    if(low<0n||high>=BigInt(d.storageScalars))throw new RangeError('view outside storage');
  } else if(d.offset>d.storageScalars)throw new RangeError('empty view offset');
  if(d.ownership==='moved'&&elements>0n)throw new TypeError('moved handle cannot expose live elements');
  return Object.freeze({elements:Number(elements),rank:shape.length,minOffset:elements?Number(BigInt(d.offset)+shape.reduce((n,x,i)=>{const span=BigInt(x-1)*BigInt(strides[i]);return n+(span<0n?span:0n)},0n)):null});
}
