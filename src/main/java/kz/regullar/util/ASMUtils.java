package kz.regullar.util;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.CodeSizeEvaluator;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class ASMUtils implements Opcodes {

    private ASMUtils() { }

    public static class BuiltInstructions {
        public static InsnList getPrintln(String s) {
            final InsnList insnList = new InsnList();
            insnList.add(new FieldInsnNode(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
            insnList.add(new LdcInsnNode(s));
            insnList.add(new MethodInsnNode(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V"));
            return insnList;
        }

        public static InsnList getThrowNull() {
            final InsnList insnList = new InsnList();
            insnList.add(new InsnNode(ACONST_NULL));
            insnList.add(new InsnNode(ATHROW));
            return insnList;
        }
    }

    public static AbstractInsnNode getRandomLabel(MethodNode mn) {
        List<LabelNode> labels = new ArrayList<>();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof LabelNode) {
                labels.add((LabelNode) insn);
            }
        }

        if (labels.isEmpty()) {
            LabelNode fallback = new LabelNode();
            mn.instructions.insert(fallback);
            return fallback;
        }

        return labels.get(ThreadLocalRandom.current().nextInt(labels.size()));
    }

    public static InsnList getCastConvertInsnList(Type type) {
        final InsnList insnList = new InsnList();

        if(type.getDescriptor().equals("V")) {
            insnList.add(new InsnNode(POP));
            return insnList;
        }

        String methodName = switch (type.getDescriptor()) {
            case "I" -> "intValue";
            case "Z" -> "booleanValue";
            case "B" -> "byteValue";
            case "C" -> "charValue";
            case "S" -> "shortValue";
            case "D" -> "doubleValue";
            case "F" -> "floatValue";
            case "J" -> "longValue";
            default -> null;
        };
        if(methodName != null) insnList.add(getCastConvertInsnList(type, getPrimitiveClassType(type), methodName));
        else insnList.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
        return insnList;
    }

    private static InsnList getCastConvertInsnList(Type type, Type classType, String convertMethodName) {
        return InsnBuilder.createEmpty()
                .insn(new TypeInsnNode(CHECKCAST, classType.getInternalName()))
                .insn(new MethodInsnNode(INVOKEVIRTUAL, classType.getInternalName(), convertMethodName, "()" + type.getDescriptor()))
                .getInsnList();
    }

    private static final Map<String, String> primitives = Map.of(
            "V", "java/lang/Void",
            "I", "java/lang/Integer",
            "Z",  "java/lang/Boolean",
            "B",  "java/lang/Byte",
            "C",  "java/lang/Character",
            "S",  "java/lang/Short",
            "D",  "java/lang/Double",
            "F",  "java/lang/Float",
            "J",  "java/lang/Long"
    );
    public static Type getPrimitiveClassType(Type type) {
        if(!primitives.containsKey(type.getDescriptor()))
            throw new IllegalArgumentException(type + " is not a primitive type");
        return Type.getType("L" + primitives.get(type.getDescriptor()) + ";");
    }

    public static Type getPrimitiveFromClassType(Type type) throws IllegalArgumentException {
        return primitives.entrySet().stream()
                .filter(entry -> entry.getValue().equals(type.getInternalName()))
                .map(Map.Entry::getKey)
                .map(Type::getType)
                .findFirst()
                .orElseThrow(IllegalArgumentException::new);
    }

    public static List<Type> getMethodArguments(String desc) {
        String args = desc.substring(1, desc.indexOf(")"));

        List<Type> typeStrings = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean isClass = false, isArray = false;
        for (char c : args.toCharArray()) {
            if(c == '[') {
                isArray = true;
                continue;
            }

            if(c == 'L') isClass = true;
            if(!isClass) {
                Type type = getPrimitiveClassType(Type.getType(String.valueOf(c)));
                if(isArray) type = Type.getType("[" + type);
                typeStrings.add(type);
                isArray = false;
            }
            else {
                sb.append(c);
                if(c == ';') {
                    typeStrings.add(Type.getType((isArray ? "[" : "") + sb));
                    sb = new StringBuilder();

                    isClass = false;
                    isArray = false;
                }
            }
        }
        return typeStrings;
    }

    public static boolean isClassEligibleToModify(ClassNode classNode) {
        return (classNode.access & ACC_INTERFACE) == 0;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isMethodEligibleToModify(ClassNode classNode, MethodNode methodNode) {
        return isClassEligibleToModify(classNode) && (methodNode.access & ACC_ABSTRACT) == 0;
    }

    public static byte[] toByteArrayDefault(ClassNode classNode) {
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(classWriter);
        return classWriter.toByteArray();
    }

    public static String getName(ClassNode classNode) {
        return classNode.name.replace("/", ".");
    }

    public static String getName(ClassNode classNode, FieldNode fieldNode) {
        return classNode.name + "." + fieldNode.name;
    }

    public static String getName(ClassNode classNode, MethodNode methodNode) {
        return classNode.name + "." + methodNode.name + methodNode.desc;
    }

    public static InsnList arrayToList(AbstractInsnNode[] insns) {
        final InsnList insnList = new InsnList();
        Arrays.stream(insns).forEach(insnList::add);
        return insnList;
    }

    public static boolean isMethodSizeValid(MethodNode methodNode) {
        return getCodeSize(methodNode) <= 65536;
    }

    public static int getCodeSize(MethodNode methodNode) {
        CodeSizeEvaluator cse = new CodeSizeEvaluator(null);
        methodNode.accept(cse);
        return cse.getMaxSize();
    }

    public static MethodNode findOrCreateInit(ClassNode classNode) {
        MethodNode clinit = findMethod(classNode, "<init>", "()V");
        if (clinit == null) {
            clinit = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
            clinit.instructions.add(new InsnNode(RETURN));
            classNode.methods.add(clinit);
        } return clinit;
    }

    public static MethodNode findOrCreateClinit(ClassNode classNode) {
        MethodNode clinit = findMethod(classNode, "<clinit>", "()V");
        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.instructions.add(new InsnNode(RETURN));
            classNode.methods.add(clinit);
        } return clinit;
    }

    public static MethodNode findMethod(ClassNode classNode, String name, String desc) {
        return classNode.methods
                .stream()
                .filter(methodNode -> name.equals(methodNode.name) && desc.equals(methodNode.desc))
                .findAny()
                .orElse(null);
    }

    public static boolean isInvokeMethod(AbstractInsnNode insn, boolean includeInvokeDynamic) {
        return insn.getOpcode() >= INVOKEVIRTUAL && (includeInvokeDynamic ? insn.getOpcode() <= INVOKEDYNAMIC : insn.getOpcode() < INVOKEDYNAMIC);
    }

    public static boolean isFieldInsn(AbstractInsnNode insn) {
        return insn.getOpcode() >= GETSTATIC && insn.getOpcode() <= PUTFIELD;
    }

    public static boolean isIf(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= IFEQ && op <= IF_ACMPNE) || op == IFNULL || op == IFNONNULL;
    }

    public static AbstractInsnNode pushLong(long value) {
        if (value == 0) return new InsnNode(LCONST_0);
        else if (value == 1) return new InsnNode(LCONST_1);
        else return new LdcInsnNode(value);
    }

    public static boolean isPushLong(AbstractInsnNode insn) {
        try {
            getPushedLong(insn);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static long getPushedLong(AbstractInsnNode insn) throws IllegalArgumentException {
        var ex = new IllegalArgumentException("Insn is not a push long instruction");
        return switch (insn.getOpcode()) {
            case LCONST_0 -> 0;
            case LCONST_1 -> 1;
            case LDC -> {
                Object cst = ((LdcInsnNode)insn).cst;
                if (cst instanceof Long)
                    yield (long) cst;
                throw ex;
            }
            default -> throw ex;
        };
    }

    public static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    public static boolean isPushInt(AbstractInsnNode insn) {
        try {
            getPushedInt(insn);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static int getPushedInt(AbstractInsnNode insn) throws IllegalArgumentException {
        var ex = new IllegalArgumentException("Insn is not a push int instruction");
        int op = insn.getOpcode();
        return switch (op) {
            case ICONST_M1, ICONST_0, ICONST_1, ICONST_2, ICONST_3, ICONST_4, ICONST_5 -> op - ICONST_0;
            case BIPUSH, SIPUSH -> ((IntInsnNode)insn).operand;
            case LDC -> {
                Object cst = ((LdcInsnNode)insn).cst;
                if (cst instanceof Integer)
                    yield  (int) cst;
                throw ex;
            }
            default -> throw ex;
        };
    }

    public static AbstractInsnNode getNumberInsn(int value) {
        switch (value) {
            case -1: return new InsnNode(ICONST_M1);
            case 0:  return new InsnNode(ICONST_0);
            case 1:  return new InsnNode(ICONST_1);
            case 2:  return new InsnNode(ICONST_2);
            case 3:  return new InsnNode(ICONST_3);
            case 4:  return new InsnNode(ICONST_4);
            case 5:  return new InsnNode(ICONST_5);
            default:
        }

        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(SIPUSH, value);
        } else {
            return new LdcInsnNode(value);
        }
    }

    public static AbstractInsnNode getNumberInsn(long value) {
        if (value == 0L) return new InsnNode(LCONST_0);
        if (value == 1L) return new InsnNode(LCONST_1);
        return new LdcInsnNode(value);
    }

    public static AbstractInsnNode getNumberInsn(float value) {
        if (value == 0.0f) return new InsnNode(FCONST_0);
        if (value == 1.0f) return new InsnNode(FCONST_1);
        if (value == 2.0f) return new InsnNode(FCONST_2);
        return new LdcInsnNode(value);
    }

    public static AbstractInsnNode getNumberInsn(double value) {
        if (value == 0.0d) return new InsnNode(DCONST_0);
        if (value == 1.0d) return new InsnNode(DCONST_1);
        return new LdcInsnNode(value);
    }

    public static boolean isReturnInsn(AbstractInsnNode insn) {
        if (insn == null) return false;
        int opcode = insn.getOpcode();
        return opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
                || opcode == Opcodes.ATHROW;
    }

    public static boolean isJumpInsn(AbstractInsnNode insn) {
        if (insn == null) return false;
        int opcode = insn.getOpcode();
        return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
                || opcode == Opcodes.GOTO
                || opcode == Opcodes.JSR
                || opcode == Opcodes.IFNULL
                || opcode == Opcodes.IFNONNULL;
    }
}