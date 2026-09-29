import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PatchYuushya {
    static final String CLASS = "com/yuushya/Yuushya";
    static final String EVENT = "dev/architectury/event/Event";
    static final String PLAYER_EVENT = "dev/architectury/event/events/common/PlayerEvent";
    static final String CALLBACK = "(Lnet/minecraft/class_3222;)V";
    static final Handle METAFACTORY =
            new Handle(
                    Opcodes.H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory",
                    "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                    false);

    static AbstractInsnNode nextInstruction(AbstractInsnNode node) {
        do {
            node = node.getNext();
        } while (node != null && node.getOpcode() < 0);
        return node;
    }

    static byte[] patch(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (!CLASS.equals(node.name)) {
            throw new IllegalArgumentException("Expected " + CLASS + ", got " + node.name);
        }
        List<FieldInsnNode> matches = new ArrayList<>();
        MethodNode method = null;
        for (MethodNode candidate : node.methods) {
            for (AbstractInsnNode instruction : candidate.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && PLAYER_EVENT.equals(field.owner)
                        && "PLAYER_JOIN".equals(field.name)) {
                    matches.add(field);
                    method = candidate;
                }
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one PLAYER_JOIN registration, got " + matches.size());
        }
        FieldInsnNode field = matches.getFirst();
        AbstractInsnNode second = nextInstruction(field);
        AbstractInsnNode third = second == null ? null : nextInstruction(second);
        Object[] expectedArguments = {
            Type.getMethodType(CALLBACK),
            new Handle(Opcodes.H_INVOKESTATIC, CLASS, "lambda$init$4", CALLBACK, false),
            Type.getMethodType(CALLBACK)
        };
        boolean helper =
                node.methods.stream()
                        .anyMatch(
                                m ->
                                        "lambda$init$4".equals(m.name)
                                                && CALLBACK.equals(m.desc)
                                                && m.access
                                                        == (Opcodes.ACC_PRIVATE
                                                                | Opcodes.ACC_STATIC
                                                                | Opcodes.ACC_SYNTHETIC));
        if (!"init".equals(method.name)
                || !"()V".equals(method.desc)
                || (method.access & Opcodes.ACC_STATIC) == 0
                || field.getOpcode() != Opcodes.GETSTATIC
                || !("L" + EVENT + ";").equals(field.desc)
                || !(second instanceof InvokeDynamicInsnNode dynamic)
                || !"join".equals(dynamic.name)
                || !("()L" + PLAYER_EVENT + "$PlayerJoin;").equals(dynamic.desc)
                || !METAFACTORY.equals(dynamic.bsm)
                || !Arrays.equals(expectedArguments, dynamic.bsmArgs)
                || !(third instanceof MethodInsnNode register)
                || register.getOpcode() != Opcodes.INVOKEINTERFACE
                || !register.itf
                || !EVENT.equals(register.owner)
                || !"register".equals(register.name)
                || !"(Ljava/lang/Object;)V".equals(register.desc)
                || !helper) {
            throw new IllegalStateException(
                    "Unexpected PLAYER_JOIN registration shape in " + method.name + method.desc);
        }
        method.instructions.remove(field);
        method.instructions.remove(second);
        method.instructions.remove(third);
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(new CheckClassAdapter(writer, true));
        return writer.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 0) {
            throw new IllegalArgumentException("Class bytes are read from stdin");
        }
        System.out.write(patch(System.in.readAllBytes()));
    }
}
