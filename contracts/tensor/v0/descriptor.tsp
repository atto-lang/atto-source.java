// Independently authored peer contract; compare generated witness against JSON Schema.
// TypeSpec compiler integration is a separate admission gate, not claimed here.
namespace Oreslang.Tensor.V0;
enum DType { f32, f64, i32, i64, bool }
enum Ownership { owned, borrowed_read, borrowed_mut, frozen_shared, moved }
enum Device { cpu, gpu }
model TensorDescriptor {
  dtype: DType;
  shape: int32[];
  strides: int64[];
  offset: int64;
  storageScalars: int64;
  ownership: Ownership;
  device: Device;
}
