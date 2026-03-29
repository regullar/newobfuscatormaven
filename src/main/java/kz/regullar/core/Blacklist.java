package kz.regullar.core;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class Blacklist {
    private final Set<String> exactMatches = new HashSet<>();
    private final Set<String> packagePrefixes = new HashSet<>();
    private final Set<Pattern> regexPatterns = new HashSet<>();

    public Blacklist() {
    }

    public Blacklist(Set<String> patterns) {
        if (patterns != null) {
            patterns.forEach(this::add);
        }
    }

    public void add(String pattern) {
        if (pattern == null || pattern.isEmpty())
            return;

        if (pattern.startsWith("regex:")) {
            try {
                regexPatterns.add(Pattern.compile(pattern.substring(6)));
            } catch (Exception e) {
                System.err.println("Invalid regex pattern in blacklist: " + pattern);
            }
        } else if (pattern.endsWith("/")) {
            packagePrefixes.add(pattern);
        } else {
            if (pattern.endsWith(".class")) {
                exactMatches.add(pattern.substring(0, pattern.length() - 6));
            } else {
                exactMatches.add(pattern);
            }
        }
    }

    public boolean isBlacklisted(String name) {
        for (Pattern pattern : regexPatterns) {
            if (pattern.matcher(name).matches())
                return true;
        }

        for (String prefix : packagePrefixes) {
            if (name.startsWith(prefix))
                return true;
        }

        String internalName = name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;

        return exactMatches.contains(internalName);
    }
}
