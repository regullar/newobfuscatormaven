package kz.regullar.transformer.impl.other;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import kz.regullar.util.ASMUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.stream.IntStream;

public class ConstantTransformer implements Opcodes, TransformHandler {
    protected final Random random = new Random();

    @Override
    public String name() {
        return "Constant Transformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        for (MethodNode method : classNode.methods) {
            transformMethod(classNode, method);
        }
        for (FieldNode field : classNode.fields) {
            transformField(classNode, field);
        }
    }

    private void obfuscateNumbers(ClassNode classNode, MethodNode methodNode) {
        Arrays.stream(methodNode.instructions.toArray())
                .filter(insn -> ASMUtils.isPushInt(insn) || ASMUtils.isPushLong(insn))
                .forEach(insn -> {
                    final InsnList insnList = new InsnList();

                    final ValueType valueType = this.getValueType(insn);
                    final long value = switch (valueType) {
                        case INTEGER -> ASMUtils.getPushedInt(insn);
                        case LONG -> ASMUtils.getPushedLong(insn);
                    };

                    int type = random.nextInt(2);

                    final byte shift = 2;
                    final boolean canShift = switch (valueType) {
                        case INTEGER -> this.canShiftLeft(shift, value, Integer.MIN_VALUE);
                        case LONG -> this.canShiftLeft(shift, value, Long.MIN_VALUE);
                    };
                    if(!canShift && type == 1)
                        type--;

                    switch (type) {
                        case 0 -> {
                            int xor1 = random.nextInt(Short.MAX_VALUE);
                            long xor2 = value ^ xor1;
                            switch (valueType) {
                                case INTEGER -> {
                                    insnList.add(ASMUtils.pushInt(xor1));
                                    insnList.add(ASMUtils.pushInt((int) xor2));
                                    insnList.add(new InsnNode(IXOR));
                                }
                                case LONG -> {
                                    insnList.add(ASMUtils.pushLong(xor1));
                                    insnList.add(ASMUtils.pushLong(xor2));
                                    insnList.add(new InsnNode(LXOR));
                                }
                            }
                        }
                        case 1 -> { // Shift
                            switch (valueType) {
                                case INTEGER -> {
                                    insnList.add(ASMUtils.pushInt((int) (value << shift)));
                                    insnList.add(ASMUtils.pushInt(shift));
                                    insnList.add(new InsnNode(IUSHR));
                                }
                                case LONG -> {
                                    insnList.add(ASMUtils.pushLong(value << shift));
                                    insnList.add(ASMUtils.pushInt(shift));
                                    insnList.add(new InsnNode(LUSHR));
                                }
                            }
                        }
                    }
                    methodNode.instructions.insert(insn, insnList);
                    methodNode.instructions.remove(insn);
                });

        Arrays.stream(methodNode.instructions.toArray())
                .filter(ASMUtils::isPushInt)
                .filter(insn -> {
                    int val = ASMUtils.getPushedInt(insn);
                    return val >= 0 && val <= Byte.MAX_VALUE;
                })
                .forEach(insn -> {
                    final InsnList insnList = new InsnList();
                    int value = ASMUtils.getPushedInt(insn);

                    insnList.add(new LdcInsnNode("\0".repeat(value)));
                    insnList.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
                    methodNode.instructions.insert(insn, insnList);
                    methodNode.instructions.remove(insn);
                });
    }

    public void transformMethod(ClassNode classNode, MethodNode methodNode) {
        Arrays.stream(methodNode.instructions.toArray())
                .filter(insn -> insn instanceof LdcInsnNode && ((LdcInsnNode)insn).cst instanceof String)
                .map(insn -> (LdcInsnNode)insn)
                .forEach(ldc -> {
                    methodNode.instructions.insertBefore(ldc, this.convertString(methodNode, (String) ldc.cst));
                    methodNode.instructions.remove(ldc);
                });

        this.obfuscateNumbers(classNode, methodNode);
    }

    public void transformField(ClassNode classNode, FieldNode fieldNode) {
        if(fieldNode.value instanceof String)
            if((fieldNode.access & ACC_STATIC) != 0)
                this.addDirectInstructions(classNode, ASMUtils.findOrCreateClinit(classNode), fieldNode);
            else
                this.addDirectInstructions(classNode, ASMUtils.findOrCreateInit(classNode), fieldNode);
    }

    private void addDirectInstructions(ClassNode classNode, MethodNode methodNode, FieldNode fieldNode) {
        final InsnList insnList = new InsnList();
        insnList.add(new LdcInsnNode(fieldNode.value));
        int opcode;
        if((fieldNode.access & ACC_STATIC) != 0)
            opcode = PUTSTATIC;
        else
            opcode = PUTFIELD;
        insnList.add(new FieldInsnNode(opcode, classNode.name, fieldNode.name, fieldNode.desc));
        methodNode.instructions.insert(insnList);

        fieldNode.value = null;
    }

    private InsnList convertString(MethodNode methodNode, String str) {
        final InsnList insnList = new InsnList();
        final int varIndex = methodNode.maxLocals + 1;

        insnList.add(ASMUtils.pushInt(str.length()));
        insnList.add(new IntInsnNode(NEWARRAY, T_BYTE));
        insnList.add(new VarInsnNode(ASTORE, varIndex));

        ArrayList<Integer> indexes = new ArrayList<>();
        for(int i = 0; i < str.length(); i++) indexes.add(i);
        Collections.shuffle(indexes);

        for(int i = 0; i < str.length(); i++) {
            int index = indexes.get(0);
            indexes.remove(0);
            char ch = str.toCharArray()[index];

            if(i == 0) {
                insnList.add(new VarInsnNode(ALOAD, varIndex));
                insnList.add(ASMUtils.pushInt(index));
                insnList.add(ASMUtils.pushInt((byte)random.nextInt(Character.MAX_VALUE)));
                insnList.add(new InsnNode(BASTORE));
            }

            insnList.add(new VarInsnNode(ALOAD, varIndex));
            insnList.add(ASMUtils.pushInt(index));
            insnList.add(ASMUtils.pushInt(ch));
            insnList.add(new InsnNode(BASTORE));
        }

        insnList.add(new TypeInsnNode(NEW, "java/lang/String"));
        insnList.add(new InsnNode(DUP));
        insnList.add(new VarInsnNode(ALOAD, varIndex));
        insnList.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/String", "<init>", "([B)V", false));
        return insnList;
    }

    private enum ValueType {
        INTEGER, LONG
    }

    private ValueType getValueType(AbstractInsnNode insn) {
        if(ASMUtils.isPushInt(insn)) return ValueType.INTEGER;
        else if(ASMUtils.isPushLong(insn)) return ValueType.LONG;
        throw new IllegalArgumentException("Insn is not a push int/long instruction");
    }

    private boolean canShiftLeft(byte shift, long value, final long minValue) {
        int power = (int) (Math.log(-(minValue >> 1)) / Math.log(2)) + 1;
        return IntStream.range(0, shift).allMatch(i -> (value >> power - i) == 0);
    }

    public enum ConstantObfuscationOption {
        NONE,
        LIGHT,
        FLOW
    }
}
