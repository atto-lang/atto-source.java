package dev.oreslang.runtime;

/** Standalone JDK 21 host preflight smoke; no Maven, FFI or guest compiler. */
public final class F32ActorHostPreflightV1Smoke {
    private F32ActorHostPreflightV1Smoke() { }

    private static void check(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        ExecutionContext actor = ExecutionContext.actor(7, 11, 31, 9, 5, 0, 0);
        ExecutionContext other = ExecutionContext.actor(7, 12, 31, 9, 5, 0, 0);
        ExecutionContext forged = ExecutionContext.actor(7, 11, 31, 9, 5, 0, 0);
        F32ActorHostPreflightV1 gate = new F32ActorHostPreflightV1(actor);
        var expected = F32ActorHostPreflightV1.Result.HOST_PREFLIGHT_ONLY;
        check(gate.inspect(actor, 2, 3, 3, 2, 4096) == expected, "golden preflight");
        check(gate.inspect(actor, 32, 32, 32, 32, 4096) == expected, "max preflight");
        check(gate.inspect(actor, 32, 32, 32, 32, 4095)
                == F32ActorHostPreflightV1.Result.QUOTA, "quota");
        check(gate.inspect(actor, 0, 3, 3, 2, 4096)
                == F32ActorHostPreflightV1.Result.BAD_SHAPE, "zero rows");
        check(gate.inspect(actor, 33, 3, 3, 2, 4096)
                == F32ActorHostPreflightV1.Result.BAD_SHAPE, "unbounded rows");
        check(gate.inspect(actor, 2, 3, 2, 2, 4096)
                == F32ActorHostPreflightV1.Result.BAD_SHAPE, "inner mismatch");
        check(gate.inspect(actor, 2, 3, 3, 2, 4097)
                == F32ActorHostPreflightV1.Result.BAD_SHAPE, "overwide budget");
        check(gate.inspect(actor, 2, 3, 3, 2, 15)
                == F32ActorHostPreflightV1.Result.QUOTA, "insufficient budget");
        check(gate.inspect(null, 2, 3, 3, 2, 4096)
                == F32ActorHostPreflightV1.Result.INVALID, "null context");
        check(gate.inspect(other, 2, 3, 3, 2, 4096)
                == F32ActorHostPreflightV1.Result.WRONG_ACTOR, "foreign execution");
        check(gate.inspect(forged, 2, 3, 3, 2, 4096)
                == F32ActorHostPreflightV1.Result.WRONG_ACTOR, "equal numbers must not be authority");
        final F32ActorHostPreflightV1.Result[] foreign = new F32ActorHostPreflightV1.Result[1];
        Thread thief = new Thread(() -> foreign[0] = gate.inspect(actor, 2, 3, 3, 2, 4096));
        thief.start();
        thief.join();
        check(foreign[0] == F32ActorHostPreflightV1.Result.NOT_RUNNING,
                "copied context on foreign physical thread");
        check(gate.inspect(actor, 2, 3, 3, 2, 4096) == expected, "owner survives foreign attempt");
        gate.retireTurn();
        check(gate.inspect(actor, 2, 3, 3, 2, 4096)
                == F32ActorHostPreflightV1.Result.NOT_RUNNING, "retired turn");
        gate.retireTurn();
        boolean taskRejected = false;
        try {
            new F32ActorHostPreflightV1(ExecutionContext.task(7, 50));
        } catch (IllegalArgumentException ok) {
            taskRejected = true;
        }
        check(taskRejected, "task cannot access actor-owned f32 gate");
        System.out.println(
            "ORES_F32_JAVA_HOST_PREFLIGHT_V1_PASS|stolen_thread=DENIED|retired=DENIED|guest=NOT_EXECUTED");
    }
}
