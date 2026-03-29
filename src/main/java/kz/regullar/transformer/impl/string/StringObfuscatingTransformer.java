package kz.regullar.transformer.impl.string;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import org.objectweb.asm.*;
        import org.objectweb.asm.tree.*;
        import kz.regullar.util.SimpleNameFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class StringObfuscatingTransformer implements TransformHandler, Opcodes {
    private String cacheFieldName;
    private String helperClassName;
    private String decryptMethodName;
    private String putHashMethodName;

    private static final String HELPER_NATIVE_METHOD_NAME = "meow";
    private static final String HELPER_NATIVE_METHOD_DESC = "([B)[B";
    private static final String HELPER_LOADER_METHOD_NAME = "loadNativeLibrary";
    private static final String HELPER_RESOURCE_NAME = "/StringPool.dll";

    @Override
    public String name() {
        return "String Transformer";
    }

    @Override
    public void initialize(TransformContext context) {
        SimpleNameFactory nameFactory = context.getNameFactory();
        this.cacheFieldName = nameFactory.nextName();
        this.helperClassName = "regullarTech/" + "StringPool";
        this.decryptMethodName = nameFactory.nextName();
        this.putHashMethodName = nameFactory.nextName();
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        boolean isInterface = (classNode.access & ACC_INTERFACE) != 0;
        if (isInterface) {
            return;
        }

        List<ObfuscatedString> classStrings = new ArrayList<>();
        SimpleNameFactory nameFactory = context.getNameFactory();

        for (MethodNode method : classNode.methods) {
            if (NativeAnnotationUtils.shouldSkipObfuscation(classNode, method)) continue;
            transformMethod(method, classNode, classStrings, nameFactory);
        }

        if (classStrings.isEmpty()) {
            return;
        }

        addFields(classNode, classStrings);

        MethodNode clinit = null;
        for (MethodNode method : classNode.methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                clinit = method;
                break;
            }
        }
        if (clinit == null) {
            clinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            classNode.methods.add(clinit);
        }

        List<MethodNode> initChunks = createInitChunks(classNode, classStrings, nameFactory);
        for (MethodNode chunk : initChunks) {
            classNode.methods.add(chunk);
        }

        InsnList clinitCalls = new InsnList();
        for (MethodNode chunk : initChunks) {
            clinitCalls.add(new MethodInsnNode(INVOKESTATIC, classNode.name, chunk.name, chunk.desc, false));
        }

        if (clinit.instructions.getFirst() == null) {
            clinit.instructions.add(clinitCalls);
            clinit.instructions.add(new InsnNode(RETURN));
        } else {
            clinit.instructions.insertBefore(clinit.instructions.getFirst(), clinitCalls);
        }

        clinit.maxStack = Math.max(clinit.maxStack, 1);
        clinit.maxLocals = Math.max(clinit.maxLocals, 0);
    }

    @Override
    public void finalize(TransformContext context) {
        ClassNode helperClass = createHuesosClass(
                "regullarTech/StringPool",
                this.putHashMethodName,
                this.decryptMethodName
        );
        context.addClass(helperClass);

        try {
            byte[] bytes = Files.readAllBytes(Path.of("StringPool.dll"));
            context.addResource(HELPER_RESOURCE_NAME.substring(1), bytes);
        } catch (IOException e) {
            System.err.println("Ошибка: не удалось прочитать StringPool.dll. Убедитесь, что файл существует.");
            e.printStackTrace();
        }
    }

    private void transformMethod(MethodNode method, ClassNode classNode, List<ObfuscatedString> classStrings, SimpleNameFactory nameFactory) {        if (method.instructions == null) return;

        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (AbstractInsnNode insn : instructions) {
            if (insn.getOpcode() == LDC) {
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (ldc.cst instanceof String && !((String) ldc.cst).isEmpty()) {
                    String originalString = (String) ldc.cst;

                    String cacheKey = "str_" + Integer.toHexString(originalString.hashCode());
                    try {
                        byte[] originalBytes = originalString.getBytes("UTF-8");

                        ByteBuffer buffer = ByteBuffer.allocate(4);
                        buffer.putInt(originalBytes.length);
                        byte[] lengthBytes = buffer.array();

                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        digest.update(lengthBytes);
                        byte[] hash = digest.digest();
                        byte[] key = hash;
                        byte[] nonce = new byte[12];
                        System.arraycopy(hash, 0, nonce, 0, Math.min(key.length, 12));

                        Cipher cipher = Cipher.getInstance("ChaCha20");
                        SecretKeySpec keySpec = new SecretKeySpec(key, "ChaCha20");
                        ChaCha20ParameterSpec paramSpec = new ChaCha20ParameterSpec(nonce, 0);
                        cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);
                        byte[] encrypted = cipher.doFinal(originalBytes);
                        String fieldName = nameFactory.nextName();
                        classStrings.add(new ObfuscatedString(fieldName, encrypted));

                        InsnList newInstructions = new InsnList();
                        newInstructions.add(new FieldInsnNode(GETSTATIC, classNode.name, cacheFieldName, "Ljava/util/Map;"));
                        newInstructions.add(new LdcInsnNode(cacheKey));
                        newInstructions.add(new FieldInsnNode(GETSTATIC, classNode.name, fieldName, "[B"));
                        newInstructions.add(new MethodInsnNode(INVOKESTATIC,
                                "regullarTech/StringPool",
                                this.putHashMethodName,
                                "(Ljava/util/Map;Ljava/lang/String;[B)Ljava/lang/String;",
                                false));

                        method.instructions.insertBefore(ldc, newInstructions);
                        method.instructions.remove(ldc);
                    } catch (Exception e) {
                        System.err.println("Ошибка при обфускации строки: " + originalString);
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void addFields(ClassNode classNode, List<ObfuscatedString> classStrings) {
        classNode.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, this.cacheFieldName, "Ljava/util/Map;", null, null));

        for (ObfuscatedString os : classStrings) {
            classNode.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC, os.fieldName, "[B", null, null));
        }
    }

    private List<MethodNode> createInitChunks(ClassNode classNode,
                                              List<ObfuscatedString> classStrings,
                                              SimpleNameFactory nameFactory) {

        final int MAX_INSTRUCTIONS_PER_CHUNK = 8000;

        List<MethodNode> chunks = new ArrayList<>();
        MethodNode currentChunk = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                nameFactory.nextName(), "()V", null, null);

        for (ObfuscatedString os : classStrings) {
            if (currentChunk.instructions.size() > MAX_INSTRUCTIONS_PER_CHUNK) {
                currentChunk.instructions.add(new InsnNode(RETURN));
                chunks.add(currentChunk);
                currentChunk = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                        nameFactory.nextName(), "()V", null, null);
            }

            pushByteArray(currentChunk, os.encryptedData);
            currentChunk.visitFieldInsn(PUTSTATIC, classNode.name, os.fieldName, "[B");
        }

        currentChunk.visitTypeInsn(NEW, "java/util/concurrent/ConcurrentHashMap");
        currentChunk.visitInsn(DUP);
        currentChunk.visitMethodInsn(INVOKESPECIAL, "java/util/concurrent/ConcurrentHashMap", "<init>", "()V", false);
        currentChunk.visitFieldInsn(PUTSTATIC, classNode.name, this.cacheFieldName, "Ljava/util/Map;");

        currentChunk.instructions.add(new InsnNode(RETURN));
        chunks.add(currentChunk);

        return chunks;
    }

    private void pushByteArray(MethodNode mv, byte[] data) {
        mv.visitIntInsn(SIPUSH, data.length);
        mv.visitIntInsn(NEWARRAY, T_BYTE);
        for (int i = 0; i < data.length; i++) {
            mv.visitInsn(DUP);
            mv.visitIntInsn(SIPUSH, i);
            mv.visitIntInsn(BIPUSH, data[i]);
            mv.visitInsn(BASTORE);
        }
    }

    public ClassNode createHuesosClass(String helperClassName, String putHashName, String decryptName) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        cn.name = "regullarTech/StringPool";
        cn.superName = "java/lang/Object";
        cn.sourceFile = "StringPool.java";
        cn.signature = null;

        {
            MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            Label label0 = new Label();
            init.visitLabel(label0);
            init.visitLineNumber(11, label0);
            init.visitVarInsn(ALOAD, 0);
            init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(RETURN);
            Label label1 = new Label();
            init.visitLabel(label1);
            init.visitLocalVariable("this", "LregullarTech/StringPool;", null, label0, label1, 0);
            init.visitMaxs(1, 1);
            init.visitEnd();
            cn.methods.add(init);
        }
        {
            MethodNode loadNativeLib = new MethodNode(ACC_PRIVATE | ACC_STATIC, "loadNativeLibrary", "(Ljava/lang/String;)V", null, null);
            loadNativeLib.visitCode();
            Label label0 = new Label();
            Label label1 = new Label();
            Label label2 = new Label();
            loadNativeLib.visitTryCatchBlock(label0, label1, label2, "java/lang/Throwable");
            Label label3 = new Label();
            Label label4 = new Label();
            Label label5 = new Label();
            loadNativeLib.visitTryCatchBlock(label3, label4, label5, "java/lang/Throwable");
            Label label6 = new Label();
            Label label7 = new Label();
            Label label8 = new Label();
            loadNativeLib.visitTryCatchBlock(label6, label7, label8, "java/lang/Throwable");
            Label label9 = new Label();
            Label label10 = new Label();
            Label label11 = new Label();
            loadNativeLib.visitTryCatchBlock(label9, label10, label11, "java/lang/Throwable");
            Label label12 = new Label();
            Label label13 = new Label();
            Label label14 = new Label();
            loadNativeLib.visitTryCatchBlock(label12, label13, label14, "java/io/IOException");
            loadNativeLib.visitLabel(label12);
            loadNativeLib.visitLineNumber(14, label12);
            loadNativeLib.visitLdcInsn(Type.getType("L" + helperClassName + ";"));
            loadNativeLib.visitVarInsn(ALOAD, 0);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
            loadNativeLib.visitVarInsn(ASTORE, 1);
            loadNativeLib.visitLabel(label6);
            loadNativeLib.visitLineNumber(17, label6);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            Label label15 = new Label();
            loadNativeLib.visitJumpInsn(IFNONNULL, label15);
            Label label16 = new Label();
            loadNativeLib.visitLabel(label16);
            loadNativeLib.visitLineNumber(18, label16);
            loadNativeLib.visitTypeInsn(NEW, "java/lang/UnsatisfiedLinkError");
            loadNativeLib.visitInsn(DUP);
            loadNativeLib.visitVarInsn(ALOAD, 0);
            loadNativeLib.visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), new Object[]{"Resource not found: \u0001"});
            loadNativeLib.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsatisfiedLinkError", "<init>", "(Ljava/lang/String;)V", false);
            loadNativeLib.visitInsn(ATHROW);
            loadNativeLib.visitLabel(label15);
            loadNativeLib.visitLineNumber(21, label15);
            loadNativeLib.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/io/InputStream"}, 0, null);
            loadNativeLib.visitLdcInsn("StringPool");
            loadNativeLib.visitLdcInsn(".dll");
            loadNativeLib.visitInsn(ICONST_0);
            loadNativeLib.visitTypeInsn(ANEWARRAY, "java/nio/file/attribute/FileAttribute");
            loadNativeLib.visitMethodInsn(INVOKESTATIC, "java/nio/file/Files", "createTempFile", "(Ljava/lang/String;Ljava/lang/String;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;", false);
            loadNativeLib.visitMethodInsn(INVOKEINTERFACE, "java/nio/file/Path", "toFile", "()Ljava/io/File;", true);
            loadNativeLib.visitVarInsn(ASTORE, 2);
            Label label17 = new Label();
            loadNativeLib.visitLabel(label17);
            loadNativeLib.visitLineNumber(22, label17);
            loadNativeLib.visitVarInsn(ALOAD, 2);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "deleteOnExit", "()V", false);
            Label label18 = new Label();
            loadNativeLib.visitLabel(label18);
            loadNativeLib.visitLineNumber(23, label18);
            loadNativeLib.visitTypeInsn(NEW, "java/io/FileOutputStream");
            loadNativeLib.visitInsn(DUP);
            loadNativeLib.visitVarInsn(ALOAD, 2);
            loadNativeLib.visitMethodInsn(INVOKESPECIAL, "java/io/FileOutputStream", "<init>", "(Ljava/io/File;)V", false);
            loadNativeLib.visitVarInsn(ASTORE, 3);
            loadNativeLib.visitLabel(label0);
            loadNativeLib.visitLineNumber(26, label0);
            loadNativeLib.visitIntInsn(SIPUSH, 8192);
            loadNativeLib.visitIntInsn(NEWARRAY, T_BYTE);
            loadNativeLib.visitVarInsn(ASTORE, 4);
            Label label19 = new Label();
            loadNativeLib.visitLabel(label19);
            loadNativeLib.visitLineNumber(29, label19);
            loadNativeLib.visitFrame(Opcodes.F_APPEND,3, new Object[] {"java/io/File", "java/io/FileOutputStream", "[B"}, 0, null);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            loadNativeLib.visitVarInsn(ALOAD, 4);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/InputStream", "read", "([B)I", false);
            loadNativeLib.visitInsn(DUP);
            loadNativeLib.visitVarInsn(ISTORE, 5);
            Label label20 = new Label();
            loadNativeLib.visitLabel(label20);
            loadNativeLib.visitInsn(ICONST_M1);
            loadNativeLib.visitJumpInsn(IF_ICMPEQ, label1);
            Label label21 = new Label();
            loadNativeLib.visitLabel(label21);
            loadNativeLib.visitLineNumber(30, label21);
            loadNativeLib.visitVarInsn(ALOAD, 3);
            loadNativeLib.visitVarInsn(ALOAD, 4);
            loadNativeLib.visitInsn(ICONST_0);
            loadNativeLib.visitVarInsn(ILOAD, 5);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/FileOutputStream", "write", "([BII)V", false);
            loadNativeLib.visitJumpInsn(GOTO, label19);
            loadNativeLib.visitLabel(label1);
            loadNativeLib.visitLineNumber(40, label1);
            loadNativeLib.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
            Label label22 = new Label();
            loadNativeLib.visitJumpInsn(GOTO, label22);
            loadNativeLib.visitLabel(label2);
            loadNativeLib.visitLineNumber(32, label2);
            loadNativeLib.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
            loadNativeLib.visitVarInsn(ASTORE, 4);
            loadNativeLib.visitLabel(label3);
            loadNativeLib.visitLineNumber(34, label3);
            loadNativeLib.visitVarInsn(ALOAD, 3);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/FileOutputStream", "close", "()V", false);
            loadNativeLib.visitLabel(label4);
            loadNativeLib.visitLineNumber(37, label4);
            Label label23 = new Label();
            loadNativeLib.visitJumpInsn(GOTO, label23);
            loadNativeLib.visitLabel(label5);
            loadNativeLib.visitLineNumber(35, label5);
            loadNativeLib.visitFrame(Opcodes.F_FULL, 5, new Object[] {"java/lang/String", "java/io/InputStream", "java/io/File", "java/io/FileOutputStream", "java/lang/Throwable"}, 1, new Object[] {"java/lang/Throwable"});
            loadNativeLib.visitVarInsn(ASTORE, 5);
            Label label24 = new Label();
            loadNativeLib.visitLabel(label24);
            loadNativeLib.visitLineNumber(36, label24);
            loadNativeLib.visitVarInsn(ALOAD, 4);
            loadNativeLib.visitVarInsn(ALOAD, 5);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "addSuppressed", "(Ljava/lang/Throwable;)V", false);
            loadNativeLib.visitLabel(label23);
            loadNativeLib.visitLineNumber(39, label23);
            loadNativeLib.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            loadNativeLib.visitVarInsn(ALOAD, 4);
            loadNativeLib.visitInsn(ATHROW);
            loadNativeLib.visitLabel(label22);
            loadNativeLib.visitLineNumber(42, label22);
            loadNativeLib.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
            loadNativeLib.visitVarInsn(ALOAD, 3);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/FileOutputStream", "close", "()V", false);
            Label label25 = new Label();
            loadNativeLib.visitLabel(label25);
            loadNativeLib.visitLineNumber(43, label25);
            loadNativeLib.visitVarInsn(ALOAD, 2);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/File", "getAbsolutePath", "()Ljava/lang/String;", false);
            loadNativeLib.visitMethodInsn(INVOKESTATIC, "java/lang/System", "load", "(Ljava/lang/String;)V", false);
            loadNativeLib.visitLabel(label7);
            loadNativeLib.visitLineNumber(54, label7);
            Label label26 = new Label();
            loadNativeLib.visitJumpInsn(GOTO, label26);
            loadNativeLib.visitLabel(label8);
            loadNativeLib.visitLineNumber(44, label8);
            loadNativeLib.visitFrame(Opcodes.F_FULL, 2, new Object[] {"java/lang/String", "java/io/InputStream"}, 1, new Object[] {"java/lang/Throwable"});
            loadNativeLib.visitVarInsn(ASTORE, 2);
            Label label27 = new Label();
            loadNativeLib.visitLabel(label27);
            loadNativeLib.visitLineNumber(45, label27);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            Label label28 = new Label();
            loadNativeLib.visitJumpInsn(IFNULL, label28);
            loadNativeLib.visitLabel(label9);
            loadNativeLib.visitLineNumber(47, label9);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
            loadNativeLib.visitLabel(label10);
            loadNativeLib.visitLineNumber(50, label10);
            loadNativeLib.visitJumpInsn(GOTO, label28);
            loadNativeLib.visitLabel(label11);
            loadNativeLib.visitLineNumber(48, label11);
            loadNativeLib.visitFrame(Opcodes.F_FULL, 3, new Object[] {"java/lang/String", "java/io/InputStream", "java/lang/Throwable"}, 1, new Object[] {"java/lang/Throwable"});
            loadNativeLib.visitVarInsn(ASTORE, 3);
            Label label29 = new Label();
            loadNativeLib.visitLabel(label29);
            loadNativeLib.visitLineNumber(49, label29);
            loadNativeLib.visitVarInsn(ALOAD, 2);
            loadNativeLib.visitVarInsn(ALOAD, 3);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "addSuppressed", "(Ljava/lang/Throwable;)V", false);
            loadNativeLib.visitLabel(label28);
            loadNativeLib.visitLineNumber(53, label28);
            loadNativeLib.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            loadNativeLib.visitVarInsn(ALOAD, 2);
            loadNativeLib.visitInsn(ATHROW);
            loadNativeLib.visitLabel(label26);
            loadNativeLib.visitLineNumber(56, label26);
            loadNativeLib.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            loadNativeLib.visitJumpInsn(IFNULL, label13);
            Label label30 = new Label();
            loadNativeLib.visitLabel(label30);
            loadNativeLib.visitLineNumber(57, label30);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
            loadNativeLib.visitLabel(label13);
            loadNativeLib.visitLineNumber(62, label13);
            loadNativeLib.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
            Label label31 = new Label();
            loadNativeLib.visitJumpInsn(GOTO, label31);
            loadNativeLib.visitLabel(label14);
            loadNativeLib.visitLineNumber(60, label14);
            loadNativeLib.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/io/IOException"});
            loadNativeLib.visitVarInsn(ASTORE, 1);
            Label label32 = new Label();
            loadNativeLib.visitLabel(label32);
            loadNativeLib.visitLineNumber(61, label32);
            loadNativeLib.visitTypeInsn(NEW, "java/lang/UnsatisfiedLinkError");
            loadNativeLib.visitInsn(DUP);
            loadNativeLib.visitVarInsn(ALOAD, 1);
            loadNativeLib.visitMethodInsn(INVOKEVIRTUAL, "java/io/IOException", "getMessage", "()Ljava/lang/String;", false);
            loadNativeLib.visitInvokeDynamicInsn("makeConcatWithConstants", "(Ljava/lang/String;)Ljava/lang/String;", new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false), new Object[]{"Failed to load native library: \u0001"});
            loadNativeLib.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsatisfiedLinkError", "<init>", "(Ljava/lang/String;)V", false);
            loadNativeLib.visitInsn(ATHROW);
            loadNativeLib.visitLabel(label31);
            loadNativeLib.visitLineNumber(63, label31);
            loadNativeLib.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            loadNativeLib.visitInsn(RETURN);
            Label label33 = new Label();
            loadNativeLib.visitLabel(label33);
            loadNativeLib.visitLocalVariable("buffer", "[B", null, label19, label1, 4);
            loadNativeLib.visitLocalVariable("len", "I", null, label20, label1, 5);
            loadNativeLib.visitLocalVariable("var7", "Ljava/lang/Throwable;", null, label24, label23, 5);
            loadNativeLib.visitLocalVariable("var8", "Ljava/lang/Throwable;", null, label3, label22, 4);
            loadNativeLib.visitLocalVariable("tempFile", "Ljava/io/File;", null, label17, label7, 2);
            loadNativeLib.visitLocalVariable("out", "Ljava/io/FileOutputStream;", null, label0, label7, 3);
            loadNativeLib.visitLocalVariable("var6", "Ljava/lang/Throwable;", null, label29, label28, 3);
            loadNativeLib.visitLocalVariable("var9", "Ljava/lang/Throwable;", null, label27, label26, 2);
            loadNativeLib.visitLocalVariable("in", "Ljava/io/InputStream;", null, label6, label13, 1);
            loadNativeLib.visitLocalVariable("var10", "Ljava/io/IOException;", null, label32, label31, 1);
            loadNativeLib.visitLocalVariable("resourcePath", "Ljava/lang/String;", null, label12, label33, 0);
            loadNativeLib.visitMaxs(4, 6);
            loadNativeLib.visitEnd();
            cn.methods.add(loadNativeLib);
        }
        {
            MethodNode meow = new MethodNode(ACC_PUBLIC | ACC_STATIC | ACC_NATIVE, "meow", "([B)[B", null, null);
            meow.visitEnd();
            cn.methods.add(meow);
        }
        {
            MethodNode dec = new MethodNode(ACC_PUBLIC | ACC_STATIC, decryptName, "([B)Ljava/lang/String;", null, null);
            dec.visitCode();
            Label label0 = new Label();
            dec.visitLabel(label0);
            dec.visitLineNumber(68, label0);
            dec.visitVarInsn(ALOAD, 0);
            dec.visitMethodInsn(INVOKESTATIC, helperClassName, HELPER_NATIVE_METHOD_NAME, HELPER_NATIVE_METHOD_DESC, false);
            dec.visitVarInsn(ASTORE, 1);
            Label label1 = new Label();
            dec.visitLabel(label1);
            dec.visitLineNumber(69, label1);
            dec.visitTypeInsn(NEW, "java/lang/String");
            dec.visitInsn(DUP);
            dec.visitVarInsn(ALOAD, 1);
            dec.visitFieldInsn(GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
            dec.visitMethodInsn(INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false);
            dec.visitInsn(ARETURN);
            Label label2 = new Label();
            dec.visitLabel(label2);
            dec.visitLocalVariable("var1", "[B", null, label0, label2, 0);
            dec.visitLocalVariable("var2", "[B", null, label1, label2, 1);
            dec.visitMaxs(4, 2);
            dec.visitEnd();
            cn.methods.add(dec);
        }
        {
            MethodNode methodVisitor = new MethodNode(ACC_PUBLIC | ACC_STATIC, putHashName, "(Ljava/util/Map;Ljava/lang/String;[B)Ljava/lang/String;", "(Ljava/util/Map<Ljava/lang/String;Ljava/lang/String;>;Ljava/lang/String;[B)Ljava/lang/String;", null);
            methodVisitor.visitCode();
            Label label0 = new Label();
            methodVisitor.visitLabel(label0);
            methodVisitor.visitLineNumber(73, label0);
            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            methodVisitor.visitTypeInsn(CHECKCAST, "java/lang/String");
            methodVisitor.visitVarInsn(ASTORE, 3);
            Label label1 = new Label();
            methodVisitor.visitLabel(label1);
            methodVisitor.visitLineNumber(75, label1);
            methodVisitor.visitVarInsn(ALOAD, 3);
            Label label2 = new Label();
            methodVisitor.visitJumpInsn(IFNULL, label2);
            Label label3 = new Label();
            methodVisitor.visitLabel(label3);
            methodVisitor.visitLineNumber(76, label3);
            methodVisitor.visitVarInsn(ALOAD, 3);
            methodVisitor.visitInsn(ARETURN);
            methodVisitor.visitLabel(label2);
            methodVisitor.visitLineNumber(79, label2);
            methodVisitor.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/lang/String"}, 0, null);
            methodVisitor.visitVarInsn(ALOAD, 2);
            methodVisitor.visitMethodInsn(INVOKESTATIC, helperClassName, decryptName, "([B)Ljava/lang/String;", false);
            methodVisitor.visitVarInsn(ASTORE, 4);
            Label label4 = new Label();
            methodVisitor.visitLabel(label4);
            methodVisitor.visitLineNumber(81, label4);
            methodVisitor.visitVarInsn(ALOAD, 0);
            methodVisitor.visitVarInsn(ALOAD, 1);
            methodVisitor.visitVarInsn(ALOAD, 4);
            methodVisitor.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            methodVisitor.visitInsn(POP);
            Label label5 = new Label();
            methodVisitor.visitLabel(label5);
            methodVisitor.visitLineNumber(82, label5);
            methodVisitor.visitVarInsn(ALOAD, 4);
            methodVisitor.visitInsn(ARETURN);
            Label label6 = new Label();
            methodVisitor.visitLabel(label6);
            methodVisitor.visitLocalVariable("cache", "Ljava/util/Map;", "Ljava/util/Map<Ljava/lang/String;Ljava/lang/String;>;", label0, label6, 0);
            methodVisitor.visitLocalVariable("key", "Ljava/lang/String;", null, label0, label6, 1);
            methodVisitor.visitLocalVariable("encryptedData", "[B", null, label0, label6, 2);
            methodVisitor.visitLocalVariable("cachedValue", "Ljava/lang/String;", null, label1, label6, 3);
            methodVisitor.visitLocalVariable("decryptedValue", "Ljava/lang/String;", null, label4, label6, 4);
            methodVisitor.visitMaxs(3, 5);
            methodVisitor.visitEnd();
            cn.methods.add(methodVisitor);
        }
        {
            MethodNode methodVisitor = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
            methodVisitor.visitCode();
            Label label0 = new Label();
            methodVisitor.visitLabel(label0);
            methodVisitor.visitLineNumber(86, label0);
            methodVisitor.visitLdcInsn(HELPER_RESOURCE_NAME);
            methodVisitor.visitMethodInsn(INVOKESTATIC, helperClassName, HELPER_LOADER_METHOD_NAME, "(Ljava/lang/String;)V", false);
            Label label1 = new Label();
            methodVisitor.visitLabel(label1);
            methodVisitor.visitLineNumber(87, label1);
            methodVisitor.visitInsn(RETURN);
            methodVisitor.visitMaxs(1, 0);
            methodVisitor.visitEnd();
            cn.methods.add(methodVisitor);
        }
        return cn;
    }
}
