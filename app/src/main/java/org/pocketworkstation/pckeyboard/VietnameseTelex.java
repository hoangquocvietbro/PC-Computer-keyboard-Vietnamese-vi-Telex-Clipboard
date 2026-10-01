package org.pocketworkstation.pckeyboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Telex input engine for Vietnamese.
 *
 * <p>Pure logic, no Android dependencies so it stays unit-testable.
 * The caller passes the current syllable (text since the last word
 * separator, without the newly typed key) plus the newly typed ASCII
 * key, and gets back the replacement syllable when a Telex rule fires.
 *
 * <p>Supported rules (case preserving):
 * <ul>
 *   <li>aa-&gt;â, ee-&gt;ê, oo-&gt;ô; pressing the creator again removes the
 *       circumflex AND writes the key (â+a-&gt;aa, ấ+a-&gt;áa)</li>
 *   <li>aw-&gt;ă, ow-&gt;ơ, uw-&gt;ư (+ undo with a second w)</li>
 *   <li>dd-&gt;đ; a further d removes it AND writes the key (đ+d-&gt;dd)</li>
 *   <li>s/f/r/x/j add sắc/huyền/hỏi/ngã/nặng (replaces another tone);
 *       pressing the same key again removes the tone AND writes the key
 *       (á+s-&gt;as); once the toneless syllable ends with that letter,
 *       further presses write it normally (as+s-&gt;ass)</li>
 *   <li>Appending a different vowel never resets tone/marks (má+i-&gt;mái)</li>
 *   <li>z strips tone first, then restores ăâêôơư/đ keystrokes</li>
 * </ul>
 */
public final class VietnameseTelex {

    private VietnameseTelex() {}

    /** Result of {@link #process}. */
    public static final class Result {
        public final String text;
        public final boolean transformed;
        private Result(String text, boolean transformed) {
            this.text = text;
            this.transformed = transformed;
        }
        public static Result transformed(String text) {
            return new Result(text, true);
        }
        public static Result literal(String text) {
            return new Result(text, false);
        }
    }

    // Row ids for the vowel tables below.
    private static final int ROW_A = 0;
    private static final int ROW_BREVE_A = 1; // ă
    private static final int ROW_CIRC_A = 2;  // â
    private static final int ROW_E = 3;
    private static final int ROW_CIRC_E = 4;  // ê
    private static final int ROW_I = 5;
    private static final int ROW_O = 6;
    private static final int ROW_CIRC_O = 7;  // ô
    private static final int ROW_HORN_O = 8;  // ơ
    private static final int ROW_U = 9;
    private static final int ROW_HORN_U = 10; // ư
    private static final int ROW_Y = 11;

    // Tone ids.
    private static final int TONE_NGANG = 0;
    private static final int TONE_SAC = 1;
    private static final int TONE_HUYEN = 2;
    private static final int TONE_HOI = 3;
    private static final int TONE_NGA = 4;
    private static final int TONE_NANG = 5;

    private static final char[][] LOWER = {
        {'a', 'á', 'à', 'ả', 'ã', 'ạ'},
        {'ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'},
        {'â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'},
        {'e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'},
        {'ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'},
        {'i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'},
        {'o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'},
        {'ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'},
        {'ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'},
        {'u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'},
        {'ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'},
        {'y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'},
    };

