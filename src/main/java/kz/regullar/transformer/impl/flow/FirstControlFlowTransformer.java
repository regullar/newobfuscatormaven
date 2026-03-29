package kz.regullar.transformer.impl.flow;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import kz.regullar.util.generator.name.zalgo.ZalgoGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import kz.regullar.util.ASMUtils;
import kz.regullar.util.RandomUtils;
import kz.regullar.util.SimpleNameFactory;
import org.objectweb.asm.tree.analysis.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class FirstControlFlowTransformer implements TransformHandler {
    private static final int PRED_ACCESS = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;
    private static ZalgoGenerator generator = new ZalgoGenerator(ZalgoGenerator.Intensity.HEAVY, true, 1);
    static SimpleNameFactory nameFactory = new SimpleNameFactory(true);
    static String ifElse = nameFactory.nextName();
    static String switchCase = nameFactory.nextName();
    static String goTo = nameFactory.nextName();

    @Override
    public String name() {
        return "FirstControlFlowTransformer";
    }

    @Override
    public void transform(ClassNode classWrapper, TransformContext context) {
        List<MethodNode> targetMethods = classWrapper.methods.stream()
//                .filter(m -> !NativeAnnotationUtils.hasNativeAnnotation(m))
                .filter(m -> !"<clinit>".equals(m.name))
                .filter(m -> !NativeAnnotationUtils.shouldSkipObfuscation(classWrapper, m))
                .collect(Collectors.toList());

        addIfElseCase(classWrapper, targetMethods);

        addSwitchCase(classWrapper, targetMethods);

        addGotoExceptions(classWrapper, targetMethods);
    }

    private void addIfElseCase(ClassNode classWrapper, List<MethodNode> methods) {
        AtomicBoolean shouldAdd = new AtomicBoolean();
        FieldNode predicate = new FieldNode(PRED_ACCESS, ifElse, "Z", null, null);

        methods.stream().filter(mw -> mw.instructions.size() != 0).forEach(mw -> {
            InsnList insns = mw.instructions;

            int varIndex = mw.maxLocals;
            mw.maxLocals++;

            AbstractInsnNode[] untouchedList = insns.toArray();
            LabelNode labelNode = exitLabel(mw);
            boolean calledSuper = false;

            StackHeightZeroFinder shzf = new StackHeightZeroFinder(mw, insns.getLast(), classWrapper.name);
            try {
                shzf.execute(false);
            } catch (Exception e) {
                e.printStackTrace();
            }

            Set<AbstractInsnNode> emptyAt = shzf.getEmptyAt();
            for (AbstractInsnNode insn : untouchedList) {
                if ("<init>".equals(mw.name))
                    calledSuper = (insn instanceof MethodInsnNode && insn.getOpcode() == INVOKESPECIAL
                            && insn.getPrevious() instanceof VarInsnNode && ((VarInsnNode) insn.getPrevious()).var == 0);
                if (insn != insns.getFirst() && !(insn instanceof LineNumberNode)) {
                    if ("<init>".equals(mw.name) && !calledSuper)
                        continue;
                    if (emptyAt.contains(insn)) {
                        insns.insertBefore(insn, new VarInsnNode(ILOAD, varIndex));
                        insns.insertBefore(insn, new JumpInsnNode(IFNE, labelNode));
                        shouldAdd.set(true);
                    }
                }
            }

            if (shouldAdd.get()) {
                insns.insert(new VarInsnNode(ISTORE, varIndex));
                insns.insert(new FieldInsnNode(GETSTATIC, classWrapper.name, predicate.name, "Z"));
            }
        });

        if (shouldAdd.get())
            classWrapper.fields.add(predicate);
    }

    private void addSwitchCase(ClassNode classWrapper, List<MethodNode> methods) {
        AtomicBoolean shouldAdd = new AtomicBoolean();
        FieldNode predicate = new FieldNode(PRED_ACCESS, switchCase, "I", null, null);

        methods.stream().filter(mw -> mw.instructions.size() != 0).forEach(mw -> {
            InsnList insns = mw.instructions;

            int varIndex = mw.maxLocals;
            mw.maxLocals++;

            StackHeightZeroFinder shzf = new StackHeightZeroFinder(mw, insns.getLast(), classWrapper.name);
            try {
                shzf.execute(false);
            } catch (Exception e) {
                e.printStackTrace();
            }

            Set<AbstractInsnNode> check = shzf.getEmptyAt();
            ArrayList<AbstractInsnNode> emptyAt = new ArrayList<>(check);

            if (emptyAt.size() <= 5)
                return;

            int nTargets = emptyAt.size() / 2;

            ArrayList<LabelNode> targets = new ArrayList<>();
            for (int i = 0; i < nTargets; i++)
                targets.add(new LabelNode());

            LabelNode back = new LabelNode();
            LabelNode dflt = new LabelNode();
            TableSwitchInsnNode tsin = new TableSwitchInsnNode(0, targets.size() - 1, dflt, targets.toArray(new LabelNode[0]));

            InsnList block = new InsnList();
            block.add(new VarInsnNode(ILOAD, varIndex));
            block.add(new JumpInsnNode(IFEQ, dflt));
            block.add(back);
            block.add(new VarInsnNode(ILOAD, varIndex));
            block.add(tsin);
            block.add(dflt);

            AbstractInsnNode switchTarget = emptyAt.get(RandomUtils.getRandomInt(emptyAt.size()));

            insns.insertBefore(switchTarget, block);

            targets.forEach(target -> {
                AbstractInsnNode here = insns.getLast();

                InsnList landing = new InsnList();
                landing.add(target);
                landing.add(ASMUtils.getNumberInsn(RandomUtils.getRandomInt(nTargets)));
                landing.add(new VarInsnNode(ISTORE, varIndex));
                landing.add(new JumpInsnNode(GOTO, targets.get(RandomUtils.getRandomInt(targets.size()))));

                insns.insert(here, landing);
            });

            insns.insert(new VarInsnNode(ISTORE, varIndex));
            insns.insert(new FieldInsnNode(GETSTATIC, classWrapper.name, predicate.name, "I"));

            shouldAdd.set(true);
        });

        if (shouldAdd.get())
            classWrapper.fields.add(predicate);
    }

    public void addGotoExceptions(ClassNode classNode, List<MethodNode> methods) {
        AtomicBoolean shouldAdd = new AtomicBoolean();
        FieldNode predicate = new FieldNode(PRED_ACCESS, goTo, "Z", null, null);

        methods.stream().filter(mw -> mw.instructions.size() != 0
                && !mw.name.contains("<")).forEach(mw -> {

            InsnList insns = mw.instructions;

            int varIndex = mw.maxLocals;
            mw.maxLocals++;

            for (AbstractInsnNode insn : insns.toArray()) {
                if (insn.getOpcode() == GOTO) {
                    insns.insertBefore(insn, new VarInsnNode(ILOAD, varIndex));
                    insns.insertBefore(insn, new JumpInsnNode(IFEQ, ((JumpInsnNode) insn).label));
                    insns.insert(insn, new InsnNode(ATHROW));
                    insns.insert(insn, new InsnNode(ACONST_NULL));
                    insns.remove(insn);

                    shouldAdd.set(true);
                }
            }

            if (shouldAdd.get()) {
                insns.insert(new VarInsnNode(ISTORE, varIndex));
                insns.insert(new FieldInsnNode(GETSTATIC, classNode.name, predicate.name, "Z"));
            }
        });

        if (shouldAdd.get())
            classNode.fields.add(predicate);
    }

    private static LabelNode exitLabel(MethodNode methodNode) {
        LabelNode lb = new LabelNode();
        LabelNode escapeNode = new LabelNode();

        InsnList insns = methodNode.instructions;
        AbstractInsnNode target = insns.getFirst();

        insns.insertBefore(target, new JumpInsnNode(GOTO, escapeNode));
        insns.insertBefore(target, lb);

        switch (Type.getReturnType(methodNode.desc).getSort()) {
            case Type.VOID:
                insns.insertBefore(target, new InsnNode(RETURN));
                break;
            case Type.BOOLEAN:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomInt(2)));
                insns.insertBefore(target, new InsnNode(IRETURN));
                break;
            case Type.CHAR:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils
                        .getRandomInt(Character.MAX_VALUE + 1)));
                insns.insertBefore(target, new InsnNode(IRETURN));
                break;
            case Type.BYTE:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomInt(Byte.MAX_VALUE + 1)));
                insns.insertBefore(target, new InsnNode(IRETURN));
                break;
            case Type.SHORT:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomInt(Short.MAX_VALUE + 1)));
                insns.insertBefore(target, new InsnNode(IRETURN));
                break;
            case Type.INT:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomInt()));
                insns.insertBefore(target, new InsnNode(IRETURN));
                break;
            case Type.LONG:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomLong()));
                insns.insertBefore(target, new InsnNode(LRETURN));
                break;
            case Type.FLOAT:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomFloat()));
                insns.insertBefore(target, new InsnNode(FRETURN));
                break;
            case Type.DOUBLE:
                insns.insertBefore(target, ASMUtils.getNumberInsn(RandomUtils.getRandomDouble()));
                insns.insertBefore(target, new InsnNode(DRETURN));
                break;
            default:
                insns.insertBefore(target, new InsnNode(ACONST_NULL));
                insns.insertBefore(target, new InsnNode(ARETURN));
                break;
        }
        insns.insertBefore(target, escapeNode);

        return lb;
    }

    public static class StackHeightZeroFinder implements Opcodes {

        private final MethodNode methodNode;
        private final AbstractInsnNode breakPoint;
        private final Set<AbstractInsnNode> emptyAt = new HashSet<>();
        private final String owner;

        public StackHeightZeroFinder(MethodNode methodNode,
                                     AbstractInsnNode breakPoint,
                                     String owner) {
            this.methodNode = methodNode;
            this.breakPoint = breakPoint;
            this.owner = owner == null ? "" : owner;
        }

        public Set<AbstractInsnNode> getEmptyAt() {
            return emptyAt;
        }

        public void execute(boolean debug) throws Exception {
            methodNode.maxStack = Math.max(methodNode.maxStack, 32);

            Analyzer<BasicValue> analyzer =
                    new Analyzer<>(new BasicInterpreter());

            Frame<BasicValue>[] frames;
            try {
                frames = analyzer.analyze(owner, methodNode);
            } catch (AnalyzerException e) {
                throw new Exception("Stack analysis failed (BasicInterpreter)", e);
            }

            InsnList insns = methodNode.instructions;
            int size = insns.size();

            for (int i = 0; i < size; i++) {
                AbstractInsnNode insn = insns.get(i);
                Frame<BasicValue> frame = frames[i];

                if (frame != null && frame.getStackSize() == 0) {
                    emptyAt.add(insn);
                }

                if (insn == breakPoint) {
                    break;
                }
            }
        }
    }
}