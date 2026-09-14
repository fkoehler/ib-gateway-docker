// SPDX-License-Identifier: GPL-3.0-or-later
package ibcalpha.ibc;

import java.awt.event.WindowEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Network-free test through the actual patched IBC WindowHandler and Swing EDT. */
public final class TotpDialogTest {
    private static final SecondFactorAuthenticationDialogHandler HANDLER = SecondFactorAuthenticationDialogHandler.getInstance();
    private static String verboseSetting;
    public static void main(String[] args) throws Exception {
        Settings.initialise(new DefaultSettings() {
            @Override public String getString(String name, String defaultValue) {
                if (name.equals(verboseSetting)) return name.equals("LogStructureWhen") ? "open" : "yes";
                return name.equals("SecondFactorDevice") ? "Mobile Authenticator app" : super.getString(name, defaultValue);
            }
        });
        LoginManager.setDefault();
        boolean manual = System.getenv("TOTP_SECRET_FILE") == null;
        Path file = manual ? null : Paths.get(System.getenv("TOTP_SECRET_FILE"));
        try {
            if (manual) {
                noSubmission("Enter Mobile Authenticator app code");
                System.out.println("PASS: unset secret preserves manual authentication");
                return;
            }
            Files.write(file, TotpTest.PUBLIC_KEY.getBytes(StandardCharsets.US_ASCII));
            submit(false);
            submit(true);
            noSubmission("Approve using IB Key");
            for (String setting : new String[]{"LogStructureWhen", "LogComponents"}) {
                verboseSetting = setting;
                noSubmission("Enter Mobile Authenticator app code");
            }
            verboseSetting = null;
            Files.write(file, "invalid-secret".getBytes(StandardCharsets.US_ASCII));
            noSubmission("Enter Mobile Authenticator app code");
            System.out.println("PASS: Gateway dialog submission, method chooser, delayed prompt, duplicate prevention, logging guard and fail-closed paths");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    TotpDialogHandler.closed(window);
                    window.dispose();
                }
            });
            if (file != null) Files.deleteIfExists(file);
        }
    }

    private static void submit(boolean chooser) throws Exception {
        CountDownLatch clicked = new CountDownLatch(1);
        AtomicInteger clicks = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        JDialog[] window = new JDialog[1];
        JTextField[] field = new JTextField[1];
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog();
            window[0] = dialog;
            dialog.setTitle("Second Factor Authentication");
            JPanel form = TotpTest.panel("Preparing authentication");
            field[0] = (JTextField) form.getComponent(1);
            JButton submit = (JButton) form.getComponent(2);
            submit.setEnabled(false);
            field[0].getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { update(); }
                public void removeUpdate(DocumentEvent e) { update(); }
                public void changedUpdate(DocumentEvent e) { update(); }
                private void update() { submit.setEnabled(field[0].getText().length() == 6); }
            });
            submit.addActionListener(event -> {
                try {
                    TotpTest.check(SwingUtilities.isEventDispatchThread(), "Submission must run on EDT");
                    String actual = field[0].getText();
                    String expected = Totp.generate(Totp.decodeBase32(TotpTest.PUBLIC_KEY), System.currentTimeMillis(), 6);
                    TotpTest.check(actual.equals(expected), "Submitted code must match current period");
                    clicks.incrementAndGet();
                } catch (Throwable t) { error.set(t); }
                clicked.countDown();
                // Keep window open, like a rejected code, to test no repeated submissions.
            });
            if (chooser) {
                JPanel select = new JPanel();
                select.add(new JTextArea("Select second factor device"));
                JList<String> methods = new JList<>(new String[]{"IB Key", "Mobile Authenticator app"});
                select.add(methods);
                JButton ok = new JButton("OK");
                ok.addActionListener(event -> {
                    TotpTest.check(methods.getSelectedValue().equals("Mobile Authenticator app"), "IBC selects desired enrolled method");
                    dialog.setContentPane(form);
                    dialog.pack();
                });
                select.add(ok);
                dialog.setContentPane(select);
            } else { dialog.setContentPane(form); }
            dialog.pack();
            dialog.setVisible(true);
            TotpTest.check(HANDLER.recogniseWindow(dialog), "Patched handler recognizes actual Gateway dialog title");
            HANDLER.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            HANDLER.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
            Timer delayed = new Timer(500, event -> ((JLabel) form.getComponent(0)).setText("Enter Mobile Authenticator app code"));
            delayed.setRepeats(false);
            delayed.start();
        });
        TotpTest.check(clicked.await(15, TimeUnit.SECONDS), "TOTP submitted within fresh-code window");
        if (error.get() != null) throw new AssertionError("Submission failed", error.get());
        Thread.sleep(750);
        SwingUtilities.invokeAndWait(() -> {
            TotpTest.check(clicks.get() == 1, "Only one submission per dialog, including duplicate open events");
            TotpTest.check(field[0].getText().length() == 6, "Keep code available for Gateway's deferred readers");
            TotpDialogHandler.closed(window[0]);
            TotpTest.check(field[0].getText().isEmpty(), "Clear code on dialog close");
            window[0].dispose();
        });
    }

    private static void noSubmission(String text) throws Exception {
        AtomicInteger clicks = new AtomicInteger();
        JDialog[] window = new JDialog[1];
        SwingUtilities.invokeAndWait(() -> {
            JDialog dialog = new JDialog();
            window[0] = dialog;
            dialog.setTitle("Second Factor Authentication");
            JPanel form = TotpTest.panel(text);
            ((JButton) form.getComponent(2)).addActionListener(event -> clicks.incrementAndGet());
            dialog.setContentPane(form);
            dialog.pack();
            dialog.setVisible(true);
            HANDLER.handleWindow(dialog, WindowEvent.WINDOW_OPENED);
        });
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> {
            TotpTest.check(clicks.get() == 0, "Wrong method or invalid/unset secret must not submit");
            // IBC's remaining close handling requires a real broker session. Test
            // our cancellation without fabricating that unrelated session state.
            TotpDialogHandler.closed(window[0]);
            window[0].dispose();
        });
    }
}
