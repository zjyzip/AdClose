package com.close.hook.ads.util;

import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

public class EncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    private static final byte[] FIXED_KEY = hexStringToByteArray("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public static String encrypt(String plainText) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        SecretKeySpec keySpec = new SecretKeySpec(FIXED_KEY, ALGORITHM);
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmParameterSpec);

        byte[] cipherText = cipher.doFinal(plainText.getBytes("UTF-8"));

        byte[] encryptedData = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, encryptedData, 0, iv.length);
        System.arraycopy(cipherText, 0, encryptedData, iv.length, cipherText.length);

        return Base64.encodeToString(encryptedData, Base64.NO_WRAP);
    }

    public static String decrypt(String encryptedText) throws Exception {
        byte[] encryptedDataWithIv = Base64.decode(encryptedText, Base64.NO_WRAP);

        if (encryptedDataWithIv.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted data is too short to contain IV.");
        }

        byte[] iv = Arrays.copyOfRange(encryptedDataWithIv, 0, GCM_IV_LENGTH);
        byte[] cipherText = Arrays.copyOfRange(encryptedDataWithIv, GCM_IV_LENGTH, encryptedDataWithIv.length);

        SecretKeySpec keySpec = new SecretKeySpec(FIXED_KEY, ALGORITHM);
        GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmParameterSpec);

        byte[] decryptedBytes = cipher.doFinal(cipherText);
        return new String(decryptedBytes, "UTF-8");
    }
}
