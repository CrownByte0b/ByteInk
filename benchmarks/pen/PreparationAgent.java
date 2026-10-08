import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Benchmark only: retain the original loop and conditionally substitute pure preparation. */
public final class PreparationAgent {
    public static volatile int transformed;
    public static void premain(String argument, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (!name.equals("com/vivenotes/byteink/compose/InkMeshShaderKt")) return null;
                ClassFile file = ClassFile.of();
                CodeTransform guard = new CodeTransform() {
                    @Override public void atStart(CodeBuilder code) {
                        for (int i = 0; i < 7; i++) code.aload(i);
                        code.invokestatic(ClassDesc.of("MeshPreparation"), "tryPrepare",
                            java.lang.constant.MethodTypeDesc.ofDescriptor(
                                "(Lcom/vivenotes/byteink/core/StrokeMesh;"
                                + "Lcom/vivenotes/byteink/compose/MeshLinearTransform;"
                                + "Lcom/vivenotes/byteink/compose/MeshColor;"
                                + "Lcom/vivenotes/byteink/compose/StampAnimation;[F[F[Z)Z"));
                        code.ifThen(yes -> yes.return_());
                    }
                    @Override public void accept(CodeBuilder builder, CodeElement element) {
                        builder.with(element);
                    }
                };
                byte[] result = file.transformClass(file.parse(bytes), (builder, element) -> {
                    if (element instanceof MethodModel method && method.methodName().equalsString("prepareVertices")) {
                        if (!method.flags().has(java.lang.reflect.AccessFlag.STATIC) || method.methodTypeSymbol().parameterCount() != 7)
                            throw new IllegalStateException("Pinned pure preparation signature changed");
                        builder.transformMethod(method, MethodTransform.transformingCode(guard));
                        transformed++;
                    } else builder.with(element);
                });
                if (transformed != 1) throw new IllegalStateException("Expected exactly one pure preparation loop");
                return result;
            }
        });
    }
}
