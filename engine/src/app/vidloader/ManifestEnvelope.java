package app.vidloader;

import java.util.Base64;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/* JADX INFO: compiled from: r8-map-id-e33b202440f515bf5d2dc12ff38b5f563f89eb23bf78d61ca6f9da422edf884e */
/* JADX INFO: loaded from: classes.dex */
public final class ManifestEnvelope {
    // Recovered hf4 manifest serialization; see docs/RECONSTRUCTION_SECTIONS.md.
    public static String unwrap(String text) {
        if (text == null || text.trim().startsWith("#EXTM3U")) return text;
        byte[] decoded = c(text);
        if (decoded == null) return text;
        String playlist = new String(decoded, StandardCharsets.UTF_8);
        return playlist.trim().startsWith("#EXTM3U") ? playlist : text;
    }
    public static final String[] a = {"AA", "AB", "AC", "AD", "AE", "AF"};

    public static byte[] a(String str, byte[] bArr, byte[] bArr2, byte[] bArr3) throws java.security.GeneralSecurityException {
        if (bArr2.length == 16 || bArr2.length == 24 || bArr2.length == 32) {
            Cipher cipher = Cipher.getInstance(str);
            SecretKeySpec secretKeySpec = new SecretKeySpec(bArr2, "AES");
            if (bArr3 != null) {
                cipher.init(2, secretKeySpec, new IvParameterSpec(bArr3));
            } else {
                cipher.init(2, secretKeySpec);
            }
            return cipher.doFinal(bArr);
        }
        throw new IllegalArgumentException("Invalid AES key length: " + bArr2.length + " (expected 16/24/32)");
    }

    public static byte[] b(byte[] bArr) {
        String strReplace = new String(bArr, StandardCharsets.US_ASCII).replaceAll("[\\s\\r\\n\\t]", "").replace('-', '+').replace('_', '/');
        int length = strReplace.length() % 4;
        if (length == 2) {
            strReplace = strReplace.concat("==");
        } else if (length == 3) {
            strReplace = strReplace.concat("=");
        }
        return Base64.getDecoder().decode(strReplace);
    }

    public static byte[] c(String str) {
        int length;
        int length2;
        if (str.length() >= 3) {
            String strTrim = str.trim();
            try {
                int i = Integer.parseInt(strTrim.substring(strTrim.length() - 2));
                if (i > 0 && i <= strTrim.length() / 2 && (length2 = (length = strTrim.length() - 2) - i) >= 0) {
                    String strSubstring = strTrim.substring(length2, length);
                    String strSubstring2 = strTrim.substring(0, length2);
                    String strSubstring3 = strSubstring.substring(0, 2);
                    for (String str2 : a) {
                        if (str2.equals(strSubstring3)) {
                            int i2 = Integer.parseInt(strSubstring.substring(2, 4));
                            int i3 = 4 + i2;
                            String strSubstring4 = strSubstring.substring(4, i3);
                            Charset charset = StandardCharsets.US_ASCII;
                            byte[] bytes = strSubstring4.getBytes(charset);
                            int i4 = i2 + 6;
                            int i5 = Integer.parseInt(strSubstring.substring(i3, i4)) + i4;
                            byte[] bytes2 = strSubstring.substring(i4, i5).getBytes(charset);
                            if (i5 != strSubstring.length()) {
                                break;
                            }
                            byte[] bArrB = b(strSubstring2.getBytes(charset));
                            switch (strSubstring3.hashCode()) {
                                case 2080:
                                    if (strSubstring3.equals("AA")) {
                                        return b(bArrB);
                                    }
                                    break;
                                case 2081:
                                    if (strSubstring3.equals("AB")) {
                                        d("AB(XOR)", bytes);
                                        byte[] bArr = new byte[bArrB.length];
                                        for (int i6 = 0; i6 < bArrB.length; i6++) {
                                            bArr[i6] = (byte) (bArrB[i6] ^ bytes[i6 % bytes.length]);
                                        }
                                        return bArr;
                                    }
                                    break;
                                case 2082:
                                    if (strSubstring3.equals("AC")) {
                                        d("AC(AES-ECB)", bytes);
                                        return a("AES/ECB/PKCS5Padding", bArrB, bytes, null);
                                    }
                                    break;
                                case 2083:
                                    if (strSubstring3.equals("AD")) {
                                        d("AD(AES-CBC)", bytes);
                                        return a("AES/CBC/PKCS5Padding", bArrB, bytes, b(bytes2));
                                    }
                                    break;
                                case 2084:
                                    if (strSubstring3.equals("AE")) {
                                        d("AE(AES-CFB)", bytes);
                                        return a("AES/CFB128/PKCS5Padding", bArrB, bytes, b(bytes2));
                                    }
                                    break;
                                case 2085:
                                    if (strSubstring3.equals("AF")) {
                                        d("AF(AES-OFB)", bytes);
                                        return a("AES/OFB128/PKCS5Padding", bArrB, bytes, b(bytes2));
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                    }
                }
            } catch (Exception unused) {
            }
        }
        return null;
    }

    public static void d(String str, byte[] bArr) {
        if (bArr == null || bArr.length == 0) {
            throw new IllegalArgumentException("Missing key for ".concat(str));
        }
    }
}


