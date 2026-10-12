// Reference-only v1 tensor semantic guard. Call after independent JSON Schema validation.
const widths = Object.freeze({f32:4,f64:8,i32:4,i64:8,u8:1});
const MAX=BigInt(Number.MAX_SAFE_INTEGER);
function safe(n,name){if(!Number.isSafeInteger(n)||n<0)throw new RangeError(name+" must be nonnegative safe integer");return BigInt(n);}
export function validateTensor(t,{acrossActor=false,allowGpu=false}={}){
 if(!t||typeof t!=="object"||Array.isArray(t))throw new TypeError("tensor object required");
 const keys=["dtype","shape","strides","byte_length","layout","device","ownership","lifetime"];
 const own=Object.keys(t),expected=new Set(keys);
 if(own.length!==keys.length||own.some(k=>!expected.has(k))||keys.some(k=>!Object.hasOwn(t,k)))throw new TypeError("unknown or missing tensor fields");
 const width=Object.hasOwn(widths,t.dtype)?widths[t.dtype]:null;if(!width)throw new TypeError("unsupported dtype");
 if(!Array.isArray(t.shape)||!Array.isArray(t.strides)||t.shape.length<1||t.shape.length>8||t.shape.length!==t.strides.length)throw new RangeError("rank mismatch");
 if(!["row_major","column_major","strided"].includes(t.layout))throw new TypeError("layout");
 if(!["cpu","gpu"].includes(t.device))throw new TypeError("device");
 if(!["moved","frozen_shared","borrowed_local"].includes(t.ownership))throw new TypeError("ownership");
 if(!["actor_turn","actor_incarnation","explicit_lease"].includes(t.lifetime))throw new TypeError("lifetime");
 if(t.device==="gpu"&&!allowGpu)throw new Error("UNSUPPORTED_BACKEND GPU");
 if(acrossActor&&t.ownership==="borrowed_local")throw new Error("borrowed alias crosses actor boundary");
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
 if(!profile||typeof profile!=="object"||!Object.hasOwn(profile,feature)||profile[feature]!=="verified")throw new Error("UNSUPPORTED_BACKEND "+feature);
}
