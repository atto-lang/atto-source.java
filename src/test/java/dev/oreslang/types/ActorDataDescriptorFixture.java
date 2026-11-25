package dev.oreslang.types;

import dev.oreslang.types.PortableCodecRegistry.*;

/** Test-only compiler registration, not a guest-accessible factory. */
public final class ActorDataDescriptorFixture {
    private ActorDataDescriptorFixture() { }
    public static Descriptor scalar(Purpose purpose, Format format) {
        return shape(purpose, format, new Scalar(ScalarKind.I64));
    }
    public static Descriptor shape(Purpose purpose, Format format, Shape shape) {
        return new Registry().registerCompilerDescriptor(format, purpose,
                "app.Mail", "app.MailWire", 1, shape);
    }
}
