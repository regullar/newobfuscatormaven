package kz.regullar.nativeguard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

public class NativeNative {
    private static void loadNativeLibrary(String resourcePath) {
        try {
            InputStream in = NativeCrypt.class.getResourceAsStream(resourcePath);

            try {
                if (in == null) {
                    throw new UnsatisfiedLinkError("Resource not found: " + resourcePath);
                }

                File tempFile = Files.createTempFile("huesos", ".dll").toFile();
                tempFile.deleteOnExit();
                FileOutputStream out = new FileOutputStream(tempFile);

                try {
                    byte[] buffer = new byte[8192];

                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                } catch (Throwable var8) {
                    try {
                        out.close();
                    } catch (Throwable var7) {
                        var8.addSuppressed(var7);
                    }

                    throw var8;
                }

                out.close();
                System.load(tempFile.getAbsolutePath());
            } catch (Throwable var9) {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable var6) {
                        var9.addSuppressed(var6);
                    }
                }

                throw var9;
            }

            if (in != null) {
                in.close();
            }

        } catch (IOException var10) {
            throw new UnsatisfiedLinkError("Failed to load native library: " + var10.getMessage());
        }
    }

    public static native byte[] meow(byte[] var1);

    public static String decrypt(byte[] var1) {
        byte[] var2 = meow(var1);
        return new String(var2, StandardCharsets.UTF_8);
    }

    public static String decryptAndCache(Map<String, String> cache, String key, byte[] encryptedData) {
        String cachedValue = cache.get(key);

        if (cachedValue != null) {
            return cachedValue;
        }

        String decryptedValue = decrypt(encryptedData);

        cache.put(key, decryptedValue);
        return decryptedValue;
    }

    static {
        loadNativeLibrary("/StringPool.dll");
    }
}