    private static final char[][] UPPER = {
        {'A', 'Á', 'À', 'Ả', 'Ã', 'Ạ'},
        {'Ă', 'Ắ', 'Ằ', 'Ẳ', 'Ẵ', 'Ặ'},
        {'Â', 'Ấ', 'Ầ', 'Ẩ', 'Ẫ', 'Ậ'},
        {'E', 'É', 'È', 'Ẻ', 'Ẽ', 'Ẹ'},
        {'Ê', 'Ế', 'Ề', 'Ể', 'Ễ', 'Ệ'},
        {'I', 'Í', 'Ì', 'Ỉ', 'Ĩ', 'Ị'},
        {'O', 'Ó', 'Ò', 'Ỏ', 'Õ', 'Ọ'},
        {'Ô', 'Ố', 'Ồ', 'Ổ', 'Ỗ', 'Ộ'},
        {'Ơ', 'Ớ', 'Ờ', 'Ở', 'Ỡ', 'Ợ'},
        {'U', 'Ú', 'Ù', 'Ủ', 'Ũ', 'Ụ'},
        {'Ư', 'Ứ', 'Ừ', 'Ử', 'Ữ', 'Ự'},
        {'Y', 'Ý', 'Ỳ', 'Ỷ', 'Ỹ', 'Ỵ'},
    };

    /** char -> {row, tone, isUpper(0/1)}. */
    private static final Map<Character, int[]> DECOMPOSE = new HashMap<Character, int[]>();
    static {
        for (int row = 0; row < 12; row++) {
            for (int tone = 0; tone < 6; tone++) {
                DECOMPOSE.put(Character.valueOf(LOWER[row][tone]), new int[]{row, tone, 0});
                DECOMPOSE.put(Character.valueOf(UPPER[row][tone]), new int[]{row, tone, 1});
            }
        }
    }

    private static int[] decompose(char c) {
        return DECOMPOSE.get(Character.valueOf(c));
    }

    private static boolean isVowelChar(char c) {
        return decompose(c) != null;
    }

    private static char compose(int row, int tone, boolean upper) {
        return upper ? UPPER[row][tone] : LOWER[row][tone];
    }

    /** True for keys that can ever trigger a Telex rewrite. */
    public static boolean isTrigger(char c) {
        switch (c) {
            case 'a': case 'A':
            case 'e': case 'E':
            case 'o': case 'O':
            case 'u': case 'U':
            case 'd': case 'D':
            case 'w': case 'W':
            case 's': case 'S':
            case 'f': case 'F':
            case 'r': case 'R':
            case 'x': case 'X':
            case 'j': case 'J':
            case 'z': case 'Z':
                return true;
            default:
                return false;
        }
    }

    private static int toneForKey(char c) {
        switch (Character.toLowerCase(c)) {
            case 's': return TONE_SAC;
            case 'f': return TONE_HUYEN;
            case 'r': return TONE_HOI;
            case 'x': return TONE_NGA;
            case 'j': return TONE_NANG;
            default: return -1;
        }
    }

