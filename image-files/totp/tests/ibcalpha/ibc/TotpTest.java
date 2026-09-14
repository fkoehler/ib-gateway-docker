// SPDX-License-Identifier: GPL-3.0-or-later
package ibcalpha.ibc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

public final class TotpTest {
    // RFC 6238 Appendix B: public SHA1 test key, never an account credential.
    static final String PUBLIC_KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        long[] seconds = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] expected = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        byte[] key = Totp.decodeBase32(PUBLIC_KEY);
        check(Arrays.equals(key, "12345678901234567890".getBytes(StandardCharsets.US_ASCII)), "Base32 RFC key");
        for (int i = 0; i < seconds.length; i++) {
            check(Totp.generate(key, seconds[i] * 1000, 8).equals(expected[i]), "RFC 6238 vector " + i);
            check(Totp.generate(key, seconds[i] * 1000, 6).equals(expected[i].substring(2)), "Six-digit truncation " + i);
        }
        check(Totp.freshEnough(20000), "10s remaining accepted");
        check(!Totp.freshEnough(20001), "Under 10s waits");
        check(!Totp.freshEnough(29999), "Expiry waits");
        check(Totp.freshEnough(30000), "New period accepted");
        check(Arrays.equals(key, Totp.decodeBase32("  " + PUBLIC_KEY.toLowerCase() + "\n")), "Whitespace and lowercase");
        check(Arrays.equals(Totp.decodeBase32("GEZDGNBVGY3TQOJQGE======"),
                "12345678901".getBytes(StandardCharsets.US_ASCII)), "RFC Base32 padding");
        for (String invalid : new String[]{"", "123456", "otpauth://totp/example", PUBLIC_KEY + "!",
                PUBLIC_KEY + "=A", PUBLIC_KEY + "========", PUBLIC_KEY + "A",
                "AAAAAAAAAAAAAAAAAB", "AAAAAAAAAAAAAAAAA"}) {
            boolean rejected = false;
            try { Totp.decodeBase32(invalid); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "Malformed Base32 rejected");
        }
        Path path = Files.createTempFile("totp-public-fixture", ".txt");
        try {
            Files.write(path, PUBLIC_KEY.getBytes(StandardCharsets.US_ASCII));
            check(Arrays.equals(key, Totp.readSecret(path.toString())), "File read");
            Files.write(path, new byte[4097]);
            boolean rejected = false;
            try { Totp.readSecret(path.toString()); } catch (Exception e) { rejected = true; }
            check(rejected, "Oversized file rejected");
        } finally { Files.delete(path); }
        Path directory = Files.createTempDirectory("totp-public-directory");
        try {
            for (String invalid : new String[]{null, path.toString(), directory.toString()}) {
                boolean rejected = false;
                try { Totp.readSecret(invalid); } catch (Exception e) { rejected = true; }
                check(rejected, "Missing and non-file secret inputs rejected");
            }
        } finally { Files.delete(directory); }
        testPrompts();
        System.out.println("PASS: RFC 6238 vectors, Base32/file validation, expiry boundaries and prompt discrimination");
    }

    private static void testPrompts() {
        for (String text : new String[]{"Enter IB Key code", "Enter SMS code", "Security Code Card Authentication",
                "Approve using IB Key", "Enter authentication code", "Passkey", "Mobile Authenticator app"}) {
            JPanel panel = panel(text);
            check(TotpDialogHandler.inspect(panel) == null, "Unrecognized/wrong method must remain manual");
        }
        JPanel valid = panel("<html>Enter Mobile Authenticator app code</html>");
        check(TotpDialogHandler.inspect(valid) != null, "Exact TOTP prompt accepted");
        JTextField field = (JTextField) valid.getComponent(1);
        field.setText("manual input");
        check(TotpDialogHandler.inspect(valid) == null, "Never overwrite manual input");
        field.setText("");
        valid.add(new JTextField(6));
        check(TotpDialogHandler.inspect(valid) == null, "Ambiguous field rejected");
        JPanel ambiguous = panel("Enter Mobile Authenticator app code");
        ambiguous.add(new JButton("Verify"));
        check(TotpDialogHandler.inspect(ambiguous) == null, "Ambiguous submit rejected");
        JPanel hidden = panel("Enter Mobile Authenticator app code");
        hidden.getComponent(0).setVisible(false);
        check(TotpDialogHandler.inspect(hidden) == null, "Hidden label rejected");
        JPanel conflicting = panel("Enter Mobile Authenticator app code");
        conflicting.add(new JLabel("Enter IB Key code"));
        check(TotpDialogHandler.inspect(conflicting) == null, "Conflicting challenge rejected");
    }

    static JPanel panel(String prompt) {
        JPanel panel = new JPanel();
        panel.add(new JLabel(prompt));
        panel.add(new JTextField(6));
        panel.add(new JButton("OK"));
        return panel;
    }
}
