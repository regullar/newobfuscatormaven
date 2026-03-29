package kz.regullar.transformer.impl.flow;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class SecondControlFlowTransformer implements TransformHandler, Opcodes {

    @Override
    public String name() {
        return "SecondControlFlowTransformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        if ((classNode.access & ACC_INTERFACE) != 0) return;

        Set<String> used = new HashSet<>();
        for (FieldNode fn : classNode.fields) {
            used.add(fn.name);
        }

        String fieldName;
        do {
            fieldName = "f" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        } while (used.contains(fieldName));
        boolean setupField = false;

        for (MethodNode method : classNode.methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            if (NativeAnnotationUtils.shouldSkipObfuscation(classNode, method)) continue;

            AbstractInsnNode[] insns = method.instructions.toArray();

            for (AbstractInsnNode insn : insns) {
                if (insn instanceof JumpInsnNode jumpInsn && jumpInsn.getOpcode() == GOTO) {
                    LabelNode targetLabel = jumpInsn.label;

                    InsnList repl = new InsnList();
                    repl.add(new FieldInsnNode(GETSTATIC, classNode.name, fieldName, "I"));
                    repl.add(new JumpInsnNode(IFLT, targetLabel));

                    int randomInt = ThreadLocalRandom.current().nextInt(0, 5);
                    boolean usePop = true;
                    switch (randomInt) {
                        case 0 -> {
                            int v = ThreadLocalRandom.current().nextInt();
                            repl.add(new LdcInsnNode(v));
                            usePop = true;
                        }
                        case 1 -> {
                            long v = ThreadLocalRandom.current().nextLong();
                            repl.add(new LdcInsnNode(v));
                            usePop = false;
                        }
                        case 2 -> {
                            repl.add(new InsnNode(ACONST_NULL));
                            usePop = true;
                        }
                        case 3 -> {
                            float v = ThreadLocalRandom.current().nextFloat();
                            repl.add(new LdcInsnNode(v));
                            usePop = true;
                        }
                        case 4 -> {
                            double v = ThreadLocalRandom.current().nextDouble();
                            repl.add(new LdcInsnNode(v));
                            usePop = false;
                        }
                    }

                    repl.add(new InsnNode(usePop ? POP : POP2));
                    repl.add(new InsnNode(ACONST_NULL));
                    repl.add(new InsnNode(ATHROW));

                    method.instructions.insertBefore(jumpInsn, repl);
                    method.instructions.remove(jumpInsn);
                    setupField = true;
                }

                if (insn instanceof VarInsnNode varInsn) {
                    int opc = varInsn.getOpcode();
                    if (opc == ILOAD || opc == LLOAD || opc == FLOAD || opc == DLOAD || opc == ALOAD) {
                        boolean isWide = (opc == LLOAD || opc == DLOAD);
                        int size = isWide ? 2 : 1;

                        method.maxLocals = method.maxLocals + size;
                        int newIndex = method.maxLocals;

                        LabelNode label = new LabelNode();

                        InsnList append = new InsnList();
                        append.add(new VarInsnNode(opc + 33, newIndex));
                        append.add(new VarInsnNode(opc, newIndex));
                        append.add(new FieldInsnNode(GETSTATIC, classNode.name, fieldName, "I"));
                        append.add(new JumpInsnNode(IFLT, label));
                        append.add(new FieldInsnNode(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
                        append.add(new LdcInsnNode(ThreadLocalRandom.current().nextLong()));
                        append.add(new MethodInsnNode(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(J)V", false));
                        append.add(new InsnNode(ACONST_NULL));
                        append.add(new InsnNode(ATHROW));
                        append.add(label);

                        method.instructions.insert(varInsn, append);
                        setupField = true;
                    }

                    if (opc == ISTORE || opc == LSTORE || opc == FSTORE || opc == DSTORE || opc == ASTORE) {
                        InsnList append = new InsnList();
                        append.add(new VarInsnNode(opc - 33, varInsn.var));
                        if (opc == DSTORE || opc == LSTORE) {
                            append.add(new InsnNode(POP2));
                        } else {
                            append.add(new InsnNode(POP));
                        }
                        method.instructions.insert(varInsn, append);
                    }
                }
            }
        }

        if (setupField) {
            FieldNode field = new FieldNode(ACC_PRIVATE | ACC_STATIC, fieldName, "I", null, ThreadLocalRandom.current().nextInt(Integer.MIN_VALUE, 0));
            classNode.fields.add(field);
        }
    }
}
