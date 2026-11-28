# Java independent Atto core namespaces

The Java parser supports **flat** `use namespace slice;` headers, not
dotted namespace declarations. The native .atto source implementations
`core/namespace/{slice,iter,cmp}.atto` use this supported grammar with
public root-scope scalar functions and without a module wrapper.

`AttoCoreNamespaceGuestTest` compiles and executes the exact source
functions through the Java guest compiler with a source-local appended main.
This is **not** the same as packaged `atto.core/*` namespace imports:
those remain closed, separately registered module-shaped source in
`src/main/resources/dev/oreslang/core/` until the Java linker supports
verified cross-file namespace import visibility and identity.

These functions are checked scalar range/cursor/ordering policies only.
Borrow lifetime, shared Array<T>, Slice<T>, Iterator<T> and async actor
semantics are not being claimed for this source profile.
