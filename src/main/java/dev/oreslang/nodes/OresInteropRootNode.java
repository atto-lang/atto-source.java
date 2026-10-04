package dev.oreslang.nodes;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.runtime.OresVoid;

/**
 * Adapts internal evaluator values to values that are legal at the polyglot
 * guest/host boundary. Internal Java null is implementation-only and means
 * Oreslang void; it is exported as a non-null OresVoid sentinel.
 */
public final class OresInteropRootNode extends RootNode {
    @Child private DirectCallNode delegate;

    public OresInteropRootNode(OresLanguage language, RootCallTarget delegateTarget) {
        super(language);
        this.delegate = DirectCallNode.create(delegateTarget);
    }

    @Override
    public String getName() {
        return "ores-polyglot-eval";
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = delegate.call(frame.getArguments());
        return value == null ? OresVoid.INSTANCE : value;
    }
}
