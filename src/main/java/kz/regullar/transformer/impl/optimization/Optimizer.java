package kz.regullar.transformer.impl.optimization;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.Iterator;
import java.util.ListIterator;

public class Optimizer implements TransformHandler, Opcodes {

    @Override
    public String name() {
        return "Optimizer";
    }

    @Override
    public void transform(ClassNode classNode, TransformContext context) {
        if (classNode == null) return;

        stripClassDebug(classNode);

        for (MethodNode method : classNode.methods) {
            stripMethodDebug(method);
        }

        for (FieldNode field : classNode.fields) {
            stripFieldDebug(field);
        }
    }

    private void stripClassDebug(ClassNode cn) {
        cn.sourceFile = null;
        cn.sourceDebug = null;
//        cn.signature = null;

        if (cn.attrs != null) {
            cn.attrs.clear();
            cn.attrs = null;
        }

        if (cn.innerClasses != null) {
            cn.innerClasses.clear();
        }
    }

    private void stripMethodDebug(MethodNode mn) {
        if (mn.instructions != null) {
            ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();

                if (insn.getOpcode() == NOP) {
                    it.remove();
                    continue;
                }

                if (insn instanceof LineNumberNode) {
                    it.remove();
                }
            }
        }

        mn.localVariables = null;

        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;

        if (mn.attrs != null) {
            mn.attrs.clear();
            mn.attrs = null;
        }
    }

    private void stripFieldDebug(FieldNode fn) {
        if (fn.attrs != null) {
            fn.attrs.clear();
            fn.attrs = null;
        }
    }
}


/*
*
* private void removeInst(ClassNode classNode){
        for (MethodNode method : classNode.methods) {
            ListIterator<AbstractInsnNode> it = method.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn.getOpcode() == Opcodes.NOP || insn instanceof LineNumberNode) {
                    it.remove();
                }
            }
        }
    }

    private void deleteDebug(ClassNode classNode){
        classNode.sourceDebug = null;
        classNode.sourceFile = null;
        classNode.signature = null;
    }
    *
    * */

