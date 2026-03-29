package kz.regullar.transformer.impl.other;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class StaticInitializionTransformer implements TransformHandler, Opcodes {
    private final Random random = new Random();

    @Override
    public String name() {
        return "Static Method Initializer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        Map<FieldNode, Object> fieldsToMove = new HashMap<>();

        for (FieldNode field : classNode.fields) {
            if (field.value != null && (field.access & ACC_STATIC) != 0) {
                if (field.value instanceof String || field.value instanceof Integer) {
                    fieldsToMove.put(field, field.value);
                    field.value = null;
                }
            }
        }

        if (fieldsToMove.isEmpty()) return;

        String methodName = "HOHHOHOHO";

        MethodNode initMethod = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                methodName,
                "()V",
                null,
                null
        );

        for (Map.Entry<FieldNode, Object> entry : fieldsToMove.entrySet()) {
            if (entry.getValue() instanceof String) {
                initMethod.instructions.add(new LdcInsnNode(entry.getValue()));
            } else {
                initMethod.instructions.add(pushInt((Integer) entry.getValue()));
            }
            initMethod.instructions.add(new FieldInsnNode(PUTSTATIC, classNode.name, entry.getKey().name, entry.getKey().desc));
        }
        initMethod.instructions.add(new InsnNode(RETURN));
        classNode.methods.add(initMethod);

        MethodNode clinit = null;
        for (MethodNode m : classNode.methods) {
            if (m.name.equals("<clinit>")) {
                clinit = m;
                break;
            }
        }

        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new MethodInsnNode(INVOKESTATIC, classNode.name, methodName, "()V", false));
            clinit.instructions.add(new InsnNode(RETURN));
            classNode.methods.add(clinit);
        } else {
            clinit.instructions.insertBefore(clinit.instructions.getFirst(),
                    new MethodInsnNode(INVOKESTATIC, classNode.name, methodName, "()V", false));
        }
    }

    private AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }
}