// SPDX-License-Identifier: GPL-3.0-or-later
package ibcalpha.ibc;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;

/** Runs on Swing's EDT. Unknown prompts remain manual; no blind Enter fallback. */
final class TotpDialogHandler {
    private static final Map<Window, Timer> pending = new HashMap<>();
    private static final Map<Window, JTextField> submittedFields = new HashMap<>();
    private static final long TIMEOUT_MS = 120000;

    private TotpDialogHandler() {}

    static void opened(Window window) {
        String path = System.getenv("TOTP_SECRET_FILE");
        if (path == null || path.trim().isEmpty() || pending.containsKey(window)) return;
        String logWhen = Settings.settings().getString("LogStructureWhen", "never");
        String logComponents = Settings.settings().getString("LogComponents", "ignore");
        if (!"never".equalsIgnoreCase(logWhen) || !"ignore".equalsIgnoreCase(logComponents)) {
            Utils.logError("TOTP: disable IBC component/structure logging before automatic code entry");
            return;
        }
        long started = System.nanoTime();
        Timer timer = new Timer(250, event -> {
            if (!window.isDisplayable()) {
                closed(window);
                return;
            }
            if ((System.nanoTime() - started) / 1000000 >= TIMEOUT_MS) {
                pending.get(window).stop();
                Utils.logError("TOTP: no supported Mobile Authenticator prompt; check enrolled/default method or complete login manually");
                return;
            }
            Form form = inspect(window);
            if (form == null || !Totp.freshEnough(System.currentTimeMillis())) return;
            // Mark this dialog consumed BEFORE clicking: doClick may re-enter the EDT.
            pending.get(window).stop();
            byte[] key = null;
            boolean submitted = false;
            try {
                key = Totp.readSecret(path);
                long now = System.currentTimeMillis();
                if (!Totp.freshEnough(now)) {
                    pending.get(window).start();
                    return;
                }
                form.field.setText(Totp.generate(key, now, 6));
                if (!form.submit.isEnabled()) {
                    Utils.logError("TOTP: submit button disabled after entry; complete login manually");
                    return;
                }
                Utils.logToConsole("TOTP: submitting Mobile Authenticator code (value not logged)");
                submittedFields.put(window, form.field);
                form.submit.doClick(0);
                submitted = true;
            } catch (Exception e) {
                // Do not log exception messages: paths, UI content or secret data may appear.
                Utils.logError("TOTP: code submission failed; check secret configuration and complete login manually");
            } finally {
                if (key != null) Arrays.fill(key, (byte) 0);
                // Let Gateway finish reading the field, including deferred action
                // listeners. Clear it on close rather than racing its submission.
                if (!submitted) {
                    submittedFields.remove(window);
                    form.field.setText("");
                }
            }
        });
        pending.put(window, timer);
        timer.start();
    }

    static void closed(Window window) {
        Timer timer = pending.remove(window);
        if (timer != null) timer.stop();
        JTextField field = submittedFields.remove(window);
        if (field != null) field.setText("");
    }

    static Form inspect(Container root) {
        List<Component> components = new ArrayList<>();
        collect(root, components);
        boolean prompt = false;
        List<JTextField> inputs = new ArrayList<>();
        List<JButton> buttons = new ArrayList<>();
        for (Component component : components) {
            if (!component.isVisible()) continue;
            String text = null;
            if (component instanceof JLabel) text = ((JLabel) component).getText();
            if (component instanceof JTextComponent && !((JTextComponent) component).isEditable()) {
                text = ((JTextComponent) component).getText();
            }
            if (text != null) {
                String normalized = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (normalized.contains("enter mobile authenticator app code")) prompt = true;
                // Reject explicit conflicting challenges, even if an option list mentions TOTP.
                if (normalized.contains("enter ib key") || normalized.contains("security code card")
                        || normalized.contains("enter sms") || normalized.contains("enter the sms")) return null;
            }
            if (component instanceof JTextField) {
                JTextField field = (JTextField) component;
                if (field.isEditable() && field.isEnabled()) inputs.add(field);
            }
            if (component instanceof JButton) {
                JButton button = (JButton) component;
                if ("OK".equals(button.getText()) || "Submit".equals(button.getText()) || "Verify".equals(button.getText())) {
                    buttons.add(button);
                }
            }
        }
        if (!prompt || inputs.size() != 1 || buttons.size() != 1 || !inputs.get(0).getText().isEmpty()) return null;
        return new Form(inputs.get(0), buttons.get(0));
    }

    private static void collect(Container root, List<Component> output) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            output.add(child);
            if (child instanceof Container) collect((Container) child, output);
        }
    }

    static final class Form {
        final JTextField field;
        final JButton submit;
        Form(JTextField field, JButton submit) { this.field = field; this.submit = submit; }
    }
}
