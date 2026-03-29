package kz.regullar.transformer.impl.flow;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.ASMUtils;
import kz.regullar.util.RandomUtils;
import kz.regullar.util.SimpleNameFactory;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class CodexFlowTransformer implements TransformHandler {
    private static final int PRED_ACCESS = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;

    static SimpleNameFactory nameFactory = new SimpleNameFactory(false);
    static String loopField = nameFactory.nextName();

    @Override
    public String name() {
        return "Codex Flow Transformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        List<MethodNode> targetMethods = classNode.methods.stream()
                .filter(m -> !"<clinit>".equals(m.name) && !"<init>".equals(m.name))
                .filter(m -> m.instructions != null && m.instructions.size() != 0)
                .collect(Collectors.toList());

        weaveFlow(classNode, targetMethods);
    }

    private void weaveFlow(ClassNode classNode, List<MethodNode> methods) {
        AtomicBoolean shouldAdd = new AtomicBoolean();
        FieldNode predicate = new FieldNode(PRED_ACCESS, loopField, "I", null, null);

        methods.forEach(method -> {
            InsnList insns = method.instructions;
            AbstractInsnNode entry = insns.getFirst();
            if (entry == null)
                return;

            ThirdControlFlowTransformer.StackHeightZeroFinder finder =
                    new ThirdControlFlowTransformer.StackHeightZeroFinder(method, insns.getLast());
            try {
                finder.execute(false);
            } catch (Exception e) {
                return;
            }

            List<AbstractInsnNode> candidates = finder.getEmptyAt().stream()
                    .filter(node -> node.getOpcode() >= 0)
                    .filter(node -> !(node instanceof LineNumberNode))
                    .filter(node -> node != entry)
                    .collect(Collectors.toList());

            if (candidates.isEmpty())
                return;

            AbstractInsnNode injectionPoint = candidates.get(RandomUtils.getRandomInt(candidates.size()));

            InsnList initField = new InsnList();
            initField.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(6) + 4));
            initField.add(new FieldInsnNode(PUTSTATIC, classNode.name, loopField, "I"));
            insns.insertBefore(entry, initField);

            InsnList loopBlock = constructLoopBlock(classNode);
            insns.insertBefore(injectionPoint, loopBlock);
            shouldAdd.set(true);
        });

        if (shouldAdd.get())
            classNode.fields.add(predicate);
    }

    private InsnList constructLoopBlock(ClassNode classNode) {
        InsnList block = new InsnList();
        LabelNode loopStart = new LabelNode();
        LabelNode exitLoop = new LabelNode();
        LabelNode branchTrue = new LabelNode();
        LabelNode branchFalse = new LabelNode();

        block.add(loopStart);
        block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
        block.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(5) + 3));
        block.add(new JumpInsnNode(IF_ICMPLT, exitLoop));

        block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
        block.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(3) + 1));
        block.add(new InsnNode(IADD));
        block.add(new FieldInsnNode(PUTSTATIC, classNode.name, loopField, "I"));

        block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
        block.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(3) + 2));
        block.add(new JumpInsnNode(IF_ICMPEQ, branchTrue));
        block.add(new JumpInsnNode(GOTO, branchFalse));

        block.add(branchTrue);
        injectSwitch(block, classNode, loopStart);

        block.add(branchFalse);
        block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
        block.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(5) + 2));
        block.add(new InsnNode(IREM));
        block.add(new FieldInsnNode(PUTSTATIC, classNode.name, loopField, "I"));
        block.add(new JumpInsnNode(GOTO, loopStart));

        block.add(exitLoop);
        return block;
    }

    private void injectSwitch(InsnList block, ClassNode classNode, LabelNode loopStart) {
        LabelNode defaultLabel = new LabelNode();
        LabelNode[] caseLabels = new LabelNode[3];
        for (int i = 0; i < caseLabels.length; i++) {
            caseLabels[i] = new LabelNode();
        }

        block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
        block.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false));
        block.add(ASMUtils.getNumberInsn(caseLabels.length));
        block.add(new InsnNode(IREM));
        block.add(new TableSwitchInsnNode(0, caseLabels.length - 1, defaultLabel, caseLabels));

        block.add(defaultLabel);
        block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
        block.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(4) + 2));
        block.add(new InsnNode(ISUB));
        block.add(new FieldInsnNode(PUTSTATIC, classNode.name, loopField, "I"));
        block.add(new JumpInsnNode(GOTO, loopStart));

        for (int i = 0; i < caseLabels.length; i++) {
            block.add(caseLabels[i]);
            block.add(new FieldInsnNode(GETSTATIC, classNode.name, loopField, "I"));
            block.add(ASMUtils.getNumberInsn(i + 2));
            block.add(new InsnNode(IAND));
            block.add(new FieldInsnNode(PUTSTATIC, classNode.name, loopField, "I"));
            block.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(6)));
            block.add(new InsnNode(POP));
            block.add(new JumpInsnNode(GOTO, loopStart));
        }
    }
}
