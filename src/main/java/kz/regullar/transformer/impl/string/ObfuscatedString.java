package kz.regullar.transformer.impl.string;

public class ObfuscatedString {
    final String fieldName;
    final byte[] encryptedData;

    ObfuscatedString(String fieldName, byte[] encryptedData) {
        this.fieldName = fieldName;
        this.encryptedData = encryptedData;
    }
}