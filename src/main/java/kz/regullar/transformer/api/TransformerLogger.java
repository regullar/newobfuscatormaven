package kz.regullar.transformer.api;

import java.time.LocalTime;

public final class TransformerLogger {

    private boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void debug(String message, Object... args) {
        log("DEBUG", message, args);
    }

    public void info(String message, Object... args) {
        log("INFO", message, args);
    }

    public void warn(String message, Object... args) {
        log("WARN", message, args);
    }

    public void error(String message, Object... args) {
        log("ERROR", message, args);
    }

    private void log(String level, String message, Object... args) {
        if (!enabled) return;
        String formatted = format(message, args);
        System.out.println("[" + LocalTime.now() + "] [" + level + "] " + formatted);
    }

    private String format(String message, Object... args) {
        if (args == null || args.length == 0) return message;

        StringBuilder sb = new StringBuilder();
        int argIndex = 0;
        for (int i = 0; i < message.length(); i++) {
            if (i + 1 < message.length() && message.charAt(i) == '{' && message.charAt(i + 1) == '}') {
                if (argIndex < args.length) {
                    sb.append(args[argIndex++]);
                } else {
                    sb.append("{}");
                }
                i++;
            } else {
                sb.append(message.charAt(i));
            }
        }
        return sb.toString();
    }
}