    /**
     * Apply one Telex keystroke.
     *
     * @param syllable current syllable (may be empty, never null in practice)
     * @param newChar  the just-typed key (ASCII, case already resolved)
     * @return transformed syllable, or {@code literal(syllable + newChar)}
     *         with {@code transformed=false} when no rule fires (caller then
     *         inserts the key normally).
     */
    public static Result process(String syllable, char newChar) {
        if (syllable == null) syllable = "";
        char lower = Character.toLowerCase(newChar);

        // --- dd -> đ (2nd d on đ removes it AND writes the key) ---
        if (lower == 'd') {
            if (syllable.length() > 0) {
                char last = syllable.charAt(syllable.length() - 1);
                if (last == 'd' || last == 'D') {
                    boolean upper = Character.isUpperCase(last) || Character.isUpperCase(newChar);
                    String out = syllable.substring(0, syllable.length() - 1)
                            + (upper ? 'Đ' : 'đ');
                    return Result.transformed(out);
                }
                if (last == 'đ' || last == 'Đ') {
                    char base = (last == 'Đ') ? 'D' : 'd';
                    return Result.transformed(
                            syllable.substring(0, syllable.length() - 1) + base + newChar);
                }
            }
            return Result.literal(syllable + newChar);
        }

        // --- w: ă / ơ / ư (+ undo) ---
        if (lower == 'w') {
            // Shortcut: trailing "uoi"/"uo" + w -> "ươi"/"ươ", moving any
            // tone onto ơ. Covers tươi, người, rượu, mướp.
            String ươi = tryUoiShortcut(syllable);
            if (ươi != null) return Result.transformed(ươi);
            if (syllable.length() > 0) {
                char last = syllable.charAt(syllable.length() - 1);
                int[] dl = decompose(last);
                // Undo: ă/w -> aw, ơ/w -> ow, ư/w -> uw.
                if (dl != null && dl[1] == TONE_NGANG
                        && (dl[0] == ROW_BREVE_A || dl[0] == ROW_HORN_O || dl[0] == ROW_HORN_U)) {
                    boolean upper = dl[2] == 1;
                    char base;
                    if (dl[0] == ROW_BREVE_A) base = 'a';
                    else if (dl[0] == ROW_HORN_O) base = 'o';
                    else base = 'u';
                    if (upper) base = Character.toUpperCase(base);
                    char wCh = upper ? 'W' : 'w';
                    return Result.transformed(
                            syllable.substring(0, syllable.length() - 1) + base + wCh);
                }
                // Transform the last plain a/o/u (tone preserving).
                for (int i = syllable.length() - 1; i >= 0; i--) {
                    char c = syllable.charAt(i);
                    int[] d = decompose(c);
                    if (d == null) continue;
                    int targetRow = -1;
                    if (d[0] == ROW_A) targetRow = ROW_BREVE_A;
                    else if (d[0] == ROW_O) targetRow = ROW_HORN_O;
                    else if (d[0] == ROW_U) targetRow = ROW_HORN_U;
                    if (targetRow >= 0) {
                        char repl = compose(targetRow, d[1], d[2] == 1);
                        return Result.transformed(
                                syllable.substring(0, i) + repl + syllable.substring(i + 1));
                    }
                    // Stop at the first vowel cluster member that cannot take w:
                    // only look at the trailing vowel run.
                    if (d[0] == ROW_BREVE_A || d[0] == ROW_CIRC_A || d[0] == ROW_CIRC_E
                            || d[0] == ROW_CIRC_O || d[0] == ROW_HORN_O
                            || d[0] == ROW_HORN_U) {
                        break;
                    }
                }
            }
            return Result.literal(syllable + newChar);
        }

        // --- doubling: aa -> â, ee -> ê, oo -> ô (+ undo) ---
        if (lower == 'a' || lower == 'e' || lower == 'o') {
            int plainRow = lower == 'a' ? ROW_A : (lower == 'e' ? ROW_E : ROW_O);
            int markedRow = lower == 'a' ? ROW_CIRC_A : (lower == 'e' ? ROW_CIRC_E : ROW_CIRC_O);
            if (syllable.length() > 0) {
                char last = syllable.charAt(syllable.length() - 1);
                int[] dl = decompose(last);
                if (dl != null) {
                    // 2nd press of the creator: remove the circumflex (keeping
                    // any tone) AND write the key: â+a -> aa, ấ+a -> áa.
                    if (dl[0] == markedRow) {
                        char base = compose(plainRow, dl[1], dl[2] == 1);
                        return Result.transformed(
                                syllable.substring(0, syllable.length() - 1) + base + newChar);
                    }
                    if (dl[0] == plainRow && Character.toLowerCase(last) == lower) {
                        char repl = compose(markedRow, dl[1], dl[2] == 1);
                        return Result.transformed(
                                syllable.substring(0, syllable.length() - 1) + repl);
                    }
                }
            }
            return Result.literal(syllable + newChar);
        }

        // --- tones: s / f / r / x / j ---
        // 1st press creates the tone, 2nd press of the same key removes it
        // AND writes the key, 3rd+ presses write the key normally.
        int tone = toneForKey(newChar);
        if (tone >= 0) {
            int idx = findToneIndex(syllable);
            if (idx < 0) return Result.literal(syllable + newChar);
            char target = syllable.charAt(idx);
            int[] d = decompose(target);
            if (d == null) return Result.literal(syllable + newChar);
            if (d[1] == tone) {
                char toneless = compose(d[0], TONE_NGANG, d[2] == 1);
                return Result.transformed(
                        syllable.substring(0, idx) + toneless
                                + syllable.substring(idx + 1) + newChar);
            }
            if (d[1] == TONE_NGANG && endsWithLetter(syllable, newChar)) {
                // Toneless syllable already ending with this letter
                // (e.g. after an undo): write it literally.
                return Result.literal(syllable + newChar);
            }
            char repl = compose(d[0], tone, d[2] == 1);
            return Result.transformed(
                    syllable.substring(0, idx) + repl + syllable.substring(idx + 1));
        }

        // --- z: strip tone, then mark ---
        if (lower == 'z') {
            if (syllable.length() == 0) return Result.literal(syllable + newChar);
            int idx = findToneIndex(syllable);
            if (idx >= 0) {
                char target = syllable.charAt(idx);
                int[] d = decompose(target);
                if (d != null && d[1] != TONE_NGANG) {
                    char repl = compose(d[0], TONE_NGANG, d[2] == 1);
                    return Result.transformed(
                            syllable.substring(0, idx) + repl + syllable.substring(idx + 1));
                }
            }
            // No tone: undo one mark (last marked vowel or đ), restoring
            // the keystrokes that produced it (â->aa, ă->aw, ...).
            for (int i = syllable.length() - 1; i >= 0; i--) {
                char c = syllable.charAt(i);
                if (c == 'đ') return Result.transformed(
                        syllable.substring(0, i) + 'd' + syllable.substring(i + 1));
                if (c == 'Đ') return Result.transformed(
                        syllable.substring(0, i) + 'D' + syllable.substring(i + 1));
                int[] d = decompose(c);
                if (d == null) continue;
                boolean upper = d[2] == 1;
                String undo = null;
                if (d[0] == ROW_CIRC_A) {
                    char p = upper ? 'A' : 'a';
                    undo = "" + p + p;
                } else if (d[0] == ROW_CIRC_E) {
                    char p = upper ? 'E' : 'e';
                    undo = "" + p + p;
                } else if (d[0] == ROW_CIRC_O) {
                    char p = upper ? 'O' : 'o';
                    undo = "" + p + p;
                } else if (d[0] == ROW_BREVE_A) {
                    undo = upper ? "AW" : "aw";
                } else if (d[0] == ROW_HORN_O) {
                    undo = upper ? "OW" : "ow";
                } else if (d[0] == ROW_HORN_U) {
                    undo = upper ? "UW" : "uw";
                }
                if (undo != null) {
                    return Result.transformed(
                            syllable.substring(0, i) + undo + syllable.substring(i + 1));
                }
            }
            return Result.literal(syllable + newChar);
        }

        return Result.literal(syllable + newChar);
    }

