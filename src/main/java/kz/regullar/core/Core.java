package kz.regullar.core;

import kz.regullar.transformer.api.TransformContext;
import kz.regullar.transformer.api.TransformHandler;
import kz.regullar.util.SimpleNameFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

public class Core {
    private final Path inputPath;
    private final Path outputPath;
    private final Blacklist blacklist;
    private final List<TransformHandler> transformers;
    private final SimpleNameFactory nameFactory;

    private Core(Path inputPath, Path outputPath, Blacklist blacklist,
            List<TransformHandler> transformers, SimpleNameFactory nameFactory) {
        this.inputPath = inputPath;
        this.outputPath = outputPath;
        this.blacklist = blacklist;
        this.transformers = transformers;
        this.nameFactory = nameFactory;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void run() throws IOException {
        long start = System.nanoTime();
        Map<String, ClassNode> targetClassMap = new HashMap<>();
        Map<String, ClassNode> libraryClassMap = new HashMap<>();
        Map<String, byte[]> classBytesMap = new HashMap<>();
        Map<String, byte[]> resourceBytesMap = new HashMap<>();
        Map<String, byte[]> newResourceMap = new ConcurrentHashMap<>();
        Manifest manifest;
        try (JarFile inputJarFile = new JarFile(inputPath.toFile())) {
            manifest = inputJarFile.getManifest();
            Enumeration<JarEntry> entries = inputJarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    continue;
                }
                byte[] bytes = inputJarFile.getInputStream(entry).readAllBytes();
                if (name.endsWith(".class")) {
                    ClassReader cr = new ClassReader(bytes);
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    cn.version = Opcodes.V21;

                    if (!isBlacklisted(name)) {
                        targetClassMap.put(cn.name, cn);
                        classBytesMap.put(cn.name, bytes);
                    } else {
                        libraryClassMap.put(cn.name, cn);
                        resourceBytesMap.put(name, bytes);
                    }
                } else {
                    resourceBytesMap.put(name, bytes);
                }
            }
        }

        TransformContext context = new TransformContext(targetClassMap, libraryClassMap, newResourceMap, nameFactory,
                blacklist);

        for (TransformHandler transformer : transformers) {
            try {
                transformer.initialize(context);
            } catch (Exception e) {
                System.err.println("Transformer " + transformer.name() + " failed during initialize:");
                e.printStackTrace();
            }
        }
        System.out.println("[Obfuscator] Анализ завершен. Начинаем трансформацию и запись...");
        final Set<String> writtenEntries = new HashSet<>();

        Map<String, byte[]> transformedClasses = targetClassMap.keySet().parallelStream()
                .collect(Collectors.toMap(
                        className -> className,
                        className -> {
                            byte[] originalBytes = classBytesMap.get(className);
                            ClassReader cr = new ClassReader(originalBytes);
                            ClassNode cn = new ClassNode();
                            cr.accept(cn, ClassReader.EXPAND_FRAMES);
                            cn.version = Opcodes.V21;
                            for (TransformHandler transformer : transformers) {
                                try {
                                    transformer.transform(cn, context);
                                } catch (Exception e) {
                                    System.err.println(
                                            "Transformer " + transformer.name() + " failed for class " + cn.name);
                                    e.printStackTrace();
                                }
                            }
                            return generateClassBytes(cn);
                        },
                        (a, b) -> a,
                        ConcurrentHashMap::new));

        BufferedOutputStream fileOutputStream = new BufferedOutputStream(Files.newOutputStream(outputPath));

        if (context.getPreamble() != null && context.getPreamble().length > 0) {
            System.out.println("[Core] Writing preamble (" + context.getPreamble().length + " bytes)...");
            fileOutputStream.write(context.getPreamble());
            fileOutputStream.flush();
        }

