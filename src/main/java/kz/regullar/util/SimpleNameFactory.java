package kz.regullar.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class SimpleNameFactory {
    private static final int CHARACTER_COUNT = 26;
    private static final String[] reservedNames = new String[]{"AUX", "CON", "NUL", "PRN"};

    private final Set<String> generatedNames = new HashSet<>();
    private final Random random = new Random();
    boolean proguard;
    private int index = 0;

    private static final String[] CONFUSE_TOKENS = {
            "if", "for", "while", "do", "switch",
            "case", "default", "try", "catch",
            "true", "false", "null",
            "boolean", "int", "long", "Object", "String"
    };

    public SimpleNameFactory() {
        this(false);
    }

    public SimpleNameFactory(boolean proguard) {
        this.proguard = proguard;
    }

    public void reset() {
        generatedNames.clear();
    }

    public String nextName() {
        if(proguard) {
            return nextNameProguard();
        }
        else{
            return nextGeneratedName();
        }
    }

    public String nextNameProguard() {
        return name(index++);
    }

    public String nextGeneratedName() {
        String name;
        do {
            name = generateConfusingName();
        } while (generatedNames.contains(name));
        generatedNames.add(name);
        return name;
    }

    private String generateRandomName() {
            return generateRandomIdentifier();
    }

    private String generateRandomIdentifier() {
        int length = 65 + random.nextInt(70);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(randomChar());
        }

        String name = sb.toString();
        if (Arrays.binarySearch(reservedNames, name.toUpperCase()) >= 0) {
            name += randomChar();
        }
        return name;
    }

    private char randomChar() {
        int totalCharacterCount = CHARACTER_COUNT;
        int index = random.nextInt(totalCharacterCount);

        return (char) (0x4E00 + index);
    }

    private String generateConfusingName() {
        StringBuilder sb = new StringBuilder();

        sb.append(randomToken());

        int parts = 2 + random.nextInt(4);
        for (int i = 0; i < parts; i++) {
            sb.append('$');

            if (random.nextBoolean()) {
                sb.append(randomToken());
            } else {
                sb.append(randomGarbage());
            }
        }

        return sb.toString();
    }

    private String randomToken() {
        return CONFUSE_TOKENS[random.nextInt(CONFUSE_TOKENS.length)];
    }

    private String randomGarbage() {
        int len = 1 + random.nextInt(4);
        StringBuilder sb = new StringBuilder(len);

        for (int i = 0; i < len; i++) {
            char c;
            if (random.nextBoolean()) {
                c = (char) ('a' + random.nextInt(26));
            } else {
                c = (char) ('0' + random.nextInt(10));
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String name(int index) {
        return newName(index);
    }

    /*
    *     private String name(int index) {
        int totalCharacterCount = CHARACTER_COUNT;

        int baseIndex = index / totalCharacterCount;
        int offset = index % totalCharacterCount;

        char newChar = charAt(offset);

        String newName = baseIndex == 0 ? String.valueOf(newChar) : (name(baseIndex - 1) + newChar);

        if (Arrays.binarySearch(reservedNames, newName.toUpperCase()) >= 0) {
            newName += newChar;
        }
        return newName;
    }
    * */

    private String newName(int index) {
        int totalCharacterCount = CHARACTER_COUNT;

        int baseIndex = index / totalCharacterCount;
        int offset = index % totalCharacterCount;

        char newChar = charAt(offset);

        String newName = baseIndex == 0 ? String.valueOf(newChar) : (name(baseIndex - 1) + newChar);

        if (Arrays.binarySearch(reservedNames, newName.toUpperCase()) >= 0) {
            newName += newChar;
        }
        return newName;
    }

    private char charAt(int index) {
        return (char) ((index < CHARACTER_COUNT ? 'a' : 'A' - CHARACTER_COUNT) + index);
    }

    public static void main(String[] args) {
        System.out.println("Some mixed-case names:");
        printNameSamples(new SimpleNameFactory(true), 60);
        System.out.println("Some lower-case names:");
        printNameSamples(new SimpleNameFactory(false), 60);
        System.out.println("Some more mixed-case names:");
        printNameSamples(new SimpleNameFactory(true), 80);
        System.out.println("Some more lower-case names:");
        printNameSamples(new SimpleNameFactory(false), 80);
    }

    private static void printNameSamples(SimpleNameFactory factory, int count) {
        for (int counter = 0; counter < count; counter++) {
            System.out.println("  [" + factory.nextName() + "]");
        }
    }
}
