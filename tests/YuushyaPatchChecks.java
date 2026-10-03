import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
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

    static String trace(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        StringWriter output = new StringWriter();
        node.accept(new TraceClassVisitor(new PrintWriter(output)));
        return output.toString();
    }

    static MethodNode doorConstructor(ClassNode node) {
        return node.methods.stream()
                .filter(method -> "<init>".equals(method.name))
                .findFirst()
                .orElseThrow();
    }

    static ClassWriter stub(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return writer;
    }

    static void checkDoorRuntime(byte[] patched) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (String name :
                new String[] {
                    "net/minecraft/class_8177",
                    "net/minecraft/class_1922",
                    "net/minecraft/class_2338",
                    "net/minecraft/class_3726",
                    "net/minecraft/class_2248",
                    PatchYuushya.SHAPE,
                    PatchYuushya.BLOCK
                }) definitions.put(name.replace('/', '.'), stub(name).toByteArray());

        ClassWriter settings = stub(PatchYuushya.SETTINGS);
        settings.visitField(Opcodes.ACC_PUBLIC, "dynamic", "Z", null, null).visitEnd();
        MethodVisitor dynamic =
                settings.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "method_9624",
                        "()L" + PatchYuushya.SETTINGS + ";",
                        null,
                        null);
        dynamic.visitCode();
        dynamic.visitVarInsn(Opcodes.ALOAD, 0);
        dynamic.visitInsn(Opcodes.ICONST_1);
        dynamic.visitFieldInsn(Opcodes.PUTFIELD, PatchYuushya.SETTINGS, "dynamic", "Z");
        dynamic.visitVarInsn(Opcodes.ALOAD, 0);
        dynamic.visitInsn(Opcodes.ARETURN);
        dynamic.visitMaxs(2, 1);
        dynamic.visitEnd();
        definitions.put(PatchYuushya.SETTINGS.replace('/', '.'), settings.toByteArray());

        ClassWriter state = stub("net/minecraft/class_2680");
        MethodVisitor key =
                state.visitMethod(
                        Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        key.visitCode();
        key.visitLdcInsn("fixture-state");
        key.visitInsn(Opcodes.ARETURN);
        key.visitMaxs(1, 1);
        key.visitEnd();
        MethodVisitor block =
                state.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "method_26204",
                        "()Lnet/minecraft/class_2248;",
                        null,
                        null);
        block.visitCode();
        block.visitInsn(Opcodes.ACONST_NULL);
        block.visitInsn(Opcodes.ARETURN);
        block.visitMaxs(1, 1);
        block.visitEnd();
        definitions.put("net.minecraft.class_2680", state.toByteArray());

        ClassWriter identifier = stub("net/minecraft/class_2960");
        MethodVisitor id =
                identifier.visitMethod(
                        Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        id.visitCode();
        id.visitLdcInsn("yuushya:fixture-door");
        id.visitInsn(Opcodes.ARETURN);
        id.visitMaxs(1, 1);
        id.visitEnd();
        definitions.put("net.minecraft.class_2960", identifier.toByteArray());
        ClassWriter registry = new ClassWriter(0);
        registry.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "net/minecraft/class_7922",
                null,
                "java/lang/Object",
                null);
        registry.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                        "method_10221",
                        "(Ljava/lang/Object;)Lnet/minecraft/class_2960;",
                        null,
                        null)
                .visitEnd();
        definitions.put("net.minecraft.class_7922", registry.toByteArray());
        ClassWriter registries = stub("net/minecraft/class_7923");
        registries
                .visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "field_41175",
                        "Lnet/minecraft/class_7922;",
                        null,
                        null)
                .visitEnd();
        definitions.put("net.minecraft.class_7923", registries.toByteArray());

        ClassWriter factory = stub(PatchYuushya.FACTORY);
        factory.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "shapes",
                        "Ljava/util/Map;",
                        null,
                        null)
                .visitEnd();
        MethodVisitor shapes =
                factory.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "getYuushyaVoxelShapes",
                        "()Ljava/util/Map;",
                        null,
                        null);
        shapes.visitCode();
        shapes.visitFieldInsn(Opcodes.GETSTATIC, PatchYuushya.FACTORY, "shapes", "Ljava/util/Map;");
        shapes.visitInsn(Opcodes.ARETURN);
        shapes.visitMaxs(1, 0);
        shapes.visitEnd();
        definitions.put(PatchYuushya.FACTORY.replace('/', '.'), factory.toByteArray());

        ClassWriter reader = stub(PatchYuushya.READER);
        reader.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "pending",
                        "Ljava/util/Map;",
                        null,
                        null)
                .visitEnd();
        reader.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "lastId",
                        "Ljava/lang/String;",
                        null,
                        null)
                .visitEnd();
        reader.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null)
                .visitEnd();
        MethodVisitor lazy =
                reader.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "readCollisionToVoxelShape",
                        PatchYuushya.LAZY_DESCRIPTOR,
                        null,
                        null);
        lazy.visitCode();
        lazy.visitVarInsn(Opcodes.ALOAD, 1);
        lazy.visitFieldInsn(Opcodes.PUTSTATIC, PatchYuushya.READER, "lastId", "Ljava/lang/String;");
        lazy.visitFieldInsn(Opcodes.GETSTATIC, PatchYuushya.READER, "calls", "I");
        lazy.visitInsn(Opcodes.ICONST_1);
        lazy.visitInsn(Opcodes.IADD);
        lazy.visitFieldInsn(Opcodes.PUTSTATIC, PatchYuushya.READER, "calls", "I");
        lazy.visitFieldInsn(Opcodes.GETSTATIC, PatchYuushya.FACTORY, "shapes", "Ljava/util/Map;");
        lazy.visitVarInsn(Opcodes.ALOAD, 0);
        lazy.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/class_2680",
                "toString",
                "()Ljava/lang/String;",
                false);
        lazy.visitFieldInsn(Opcodes.GETSTATIC, PatchYuushya.READER, "pending", "Ljava/util/Map;");
        lazy.visitVarInsn(Opcodes.ALOAD, 0);
        lazy.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/class_2680",
                "toString",
                "()Ljava/lang/String;",
                false);
        lazy.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true);
        lazy.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true);
        lazy.visitInsn(Opcodes.POP);
        lazy.visitInsn(Opcodes.RETURN);
        lazy.visitMaxs(4, 2);
        lazy.visitEnd();
        definitions.put(PatchYuushya.READER.replace('/', '.'), reader.toByteArray());

        ClassWriter base = stub(PatchYuushya.DOOR);
        base.visitField(Opcodes.ACC_PUBLIC, "dynamicAtConstruction", "Z", null, null).visitEnd();
        base.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "fallback",
                        "L" + PatchYuushya.SHAPE + ";",
                        null,
                        null)
                .visitEnd();
        MethodVisitor constructor =
                base.visitMethod(
                        Opcodes.ACC_PUBLIC, "<init>", PatchYuushya.DOOR_CONSTRUCTOR, null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 2);
        constructor.visitFieldInsn(Opcodes.GETFIELD, PatchYuushya.SETTINGS, "dynamic", "Z");
        constructor.visitFieldInsn(
                Opcodes.PUTFIELD, PatchYuushya.DOOR, "dynamicAtConstruction", "Z");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 3);
        constructor.visitEnd();
        MethodVisitor fallback =
                base.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "method_9530",
                        PatchYuushya.SHAPE_DESCRIPTOR,
                        null,
                        null);
        fallback.visitCode();
        fallback.visitFieldInsn(
                Opcodes.GETSTATIC, PatchYuushya.DOOR, "fallback", "L" + PatchYuushya.SHAPE + ";");
        fallback.visitInsn(Opcodes.ARETURN);
        fallback.visitMaxs(1, 5);
        fallback.visitEnd();
        definitions.put(PatchYuushya.DOOR.replace('/', '.'), base.toByteArray());

        // Only the patched constructor and shape execute here; the untouched tooltip needs
        // Minecraft.
        ClassNode fixture = read(patched);
        fixture.methods.removeIf(
                method -> !"<init>".equals(method.name) && !"method_9530".equals(method.name));
        definitions.put(PatchYuushya.DOOR_CLASS.replace('/', '.'), write(fixture, false));
        ClassLoader loader =
                new ClassLoader() {
                    @Override
                    protected Class<?> findClass(String name) throws ClassNotFoundException {
                        byte[] bytes = definitions.get(name);
                        if (bytes == null) throw new ClassNotFoundException(name);
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                };
        Class<?> settingsClass = loader.loadClass(PatchYuushya.SETTINGS.replace('/', '.'));
        Class<?> registryClass = loader.loadClass("net.minecraft.class_7922");
        Object identifierValue =
                loader.loadClass("net.minecraft.class_2960").getConstructor().newInstance();
        Object registryValue =
                java.lang.reflect.Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {registryClass},
                        (proxy, method, arguments) -> identifierValue);
        loader.loadClass("net.minecraft.class_7923")
                .getField("field_41175")
                .set(null, registryValue);
        Class<?> readerClass = loader.loadClass(PatchYuushya.READER.replace('/', '.'));
        Map<String, Object> pending = new HashMap<>();
        readerClass.getField("pending").set(null, pending);
        Class<?> doorClass = loader.loadClass(PatchYuushya.DOOR_CLASS.replace('/', '.'));
        var doorConstructor =
                doorClass.getDeclaredConstructor(
                        loader.loadClass("net.minecraft.class_8177"),
                        settingsClass,
                        loader.loadClass(PatchYuushya.BLOCK.replace('/', '.')));
        doorConstructor.setAccessible(true);
        Object door =
                doorConstructor.newInstance(
                        null, settingsClass.getConstructor().newInstance(), null);
        if (!doorClass.getField("dynamicAtConstruction").getBoolean(door))
            throw new AssertionError("Door shape would be cached before collision resources load");
        Object vanilla =
                loader.loadClass(PatchYuushya.SHAPE.replace('/', '.'))
                        .getConstructor()
                        .newInstance();
        Object mapped =
                loader.loadClass(PatchYuushya.SHAPE.replace('/', '.'))
                        .getConstructor()
                        .newInstance();
        loader.loadClass(PatchYuushya.DOOR.replace('/', '.'))
                .getField("fallback")
                .set(null, vanilla);
        Map<String, Object> shapeMap = new HashMap<>();
        shapeMap.put("unrelated-state", vanilla);
        loader.loadClass(PatchYuushya.FACTORY.replace('/', '.'))
                .getField("shapes")
                .set(null, shapeMap);
        Class<?> stateClass = loader.loadClass("net.minecraft.class_2680");
        var shapeMethod =
                doorClass.getDeclaredMethod(
                        "method_9530",
                        stateClass,
                        loader.loadClass("net.minecraft.class_1922"),
                        loader.loadClass("net.minecraft.class_2338"),
                        loader.loadClass("net.minecraft.class_3726"));
        shapeMethod.setAccessible(true);
        Object stateValue = stateClass.getConstructor().newInstance();
        if (shapeMethod.invoke(door, stateValue, null, null, null) != vanilla)
            throw new AssertionError("Missing door mapping did not preserve vanilla fallback");
        if (!"yuushya:fixture-door".equals(readerClass.getField("lastId").get(null)))
            throw new AssertionError("Lazy collision lookup did not use the registry block ID");
        pending.put("fixture-state", mapped);
        if (shapeMethod.invoke(door, stateValue, null, null, null) != mapped)
            throw new AssertionError("Partial nonempty shape map prevented lazy collision loading");
        if (readerClass.getField("calls").getInt(null) != 2)
            throw new AssertionError("Missing shape did not call the existing lazy loader");
        if (shapeMethod.invoke(door, stateValue, null, null, null) != mapped
                || readerClass.getField("calls").getInt(null) != 2)
            throw new AssertionError("Mapped shape unnecessarily repeated lazy collision loading");
        pending.remove("fixture-state");
        shapeMap.remove("fixture-state");
        if (shapeMethod.invoke(door, stateValue, null, null, null) != vanilla)
            throw new AssertionError("Door retained a removed collision mapping");
    }

    static void checkDoor(byte[] original) throws Exception {
        byte[] patched = PatchYuushya.patch(original);
        if (!java.util.Arrays.equals(patched, PatchYuushya.patch(original)))
            throw new AssertionError("Nondeterministic door transformer");
        ClassNode restored = read(patched);
        restored.methods.removeIf(method -> "method_9530".equals(method.name));
        MethodNode constructor = doorConstructor(restored);
        for (AbstractInsnNode instruction : constructor.instructions.toArray())
            if (instruction instanceof MethodInsnNode call && "method_9624".equals(call.name))
                constructor.instructions.remove(instruction);
        if (!trace(original).equals(trace(write(restored, false))))
            throw new AssertionError("Door transformer changed non-target fields or methods");
        reject(patched, "Door shape override already present");
        ClassNode wrongSuper = read(original);
        wrongSuper.superName = "java/lang/Object";
        reject(write(wrongSuper, false), "Unexpected window-door class shape");
        ClassNode wrongConstructor = read(original);
        doorConstructor(wrongConstructor).desc = "()V";
        reject(write(wrongConstructor, false), "Unexpected window-door constructor");
        ClassNode noSuper = read(original);
        for (AbstractInsnNode instruction : doorConstructor(noSuper).instructions)
            if (instruction instanceof MethodInsnNode call && "<init>".equals(call.name))
                call.name = "changedConstructor";
        reject(write(noSuper, false), "Unexpected window-door super constructor");
        ClassNode alreadyDynamic = read(original);
        for (AbstractInsnNode instruction : doorConstructor(alreadyDynamic).instructions.toArray())
            if (instruction instanceof MethodInsnNode call && "<init>".equals(call.name))
                doorConstructor(alreadyDynamic)
                        .instructions
                        .insertBefore(
                                call,
                                new MethodInsnNode(
                                        Opcodes.INVOKEVIRTUAL,
                                        PatchYuushya.SETTINGS,
                                        "method_9624",
                                        "()L" + PatchYuushya.SETTINGS + ";",
                                        false));
        reject(write(alreadyDynamic, false), "Door dynamicShape already present");
        byte[] varied = write(read(original), true);
        if (!trace(patched).equals(trace(PatchYuushya.patch(varied))))
            throw new AssertionError("Constant-pool variation changed door patch behavior");
        checkDoorRuntime(patched);
    }

    public static void main(String[] args) throws Exception {
        checkDoor(Files.readAllBytes(Path.of(args[4])));
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
                        + " rejection, constant-pool and unrelated-NOP variation; door map/fallback"
                        + " execution, dynamic settings before base construction, mapping reload,"
                        + " non-target parity and class/constructor/repeat drift refusal");
    }
}