        try (JarOutputStream jos = new JarOutputStream(fileOutputStream, manifest)) {
            for (Map.Entry<String, byte[]> entry : transformedClasses.entrySet()) {
                String className = entry.getKey();
                byte[] classBytes = entry.getValue();
                if (classBytes != null) {
                    String finalName = className + ".class";
                    if (writtenEntries.add(finalName)) {
                        JarEntry newEntry = new JarEntry(finalName);
                        jos.putNextEntry(newEntry);
                        jos.write(classBytes);
                        jos.closeEntry();
                    } else {
                        System.err.println("[WARN] Skipping duplicate entry (class): " + finalName);
                    }
                } else {
                    System.err.println("!!! SKIPPING class due to critical write failure: " + className);
                }
            }
            for (Map.Entry<String, byte[]> entry : resourceBytesMap.entrySet()) {
                String name = entry.getKey();
                if (writtenEntries.add(name)) {
                    jos.putNextEntry(new JarEntry(name));
                    jos.write(entry.getValue());
                    jos.closeEntry();
                } else {
                    System.err.println("[WARN] Skipping duplicate entry (resource): " + name);
                }
            }
            for (TransformHandler transformer : transformers) {
                try {
                    transformer.finalize(context);
                } catch (Exception e) {
                    System.err.println("Transformer " + transformer.name() + " failed during finalize:");
                    e.printStackTrace();
                }
            }
            for (ClassNode cn : context.getAddedClasses()) {
                String finalName = cn.name + ".class";
                if (writtenEntries.add(finalName)) {
                    byte[] classBytes = generateClassBytes(cn);
                    if (classBytes != null) {
                        JarEntry entry = new JarEntry(finalName);
                        jos.putNextEntry(entry);
                        jos.write(classBytes);
                        jos.closeEntry();
                    } else {
                        System.err.println("!!! SKIPPING NEW class due to critical write failure: " + cn.name);
                    }
                } else {
                    System.err.println("[WARN] Skipping duplicate entry (new class): " + finalName);
                }
            }
            for (Map.Entry<String, byte[]> entry : context.getResourceMap().entrySet()) {
                if (writtenEntries.add(entry.getKey())) {
                    jos.putNextEntry(new JarEntry(entry.getKey()));
                    jos.write(entry.getValue());
                    jos.closeEntry();
                } else {
                    System.err.println("[WARN] Skipping duplicate entry (new resource): " + entry.getKey());
                }
            }
        }
        long end = System.nanoTime();
        long durationMs = (end - start) / 1_000_000;
        double durationSec = durationMs / 1000.0;
        System.out.println("[Obfuscator] Обработка JAR заняла: " + durationMs + " мс (" + durationSec + " сек)");
    }

    private byte[] generateClassBytes(ClassNode cn) {
        try {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable e) {
            System.err.println("Failed to write class with COMPUTE_MAXS: " + cn.name);
            System.err.println(" Reason: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private boolean isBlacklisted(String name) {
        return this.blacklist.isBlacklisted(name);
    }

    public static class Builder {
        private Path inputPath;
        private Path outputPath;
        private Set<String> blacklist = new HashSet<>();
        private List<TransformHandler> transformers = new ArrayList<>();
        private SimpleNameFactory nameFactory = new SimpleNameFactory(true);

        public Builder input(Path path) {
            this.inputPath = path;
            return this;
        }

        public Builder output(Path path) {
            this.outputPath = path;
            return this;
        }

        public Builder blacklist(String... patterns) {
            this.blacklist.addAll(Arrays.asList(patterns));
            return this;
        }

        public Builder blacklist(Collection<String> patterns) {
            this.blacklist.addAll(patterns);
            return this;
        }

        public Builder register(TransformHandler transformer) {
            this.transformers.add(transformer);
            return this;
        }

        public Builder nameFactory(SimpleNameFactory factory) {
            this.nameFactory = factory;
            return this;
        }

        public Core build() {
            Objects.requireNonNull(inputPath, "Input path must be set");
            Objects.requireNonNull(outputPath, "Output path must be set");
            return new Core(inputPath, outputPath, new Blacklist(blacklist),
                    List.copyOf(transformers), nameFactory);
        }
    }
}