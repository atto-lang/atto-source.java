------------------------- MODULE VisibilityExports -------------------------
EXTENDS Naturals, FiniteSets, TLC

\* Finite-state abstraction of the compiler/runtime cross-file export rule.
\* Six declarations enumerate public and private members in three containers.
\* The synthetic file root exports public members but not the root namespace.

CONSTANTS Root, PrivateModule, PublicModule,
          RootPublic, RootPrivate,
          HiddenPublic, HiddenPrivate,
          NamedPublic, NamedPrivate

Declarations == {RootPublic, RootPrivate,
                 HiddenPublic, HiddenPrivate,
                 NamedPublic, NamedPrivate}
Owners == [RootPublic   |-> Root,
           RootPrivate  |-> Root,
           HiddenPublic |-> PrivateModule,
           HiddenPrivate|-> PrivateModule,
           NamedPublic  |-> PublicModule,
           NamedPrivate |-> PublicModule]
PublicMembers == {RootPublic, HiddenPublic, NamedPublic}
PublicModules == {PublicModule}

\* This is the independent specification mirrored by Java's VisibilityPolicy.
MemberExportable(d) ==
    /\ d \in PublicMembers
    /\ (Owners[d] = Root \/ Owners[d] \in PublicModules)

ModuleExportable(m) ==
    /\ m # Root
    /\ m \in PublicModules

VARIABLES scanned, exports
vars == <<scanned, exports>>

Init ==
    /\ scanned = {}
    /\ exports = {}

Scan(d) ==
    /\ d \in Declarations \ scanned
    /\ scanned' = scanned \cup {d}
    /\ exports' = IF MemberExportable(d)
                  THEN exports \cup {d}
                  ELSE exports

Next == \E d \in Declarations: Scan(d)

TypeOK ==
    /\ scanned \subseteq Declarations
    /\ exports \subseteq Declarations

\* Primary safety property: no action exposes an unmarked member, and no
\* member of a private named module leaks even if explicitly pub.
NoPrivateExposure ==
    \A d \in exports: MemberExportable(d)

\* Once all declarations have been examined, export is exact, not just safe.
CompleteAndExact ==
    scanned = Declarations =>
        exports = {d \in Declarations: MemberExportable(d)}

SyntheticRootIsNotModule == ~ModuleExportable(Root)
PrivateModuleIsNotExported == ~ModuleExportable(PrivateModule)
PublicModuleIsExported == ModuleExportable(PublicModule)

Spec == Init /\ [][Next]_vars
=============================================================================
