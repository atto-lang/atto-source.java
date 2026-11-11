# Multi-file AttoLang namespace conformance v1

**Source committed; Java JUnit/guest execution NOT VERIFIED.**

The canonical **closed source set** for the positive case is the three
`shop/*.atto` files: `contracts.atto`, `cart.atto`, `tick.atto`.
Compile all three files in one `IncrementalCompiler.compile(Map)`
invocation. Do not individually type-check the `conforms` header and
claim a proof. Running only a subset must reject, not silently pass.

`namespace shop conforms ShopApi;` is declared exactly once in the
source set, but `namespace shop;` may be repeated in sibling files.
The contract `define contract ShopApi for namespace as ... end` is
a **file-root declaration** with a distinct target from module contracts.
The set of files is determined by the package/build input, **not** by
automatically searching a physical folder or the operating system.
Every target namespace declaration in the set participates in collision
checks and the conservative ABI dependency closure.

Positive: public non-generic `Cart` and non-generic `fnc tick(int):int`
from separate files satisfy the namespace. `tick.atto` also declares
`pub fnc main(): int { return 42; }` so a future admitted Java guest
runner can independently report a real guest return receipt. Merely
parsing/type-checking or discovering the function is NOT guest execution. Negative cases replace
`shop/cart.atto` with the fixture in `reject_private_actor` (private
actor) or `reject_wrong_namespace` (explicit namespace mismatch).
Additional negative cases (missing files, duplicate actor/slot/claim,
module-vs-namespace target confusion, ABI invalidation, and direct
single-file checker refusal) are in `NamespaceContractMultiFileTest`.

**Safety boundaries:** namespace identity is scoped to the one build
input/package; a namespace name string alone never imports another
package or authorizes actor state access. This stage supports only public
nominal actor and synchronous public non-generic fnc signatures.
Cross-file external `define mtd` bodies, namespace imported contracts,
generic/inherited contracts, structural/checked effects, and executable
actor binding are not supported. The checker rejects unsupported cases.
Keep module-level `conforms` and actor-specific `implements` separate.

Required parity evidence: exact PR head/root-tree, fixture blobs, admitted
runner with >0 executed steps, compiler backend and exit diagnostics,
guest program return/exit receipt, and independent review. `runner_id=0`,
`steps=[]` is a CI admission failure, not a test result.
