package kz.regullar.transformer.impl.flow;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.tree.ClassNode;

public class FifthControlFlowTransformer implements TransformHandler {
    @Override
    public String name() {
        return "FifthControlFlowTransformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {

    }
}
