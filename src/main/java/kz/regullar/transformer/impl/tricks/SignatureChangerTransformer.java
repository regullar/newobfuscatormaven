package kz.regullar.transformer.impl.tricks;


import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.NativeAnnotationUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;

public class SignatureChangerTransformer implements TransformHandler, Opcodes {

    @Override
    public String name() {
        return "SignatureCrasher";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        addBadAnnotations(classNode);
    }

    private void addBadAnnotations(ClassNode classNode) {
        String repeatedEndLine = "\n".repeat(100);

        if (classNode.invisibleAnnotations == null)
            classNode.invisibleAnnotations = new ArrayList<>();
        classNode.invisibleAnnotations.add(new AnnotationNode(repeatedEndLine));

        classNode.methods.stream()
                .filter(m -> !NativeAnnotationUtils.shouldSkipObfuscation(classNode, m))
                .forEach(method -> {
                    if (method.invisibleAnnotations == null)
                        method.invisibleAnnotations = new ArrayList<>();
                    method.invisibleAnnotations.add(new AnnotationNode(repeatedEndLine));
                });

        for (FieldNode field : classNode.fields) {
            if (field.invisibleAnnotations == null)
                field.invisibleAnnotations = new ArrayList<>();
            field.invisibleAnnotations.add(new AnnotationNode(repeatedEndLine));
        }
    }
}