    /**
     * Locate the vowel that should carry the tone (classical placement).
     * Prefers a marked vowel (ăâêôơư); otherwise the middle vowel of a
     * cluster of three or more (ngoài), the first vowel of an open pair
     * (hòa), or the last vowel of a closed pair (hoáng).
     * Skips the 'u' of "qu" and the 'i' of "gi" prefixes.
     */
    private static int findToneIndex(String syllable) {
        int n = syllable.length();
        if (n == 0) return -1;
        boolean skipU = n >= 2
                && (syllable.charAt(0) == 'q' || syllable.charAt(0) == 'Q')
                && isUFamily(syllable.charAt(1));
        boolean skipI = n > 2
                && (syllable.charAt(0) == 'g' || syllable.charAt(0) == 'G')
                && (syllable.charAt(1) == 'i' || syllable.charAt(1) == 'I');
        java.util.ArrayList<Integer> idx = new java.util.ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (i == 1 && ((skipU && isUFamily(syllable.charAt(i)))
                    || (skipI && (syllable.charAt(i) == 'i' || syllable.charAt(i) == 'I')))) {
                continue;
            }
            if (isVowelChar(syllable.charAt(i))) idx.add(i);
        }
        if (idx.isEmpty()) return -1;
        // Prefer the rightmost marked vowel (covers ươ -> ơ, iêu -> ê, uôi -> ô).
        for (int k = idx.size() - 1; k >= 0; k--) {
            int[] d = decompose(syllable.charAt(idx.get(k)));
            if (d != null && (d[0] == ROW_BREVE_A || d[0] == ROW_CIRC_A
                    || d[0] == ROW_CIRC_E || d[0] == ROW_CIRC_O
                    || d[0] == ROW_HORN_O || d[0] == ROW_HORN_U)) {
                return idx.get(k);
            }
        }
        if (idx.size() == 1) return idx.get(0);
        if (idx.size() >= 3) return idx.get(idx.size() / 2);
        // Classical placement (Unikey default) for vowel pairs: an open
        // syllable (ending in a vowel) takes the tone on the first vowel
        // (hòa, mái, kía); a closed syllable (ending in consonants) on the
        // last vowel, i.e. the one before the finals (hoáng, toán).
        boolean closed = !isVowelChar(syllable.charAt(n - 1));
        if (closed) return idx.get(idx.size() - 1);
        return idx.get(0);
    }

    private static boolean isUFamily(char c) {
        int[] d = decompose(c);
        if (d == null) return false;
        return d[0] == ROW_U || d[0] == ROW_HORN_U;
    }

    private static boolean endsWithLetter(String syllable, char key) {
        return syllable.length() > 0
                && Character.toLowerCase(syllable.charAt(syllable.length() - 1))
                        == Character.toLowerCase(key);
    }

    /**
     * Rewrite a trailing plain "uoi"/"uo" (or "ưoi"/"ưo") for a Telex 'w'
     * into "ươi"/"ươ", transferring any tone onto ơ. Returns null when the
     * tail does not match.
     */
    private static String tryUoiShortcut(String syllable) {
        int n = syllable.length();
        if (n < 2) return null;
        int uPos = -1, oPos = -1, iPos = -1;
        if (n >= 3) {
            int[] du = decompose(syllable.charAt(n - 3));
            int[] dO = decompose(syllable.charAt(n - 2));
            char i = syllable.charAt(n - 1);
            if (du != null && dO != null
                    && (du[0] == ROW_U || du[0] == ROW_HORN_U)
                    && dO[0] == ROW_O
                    && (i == 'i' || i == 'I')) {
                uPos = n - 3;
                oPos = n - 2;
                iPos = n - 1;
            }
        }
        if (uPos < 0) {
            int[] du = decompose(syllable.charAt(n - 2));
            int[] dO = decompose(syllable.charAt(n - 1));
            if (du != null && dO != null
                    && (du[0] == ROW_U || du[0] == ROW_HORN_U)
                    && dO[0] == ROW_O) {
                uPos = n - 2;
                oPos = n - 1;
            }
        }
        if (uPos < 0) return null;
        // Don't break the "qu" digraph: the 'u' of "qu" is a consonant.
        if (uPos == 1 && (syllable.charAt(0) == 'q' || syllable.charAt(0) == 'Q')) {
            return null;
        }
        int[] du = decompose(syllable.charAt(uPos));
        int[] dO = decompose(syllable.charAt(oPos));
        int tone = du[1] != TONE_NGANG ? du[1] : dO[1];
        StringBuilder out = new StringBuilder(syllable.substring(0, uPos));
        out.append(compose(ROW_HORN_U, TONE_NGANG, du[2] == 1));
        out.append(compose(ROW_HORN_O, tone, dO[2] == 1));
        if (iPos >= 0) out.append(syllable.charAt(iPos));
        out.append(syllable.substring(iPos >= 0 ? iPos + 1 : oPos + 1));
        return out.toString();
    }
}
