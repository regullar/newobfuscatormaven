package kz.regullar.util;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public class NativeAnnotationUtils {

    private static final String NATIVE_DESC = Type.getType(antileak.api.annotation.Native.class).getDescriptor();

    private static boolean annotationListContainsNative(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode an : annotations) {
            if (NATIVE_DESC.equals(an.desc)) return true;
        }
        return false;
    }

    public static boolean hasNativeAnnotation(MethodNode method) {
        if (method == null) return false;
        if (annotationListContainsNative(method.visibleAnnotations)) return true;
        if (annotationListContainsNative(method.invisibleAnnotations)) return true;
        return false;
    }

    public static boolean hasNativeAnnotation(ClassNode cn) {
        if (cn == null) return false;
        if (annotationListContainsNative(cn.visibleAnnotations)) return true;
        if (annotationListContainsNative(cn.invisibleAnnotations)) return true;
        return false;
    }

    public static boolean shouldSkipObfuscation(ClassNode cn, MethodNode method) {
        return hasNativeAnnotation(method) || hasNativeAnnotation(cn);
    }
}
