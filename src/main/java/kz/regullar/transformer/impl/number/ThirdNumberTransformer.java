package kz.regullar.transformer.impl.number;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.tree.ClassNode;

public class ThirdNumberTransformer implements TransformHandler {
    @Override
    public String name() {
        return "ThirdNumberTransformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {

    }
}
