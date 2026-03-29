package kz.regullar.transformer.impl.flow;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import kz.regullar.util.ASMUtils;
import kz.regullar.util.NativeAnnotationUtils;
import kz.regullar.util.RandomUtils;
import kz.regullar.util.SimpleNameFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class ThirdControlFlowTransformer implements TransformHandler {
    private static final int PRED_ACCESS = ACC_PUBLIC | ACC_STATIC | ACC_FINAL;

    static SimpleNameFactory nameFactory = new SimpleNameFactory(true);
    static String ifElse = nameFactory.nextName();
    static String switchCase = nameFactory.nextName();
    static String goTo = nameFactory.nextName();

    @Override
    public String name() {
        return "Third Flow Transformer";
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

            StackHeightZeroFinder shzf = new StackHeightZeroFinder(mw, insns.getLast());
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

            StackHeightZeroFinder shzf = new StackHeightZeroFinder(mw, insns.getLast());
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
        /**
         * {@link MethodNode} we are checking.
         */
        private MethodNode methodNode;

        /**
         * {@link AbstractInsnNode} opcode which is the breakpoint.
         */
        private AbstractInsnNode breakPoint;

        /**
         * {@link HashSet} of {@link AbstractInsnNode}s where the stack is empty
         */
        private Set<AbstractInsnNode> emptyAt;

        /**
         * Constructor to create a {@link StackHeightZeroFinder} object.
         *
         * @param methodNode the method node we want to check.
         * @param breakPoint the opcode we want to break on.
         */
        public StackHeightZeroFinder(MethodNode methodNode, AbstractInsnNode breakPoint) {
            this.methodNode = methodNode;
            this.breakPoint = breakPoint;
            this.emptyAt = new HashSet<>();
        }

        /**
         * Returns {@link HashSet} of {@link AbstractInsnNode}s where the stack is empty.
         *
         * @return {@link HashSet} of {@link AbstractInsnNode}s where the stack is empty.
         */
        public Set<AbstractInsnNode> getEmptyAt() {
            return this.emptyAt;
        }

        /**
         * Weakly emulates stack execution until no more instructions are left or the breakpoint is reached.
         */
        public void execute(boolean debug) throws Exception {
            int stackSize = 0;
            Set<LabelNode> excHandlers = new HashSet<>();
            methodNode.tryCatchBlocks.forEach(tryCatchBlockNode -> excHandlers.add(tryCatchBlockNode.handler));
            for (int i = 0; i < this.methodNode.instructions.size(); i++) {
                AbstractInsnNode insn = this.methodNode.instructions.get(i);

                if (insn instanceof LabelNode && excHandlers.contains(insn))
                    stackSize = 1;

                if (stackSize < 0)
                    throw new Exception("stackSize < 0");
                if (stackSize == 0)
                    this.emptyAt.add(insn);

                if (this.breakPoint == insn)
                    break;

                switch (insn.getOpcode()) {
                    case ACONST_NULL:
                    case ICONST_M1:
                    case ICONST_0:
                    case ICONST_1:
                    case ICONST_2:
                    case ICONST_3:
                    case ICONST_4:
                    case ICONST_5:
                    case FCONST_0:
                    case FCONST_1:
                    case FCONST_2:
                    case BIPUSH:
                    case SIPUSH:
                    case ILOAD:
                    case FLOAD:
                    case ALOAD:
                    case DUP:
                    case DUP_X1:
                    case DUP_X2:
                    case I2L:
                    case I2D:
                    case F2L:
                    case F2D:
                    case NEW:
                        stackSize++;
                        break;
                    case LDC:
                        LdcInsnNode ldc = (LdcInsnNode) insn;
                        if (ldc.cst instanceof Long || ldc.cst instanceof Double)
                            stackSize++;

                        stackSize++;
                        break;
                    case LCONST_0:
                    case LCONST_1:
                    case DCONST_0:
                    case DCONST_1:
                    case LLOAD:
                    case DLOAD:
                    case DUP2:
                    case DUP2_X1:
                    case DUP2_X2:
                        stackSize += 2;
                        break;
                    case IALOAD:
                    case FALOAD:
                    case AALOAD:
                    case BALOAD:
                    case CALOAD:
                    case SALOAD:
                    case ISTORE:
                    case FSTORE:
                    case ASTORE:
                    case POP:
                    case IADD:
                    case FADD:
                    case ISUB:
                    case FSUB:
                    case IMUL:
                    case FMUL:
                    case IDIV:
                    case FDIV:
                    case IREM:
                    case FREM:
                    case ISHL:
                    case ISHR:
                    case IUSHR:
                    case LSHL:
                    case LSHR:
                    case LUSHR:
                    case IAND:
                    case IOR:
                    case IXOR:
                    case L2I:
                    case L2F:
                    case D2I:
                    case D2F:
                    case FCMPL:
                    case FCMPG:
                    case IFEQ:
                    case IFNE:
                    case IFLT:
                    case IFGE:
                    case IFGT:
                    case IFLE:
                    case TABLESWITCH:
                    case LOOKUPSWITCH:
                    case IRETURN:
                    case FRETURN:
                    case ATHROW:
                    case MONITORENTER:
                    case MONITOREXIT:
                    case IFNULL:
                    case IFNONNULL:
                    case ARETURN:
                        stackSize--;
                        break;
                    case LSTORE:
                    case DSTORE:
                    case POP2:
                    case LADD:
                    case DADD:
                    case LSUB:
                    case DSUB:
                    case LMUL:
                    case DMUL:
                    case LDIV:
                    case DDIV:
                    case LREM:
                    case DREM:
                    case LAND:
                    case LOR:
                    case LXOR:
                    case IF_ICMPEQ:
                    case IF_ICMPNE:
                    case IF_ICMPLT:
                    case IF_ICMPGE:
                    case IF_ICMPGT:
                    case IF_ICMPLE:
                    case IF_ACMPEQ:
                    case IF_ACMPNE:
                    case LRETURN:
                    case DRETURN:
                        stackSize -= 2;
                        break;
                    case IASTORE:
                    case FASTORE:
                    case AASTORE:
                    case BASTORE:
                    case CASTORE:
                    case SASTORE:
                    case LCMP:
                    case DCMPL:
                    case DCMPG:
                        stackSize -= 3;
                        break;
                    case LASTORE:
                    case DASTORE:
                        stackSize -= 4;
                        break;
                    case GETSTATIC:
                        stackSize += doFieldEmulation(((FieldInsnNode) insn).desc, true);
                        break;
                    case PUTSTATIC:
                        stackSize += doFieldEmulation(((FieldInsnNode) insn).desc, false);
                        break;
                    case GETFIELD:
                        stackSize--;
                        stackSize += doFieldEmulation(((FieldInsnNode) insn).desc, true);
                        break;
                    case PUTFIELD:
                        stackSize--;
                        stackSize += doFieldEmulation(((FieldInsnNode) insn).desc, false);
                        break;
                    case INVOKEVIRTUAL:
                    case INVOKESPECIAL:
                    case INVOKEINTERFACE:
                        stackSize--;
                        stackSize += doMethodEmulation(((MethodInsnNode) insn).desc);
                        break;
                    case INVOKESTATIC:
                        stackSize += doMethodEmulation(((MethodInsnNode) insn).desc);
                        break;
                    case INVOKEDYNAMIC:
                        stackSize += doMethodEmulation(((InvokeDynamicInsnNode) insn).desc);
                        break;
                    case MULTIANEWARRAY:
                        stackSize -= ((MultiANewArrayInsnNode) insn).dims;
                        stackSize++;
                        break;
                    case JSR:
                    case RET:
                        throw new Exception("Did not expect JSR/RET instructions");
                    default:
                        break;
                }
            }
        }

        private static int doFieldEmulation(String desc, boolean isGet) {
            Type type = Type.getType(desc);
            int result = (type.getSort() == Type.LONG || type.getSort() == Type.DOUBLE) ? 2 : 1;

            return (isGet) ? result : -result;
        }

        private static int doMethodEmulation(String desc) {
            int result = 0;
            Type[] args = Type.getArgumentTypes(desc);
            Type returnType = Type.getReturnType(desc);
            for (Type type : args) {
                if (type.getSort() == Type.LONG || type.getSort() == Type.DOUBLE)
                    result--;

                result--;
            }
            if (returnType.getSort() == Type.LONG || returnType.getSort() == Type.DOUBLE)
                result++;
            if (returnType.getSort() != Type.VOID)
                result++;

            return result;
        }
    }
}