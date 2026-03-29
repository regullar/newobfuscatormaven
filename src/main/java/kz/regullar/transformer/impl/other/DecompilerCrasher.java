package kz.regullar.transformer.impl.other;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import kz.regullar.util.ASMUtils;

import org.objectweb.asm.tree.*;
import kz.regullar.util.RandomUtils;

import java.util.Arrays;


public class DecompilerCrasher implements Opcodes, TransformHandler {

    @Override
    public String name(){
        return "Decompiler Crasher";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        for (MethodNode m : classNode.methods) addJdecAppCrasher(classNode, m);
    }

    public void addJdecAppCrasher(ClassNode classNode, MethodNode methodNode) {
        if(!ASMUtils.isMethodEligibleToModify(classNode, methodNode)) return;

        if (Arrays.stream(methodNode.instructions.toArray()).noneMatch(ASMUtils::isIf)) {
            final InsnList il = new InsnList();
            final LabelNode label0 = new LabelNode();
            final LabelNode label1 = new LabelNode();
            il.add(new InsnNode(ICONST_1));
            il.add(new JumpInsnNode(GOTO, label1));
            il.add(label0);
            il.add(new InsnNode(ICONST_5));
            il.add(label1);
            il.add(new InsnNode(ICONST_M1));
            il.add(new JumpInsnNode(IF_ICMPLE, label0));
            methodNode.instructions.insert(il);
        }

        Arrays.stream(methodNode.instructions.toArray())
                .filter(ASMUtils::isIf)
                .filter(m -> !NativeAnnotationUtils.shouldSkipObfuscation(classNode, methodNode))
                .map(insn -> (JumpInsnNode)insn)
                .forEach(jump -> {
                    var label0 = new LabelNode();
                    var label1 = new LabelNode();
                    var label2 = new LabelNode();
                    var label3 = new LabelNode();
                    long jVar;

                    final InsnList start = new InsnList();
                    final InsnList before = new InsnList();
                    final InsnList after = new InsnList();
                    final InsnList end = new InsnList();

                    before.add(label0);
                    before.add(ASMUtils.pushLong(0));
                    before.add(ASMUtils.pushLong(Math.abs((jVar = RandomUtils.getRandomLong()) == 0 ? ++jVar : jVar)));
                    before.add(new JumpInsnNode(GOTO, label2));
                    before.add(label1);
                    long v1 = RandomUtils.getRandomLong();
                    long v2 = RandomUtils.getRandomLong();
                    before.add(ASMUtils.pushLong(v1));
                    before.add(ASMUtils.pushLong(v2));
                    before.add(label2);
                    switch (RandomUtils.getRandomInt(3)) {
                        case 0 -> {
                            before.add(new InsnNode(LXOR));
                            before.add(ASMUtils.pushLong(v1 ^ v2));
                            before.add(new InsnNode(LCMP));
                            before.add(new JumpInsnNode(IFNE, label1));
                            before.add(new VarInsnNode(ALOAD, methodNode.maxLocals + 4));
                            before.add(new JumpInsnNode(IFNULL, label3));
                            before.add(new InsnNode(ACONST_NULL));
                            before.add(new VarInsnNode(ASTORE, methodNode.maxLocals + 4));
                            before.add(new JumpInsnNode(GOTO, label0));
                            before.add(label3);
                        }
                        case 1 -> {
                            before.add(new InsnNode(LCMP));
                            int index = methodNode.maxLocals + 3;
                            before.add(new VarInsnNode(ISTORE, index));
                            before.add(new VarInsnNode(ILOAD, index));
                            before.add(new JumpInsnNode(IFEQ, label0));
                            before.add(new VarInsnNode(ILOAD, index));
                            before.add(ASMUtils.pushInt(-1));
                            before.add(new JumpInsnNode(IF_ICMPNE, label1));
                        }
                        case 2 -> {
                            before.add(new InsnNode(LAND));
                            before.add(ASMUtils.pushLong(0));
                            before.add(new InsnNode(LCMP));
                            before.add(new JumpInsnNode(IFNE, label1));
                            after.add(ASMUtils.pushLong(0));
                            after.add(ASMUtils.pushLong(0));
                            after.add(new InsnNode(LCMP));
                            after.add(ASMUtils.pushInt(-1));
                            after.add(new JumpInsnNode(IF_ICMPNE, label3));
                            after.add(ASMUtils.BuiltInstructions.getThrowNull());
                            after.add(label3);
                        }
                    }

                    this.injectInstructions(methodNode, jump, start, before, after, end);
                });

        try {
            var typeConstructor = Type.class.getDeclaredConstructor(int.class, String.class, int.class, int.class);
            typeConstructor.setAccessible(true);
            methodNode.instructions.insert(new VarInsnNode(ASTORE, methodNode.maxLocals + 4));
            methodNode.instructions.insert(new LdcInsnNode(typeConstructor.newInstance(11, "()Z", 0, 3)));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void injectInstructions(MethodNode methodNode, AbstractInsnNode insn, InsnList start, InsnList before, InsnList after, InsnList end) {
        methodNode.instructions.insert(start);
        methodNode.instructions.insertBefore(insn, before);
        methodNode.instructions.insert(insn, after);
        methodNode.instructions.add(end);
    }
}
