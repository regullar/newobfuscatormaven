package kz.regullar.transformer.api;

import org.objectweb.asm.tree.ClassNode;

public interface TransformHandler {

    String name();

    default void initialize(TransformContext context) {
    }

    void transform(ClassNode classNode, TransformContext context);

    default void finalize(TransformContext context) {
    }
}