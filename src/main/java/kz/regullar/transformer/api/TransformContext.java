package kz.regullar.transformer.api;

import kz.regullar.core.Blacklist;
import kz.regullar.util.SimpleNameFactory;
import org.objectweb.asm.tree.ClassNode;

import java.util.*;

public class TransformContext {
    private final Map<String, ClassNode> classMap;
    private final Map<String, ClassNode> libraryClassMap;
    private final Map<String, byte[]> resourceMap;
    private final List<ClassNode> newClasses = new ArrayList<>();
    private final SimpleNameFactory nameFactory;
    private final Blacklist blacklist;
    private final TransformerLogger logger;
    private final Map<String, ClassNode> allLoadedClassesCache = new HashMap<>();
    private byte[] preamble;

    public TransformContext(Map<String, ClassNode> classMap,
            Map<String, ClassNode> libraryClassMap,
            Map<String, byte[]> resourceMap,
            SimpleNameFactory nameFactory,
            Blacklist blacklist) {
        this.classMap = classMap;
        this.libraryClassMap = libraryClassMap;
        this.resourceMap = resourceMap;
        this.nameFactory = nameFactory;
        this.blacklist = blacklist;
        this.logger = new TransformerLogger();
    }

    public Collection<ClassNode> getTargetClasses() {
        return classMap.values();
    }

    public Collection<ClassNode> getAllKnownClasses() {
        if (allLoadedClassesCache.isEmpty()) {
            allLoadedClassesCache.putAll(classMap);
            allLoadedClassesCache.putAll(libraryClassMap);
        }
        return allLoadedClassesCache.values();
    }

    public Map<String, byte[]> getResourceMap() {
        return resourceMap;
    }

    public void addClass(ClassNode classNode) {
        newClasses.add(classNode);
    }

    public Collection<ClassNode> getAddedClasses() {
        return Collections.unmodifiableList(newClasses);
    }

    public void addResource(String name, byte[] data) {
        resourceMap.put(name, data);
    }

    public SimpleNameFactory getNameFactory() {
        return nameFactory;
    }

    public boolean isBlacklisted(String name) {
        return this.blacklist.isBlacklisted(name);
    }

    public ClassNode getClass(String name) {
        ClassNode cn = classMap.get(name);
        if (cn == null) {
            cn = libraryClassMap.get(name);
        }
        if (cn == null) {
            for (ClassNode newCn : newClasses) {
                if (newCn.name.equals(name)) {
                    return newCn;
                }
            }
        }
        return cn;
    }

    public boolean isAssignableFrom(String child, String parent) {
        if (child.equals(parent))
            return true;
        if (parent.equals("java/lang/Object"))
            return true;

        ClassNode childNode = getClass(child);
        if (childNode == null) {
            return isJdkAssignableFrom(child, parent);
        }

        if (childNode.superName != null) {
            if (isAssignableFrom(childNode.superName, parent)) {
                return true;
            }
        }

        if (childNode.interfaces != null) {
            for (String iface : childNode.interfaces) {
                if (isAssignableFrom(iface, parent)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isJdkAssignableFrom(String child, String parent) {
        try {
            Class<?> childClass = Class.forName(child.replace('/', '.'));
            Class<?> parentClass = Class.forName(parent.replace('/', '.'));
            return parentClass.isAssignableFrom(childClass);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Deprecated
    public ClassNode findClass(String name) {
        return getClass(name);
    }

    public TransformerLogger getLogger() {
        return logger;
    }

    public void setPreamble(byte[] preamble) {
        this.preamble = preamble;
    }

    public byte[] getPreamble() {
        return preamble;
    }
}