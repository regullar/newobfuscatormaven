package kz.regullar.transformer.impl.number;

import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import kz.regullar.util.SimpleNameFactory;

import java.util.*;

public class FirstNumberTransformer implements Opcodes, TransformHandler {

    private static final SimpleNameFactory simpleNameFactory = new SimpleNameFactory(true);

    private static final int MIN_INSTRUCTIONS_FOR_TRANSFORM = 10;
    private static final int MAX_LOOKAHEAD_STEPS = 3;

    private static final int CHUNK_SIZE = 100;

    private static class TransformContext {
        final Random random;
        final ClassNode classNode;

        String intDecoderName = null;
        String longDecoderName = null;
        String floatDecoderName = null;
        String doubleDecoderName = null;
        String bootstrapName = null;

        final ArrayList<Integer> intValues = new ArrayList<>(32);
        final ArrayList<Integer> intKeys = new ArrayList<>(32);
        final ArrayList<Long> longValues = new ArrayList<>(16);
        final ArrayList<Long> longKeys = new ArrayList<>(16);

        final Map<Integer, Integer> intValueToIndex = new HashMap<>(32);
        final Map<Long, Integer> longValueToIndex = new HashMap<>(16);

        String intValuesFieldName = null;
        String intKeysFieldName = null;
        String longValuesFieldName = null;
        String longKeysFieldName = null;

        int transformedInts = 0;
        int transformedLongs = 0;
        int transformedFloats = 0;
        int transformedDoubles = 0;

        TransformContext(ClassNode classNode) {
            this.classNode = classNode;
            this.random = new Random(classNode.name.hashCode());
        }

        boolean hasTransformations() {
            return transformedInts > 0 || transformedLongs > 0 ||
                    transformedFloats > 0 || transformedDoubles > 0;
        }
    }

    @Override
    public String name() {
        return "First Number Transformer";
    }

    @Override
    public void transform(ClassNode cn, kz.regullar.transformer.api.TransformContext context1) {
        if (cn == null || cn.name == null || (cn.access & ACC_INTERFACE) != 0) return;

        String n = cn.name;
        if (n.startsWith("java/") || n.startsWith("javax/") ||
                n.startsWith("sun/") || n.startsWith("jdk/")) return;

        TransformContext context = new TransformContext(cn);

        List<MethodNode> methods = new ArrayList<>(cn.methods);

        for (MethodNode mn : methods) {
            if (mn.instructions == null || mn.instructions.size() < MIN_INSTRUCTIONS_FOR_TRANSFORM) {
                continue;
            }
            if (NativeAnnotationUtils.shouldSkipObfuscation(cn, mn)) continue;
            replaceConstantsInMethod(mn, context);
        }

        if (context.hasTransformations()) {
            finalizeTransformation(context);
        }
    }

