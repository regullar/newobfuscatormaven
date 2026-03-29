package kz.regullar.util;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class RandomUtils {
    private static final Random RANDOM = new Random();

//    public static int getRandomInt() {
//        return RANDOM.nextInt();
//    }

    public static int getRandomInt(int min, int max) {
        if (min > max) throw new IllegalArgumentException("min must be less or equal to max");
        long range = (long) max - (long) min + 1L;
        if (range <= 0 || range > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Range is too large");
        }
        return (int)(RANDOM.nextInt((int) range) + min);
    }

    public static boolean getRandomBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    public static int getRandomByteValue() {
        return getRandomInt(-128, 127);
    }

    public static int getRandomShortValue() {
        return getRandomInt(-32768, 32767);
    }

    public static int getRandomInt() {
        return ThreadLocalRandom.current().nextInt();
    }

    public static int getRandomInt(int bound) {
        if (bound <= 0) return 0;
        return ThreadLocalRandom.current().nextInt(bound);
    }

    public static long getRandomLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    public static float getRandomFloat() {
        return ThreadLocalRandom.current().nextFloat();
    }

    public static double getRandomDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
