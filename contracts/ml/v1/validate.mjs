// Reference-only v1 tensor semantic guard. Call after independent JSON Schema validation.
const widths = Object.freeze({f32:4,f64:8,i32:4,i64:8,u8:1});
const MAX=BigInt(Number.MAX_SAFE_INTEGER);
function safe(n,name){if(!Number.isSafeInteger(n)||n<0)throw new RangeError(name+" must be nonnegative safe integer");return BigInt(n);}
function dataRecord(value,keys,name){
 if(!value||typeof value!=="object"||Array.isArray(value))throw new TypeError(name+" object required");
 const proto=Object.getPrototypeOf(value);
 if(proto!==Object.prototype&&proto!==null)throw new TypeError(name+" must be plain JSON object");
 const actual=Reflect.ownKeys(value),allowed=new Set(keys);
 if(actual.length!==keys.length||actual.some(k=>typeof k!=="string"||!allowed.has(k))||
    keys.some(k=>!Object.hasOwn(value,k)))throw new TypeError("unknown or missing "+name+" fields");
 for(const key of keys){
   const descriptor=Object.getOwnPropertyDescriptor(value,key);
   if(!descriptor||!("value" in descriptor)||!descriptor.enumerable)
     throw new TypeError(name+" accessor or hidden field forbidden");
 }
}
function dataVector(value,name){
 if(!Array.isArray(value)||Object.getPrototypeOf(value)!==Array.prototype)
   throw new TypeError(name+" must be plain dense array");
 for(let i=0;i<value.length;i++){
   const d=Object.getOwnPropertyDescriptor(value,String(i));
   if(!d||!("value" in d)||!d.enumerable)
     throw new TypeError(name+" sparse or accessor element forbidden");
 }
 if(Reflect.ownKeys(value).length!==value.length+1)
   throw new TypeError(name+" extra own properties forbidden");
}
export function validateTensor(t,{acrossActor=true,allowGpu=false}={}){
 const keys=["dtype","shape","strides","byte_length","layout","device","ownership","lifetime"];
 dataRecord(t,keys,"tensor");
 const width=Object.hasOwn(widths,t.dtype)?widths[t.dtype]:null;if(!width)throw new TypeError("unsupported dtype");
 dataVector(t.shape,"shape");dataVector(t.strides,"strides");
 if(t.shape.length<1||t.shape.length>8||t.shape.length!==t.strides.length)throw new RangeError("rank mismatch");
 if(!["row_major","column_major","strided"].includes(t.layout))throw new TypeError("layout");
 if(!["cpu","gpu"].includes(t.device))throw new TypeError("device");
 if(!["moved","frozen_shared","borrowed_local"].includes(t.ownership))throw new TypeError("ownership");
 if(!["actor_turn","actor_incarnation","explicit_lease"].includes(t.lifetime))throw new TypeError("lifetime");
 if(t.device==="gpu"&&!allowGpu)throw new Error("UNSUPPORTED_BACKEND GPU");
 if(acrossActor&&t.ownership==="borrowed_local")throw new Error("borrowed alias crosses actor boundary");
 if(acrossActor&&t.lifetime==="actor_turn")throw new Error("actor turn lease crosses actor boundary");
 const shape=t.shape.map((n,i)=>safe(n,"shape["+i+"]")),stride=t.strides.map((n,i)=>safe(n,"stride["+i+"]")),bytes=safe(t.byte_length,"byte_length");
 let elements=1n;for(const d of shape){elements*=d;if(elements>MAX)throw new RangeError("elements overflow");}
 const required=elements===0n?0n:(shape.reduce((a,d,i)=>a+(d-1n)*stride[i],0n)+1n)*BigInt(width);
 if(required>MAX||required>bytes)throw new RangeError("byte bounds");
 if(t.layout==="strided"&&t.ownership!=="frozen_shared"&&elements>0n){
   // A mutable strided view must not alias itself. For the initial portable
   // subset, bound an exhaustive offset proof; larger mutable strided views
   // fail closed until a symbolic injectivity checker is implemented.
   if(elements>4096n)throw new Error("UNSUPPORTED_LAYOUT mutable strided alias proof");
   const seen=new Set();
   const visit=(axis,offset)=>{
     if(axis===shape.length){
       const key=offset.toString();
       if(seen.has(key))throw new Error("INVALID_TENSOR overlapping mutable strides");
       seen.add(key);
       return;
     }
     for(let i=0n;i<shape[axis];i++)visit(axis+1,offset+i*stride[axis]);
   };
   visit(0,0n);
 }
 if(t.layout!=="strided"){
   let expect=1n, order=[...shape.keys()];if(t.layout==="row_major")order.reverse();
   for(const i of order){if(stride[i]!==expect)throw new RangeError("noncontiguous declared layout");expect*=shape[i];if(expect>MAX)throw new RangeError("layout overflow");}
 }
 return {requiredBytes:Number(required),elements:Number(elements)};
}
export function requireVerifiedBackend(profile,feature){
 if(typeof feature!=="string"||!/^[_a-z][_a-z0-9]{0,63}$/.test(feature)||
    Object.hasOwn(Object.prototype,feature)||feature==="prototype")
   throw new Error("UNSUPPORTED_BACKEND invalid feature");
 if(!profile||typeof profile!=="object"||Array.isArray(profile))
   throw new Error("UNSUPPORTED_BACKEND "+feature);
 const proto=Object.getPrototypeOf(profile);
 const d=Object.getOwnPropertyDescriptor(profile,feature);
 // This only checks a declarative evidence tag. The trusted host must
 // independently authenticate the policy and origin of the profile.
 if((proto!==Object.prototype&&proto!==null)||!d||!("value" in d)||d.value!=="verified")
   throw new Error("UNSUPPORTED_BACKEND "+feature);
}
