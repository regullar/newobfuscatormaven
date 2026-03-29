package kz.regullar.transformer.impl.flow;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import kz.regullar.util.SimpleNameFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FourthControlFlowTransformer implements TransformHandler, Opcodes {

    private static final SimpleNameFactory nameFactory = new SimpleNameFactory(true);
    private static final int[] ACCESS_ARR = new int[]{0, ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED};

    @Override
    public String name() {
        return "FourthControlFlowTransformer";
    }

    private static final int MAX_METHOD_INSNS = 350;

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        if ((classNode.access & ACC_INTERFACE) != 0) {
            return;
        }

        String flowFieldName = nameFactory.nextName();
        long flowFieldValue = ThreadLocalRandom.current().nextLong();

        int fieldAccess = ACCESS_ARR[ThreadLocalRandom.current().nextInt(ACCESS_ARR.length)] | ACC_STATIC;
        classNode.fields.add(new FieldNode(fieldAccess, flowFieldName, "J", null, flowFieldValue));

        List<MethodNode> targetMethods = classNode.methods.stream()
                .filter(m -> !"<clinit>".equals(m.name) && !"<init>".equals(m.name))
                .filter(m -> !NativeAnnotationUtils.shouldSkipObfuscation(classNode, m))
                .filter(m -> (m.access & ACC_ABSTRACT) == 0 && (m.access & ACC_NATIVE) == 0)
                .filter(m -> m.instructions != null)
                .filter(m -> m.instructions.size() > 0)
                .filter(m -> m.instructions.size() <= MAX_METHOD_INSNS)
                .collect(Collectors.toList());

        for (MethodNode methodNode : targetMethods) {
            transformMethod(classNode, methodNode, flowFieldName, flowFieldValue);
        }
    }

    private void transformMethod(ClassNode classNode, MethodNode methodNode, String flowFieldName, long flowFieldValue) {
        List<AbstractInsnNode> targets = new ArrayList<>();

        for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
            if (isTargetInstruction(insn)) {
                targets.add(insn);
            }
        }

        for (AbstractInsnNode target : targets) {
            addFlowObfuscation(classNode, methodNode, target, flowFieldName, flowFieldValue);
        }
    }

    private boolean isTargetInstruction(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();

        if (opcode >= INVOKEVIRTUAL && opcode <= INVOKEDYNAMIC) {
            return true;
        }

        if (opcode == NEW) {
            return true;
        }

        return insn instanceof FieldInsnNode;
    }

    private void addFlowObfuscation(ClassNode classNode, MethodNode methodNode, AbstractInsnNode target,
                                    String flowFieldName, long flowFieldValue) {
        LabelNode labelNode = new LabelNode();
        LabelNode labelNode2 = new LabelNode();
        LabelNode labelNode3 = new LabelNode();
        LabelNode labelNode4 = new LabelNode();
        LabelNode labelNode5 = new LabelNode();
        LabelNode labelNode6 = new LabelNode();
        LabelNode labelNode7 = new LabelNode();

        InsnList before = new InsnList();
        InsnList after = new InsnList();

        int flowType = ThreadLocalRandom.current().nextInt(2);

        if (flowType == 0) {
            long randomValue;
            do {
                randomValue = ThreadLocalRandom.current().nextLong();
            } while (randomValue == flowFieldValue);

            before.add(new JumpInsnNode(GOTO, labelNode4));
            before.add(labelNode3);
            before.add(new InsnNode(POP));
            before.add(labelNode4);
            before.add(new FieldInsnNode(GETSTATIC, classNode.name, flowFieldName, "J"));
            before.add(pushLong(randomValue));
            before.add(new InsnNode(LCMP));
            before.add(new InsnNode(DUP));
            before.add(new JumpInsnNode(IFEQ, labelNode3));
            before.add(pushInt(flowFieldValue > randomValue ? 1 : -1));
            before.add(new JumpInsnNode(IF_ICMPNE, labelNode6));

            after.add(new JumpInsnNode(GOTO, labelNode7));
            after.add(labelNode6);
            after.add(pushInt(ThreadLocalRandom.current().nextInt()));
            after.add(new JumpInsnNode(GOTO, labelNode3));
            after.add(labelNode7);

        } else {
            before.add(new FieldInsnNode(GETSTATIC, classNode.name, flowFieldName, "J"));
            before.add(new JumpInsnNode(GOTO, labelNode2));
            before.add(labelNode);
            before.add(pushLong(ThreadLocalRandom.current().nextLong()));
            before.add(new InsnNode(LSUB));
            before.add(labelNode2);
            before.add(new InsnNode(L2I));

            int switchSize = 2 + ThreadLocalRandom.current().nextInt(3);
            SwitchBlock targetBlock = new SwitchBlock(createJumpList(labelNode5));
            Supplier<SwitchBlock> dummySupplier = () -> new SwitchBlock(createDummyBlock(labelNode));

            before.add(createLookupSwitch(switchSize, (int) flowFieldValue, targetBlock, dummySupplier, new InsnList()));
            before.add(labelNode5);
        }

        methodNode.instructions.insertBefore(target, before);
        methodNode.instructions.insert(target, after);
    }

    private InsnList createJumpList(LabelNode target) {
        InsnList list = new InsnList();
        list.add(new JumpInsnNode(GOTO, target));
        return list;
    }

    private InsnList createDummyBlock(LabelNode target) {
        InsnList list = new InsnList();
        list.add(pushLong(ThreadLocalRandom.current().nextLong()));
        list.add(new JumpInsnNode(GOTO, target));
        return list;
    }

    private InsnList createLookupSwitch(int switchSize, int targetKey, SwitchBlock targetBlock,
                                        Supplier<SwitchBlock> dummySupplier, InsnList defInstructions) {
        InsnList il = new InsnList();
        LabelNode switchDefaultLabel = new LabelNode();
        LabelNode switchEndLabel = new LabelNode();

        List<SwitchBlock> switchBlocks = IntStream.range(0, switchSize)
                .mapToObj(v -> dummySupplier.get())
                .collect(Collectors.toList());

        List<Integer> keyList = generateUniqueRandomInts(switchSize - 1);
        keyList.add(targetKey);
        Collections.sort(keyList);
        switchBlocks.set(keyList.indexOf(targetKey), targetBlock);

        il.add(new LookupSwitchInsnNode(
                switchDefaultLabel,
                keyList.stream().mapToInt(j -> j).toArray(),
                switchBlocks.stream().map(SwitchBlock::getLabelNode).toArray(LabelNode[]::new)
        ));

        for (SwitchBlock block : switchBlocks) {
            il.add(block.getLabelNode());
            il.add(block.getInsnList());
            il.add(new JumpInsnNode(GOTO, switchEndLabel));
        }

        il.add(switchDefaultLabel);
        il.add(defInstructions);
        il.add(switchEndLabel);

        return il;
    }

    private List<Integer> generateUniqueRandomInts(int size) {
        Set<Integer> set = new HashSet<>();
        while (set.size() < size) {
            set.add(ThreadLocalRandom.current().nextInt());
        }
        return new ArrayList<>(set);
    }

    private InsnList pushInt(int value) {
        InsnList list = new InsnList();
        if (value >= -1 && value <= 5) {
            list.add(new InsnNode(ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            list.add(new IntInsnNode(BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            list.add(new IntInsnNode(SIPUSH, value));
        } else {
            list.add(new LdcInsnNode(value));
        }
        return list;
    }

    private InsnList pushLong(long value) {
        InsnList list = new InsnList();
        if (value == 0L) {
            list.add(new InsnNode(LCONST_0));
        } else if (value == 1L) {
            list.add(new InsnNode(LCONST_1));
        } else {
            list.add(new LdcInsnNode(value));
        }
        return list;
    }

    static class SwitchBlock {
        private final LabelNode labelNode;
        private final InsnList insnList;

        public SwitchBlock() {
            this.labelNode = new LabelNode();
            this.insnList = new InsnList();
            this.insnList.add(pushLong(ThreadLocalRandom.current().nextLong()));
            this.insnList.add(new InsnNode(LDIV));
        }

        public SwitchBlock(InsnList insnList) {
            this.labelNode = new LabelNode();
            this.insnList = insnList;
        }

        public LabelNode getLabelNode() {
            return labelNode;
        }

        public InsnList getInsnList() {
            return insnList;
        }

        private static InsnList pushLong(long value) {
            InsnList list = new InsnList();
            if (value == 0L) {
                list.add(new InsnNode(LCONST_0));
            } else if (value == 1L) {
                list.add(new InsnNode(LCONST_1));
            } else {
                list.add(new LdcInsnNode(value));
            }
            return list;
        }
    }
}