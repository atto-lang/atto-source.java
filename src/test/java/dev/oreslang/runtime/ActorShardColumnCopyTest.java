package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class ActorShardColumnCopyTest {
    @Test
    void hostOutsideActorCannotUseAllocationFreeCopy() {
        assertThrows(SecurityException.class,
                () -> new ActorShardColumns(ActorShardColumns.Layout.SOA, 3, 2, 1));
    }
}