    private void replaceConstantsInMethod(MethodNode mn, TransformContext ctx) {
        InsnList instructions = mn.instructions;

        AbstractInsnNode[] insnArray = instructions.toArray();

        for (AbstractInsnNode insn : insnArray) {
            if (insn instanceof LabelNode || insn instanceof LineNumberNode ||
                    insn instanceof FrameNode) {
                continue;
            }

            if (isArrayIndexOrSize(insn, insnArray)) {
                continue;
            }

            if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Integer) {
                    handleInt(instructions, insn, (Integer) cst, ctx);
                } else if (cst instanceof Long) {
                    handleLong(instructions, insn, (Long) cst, ctx);
                } else if (cst instanceof Float) {
                    handleFloat(instructions, insn, (Float) cst, ctx);
                } else if (cst instanceof Double) {
                    handleDouble(instructions, insn, (Double) cst, ctx);
                }
            } else if (insn instanceof IntInsnNode &&
                    (insn.getOpcode() == BIPUSH || insn.getOpcode() == SIPUSH)) {
                handleInt(instructions, insn, ((IntInsnNode) insn).operand, ctx);
            } else if (insn.getOpcode() >= ICONST_M1 && insn.getOpcode() <= ICONST_5) {
                handleInt(instructions, insn, insn.getOpcode() - ICONST_0, ctx);
            } else if (insn.getOpcode() >= LCONST_0 && insn.getOpcode() <= LCONST_1) {
                handleLong(instructions, insn, (long)(insn.getOpcode() - LCONST_0), ctx);
            }
        }
    }

    private void handleInt(InsnList insns, AbstractInsnNode node, int value, TransformContext ctx) {
        int key = ctx.random.nextInt();
        int encoded = value ^ key;
        int index = addIntToPool(ctx, encoded, key);

        String methodName = ensureIntDecoder(ctx);
        replaceWithIndy(insns, node, index, ctx, methodName, "(I)I");
        ctx.transformedInts++;
    }

    private void handleLong(InsnList insns, AbstractInsnNode node, long value, TransformContext ctx) {
        long key = ctx.random.nextLong();
        long encoded = value ^ key;
        int index = addLongToPool(ctx, encoded, key);

        String methodName = ensureLongDecoder(ctx);
        replaceWithIndy(insns, node, index, ctx, methodName, "(I)J");
        ctx.transformedLongs++;
    }

    private void handleFloat(InsnList insns, AbstractInsnNode node, float value, TransformContext ctx) {
        int key = ctx.random.nextInt();
        int encodedBits = Float.floatToIntBits(value) ^ key;
        int index = addIntToPool(ctx, encodedBits, key);

        String methodName = ensureFloatDecoder(ctx);
        replaceWithIndy(insns, node, index, ctx, methodName, "(I)F");
        ctx.transformedFloats++;
    }

    private void handleDouble(InsnList insns, AbstractInsnNode node, double value, TransformContext ctx) {
        long key = ctx.random.nextLong();
        long encodedBits = Double.doubleToLongBits(value) ^ key;
        int index = addLongToPool(ctx, encodedBits, key);

        String methodName = ensureDoubleDecoder(ctx);
        replaceWithIndy(insns, node, index, ctx, methodName, "(I)D");
        ctx.transformedDoubles++;
    }

    private void replaceWithIndy(InsnList insns, AbstractInsnNode node, int index,
                                 TransformContext ctx, String targetMethodName, String targetMethodDesc) {
        ensureBootstrap(ctx);

        pushInt(insns, index, node);

        Handle bsm = new Handle(
                H_INVOKESTATIC,
                ctx.classNode.name,
                ctx.bootstrapName,
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;",
                false
        );

        Handle targetHandleConstant = new Handle(
                H_INVOKESTATIC,
                ctx.classNode.name,
                targetMethodName,
                targetMethodDesc,
                false
        );

        String indyName = simpleNameFactory.nextName();
        InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                indyName, targetMethodDesc, bsm, targetHandleConstant
        );

        insns.insertBefore(node, indy);
        insns.remove(node);
    }

    private int addIntToPool(TransformContext ctx, int encodedValue, int key) {
        Integer existingIndex = ctx.intValueToIndex.get(encodedValue);
        if (existingIndex != null) {
            return existingIndex;
        }
        int newIndex = ctx.intValues.size();
        ctx.intValues.add(encodedValue);
        ctx.intKeys.add(key);
        ctx.intValueToIndex.put(encodedValue, newIndex);
        return newIndex;
    }

    private int addLongToPool(TransformContext ctx, long encodedValue, long key) {
        Integer existingIndex = ctx.longValueToIndex.get(encodedValue);
        if (existingIndex != null) {
            return existingIndex;
        }
        int newIndex = ctx.longValues.size();
        ctx.longValues.add(encodedValue);
        ctx.longKeys.add(key);
        ctx.longValueToIndex.put(encodedValue, newIndex);
        return newIndex;
    }

    private String ensureIntDecoder(TransformContext ctx) {
        if (ctx.intDecoderName == null) {
            ctx.intDecoderName = simpleNameFactory.nextName();
            ctx.classNode.methods.add(createIntDecoder(ctx, ctx.intDecoderName));
        }
        return ctx.intDecoderName;
    }

    private String ensureLongDecoder(TransformContext ctx) {
        if (ctx.longDecoderName == null) {
            ctx.longDecoderName = simpleNameFactory.nextName();
            ctx.classNode.methods.add(createLongDecoder(ctx, ctx.longDecoderName));
        }
        return ctx.longDecoderName;
    }

    private String ensureFloatDecoder(TransformContext ctx) {
        if (ctx.floatDecoderName == null) {
            ctx.floatDecoderName = simpleNameFactory.nextName();
            ctx.classNode.methods.add(createFloatDecoder(ctx, ctx.floatDecoderName));
        }
        return ctx.floatDecoderName;
    }

    private String ensureDoubleDecoder(TransformContext ctx) {
        if (ctx.doubleDecoderName == null) {
            ctx.doubleDecoderName = simpleNameFactory.nextName();
            ctx.classNode.methods.add(createDoubleDecoder(ctx, ctx.doubleDecoderName));
        }
        return ctx.doubleDecoderName;
    }

    private MethodNode createIntDecoder(TransformContext ctx, String name) {
        MethodNode mn = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "(I)I", null, null);
        InsnList insns = mn.instructions;
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureIntValuesField(ctx), "[I"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(IALOAD));
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureIntKeysField(ctx), "[I"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(IALOAD));
        insns.add(new InsnNode(IXOR));
        insns.add(new InsnNode(IRETURN));
        mn.maxStack = 2; mn.maxLocals = 1;
        return mn;
    }

    private MethodNode createLongDecoder(TransformContext ctx, String name) {
        MethodNode mn = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "(I)J", null, null);
        InsnList insns = mn.instructions;
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureLongValuesField(ctx), "[J"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(LALOAD));
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureLongKeysField(ctx), "[J"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(LALOAD));
        insns.add(new InsnNode(LXOR));
        insns.add(new InsnNode(LRETURN));
        mn.maxStack = 4; mn.maxLocals = 1;
        return mn;
    }

    private MethodNode createFloatDecoder(TransformContext ctx, String name) {
        MethodNode mn = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "(I)F", null, null);
        InsnList insns = mn.instructions;
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureIntValuesField(ctx), "[I"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(IALOAD));
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureIntKeysField(ctx), "[I"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(IALOAD));
        insns.add(new InsnNode(IXOR));
        insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false));
        insns.add(new InsnNode(FRETURN));
        mn.maxStack = 2; mn.maxLocals = 1;
        return mn;
    }

    private MethodNode createDoubleDecoder(TransformContext ctx, String name) {
        MethodNode mn = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, "(I)D", null, null);
        InsnList insns = mn.instructions;
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureLongValuesField(ctx), "[J"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(LALOAD));
        insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, ensureLongKeysField(ctx), "[J"));
        insns.add(new VarInsnNode(ILOAD, 0));
        insns.add(new InsnNode(LALOAD));
        insns.add(new InsnNode(LXOR));
        insns.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false));
        insns.add(new InsnNode(DRETURN));
        mn.maxStack = 4; mn.maxLocals = 1;
        return mn;
    }

    private void finalizeTransformation(TransformContext ctx) {
        MethodNode clinit = findOrCreateClinit(ctx.classNode);
        InsnList init = new InsnList();

        if (!ctx.intValues.isEmpty()) {
            String intValuesField = ensureIntValuesField(ctx);
            String intKeysField = ensureIntKeysField(ctx);

            init.add(createArrayAllocation(ctx.intValues.size(), "[I", ctx.classNode.name, intValuesField));
            init.add(createArrayAllocation(ctx.intKeys.size(), "[I", ctx.classNode.name, intKeysField));

            List<String> intValueInitMethods = createChunkedInitMethods(
                    ctx, ctx.intValues, intValuesField, "[I", true
            );
            List<String> intKeyInitMethods = createChunkedInitMethods(
                    ctx, ctx.intKeys, intKeysField, "[I", true
            );

            for (String methodName : intValueInitMethods) {
                init.add(new MethodInsnNode(INVOKESTATIC, ctx.classNode.name, methodName, "()V", false));
            }
            for (String methodName : intKeyInitMethods) {
                init.add(new MethodInsnNode(INVOKESTATIC, ctx.classNode.name, methodName, "()V", false));
            }
        }

        if (!ctx.longValues.isEmpty()) {
            String longValuesField = ensureLongValuesField(ctx);
            String longKeysField = ensureLongKeysField(ctx);

            init.add(createArrayAllocation(ctx.longValues.size(), "[J", ctx.classNode.name, longValuesField));
            init.add(createArrayAllocation(ctx.longKeys.size(), "[J", ctx.classNode.name, longKeysField));

            List<String> longValueInitMethods = createChunkedInitMethods(
                    ctx, ctx.longValues, longValuesField, "[J", false
            );
            List<String> longKeyInitMethods = createChunkedInitMethods(
                    ctx, ctx.longKeys, longKeysField, "[J", false
            );

            for (String methodName : longValueInitMethods) {
                init.add(new MethodInsnNode(INVOKESTATIC, ctx.classNode.name, methodName, "()V", false));
            }
            for (String methodName : longKeyInitMethods) {
                init.add(new MethodInsnNode(INVOKESTATIC, ctx.classNode.name, methodName, "()V", false));
            }
        }

        clinit.instructions.insert(init);
        clinit.maxStack = Math.max(clinit.maxStack, 8);
    }

    private InsnList createArrayAllocation(int size, String arrayType, String className, String fieldName) {
        InsnList list = new InsnList();
        pushIntToList(list, size);

        if ("[I".equals(arrayType)) {
            list.add(new IntInsnNode(NEWARRAY, T_INT));
        } else if ("[J".equals(arrayType)) {
            list.add(new IntInsnNode(NEWARRAY, T_LONG));
        }

        list.add(new FieldInsnNode(PUTSTATIC, className, fieldName, arrayType));
        return list;
    }

    private <T> List<String> createChunkedInitMethods(TransformContext ctx, ArrayList<T> data,
                                                      String fieldName, String arrayType, boolean isInt) {
        List<String> methodNames = new ArrayList<>();
        int totalSize = data.size();
        int numChunks = (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE;

        for (int chunkIndex = 0; chunkIndex < numChunks; chunkIndex++) {
            int startIdx = chunkIndex * CHUNK_SIZE;
            int endIdx = Math.min(startIdx + CHUNK_SIZE, totalSize);

            String methodName = simpleNameFactory.nextName();
            methodNames.add(methodName);

            MethodNode initMethod = createChunkInitMethod(
                    ctx, methodName, data, fieldName, arrayType, startIdx, endIdx, isInt
            );

            ctx.classNode.methods.add(initMethod);
        }

        return methodNames;
    }

    private <T> MethodNode createChunkInitMethod(TransformContext ctx, String methodName,
                                                 ArrayList<T> data, String fieldName,
                                                 String arrayType, int startIdx, int endIdx, boolean isInt) {
        MethodNode mn = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, methodName, "()V", null, null);
        InsnList insns = mn.instructions;

        for (int i = startIdx; i < endIdx; i++) {
            insns.add(new FieldInsnNode(GETSTATIC, ctx.classNode.name, fieldName, arrayType));
            pushIntToList(insns, i);

            if (isInt) {
                pushIntToList(insns, (Integer) data.get(i));
                insns.add(new InsnNode(IASTORE));
            } else {
                pushLongToList(insns, (Long) data.get(i));
                insns.add(new InsnNode(LASTORE));
            }
        }

        insns.add(new InsnNode(RETURN));
        mn.maxStack = isInt ? 3 : 4;
        mn.maxLocals = 0;
        return mn;
    }

    private String ensureIntValuesField(TransformContext ctx) {
        if (ctx.intValuesFieldName == null) {
            ctx.intValuesFieldName = simpleNameFactory.nextName();
            ctx.classNode.fields.add(new FieldNode(
                    ACC_PRIVATE | ACC_STATIC, ctx.intValuesFieldName, "[I", null, null
            ));
        }
        return ctx.intValuesFieldName;
    }

    private String ensureIntKeysField(TransformContext ctx) {
        if (ctx.intKeysFieldName == null) {
            ctx.intKeysFieldName = simpleNameFactory.nextName();
            ctx.classNode.fields.add(new FieldNode(
                    ACC_PRIVATE | ACC_STATIC, ctx.intKeysFieldName, "[I", null, null
            ));
        }
        return ctx.intKeysFieldName;
    }

    private String ensureLongValuesField(TransformContext ctx) {
        if (ctx.longValuesFieldName == null) {
            ctx.longValuesFieldName = simpleNameFactory.nextName();
            ctx.classNode.fields.add(new FieldNode(
                    ACC_PRIVATE | ACC_STATIC, ctx.longValuesFieldName, "[J", null, null
            ));
        }
        return ctx.longValuesFieldName;
    }

    private String ensureLongKeysField(TransformContext ctx) {
        if (ctx.longKeysFieldName == null) {
            ctx.longKeysFieldName = simpleNameFactory.nextName();
            ctx.classNode.fields.add(new FieldNode(
                    ACC_PRIVATE | ACC_STATIC, ctx.longKeysFieldName, "[J", null, null
            ));
        }
        return ctx.longKeysFieldName;
    }

    private MethodNode findOrCreateClinit(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("<clinit>".equals(m.name)) {
                return m;
            }
        }
        MethodNode clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(RETURN));
        cn.methods.add(clinit);
        return clinit;
    }

    private void ensureBootstrap(TransformContext ctx) {
        if (ctx.bootstrapName == null) {
            ctx.bootstrapName = simpleNameFactory.nextName();
            ctx.classNode.methods.add(createBootstrapMethod(ctx.bootstrapName));
        }
    }

    private MethodNode createBootstrapMethod(String name) {
        String desc = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;";
        MethodNode mn = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, name, desc, null, null);
        InsnList insns = mn.instructions;
        insns.add(new TypeInsnNode(NEW, "java/lang/invoke/ConstantCallSite"));
        insns.add(new InsnNode(DUP));
        insns.add(new VarInsnNode(ALOAD, 3));
        insns.add(new VarInsnNode(ALOAD, 2));
        insns.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/invoke/ConstantCallSite", "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false));
        insns.add(new InsnNode(ARETURN));
        mn.maxStack = 4; mn.maxLocals = 4;
        return mn;
    }

    private boolean isArrayIndexOrSize(AbstractInsnNode node, AbstractInsnNode[] insnArray) {
        int currentIndex = -1;
        for (int i = 0; i < insnArray.length; i++) {
            if (insnArray[i] == node) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1 || currentIndex >= insnArray.length - 1) {
            return false;
        }

        int endIndex = Math.min(currentIndex + MAX_LOOKAHEAD_STEPS + 1, insnArray.length);

        for (int i = currentIndex + 1; i < endIndex; i++) {
            AbstractInsnNode cur = insnArray[i];
            int op = cur.getOpcode();

            if ((op >= IASTORE && op <= SASTORE) || (op >= IALOAD && op <= SALOAD) ||
                    op == ANEWARRAY || op == NEWARRAY || op == MULTIANEWARRAY || op == ARRAYLENGTH) {
                return true;
            }

            if (cur instanceof MethodInsnNode || cur instanceof InvokeDynamicInsnNode) {
                break;
            }
        }
        return false;
    }

    private void pushInt(InsnList insns, int v, AbstractInsnNode before) {
        AbstractInsnNode insnToAdd;
        if (v >= -1 && v <= 5) {
            insnToAdd = new InsnNode(ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            insnToAdd = new IntInsnNode(BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            insnToAdd = new IntInsnNode(SIPUSH, v);
        } else {
            insnToAdd = new LdcInsnNode(v);
        }
        insns.insertBefore(before, insnToAdd);
    }

    private void pushIntToList(InsnList list, int v) {
        if (v >= -1 && v <= 5) {
            list.add(new InsnNode(ICONST_0 + v));
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            list.add(new IntInsnNode(BIPUSH, v));
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            list.add(new IntInsnNode(SIPUSH, v));
        } else {
            list.add(new LdcInsnNode(v));
        }
    }

    private void pushLongToList(InsnList list, long v) {
        if (v == 0L) {
            list.add(new InsnNode(LCONST_0));
        } else if (v == 1L) {
            list.add(new InsnNode(LCONST_1));
        } else {
            list.add(new LdcInsnNode(v));
        }
    }
}