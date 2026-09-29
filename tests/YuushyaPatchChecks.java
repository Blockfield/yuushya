import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class YuushyaPatchChecks {
    static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static byte[] write(ClassNode node, boolean shiftPool) {
        ClassWriter writer = new ClassWriter(0);
        if (shiftPool) {
            for (int index = 0; index < 128; index++)
                writer.newConst("unrelated-constant-" + index);
        }
        node.accept(writer);
        return writer.toByteArray();
    }

    static FieldInsnNode target(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && PatchYuushya.PLAYER_EVENT.equals(field.owner)
                        && "PLAYER_JOIN".equals(field.name)) {
                    return field;
                }
            }
        }
        throw new AssertionError("Fixture target missing");
    }

    static MethodNode init(ClassNode node) {
        return node.methods.stream()
                .filter(m -> "init".equals(m.name) && "()V".equals(m.desc))
                .findFirst()
                .orElseThrow();
    }

    static void reject(byte[] bytes, String expected) {
        try {
            PatchYuushya.patch(bytes);
            throw new AssertionError("Unexpected successful patch: " + expected);
        } catch (IllegalStateException | IllegalArgumentException error) {
            if (!error.getMessage().contains(expected)) throw new AssertionError(error);
        }
    }

    static String semantics(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        MethodNode method = init(node);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.NOP) method.instructions.remove(instruction);
        }
        StringWriter output = new StringWriter();
        node.accept(new TraceClassVisitor(new PrintWriter(output)));
        return output.toString();
    }

    static void verify(byte[] bytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new CheckClassAdapter(writer, true), 0);
        new ClassLoader() {
            void check() {
                Class<?> value = defineClass(null, bytes, 0, bytes.length);
                resolveClass(value);
            }
        }.check();
    }

    static int nops(ClassNode node) {
        int count = 0;
        for (AbstractInsnNode instruction : init(node).instructions) {
            if (instruction.getOpcode() == Opcodes.NOP) count++;
        }
        return count;
    }

    public static void main(String[] args) throws Exception {
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        byte[] legacy = Files.readAllBytes(Path.of(args[1]));
        byte[] patched = PatchYuushya.patch(original);
        verify(patched);
        if (!semantics(patched).equals(semantics(legacy))) {
            throw new AssertionError("ASM output changed behavior beyond the legacy notice patch");
        }
        if (!java.util.Arrays.equals(patched, PatchYuushya.patch(original))) {
            throw new AssertionError("Nondeterministic transformer");
        }
        reject(patched, "got 0");

        ClassNode absent = read(original);
        target(absent).name = "OTHER_EVENT";
        reject(write(absent, false), "got 0");

        ClassNode multiple = read(original);
        FieldInsnNode field = target(multiple);
        AbstractInsnNode dynamic = PatchYuushya.nextInstruction(field);
        AbstractInsnNode register = PatchYuushya.nextInstruction(dynamic);
        Map<org.objectweb.asm.tree.LabelNode, org.objectweb.asm.tree.LabelNode> labels =
                new HashMap<>();
        InsnList duplicate = new InsnList();
        duplicate.add(field.clone(labels));
        duplicate.add(dynamic.clone(labels));
        duplicate.add(register.clone(labels));
        init(multiple).instructions.insert(register, duplicate);
        reject(write(multiple, false), "got 2");

        ClassNode badField = read(original);
        target(badField).desc = "Ljava/lang/Object;";
        reject(write(badField, false), "Unexpected PLAYER_JOIN registration shape");

        ClassNode badInvoke = read(original);
        ((MethodInsnNode)
                                PatchYuushya.nextInstruction(
                                        PatchYuushya.nextInstruction(target(badInvoke))))
                        .name =
                "clear";
        reject(write(badInvoke, false), "Unexpected PLAYER_JOIN registration shape");

        ClassNode badLambda = read(original);
        InvokeDynamicInsnNode lambda =
                (InvokeDynamicInsnNode) PatchYuushya.nextInstruction(target(badLambda));
        lambda.bsmArgs[1] =
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        PatchYuushya.CLASS,
                        "lambda$init$0",
                        PatchYuushya.CALLBACK,
                        false);
        reject(write(badLambda, false), "Unexpected PLAYER_JOIN registration shape");

        ClassNode badMethod = read(original);
        init(badMethod).name = "unexpectedInit";
        reject(write(badMethod, false), "Unexpected PLAYER_JOIN registration shape");

        ClassNode variation = read(original);
        init(variation).instructions.insertBefore(target(variation), new InsnNode(Opcodes.NOP));
        byte[] varied = write(variation, true);
        byte[] oldPattern = java.util.HexFormat.of().parseHex("b20063ba006d0000b900600200");
        for (int index = 0; index <= varied.length - oldPattern.length; index++) {
            if (java.util.Arrays.equals(
                    java.util.Arrays.copyOfRange(varied, index, index + oldPattern.length),
                    oldPattern)) {
                throw new AssertionError(
                        "Constant-pool fixture still matches the old raw byte patch");
            }
        }
        byte[] variedPatched = PatchYuushya.patch(varied);
        verify(variedPatched);
        if (nops(read(variedPatched)) != 1
                || !semantics(variedPatched).equals(semantics(patched))) {
            throw new AssertionError("Constant-pool variation damaged a non-target instruction");
        }
        Files.write(Path.of(args[2]), varied);
        Files.write(Path.of(args[3]), variedPatched);
        System.out.println(
                "PASS: pinned upstream, ASM/JVM verification, legacy semantic parity, deterministic"
                        + " class, repeat/absent/multiple/unexpected field/invoke/lambda/method"
                        + " rejection, constant-pool and unrelated-NOP variation");
    }
}
