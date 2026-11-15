package com.mrvoxel.opstools.stash;

import java.util.Locale;

/**
 * Small fuzzy matcher for {@code .find}. Scores a query against an item name from 0 (nothing alike) to 1 (identical).
 * Handles typos ("obsidain"), partial words ("shulk"), word order ("box shulker") and ids ("minecraft:end_crystal").
 */
public final class FuzzySearchUtil {
    private FuzzySearchUtil() {}

    /** Default minimum score for a match. */
    public static final double DEFAULT_THRESHOLD = 0.6;

    /** "minecraft:diamond_block" -> "diamond_block". */
    public static String stripNamespace(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Lowercase, namespace removed, everything that is not a letter or digit turned into single spaces. */
    public static String normalize(String text) {
        if (text == null) return "";
        String s = stripNamespace(text).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        return out.toString().trim();
    }

    /** Match quality between a search query and a candidate name, 0..1. */
    public static double score(String query, String candidate) {
        String q = normalize(query);
        String c = normalize(candidate);
        if (q.isEmpty() || c.isEmpty()) return 0.0;
        if (q.equals(c)) return 1.0;

        String qCompact = q.replace(" ", "");
        String cCompact = c.replace(" ", "");
        if (qCompact.equals(cCompact)) return 0.98;
        if (cCompact.startsWith(qCompact)) return 0.95;
        if (cCompact.contains(qCompact)) return 0.9;

        // Every query word should have a close word in the candidate; average the best matches.
        String[] qTokens = q.split(" ");
        String[] cTokens = c.split(" ");
        double sum = 0.0;
        for (String qt : qTokens) {
            double best = 0.0;
            for (String ct : cTokens) {
                best = Math.max(best, tokenScore(qt, ct));
            }
            sum += best;
        }
        double tokenAverage = sum / qTokens.length;

        double whole = similarity(qCompact, cCompact);
        return Math.max(tokenAverage * 0.95, whole);
    }

    private static double tokenScore(String q, String c) {
        if (q.equals(c)) return 1.0;
        if (c.startsWith(q)) return 0.92;
        if (c.contains(q) && q.length() >= 3) return 0.85;
        return similarity(q, c);
    }

    /** 1 - (edit distance / longer length). */
    private static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - levenshtein(a, b) / (double) max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
