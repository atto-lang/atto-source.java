package dev.oreslang.types;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-component candidate for transactional deep copying. Uses the finite
 * descriptor graph, but is NOT an ownership witness or guest evaluator hookup.
 * Only these closed, domain-locked model objects can be read or allocated.
 * No constructors, reflection, user callbacks or native resources are invoked.
 */
public final class GraphCopyTransaction {
    private GraphCopyTransaction() { }

    public static final class Domain {
        public synchronized Cell instance(String name, int fields) {
            return new Cell(this, Objects.requireNonNull(name), false, fields);
        }
        public synchronized Cell sequence(String name, int size) {
            return new Cell(this, Objects.requireNonNull(name), true, size);
        }
    }

    public static final class Cell {
        private final Domain domain;
        private final String name;
        private final boolean sequence;
        private final Object[] slots;

        private Cell(Domain domain, String name, boolean sequence, int size) {
            if (size < 0 || size > 65536) throw refused("slot capacity");
            this.domain = domain;
            this.name = name;
            this.sequence = sequence;
            this.slots = new Object[size];
        }

        public void set(int index, Object value) {
            synchronized (domain) { slots[index] = value; }
        }
        public Object get(int index) {
            synchronized (domain) { return slots[index]; }
        }
        public int size() { return slots.length; }
    }

    /** Null is the explicit absent payload, not an uninitialized field. */
    public record Maybe(boolean present, Object value) {
        public Maybe {
            if (present != (value != null)) throw refused("invalid optional payload");
        }
    }

    public record Limits(int visits, int cells, int slots, int stringUnits) {
        public Limits {
            if (visits < 1 || visits > 262144 || cells < 0 || cells > 65536
                    || slots < 0 || slots > 262144 || stringUnits < 0
                    || stringUnits > 16777216) throw refused("invalid copy limits");
        }
    }

