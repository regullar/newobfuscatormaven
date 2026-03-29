package kz.regullar.transformer.impl.tricks;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Random;

public class FlagConfuseTransformer implements TransformHandler, Opcodes {
    private static final Random RNG = new Random();
    @Override
    public String name() {
        return "FlagConfuseTransformer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        for (FieldNode fieldNode : classNode.fields) {
//            fieldNode.access |= ACC_SYNTHETIC;
        }
        for (MethodNode methodNode : classNode.methods) {
//            methodNode.access |= ACC_SYNTHETIC;
        }
    }
}