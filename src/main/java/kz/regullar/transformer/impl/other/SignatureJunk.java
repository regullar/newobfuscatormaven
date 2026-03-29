package kz.regullar.transformer.impl.other;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;


import java.security.SecureRandom;
import java.util.ArrayList;

public final class SignatureJunk implements Opcodes, TransformHandler {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RNG = new SecureRandom();

    @Override
    public String name(){
        return "Signature Junk";
    }

    @Override
    public void transform(ClassNode cn, TransformContext context) {
        addJunkSignatures(cn);
        addBadAnnotations(cn);
    }

    private static String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static void addJunkSignatures(final ClassNode cn) {
        if (cn == null) return;

        cn.signature = randomString(15);

        if (cn.methods != null) {
            for (MethodNode mn : cn.methods) {
                if (mn != null && mn.signature == null) {
                    mn.signature = randomString(15);
                }
            }
        }

        if (cn.fields != null) {
            for (FieldNode fn : cn.fields) {
                if (fn != null && fn.signature == null) {
                    fn.signature = randomString(15);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void addBadAnnotations(final ClassNode classNode) {
        if (classNode.visibleAnnotations == null) {
            classNode.visibleAnnotations = new ArrayList<AnnotationNode>();
        }

        classNode.visibleAnnotations.add(new AnnotationNode(""));

        for (MethodNode method : classNode.methods) {
            if (method.visibleAnnotations == null) {
                method.visibleAnnotations = new ArrayList<AnnotationNode>();
            }
            method.visibleAnnotations.add(new AnnotationNode(""));
        }

        for (FieldNode field : classNode.fields) {
            if (field.visibleAnnotations == null) {
                field.visibleAnnotations = new ArrayList<AnnotationNode>();
            }
            field.visibleAnnotations.add(new AnnotationNode(""));
        }
    }
}
