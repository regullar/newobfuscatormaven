package kz.regullar.transformer.impl.other;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.tree.ClassNode;

import java.util.Collections;
import java.util.List;

public class ShuffleTransformer implements TransformHandler {

    @Override
    public String name() {
        return "ShuffleTransformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        shuffleAll(classNode.fields, classNode.methods, classNode.innerClasses, classNode.interfaces, classNode.attrs, classNode.invisibleAnnotations, classNode.visibleAnnotations, classNode.invisibleTypeAnnotations, classNode.visibleTypeAnnotations);
        classNode.fields.forEach(f -> shuffleMemberLists(f.attrs, f.invisibleAnnotations, f.visibleAnnotations, f.invisibleTypeAnnotations, f.visibleTypeAnnotations));
        classNode.methods.forEach(m -> shuffleMemberLists(m.attrs, m.invisibleAnnotations, m.visibleAnnotations, m.invisibleTypeAnnotations, m.visibleTypeAnnotations, m.exceptions, m.invisibleLocalVariableAnnotations, m.visibleLocalVariableAnnotations, m.localVariables, m.parameters));
    }

    private static void shuffleAll(List<?>... lists) {
        for (List<?> list : lists) shuffleIfNonnull(list);
    }

    private static void shuffleMemberLists(List<?>... lists) {
        for (List<?> list : lists) shuffleIfNonnull(list);
    }

    private static void shuffleIfNonnull(List<?> list) {
        if (list != null) Collections.shuffle(list);
    }
}
