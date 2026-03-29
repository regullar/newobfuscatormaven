package kz.regullar.util.generator.name.zalgo;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class ZalgoGenerator {

    private final Set<String> generated = new HashSet<>();
    private final Random random = new Random();
    private int index = 0;
    private final boolean newlineInside;
    private final double newlineChance;
    private final Intensity intensity;

    public enum Intensity {
        LIGHT(2), MEDIUM(6), HEAVY(12);
        public final int count;
        Intensity(int count) { this.count = count; }
    }

    public ZalgoGenerator() {
        this(Intensity.MEDIUM, true, 0.05);
    }

    public ZalgoGenerator(Intensity intensity, boolean newlineInside, double newlineChance) {
        this.intensity = intensity;
        this.newlineInside = newlineInside;
        this.newlineChance = Math.max(0.0, Math.min(1.0, newlineChance));
    }

    public void reset() {
        generated.clear();
        index = 0;
    }

    public String nextZalgo() {
        String name;
        do {
            name = generateZalgo();
        } while (generated.contains(name));
        generated.add(name);
        index++;
        return name;
    }

    private String generateZalgo() {
        int baseLen = 3 + random.nextInt(6);
        StringBuilder base = new StringBuilder(baseLen);
        for (int i = 0; i < baseLen; i++) {
            base.append(randomHan());
            if (random.nextInt(8) == 0) base.append('的');
        }
        return zalgoify(base.toString());
    }

    private String zalgoify(String text) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            builder.append(c);

            int up = random.nextInt(intensity.count + 1);
            int mid = random.nextInt(intensity.count + 1);
            int down = random.nextInt(intensity.count + 1);

            appendZalgo(builder, ZalgoChars.ZALGO_UP, up);
            appendZalgo(builder, ZalgoChars.ZALGO_MID, mid);
            appendZalgo(builder, ZalgoChars.ZALGO_DOWN, down);

            if (newlineInside && random.nextDouble() < newlineChance) {
                builder.append('\n');
            }
        }

        if (newlineInside && random.nextDouble() < 0.02 && builder.length() > 2) {
            int pos = 1 + random.nextInt(builder.length() - 2);
            builder.insert(pos, '\n');
        }

        return builder.toString();
    }

    private void appendZalgo(StringBuilder builder, char[] src, int count) {
        for (int i = 0; i < count; i++) {
            builder.append(src[random.nextInt(src.length)]);
        }
    }

    private char randomHan() {
        return (char) (0x4E00 + random.nextInt(0x9FFF - 0x4E00));
    }

    public static void main(String[] args) {
        ZalgoGenerator gen = new ZalgoGenerator(Intensity.HEAVY, true, 0.15);

        for (int i = 0; i < 20; i++) {
            String s = gen.nextZalgo();
            System.out.println("---BEGIN---");
            System.out.println(s);
            System.out.println("----END----\n");
        }

        String sample = gen.nextZalgo();
        System.out.println("DEBUG (show \\n positions):");
        for (int i = 0; i < sample.length(); i++) {
            char ch = sample.charAt(i);
            if (ch == '\n') System.out.print("\\n");
            else System.out.print(ch);
        }
        System.out.println();
    }
}
