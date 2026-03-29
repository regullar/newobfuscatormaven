package kz.regullar.transformer.impl.packaging;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

public class RedHerring implements TransformHandler {
    private final boolean corrupt = false;
    private final String className = "Main";
    private final List<String> watermark = new ArrayList<>();

    public RedHerring() {
        watermark.add("прив");
    }

    @Override
    public String name() {
        return "Red Herring";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
    }

    @Override
    public void finalize(TransformContext context) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();

        if (corrupt) {
            stream.write(0x50);
            stream.write(0x4B);
            stream.write(0x03);
            stream.write(0x04);
            Random rand = new Random();
            byte[] bytes = new byte[rand.nextInt(25) + 1];
            rand.nextBytes(bytes);
            try {
                stream.write(bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            ClassNode classNode = new ClassNode();
            classNode.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

            List<String> inputList = watermark;
            if (!inputList.isEmpty() && !inputList.get(0).isEmpty()) {
                String[][] messages = new String[inputList.size()][2];

                for (int i = 0; i < inputList.size(); i++) {
                    String[] parts = inputList.get(i).split("\\s*\\|\\s*", -1);
                    if (parts.length >= 2) {
                        messages[i][0] = parts[0].trim();
                        messages[i][1] = parts[1].trim();
                    } else {
                        messages[i][0] = parts[0].trim();
                        messages[i][1] = "msg" + i;
                    }
                }

                for (String[] messageData : messages) {
                    if (messageData[0] != null) {
                        FieldNode fieldNode = new FieldNode(Opcodes.ACC_STATIC, messageData[1], "Ljava/lang/String;",
                                null, null);
                        classNode.fields.add(fieldNode);
                    }
                }

                MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                InsnList clinitInstructions = clinit.instructions;

                for (String[] messageData : messages) {
                    if (messageData[0] != null) {
                        clinitInstructions.add(new LdcInsnNode(messageData[0]));
                        clinitInstructions.add(
                                new FieldInsnNode(Opcodes.PUTSTATIC, className, messageData[1], "Ljava/lang/String;"));
                    }
                }

                clinitInstructions.add(new InsnNode(Opcodes.RETURN));
                classNode.methods.add(clinit);
            }

            ByteArrayOutputStream jarBufferStream = new ByteArrayOutputStream();
            try (JarOutputStream jarOutputStream = new JarOutputStream(jarBufferStream)) {
                ClassWriter cw = new ClassWriter(0);
                classNode.accept(cw);
                byte[] data = cw.toByteArray();

                jarOutputStream.putNextEntry(new ZipEntry(className + ".class"));
                jarOutputStream.write(data);
                jarOutputStream.closeEntry();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            try {
                stream.write(jarBufferStream.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        context.setPreamble(stream.toByteArray());
        System.out.println("[RedHerring] Generated " + stream.size() + " bytes of preamble data.");
    }
}