    /**
     * All mutations of model cells use the same domain monitor. Source graph
     * remains stable for the whole transaction; no destination escapes until
     * the return. On refusal, private heap shells become unreachable. This is
     * host GC rollback, NOT native arena retirement or guest isolation proof.
     */
    public static Object copy(DerivedGraphCopyPlan.Graph plan, Object source,
                              Domain domain, Limits limits, AtomicBoolean cancelled) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(domain);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(cancelled);
        synchronized (domain) {
            Transaction tx = new Transaction(plan, domain, limits, cancelled);
            Object root = tx.edge(plan.root(), source, 0);
            while (!tx.pending.isEmpty()) {
                tx.checkCancellation();
                Pending item = tx.pending.removeFirst();
                var shape = tx.node(item.descriptor());
                for (int i = 0; i < item.source().slots.length; i++) {
                    int child = shape instanceof DerivedGraphCopyPlan.Instance instance
                            ? instance.fields().get(i).descriptor()
                            : ((DerivedGraphCopyPlan.Sequence) shape).element();
                    tx.pendingEdges--;
                    item.target().slots[i] = tx.edge(child, item.source().slots[i], 0);
                }
            }
            tx.checkCancellation();
            return root;
        }
    }

    private record Memo(int descriptor, Cell target) { }
    private record Pending(int descriptor, Cell source, Cell target) { }

    private static final class Transaction {
        final DerivedGraphCopyPlan.Graph plan;
        final Domain domain;
        final Limits limits;
        final AtomicBoolean cancelled;
        final IdentityHashMap<Cell, Memo> memo = new IdentityHashMap<>();
        final ArrayDeque<Pending> pending = new ArrayDeque<>();
        int visits, cells, slots, stringUnits, pendingEdges;

        Transaction(DerivedGraphCopyPlan.Graph plan, Domain domain, Limits limits,
                    AtomicBoolean cancelled) {
            this.plan = plan;
            this.domain = domain;
            this.limits = limits;
            this.cancelled = cancelled;
            if (plan.nodes().isEmpty() || plan.nodes().size() > 16384) throw refused("descriptor capacity");
            validatePlan();
        }

        // Public Graph records are data, not proof. Validate the entire closed
        // descriptor table, including element types hidden by empty containers.
        void validatePlan() {
            node(plan.root());
            int edges = 0;
            for (var shape : plan.nodes()) {
                checkCancellation();
                if (shape instanceof DerivedGraphCopyPlan.Scalar scalar) {
                    if (!DerivedGraphCopyPlan.supportsScalar(scalar.typeName())) {
                        throw refused("unsupported scalar descriptor");
                    }
                } else if (shape instanceof DerivedGraphCopyPlan.OptionalValue optional) {
                    node(optional.element());
                    edges++;
                } else if (shape instanceof DerivedGraphCopyPlan.Sequence sequence) {
                    if (!"Array".equals(sequence.typeName()) && !"List".equals(sequence.typeName())) {
                        throw refused("unsupported sequence descriptor");
                    }
                    node(sequence.element());
                    edges++;
                } else if (shape instanceof DerivedGraphCopyPlan.Instance instance) {
                    if (instance.className() == null || instance.fields().size() > 128) {
                        throw refused("invalid class descriptor");
                    }
                    var names = new java.util.HashSet<String>();
                    for (var field : instance.fields()) {
                        checkCancellation();
                        if (field.name() == null || !names.add(field.name())) throw refused("duplicate field");
                        node(field.descriptor());
                        edges++;
                    }
                } else throw refused("unknown descriptor");
                if (edges > 32768) throw refused("descriptor edge capacity");
            }
            // Inline Option-only recursion has no identity shell to close it.
            // Color each optional chain once; class/sequence nodes terminate it.
            byte[] colors = new byte[plan.nodes().size()];
            for (int i = 0; i < colors.length; i++) {
                checkCancellation();
                int cursor = i;
                while (node(cursor) instanceof DerivedGraphCopyPlan.OptionalValue optional
                        && colors[cursor] == 0) {
                    checkCancellation();
                    colors[cursor] = 1;
                    cursor = optional.element();
                }
                if (colors[cursor] == 1) throw refused("inline optional descriptor cycle");
                cursor = i;
                while (node(cursor) instanceof DerivedGraphCopyPlan.OptionalValue optional
                        && colors[cursor] == 1) {
                    checkCancellation();
                    colors[cursor] = 2;
                    cursor = optional.element();
                }
            }
        }

        void checkCancellation() {
            if (cancelled.get()) throw refused("cancelled");
        }

        DerivedGraphCopyPlan.Node node(int id) {
            if (id < 0 || id >= plan.nodes().size()) throw refused("invalid descriptor ID");
            return plan.nodes().get(id);
        }

        Object edge(int id, Object value, int inlineDepth) {
            checkCancellation();
            if (++visits > limits.visits() || inlineDepth > 64) throw refused("visit/depth budget");
            var shape = node(id);
            if (shape instanceof DerivedGraphCopyPlan.Scalar scalar) {
                if (!validScalar(scalar.typeName(), value)) throw refused("scalar mismatch");
                if (value instanceof String text) {
                    if (text.length() > limits.stringUnits() - stringUnits) throw refused("string budget");
                    stringUnits += text.length();
                }
                return value; // exact immutable host scalar only
            }
            if (shape instanceof DerivedGraphCopyPlan.OptionalValue optional) {
                if (!(value instanceof Maybe maybe)) throw refused("optional mismatch");
                node(optional.element()); // even absent edges require a valid descriptor ID
                return maybe.present()
                        ? new Maybe(true, edge(optional.element(), maybe.value(), inlineDepth + 1))
                        : new Maybe(false, null);
            }
            if (!(value instanceof Cell source) || source.domain != domain) throw refused("foreign or opaque cell");
            if (shape instanceof DerivedGraphCopyPlan.Instance instance) {
                if (source.sequence || !source.name.equals(instance.className())
                        || source.slots.length != instance.fields().size()
                        || instance.fields().size() > 128) throw refused("class layout mismatch");
            } else if (shape instanceof DerivedGraphCopyPlan.Sequence sequence) {
                if (!source.sequence || !source.name.equals(sequence.typeName())) throw refused("sequence mismatch");
                node(sequence.element());
            } else {
                throw refused("unknown shape");
            }
            Memo old = memo.get(source);
            if (old != null) {
                if (old.descriptor() != id) throw refused("conflicting alias descriptor");
                return old.target();
            }
            if (cells >= limits.cells() || source.slots.length > limits.slots() - slots
                    || source.slots.length > limits.visits() - visits - pendingEdges) {
                throw refused("allocation/work budget");
            }
            cells++;
            slots += source.slots.length;
            pendingEdges += source.slots.length;
            Cell target = new Cell(domain, source.name, source.sequence, source.slots.length);
            memo.put(source, new Memo(id, target)); // before following any outgoing edge
            pending.addLast(new Pending(id, source, target));
            return target;
        }

    }

    private static boolean validScalar(String type, Object value) {
        return switch (type) {
            case "string", "String" -> value instanceof String;
            case "bool", "Bool" -> value instanceof Boolean;
            case "i8" -> value instanceof Byte;
            case "i16" -> value instanceof Short;
            case "i32" -> value instanceof Integer;
            case "int", "i64" -> value instanceof Long;
            case "f32" -> value instanceof Float;
            case "float", "f64" -> value instanceof Double;
            default -> false; // unsigned representation still requires a verified ABI
        };
    }

    private static IllegalArgumentException refused(String detail) {
        return new IllegalArgumentException("E_COPY_GRAPH_REFUSED: " + detail);
    }
}
