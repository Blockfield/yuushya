import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PatchYuushya {
    static final String CLASS = "com/yuushya/Yuushya";
    static final String DOOR_CLASS = "com/yuushya/block/YuushyaBlockFactory$3";
    static final String FACTORY = "com/yuushya/block/YuushyaBlockFactory";
    static final String DOOR = "net/minecraft/class_2323";
    static final String SETTINGS = "net/minecraft/class_4970$class_2251";
    static final String BLOCK = "com/yuushya/registries/YuushyaRegistryData$Block";
    static final String SHAPE = "net/minecraft/class_265";
    static final String SHAPE_DESCRIPTOR =
            "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1922;Lnet/minecraft/class_2338;"
                    + "Lnet/minecraft/class_3726;)L"
                    + SHAPE
                    + ";";
    static final String DOOR_CONSTRUCTOR = "(Lnet/minecraft/class_8177;L" + SETTINGS + ";)V";
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
        if (DOOR_CLASS.equals(node.name)) return patchDoor(reader, node);
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

    private static byte[] patchDoor(ClassReader reader, ClassNode node) {
        if (!DOOR.equals(node.superName)
                || node.fields.size() != 1
                || !"val$yuushyaBlock".equals(node.fields.getFirst().name)
                || !("L" + BLOCK + ";").equals(node.fields.getFirst().desc)
                || node.fields.getFirst().access != (Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)) {
            throw new IllegalStateException("Unexpected window-door class shape");
        }
        if (node.methods.stream().anyMatch(method -> "method_9530".equals(method.name)))
            throw new IllegalStateException("Door shape override already present");
        List<MethodNode> constructors =
                node.methods.stream().filter(method -> "<init>".equals(method.name)).toList();
        if (constructors.size() != 1
                || !("(Lnet/minecraft/class_8177;L" + SETTINGS + ";L" + BLOCK + ";)V")
                        .equals(constructors.getFirst().desc)) {
            throw new IllegalStateException("Unexpected window-door constructor");
        }
        MethodNode constructor = constructors.getFirst();
        List<MethodInsnNode> superCalls = new ArrayList<>();
        for (AbstractInsnNode instruction : constructor.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (SETTINGS.equals(call.owner) && "method_9624".equals(call.name))
                    throw new IllegalStateException("Door dynamicShape already present");
                if (DOOR.equals(call.owner) && "<init>".equals(call.name)) superCalls.add(call);
            }
        }
        if (superCalls.size() != 1
                || superCalls.getFirst().getOpcode() != Opcodes.INVOKESPECIAL
                || !DOOR_CONSTRUCTOR.equals(superCalls.getFirst().desc)) {
            throw new IllegalStateException("Unexpected window-door super constructor");
        }
        AbstractInsnNode settingsArgument = superCalls.getFirst().getPrevious();
        while (settingsArgument != null && settingsArgument.getOpcode() < 0)
            settingsArgument = settingsArgument.getPrevious();
        if (!(settingsArgument instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 2) {
            throw new IllegalStateException("Unexpected window-door settings argument");
        }
        constructor.instructions.insertBefore(
                superCalls.getFirst(),
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        SETTINGS,
                        "method_9624",
                        "()L" + SETTINGS + ";",
                        false));

        MethodNode shape =
                new MethodNode(Opcodes.ACC_PUBLIC, "method_9530", SHAPE_DESCRIPTOR, null, null);
        shape.instructions.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        FACTORY,
                        "getYuushyaVoxelShapes",
                        "()Ljava/util/Map;",
                        false));
        shape.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        shape.instructions.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/class_2680",
                        "toString",
                        "()Ljava/lang/String;",
                        false));
        shape.instructions.add(
                new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/Map",
                        "get",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        true));
        shape.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, SHAPE));
        shape.instructions.add(new InsnNode(Opcodes.DUP));
        LabelNode mapped = new LabelNode();
        shape.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, mapped));
        shape.instructions.add(new InsnNode(Opcodes.POP));
        for (int argument = 0; argument <= 4; argument++)
            shape.instructions.add(new VarInsnNode(Opcodes.ALOAD, argument));
        shape.instructions.add(
                new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, DOOR, "method_9530", SHAPE_DESCRIPTOR, false));
        shape.instructions.add(new InsnNode(Opcodes.ARETURN));
        shape.instructions.add(mapped);
        shape.instructions.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {SHAPE}));
        shape.instructions.add(new InsnNode(Opcodes.ARETURN));
        shape.maxStack = 5;
        shape.maxLocals = 5;
        node.methods.add(shape);
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
