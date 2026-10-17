package net.mpy.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Bounded compiled-pattern cache. Python owns match/iterator and snapshot state. */
public final class Regex {
    private Regex() {}
    private static final int I = 2, M = 8, S = 16, U = 32, X = 64, A = 256;
    private static final Map<String, Compiled> CACHE = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Compiled> e) { return size() > 64; }
    };
    private static final class Compiled {
        final Pattern pattern;
        final String translated;
        final int flags;
        final Map<String, Integer> names;
        /** The pattern that refuses an empty match at the start position, built on first use. */
        volatile Pattern nonEmpty;
        Compiled(Pattern p, String source, int f, Map<String, Integer> n) { pattern = p; translated = source; flags = f; names = n; }
    }
    /** Backtracking can revisit a character many times; bound native work per call. */
    private static final class Input implements CharSequence {
        final String text; final int start, end; final int[] budget;
        Input(String text, int end) { this(text, 0, end, new int[] {2_000_000}); }
        Input(String text, int start, int end, int[] budget) { this.text = text; this.start = start; this.end = end; this.budget = budget; }
        @Override public int length() { return end - start; }
        @Override public char charAt(int i) {
            if (--budget[0] < 0) throw new PyException("RuntimeError", "regular expression work limit exceeded");
            if (i < 0 || i >= length()) throw new IndexOutOfBoundsException();
            return text.charAt(start + i);
        }
        @Override public CharSequence subSequence(int a, int b) {
            if (a < 0 || b < a || b > length()) throw new IndexOutOfBoundsException();
            return new Input(text, start + a, start + b, budget);
        }
        @Override public String toString() { return text.substring(start, end); }
    }
    private static String string(Object o) {
        if (!(o instanceof String)) throw PyException.typeError("regular expression requires str pattern and string");
        return (String) o;
    }
    private static int integer(Object o) {
        if (!(o instanceof Long)) throw PyException.typeError("integer required");
        long n = (Long) o;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, n));
    }
    private static PyException bad(String message, int pos) {
        return new PyException("ValueError", message, new Object[] {message, (long) pos});
    }
    private static int bit(char c) {
        switch (c) { case 'i': return I; case 'm': return M; case 's': return S; case 'u': return U; case 'x': return X; case 'a': return A; default: return 0; }
    }
    private static synchronized Compiled compile(String source, int flags) {
        String key = flags + "\u0000" + source;
        Compiled cached = CACHE.get(key);
        if (cached != null) return cached;
        if (source.length() > 16384) throw PyException.valueError("regular expression exceeds 16384 characters");
        if ((flags & ~(I | M | S | U | X | A)) != 0) throw PyException.valueError("unsupported regular expression flags");
        String s = source;
        while (s.startsWith("(?")) {
            int end = s.indexOf(')');
            if (end < 3) break;
            boolean global = true;
            for (int j = 2; j < end; j++) if (bit(s.charAt(j)) == 0) { global = false; break; }
            if (!global) break;
            for (int j = 2; j < end; j++) flags |= bit(s.charAt(j));
            s = s.substring(end + 1);
        }
        if ((flags & (A | U)) == (A | U)) throw PyException.valueError("ASCII and UNICODE flags are incompatible");
        if ((flags & A) == 0) flags |= U;
        StringBuilder out = new StringBuilder();
        Map<String, Integer> names = new LinkedHashMap<>();
        int groups = 0;
        boolean inClass = false;
        for (int p = 0; p < s.length(); p++) {
            char c = s.charAt(p);
            if (c == '\\') {
                if (p + 1 >= s.length()) throw bad("bad escape (end of pattern)", p);
                char next = s.charAt(++p);
                if (next == 'Z' && !inClass) out.append("\\z");
                else out.append(c).append(next);
                continue;
            }
            if (c == '[' && !inClass) inClass = true;
            else if (c == ']' && inClass) inClass = false;
            if (!inClass && c == '#' && (flags & X) != 0) {
                int end = s.indexOf('\n', p);
                if (end < 0) break;
                p = end; out.append('\n'); continue;
            }
            if (!inClass && c == '(') {
                if (s.startsWith("(?#", p)) {
                    int end = s.indexOf(')', p + 3);
                    if (end < 0) throw bad("missing ), unterminated comment", p);
                    p = end; continue;
                }
                if (s.startsWith("(?P<", p)) {
                    int end = s.indexOf('>', p + 4);
                    if (end < 0) throw bad("missing >, unterminated name", p);
                    String name = s.substring(p + 4, end);
                    if (name.isEmpty() || !Character.isUnicodeIdentifierStart(name.charAt(0)) && name.charAt(0) != '_') throw bad("bad character in group name", p);
                    for (int j = 1; j < name.length(); j++) if (!Character.isUnicodeIdentifierPart(name.charAt(j))) throw bad("bad character in group name", p);
                    if (names.containsKey(name)) throw bad("redefinition of group name", p);
                    names.put(name, ++groups);
                    out.append("(?<G").append(groups).append('>'); p = end; continue;
                }
                if (s.startsWith("(?P=", p)) {
                    int end = s.indexOf(')', p + 4);
                    if (end < 0) throw bad("missing ), unterminated name", p);
                    Integer group = names.get(s.substring(p + 4, end));
                    if (group == null) throw bad("unknown group name", p);
                    out.append("\\k<G").append(group).append('>'); p = end; continue;
                }
                if (p + 1 >= s.length() || s.charAt(p + 1) != '?') groups++;
                else if (p + 2 < s.length() && (s.charAt(p + 2) == 'a' || s.charAt(p + 2) == 'u'))
                    throw new PyException("NotImplementedError", "scoped ASCII/UNICODE regular expression flags are not supported");
            }
            out.append(c);
        }
        int jf = Pattern.UNIX_LINES;
        if ((flags & A) == 0) jf |= Pattern.UNICODE_CHARACTER_CLASS;
        if ((flags & I) != 0) jf |= Pattern.CASE_INSENSITIVE | ((flags & A) == 0 ? Pattern.UNICODE_CASE : 0);
        if ((flags & M) != 0) jf |= Pattern.MULTILINE;
        if ((flags & S) != 0) jf |= Pattern.DOTALL;
        if ((flags & X) != 0) jf |= Pattern.COMMENTS;
        try {
            Pattern pattern = Pattern.compile(out.toString(), jf);
            Compiled result = new Compiled(pattern, out.toString(), flags, names);
            CACHE.put(key, result);
            return result;
        } catch (PatternSyntaxException e) { throw bad(e.getDescription(), Math.max(0, e.getIndex())); }
        catch (StackOverflowError e) { throw new PyException("RuntimeError", "regular expression is too deeply nested"); }
    }
    public static Object info(Object[] a) {
        Compiled c = compile(string(a[0]), integer(a[1]));
        PyObj.PyDict names = new PyObj.PyDict();
        c.names.forEach((name, index) -> names.map.put(name, index.longValue()));
        return new PyObj.Tuple(new Object[] {(long) c.flags, (long) c.pattern.matcher("").groupCount(), names});
    }
    public static Object exec(Object[] a) {
        String pattern = string(a[0]), text = string(a[1]);
        boolean anchored = Ops.isTrue(a[2]);
        int pos = Math.max(0, integer(a[3]));
        int end = a.length > 4 ? Math.max(0, Math.min(text.length(), integer(a[4]))) : text.length();
        int flags = a.length > 5 ? integer(a[5]) : 0;
        int mode = a.length > 6 ? integer(a[6]) : 0;
        if (pos > end) return PyObj.NONE;
        Compiled c = compile(pattern, flags);
        Pattern p = c.pattern;
        if (mode == 2) {
            // After an empty match, permit a nonempty alternative at the same position.
            if (c.nonEmpty == null) c.nonEmpty = Pattern.compile("(?:" + c.translated + ")(?!\\G)", p.flags());
            p = c.nonEmpty;
        }
        CharSequence input = new Input(text, end);
        Matcher matcher = p.matcher(input).region(pos, end).useTransparentBounds(true).useAnchoringBounds(false);
        boolean hit;
        try { hit = mode == 1 ? matcher.matches() : anchored ? matcher.lookingAt() : matcher.find(); }
        catch (StackOverflowError e) { throw new PyException("RuntimeError", "regular expression nesting/backtracking is too deep"); }
        if (!hit) return PyObj.NONE;
        PyObj.PyList spans = new PyObj.PyList();
        for (int group = 0; group <= matcher.groupCount(); group++) {
            spans.items.add((long) matcher.start(group)); spans.items.add((long) matcher.end(group));
        }
        return spans;
    }
    public static Object purge(Object[] ignored) { synchronized (Regex.class) { CACHE.clear(); } return PyObj.NONE; }
}
