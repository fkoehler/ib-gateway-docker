// SPDX-License-Identifier: GPL-3.0-or-later
package ibcalpha.ibc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** IBKR Mobile Authenticator: Base32 secret, HMAC-SHA1, six digits, 30 seconds. */
final class Totp {
    private Totp() {}

    static byte[] readSecret(String path) throws Exception {
        // Bound the read; never put file contents or paths in exceptions/logs.
        if (path == null || !Files.isRegularFile(Paths.get(path))) {
            throw new IllegalArgumentException("Invalid TOTP secret file");
        }
        byte[] data = new byte[4097];
        try (InputStream input = Files.newInputStream(Paths.get(path))) {
            int length = 0;
            int n;
            while (length < data.length && (n = input.read(data, length, data.length - length)) != -1) {
                length += n;
            }
            if (length == data.length) throw new IllegalArgumentException("Invalid TOTP secret file");
            return decodeBase32(new String(data, 0, length, StandardCharsets.US_ASCII));
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    static byte[] decodeBase32(String value) {
        String text = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        int padding = text.indexOf('=');
        if (padding >= 0) {
            if (!text.substring(padding).matches("=+") || text.length() % 8 != 0
                    || text.length() - padding != (8 - padding % 8) % 8) {
                throw new IllegalArgumentException("Invalid Base32 secret");
            }
            text = text.substring(0, padding);
        }
        int remainder = text.length() % 8;
        if (remainder == 1 || remainder == 3 || remainder == 6) {
            throw new IllegalArgumentException("Invalid Base32 secret length");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int bits = 0;
        int buffer = 0;
        for (char c : text.toCharArray()) {
            int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            if (digit < 0) throw new IllegalArgumentException("Invalid Base32 secret");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                output.write((buffer >> bits) & 255);
                buffer &= (1 << bits) - 1;
            }
        }
        byte[] key = output.toByteArray();
        if (buffer != 0 || key.length < 10) {
            throw new IllegalArgumentException("Invalid Base32 secret");
        }
        return key;
    }

    static String generate(byte[] secret, long unixMillis, int digits) throws Exception {
        if (unixMillis < 0 || (digits != 6 && digits != 8)) throw new IllegalArgumentException("Invalid TOTP parameters");
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(unixMillis / 30000).array());
        int offset = hash[hash.length - 1] & 15;
        int number = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
        return String.format(Locale.ROOT, "%0" + digits + "d", number % (digits == 6 ? 1000000 : 100000000));
    }

    static boolean freshEnough(long unixMillis) {
        return unixMillis >= 0 && 30000 - unixMillis % 30000 >= 10000;
    }

    /** Startup validation only. This command never prints a seed or an OTP. */
    public static void main(String[] args) {
        try {
            byte[] key = readSecret(System.getenv("TOTP_SECRET_FILE"));
            Arrays.fill(key, (byte) 0);
            System.out.println("TOTP secret file validated (value not logged)");
        } catch (Exception e) {
            System.err.println("TOTP configuration invalid: use a readable file containing only the Base32 enrollment secret");
            System.exit(1);
        }
    }
}
