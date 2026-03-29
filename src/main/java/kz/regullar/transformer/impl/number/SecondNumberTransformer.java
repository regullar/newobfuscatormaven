package kz.regullar.transformer.impl.number;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import kz.regullar.util.RandomUtils;

import java.util.List;

public class SecondNumberTransformer implements Opcodes, TransformHandler {
    boolean aggressive = true;
    boolean ldc = true;

    @Override
    public String name() {
        return "Second number transformer";
    }

    @Override
    public void transform(ClassNode cn, TransformContext context) {
        if (cn == null || cn.name == null || (cn.access & ACC_INTERFACE) != 0) return;
        List<MethodNode> methods = List.copyOf(cn.methods);
        for (MethodNode mn : methods) {
            transformMethod(cn, mn);
        }
    }

    public void transformMethod(ClassNode classNode, MethodNode methodNode) {
        InsnList instructions = methodNode.instructions;
        if (instructions == null || instructions.size() == 0) return;

        for (AbstractInsnNode insn : instructions) {
            if (insn.getOpcode() == BIPUSH || insn.getOpcode() == SIPUSH || insn.getOpcode() == LDC) {
                final InsnList list = new InsnList();

                if (insn.getOpcode() == LDC) {
                    if (!ldc) return;
                    final LdcInsnNode ldc = (LdcInsnNode) insn;
                    if (!(ldc.cst instanceof Number)) return;
                    list.add(obfuscateLDC(ldc));
                } else
                    list.add(obfuscatePUSH(insn.getOpcode(), ((IntInsnNode) insn).operand));

                if (list.size() > 0) {
                    instructions.insertBefore(insn, list);
                    instructions.remove(insn);
                }
            }
        }
    }

    private InsnList obfuscateLDC(final LdcInsnNode node) {
        InsnList list = new InsnList();

        Number number = (Number) node.cst;
        Class<?> numCls = number.getClass();
        int xorKey = RandomUtils.getRandomInt();

        if (numCls == Integer.class) {
            int val = number.intValue();

            if (aggressive) {
                final int orKey = RandomUtils.getRandomInt();
                final int xor = val ^ (xorKey | orKey);

                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new LdcInsnNode(orKey));
                list.add(new InsnNode(IOR));
                list.add(new InsnNode(IXOR));

                for (int i = 0; i < 2; i++)
                    list.add(new InsnNode(INEG));
            } else {
                final int xor = val ^ xorKey;
                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new InsnNode(IXOR));

                for (int i = 0; i < 2; i++)
                    list.add(new InsnNode(INEG));
            }

        } else if (numCls == Long.class) {
            long val = number.longValue();

            if (aggressive) {
                final int orKey = RandomUtils.getRandomInt();

                final long xor = val ^ (xorKey | orKey);

                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new InsnNode(I2L));
                list.add(new LdcInsnNode(orKey));
                list.add(new InsnNode(I2L));
                list.add(new InsnNode(LOR));
                list.add(new InsnNode(LXOR));
            } else {
                final long xor = val ^ (long)xorKey;
                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new InsnNode(I2L));
                list.add(new InsnNode(LXOR));

                for (int i = 0; i < 2; i++)
                    list.add(new InsnNode(LNEG));
            }
        } else if (numCls == Float.class) {
            float origVal = number.floatValue();
            int bits = Float.floatToIntBits(origVal);

            if (aggressive) {
                int orKey = RandomUtils.getRandomInt();
                int xor = bits ^ (xorKey | orKey);

                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new LdcInsnNode(orKey));
                list.add(new InsnNode(IOR));
                list.add(new InsnNode(IXOR));
            } else {
                int xor = bits ^ xorKey;
                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new InsnNode(IXOR));
            }

            list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));

            for (int i = 0; i < 2; i++)
                list.add(new InsnNode(FNEG));
        } else if (numCls == Double.class) {
            double origVal = number.doubleValue();
            long bits = Double.doubleToLongBits(origVal);

            if (aggressive) {
                int orKey = RandomUtils.getRandomInt();
                long xor = bits ^ (xorKey | orKey);

                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
                list.add(new LdcInsnNode(orKey));
                list.add(new InsnNode(IOR));
            } else {
                long xor = bits ^ xorKey;

                list.add(new LdcInsnNode(xor));
                list.add(new LdcInsnNode(xorKey));
            }

            list.add(new InsnNode(I2L));
            list.add(new InsnNode(LXOR));

            list.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));

            for (int i = 0; i < 2; i++)
                list.add(new InsnNode(DNEG));
        }

        return list;
    }

    private InsnList obfuscatePUSH(final int pushOpcode, final int operand) {
        final InsnList list = new InsnList();

        if (aggressive) {
            final int[] orKeys = new int[] {
                    RandomUtils.getRandomInt(1, Short.MAX_VALUE),
                    RandomUtils.getRandomInt(1, Short.MAX_VALUE)
            };

            final int orKey = orKeys[0] | orKeys[1];

            final int xorKey = RandomUtils.getRandomInt(1, Short.MAX_VALUE);

            final int val = operand ^ (xorKey | orKey);

            list.add(new IntInsnNode(pushOpcode, orKeys[0]));
            list.add(new IntInsnNode(pushOpcode, orKeys[1]));
            list.add(new InsnNode(IOR));

            list.add(new IntInsnNode(pushOpcode, xorKey));
            list.add(new InsnNode(IOR));

            list.add(new IntInsnNode(pushOpcode, val));
            list.add(new InsnNode(IXOR));
        } else {
            final int key = RandomUtils.getRandomInt(1, Short.MAX_VALUE);
            final int xorVal = operand ^ key;

            list.add(new IntInsnNode(pushOpcode, key));
            list.add(new IntInsnNode(pushOpcode, xorVal));
            list.add(new InsnNode(IXOR));
        }

        for (int i = 0; i < 2; i++)
            list.add(new InsnNode(INEG));

        return list;
    }
}
