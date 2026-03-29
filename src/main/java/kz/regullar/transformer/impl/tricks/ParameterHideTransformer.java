package kz.regullar.transformer.impl.tricks;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.SimpleNameFactory;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collections;

public final class ParameterHideTransformer implements TransformHandler, Opcodes {

    private int id = 0;
    private final ConcurrentMap<String, ClassNode> helpers = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "parameter-hide";
    }

    private String handlerClass;

    @Override
    public void initialize(TransformContext context) {
        SimpleNameFactory nameFactory = context.getNameFactory();
        this.handlerClass = nameFactory.nextName() + "ph1";
    }

    @Override
    public void transform(ClassNode cn, TransformContext ctx) {
        List<MethodNode> snapshot = new ArrayList<>(cn.methods);

        String packageInternal = packageOf(cn.name);
        String packageRoot = packageRootOf(cn.name);

        for (MethodNode m : snapshot) {
            if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
            if ((m.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;
            if (m.instructions == null || m.instructions.getFirst() == null) continue;

            if (usesOwnPrivateMember(cn, m)) continue;

            if (referencesUnsafeExternal(cn, m, packageInternal, packageRoot)) continue;

            ClassNode helper = ensureHelperForPackage(packageInternal);

            Type[] args = Type.getArgumentTypes(m.desc);
            Type ret = Type.getReturnType(m.desc);
            boolean origIsStatic = (m.access & ACC_STATIC) != 0;

            String hiddenName = m.name + "$$p$" + (id++);
            String hiddenDesc = origIsStatic
                    ? "([Ljava/lang/Object;)" + ret.getDescriptor()
                    : "(L" + cn.name + ";[Ljava/lang/Object;)" + ret.getDescriptor();

            MethodNode hidden = new MethodNode(
                    ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC,
                    hiddenName,
                    hiddenDesc,
                    null,
                    m.exceptions == null ? null : m.exceptions.toArray(new String[0])
            );

            hidden.instructions = m.instructions;
            hidden.tryCatchBlocks = m.tryCatchBlocks;

            hidden.visibleAnnotations = m.visibleAnnotations;
            hidden.invisibleAnnotations = m.invisibleAnnotations;

            hidden.visibleParameterAnnotations = null;
            hidden.invisibleParameterAnnotations = null;
            hidden.parameters = null;
            hidden.localVariables = null;
            hidden.signature = null;
            hidden.attrs = null;

            stripFrames(hidden.instructions);

            int threshold = origIsStatic ? 0 : 1;
            remapLocals(hidden, threshold, 1);

            hidden.instructions.insert(unpackArgs(args, origIsStatic));

            helper.methods.add(hidden);

            MethodNode forward = new MethodNode(
                    m.access,
                    m.name,
                    m.desc,
                    m.signature,
                    m.exceptions == null ? null : m.exceptions.toArray(new String[0])
            );

            InsnList forwardInsns = buildForwarderForHelper(
                    cn.name,
                    helper.name,
                    hiddenName,
                    args,
                    ret,
                    origIsStatic,
                    m.maxLocals
            );

            forward.instructions = forwardInsns;

            forward.visibleAnnotations = m.visibleAnnotations;
            forward.invisibleAnnotations = m.invisibleAnnotations;
            forward.visibleParameterAnnotations = m.visibleParameterAnnotations;
            forward.invisibleParameterAnnotations = m.invisibleParameterAnnotations;
            forward.parameters = m.parameters;

            int arrLocal = Math.max(m.maxLocals, 1);
            forward.maxLocals = Math.max(m.maxLocals, arrLocal + 1);
            forward.maxStack = Math.max(m.maxStack, 20);

            m.instructions = forward.instructions;
            m.tryCatchBlocks = null;
            m.localVariables = null;
            m.maxLocals = forward.maxLocals;
            m.maxStack = forward.maxStack;
        }
    }

    @Override
    public void finalize(TransformContext context) {
        for (ClassNode helper : helpers.values()) {
            context.addClass(helper);
        }
    }

    private ClassNode ensureHelperForPackage(String packageInternal) {
        return helpers.computeIfAbsent(packageInternal, pkg -> {
            String helperName = pkg.isEmpty() ? handlerClass : pkg + "/" + handlerClass;
            ClassNode cn = new ClassNode();
            cn.version = V21;
            cn.access = ACC_PUBLIC | ACC_SUPER | ACC_SYNTHETIC;
            cn.name = helperName;
            cn.superName = "java/lang/Object";

            cn.methods = Collections.synchronizedList(new ArrayList<>());

            MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
            InsnList il = new InsnList();
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            il.add(new InsnNode(RETURN));
            init.instructions = il;
            init.maxLocals = 1;
            init.maxStack = 1;
            cn.methods.add(init);

            return cn;
        });
    }

    private static String packageOf(String internalName) {
        int idx = internalName.lastIndexOf('/');
        if (idx <= 0) return "";
        return internalName.substring(0, idx);
    }

    private static String packageRootOf(String internalName) {
        int first = internalName.indexOf('/');
        if (first < 0) return internalName;
        int second = internalName.indexOf('/', first + 1);
        if (second < 0) return internalName.substring(0, first);
        return internalName.substring(0, second);
    }

    private static boolean usesOwnPrivateMember(ClassNode cn, MethodNode m) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (fin.owner.equals(cn.name)) {
                    FieldNode fn = findField(cn, fin.name, fin.desc);
                    if (fn != null && (fn.access & ACC_PRIVATE) != 0) return true;
                }
            } else if (insn instanceof MethodInsnNode) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (min.owner.equals(cn.name)) {
                    if ("<init>".equals(min.name)) return true;
                    MethodNode target = findMethod(cn, min.name, min.desc);
                    if (target != null && (target.access & ACC_PRIVATE) != 0) return true;
                }
            } else if (insn instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                if (indy.bsmArgs != null) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle) {
                            Handle h = (Handle) arg;
                            if (h.getOwner().equals(cn.name)) {
                                MethodNode target = findMethod(cn, h.getName(), h.getDesc());
                                if (target != null && (target.access & ACC_PRIVATE) != 0) return true;
                                return true;
                            }
                        }
                    }
                }
            } else if (insn instanceof LdcInsnNode) {
                LdcInsnNode ldc = (LdcInsnNode) insn;
                Object cst = ldc.cst;
                if (cst instanceof Handle) {
                    Handle h = (Handle) cst;
                    if (h.getOwner().equals(cn.name)) {
                        MethodNode target = findMethod(cn, h.getName(), h.getDesc());
                        if (target != null && (target.access & ACC_PRIVATE) != 0) return true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean referencesUnsafeExternal(ClassNode cn, MethodNode m, String ownPackage, String ownRoot) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            String owner = null;
            if (insn instanceof FieldInsnNode) owner = ((FieldInsnNode) insn).owner;
            else if (insn instanceof MethodInsnNode) owner = ((MethodInsnNode) insn).owner;
            else if (insn instanceof TypeInsnNode) {
                String desc = ((TypeInsnNode) insn).desc;
                if (desc != null) {
                    if (desc.startsWith("[")) {
                        int p = desc.indexOf('L');
                        int s = desc.indexOf(';');
                        if (p >= 0 && s > p) owner = desc.substring(p + 1, s);
                    } else owner = desc;
                }
            } else if (insn instanceof LdcInsnNode) {
                Object c = ((LdcInsnNode) insn).cst;
                if (c instanceof Type) owner = ((Type) c).getInternalName();
                else if (c instanceof Handle) owner = ((Handle) c).getOwner();
            } else if (insn instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                if (indy.bsmArgs != null) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle) {
                            Handle h = (Handle) arg;
                            owner = h.getOwner();
                            if (!isSafeReference(owner, cn.name, ownPackage, ownRoot)) return true;
                        }
                    }
                }
                continue;
            }

            if (owner != null && !isSafeReference(owner, cn.name, ownPackage, ownRoot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeReference(String ownerInternal, String currentClassInternal, String ownPackage, String ownRoot) {
        if (ownerInternal == null) return false;
        if (ownerInternal.equals(currentClassInternal)) return true;
        if (ownerInternal.startsWith("java/") || ownerInternal.startsWith("javax/")) return true;
        String ownerPkg = packageOf(ownerInternal);
        if (Objects.equals(ownerPkg, ownPackage)) return true;
        String ownerRoot = packageRootOf(ownerInternal);
        if (Objects.equals(ownerRoot, ownRoot)) return true;
        return false;
    }

    private static FieldNode findField(ClassNode cn, String name, String desc) {
        if (cn.fields == null) return null;
        for (FieldNode f : cn.fields) {
            if (f.name.equals(name) && f.desc.equals(desc)) return f;
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        if (cn.methods == null) return null;
        for (MethodNode mm : cn.methods) {
            if (mm.name.equals(name) && mm.desc.equals(desc)) return mm;
        }
        return null;
    }

    private static void stripFrames(InsnList list) {
        for (AbstractInsnNode n = list.getFirst(); n != null; ) {
            AbstractInsnNode next = n.getNext();
            if (n instanceof FrameNode) list.remove(n);
            n = next;
        }
    }

    private static void remapLocals(MethodNode mn, int from, int shift) {
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof VarInsnNode) {
                VarInsnNode v = (VarInsnNode) n;
                if (v.var >= from) v.var += shift;
            } else if (n instanceof IincInsnNode) {
                IincInsnNode i = (IincInsnNode) n;
                if (i.var >= from) i.var += shift;
            }
        }
        mn.localVariables = null;
    }

    private static InsnList unpackArgs(Type[] args, boolean origIsStatic) {
        InsnList list = new InsnList();
        int arrayIndex = origIsStatic ? 0 : 1;
        int targetIndex = arrayIndex + 1;

        for (int i = 0; i < args.length; i++) {
            Type t = args[i];
            list.add(new VarInsnNode(ALOAD, arrayIndex));
            list.add(new LdcInsnNode(i));
            list.add(new InsnNode(AALOAD));

            switch (t.getSort()) {
                case Type.BOOLEAN:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Boolean"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                    list.add(new VarInsnNode(ISTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.CHAR:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Character"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
                    list.add(new VarInsnNode(ISTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.BYTE:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Byte"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
                    list.add(new VarInsnNode(ISTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.SHORT:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Short"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
                    list.add(new VarInsnNode(ISTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.INT:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Integer"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                    list.add(new VarInsnNode(ISTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.FLOAT:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Float"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
                    list.add(new VarInsnNode(FSTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.LONG:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Long"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
                    list.add(new VarInsnNode(LSTORE, targetIndex));
                    targetIndex += 2;
                    break;
                case Type.DOUBLE:
                    list.add(new TypeInsnNode(CHECKCAST, "java/lang/Double"));
                    list.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
                    list.add(new VarInsnNode(DSTORE, targetIndex));
                    targetIndex += 2;
                    break;
                case Type.ARRAY:
                    list.add(new TypeInsnNode(CHECKCAST, t.getDescriptor()));
                    list.add(new VarInsnNode(ASTORE, targetIndex));
                    targetIndex += 1;
                    break;
                case Type.OBJECT:
                default:
                    list.add(new TypeInsnNode(CHECKCAST, t.getInternalName()));
                    list.add(new VarInsnNode(ASTORE, targetIndex));
                    targetIndex += 1;
                    break;
            }
        }

        return list;
    }

    private static InsnList buildForwarderForHelper(
            String ownerClassInternal,
            String helperInternal,
            String hiddenName,
            Type[] args,
            Type ret,
            boolean origIsStatic,
            int baseLocals
    ) {
        InsnList il = new InsnList();

        il.add(new LdcInsnNode(args.length));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));

        int local = origIsStatic ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            Type t = args[i];
            il.add(new InsnNode(DUP));
            il.add(new LdcInsnNode(i));
            il.add(new VarInsnNode(t.getOpcode(ILOAD), local));
            if (t.getSort() < Type.ARRAY) box(il, t);
            il.add(new InsnNode(AASTORE));
            local += t.getSize();
        }

        int arrLocal = Math.max(baseLocals, 1);
        il.add(new VarInsnNode(ASTORE, arrLocal));

        if (!origIsStatic) il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ALOAD, arrLocal));

        String callDesc = origIsStatic
                ? "([Ljava/lang/Object;)" + ret.getDescriptor()
                : "(L" + ownerClassInternal + ";[Ljava/lang/Object;)" + ret.getDescriptor();

        il.add(new MethodInsnNode(INVOKESTATIC, helperInternal, hiddenName, callDesc, false));
        il.add(new InsnNode(ret.getOpcode(IRETURN)));

        return il;
    }

    private static void box(InsnList il, Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)); break;
            case Type.BYTE:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false)); break;
            case Type.CHAR:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false)); break;
            case Type.SHORT:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false)); break;
            case Type.INT:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false)); break;
            case Type.LONG:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)); break;
            case Type.FLOAT:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false)); break;
            case Type.DOUBLE:
                il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)); break;
        }
    }
}
