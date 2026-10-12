package reobf.mpy4oc.tk;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Tk's text widget. Indices and selections count Unicode code points; the final newline is
 * permanent, as in Tk; only visible display lines go over the wire.
 *
 * Marks (insert, current and the program's, with gravity), tags (in priority order,
 * each a set of ranges with options; the ones that change colour or add a line are drawn),
 * Tk's index forms and modifiers, search, count, dump, and the undo stack (Tk 8.6's units and
 * automatic separators, the modified flag following undo) live here, so a player's typing
 * moves marks, extends tags and is undone like the program's edits. Checked against Tk 8.6.
 */
public final class Text extends TkWidget {
    public static final int MAX_BYTES = 512 * 1024, MAX_CHARS = 262144, MAX_LINES = 20000;
    static final int MAX_MARKS = 1024, MAX_TAGS = 256, MAX_RANGES = 8192, MAX_UNDO_CHARS = 1 << 20, MAX_UNDO_ATOMS = 16384;
    /** Styled runs drawn on one display line at most (more are drawn with the last run's style). */
    static final int MAX_RUNS = 16;
    private int[] chars = {'\n'}, starts = {0, 1};
    int bytes = 1, expandedBytes = 1, columns = 80, lines = 24, cursor, anchor, first = -1, last = -1, top, scrollX;
    int xscrollcommand, yscrollcommand;
    String wrap = "char";
    boolean modified;
    // ---- the undo stack and options --------------------------------------------------------
    boolean undo, autoseparators = true;
    int maxundo;
    /** The program's callback for notices (modified, selection). */
    int notify;
    /** -inactiveselectbackground and -insertbackground (-selectbackground and -selectforeground are the sel tag's colours). */
    int inactiveSelectBackground = 0xC3C3C3, insertBackground = 0x000000;
    private int layoutWidth = -1, maxWidth, goalX = -1;
    private String layoutWrap = "", lastX = "", lastY = "";
    private final List<Row> rows = new ArrayList<>();
    static final class Row {
        final int start, end, width;
        Row(int start, int end, int width) { this.start=start; this.end=end; this.width=width; }
    }

    // ---- marks ---------------------------------------------------------------------------
    static final class Mark {
        int pos; boolean right = true; long seq;
        Mark(int pos, long seq) { this.pos = pos; this.seq = seq; }
    }
    /** The program's marks; insert is {@link #cursor}, current is {@link #current}. */
    final Map<String, Mark> marks = new LinkedHashMap<>();
    int current;
    private boolean insertRight = true, currentRight = true;
    private long markSeq = 2;
    /** insert and current keep the order Tk creates them in (insert first). */
    private long insertSeq = 1, currentSeq;
    private long tagSeq = 1;

    // ---- tags ----------------------------------------------------------------------------
    static final List<String> TAG_COLORS = Arrays.asList("background", "foreground", "underlinefg", "overstrikefg", "lmargincolor", "rmargincolor",
            "selectbackground", "selectforeground");
    static final String[] TAG_OPTIONS = {"background", "bgstipple", "borderwidth", "elide", "fgstipple", "font", "foreground", "justify",
            "lmargin1", "lmargin2", "lmargincolor", "offset", "overstrike", "overstrikefg", "relief", "rmargin", "rmargincolor",
            "selectbackground", "selectforeground", "spacing1", "spacing2", "spacing3", "tabs", "tabstyle", "underline", "underlinefg", "wrap"};
    static final class Tag {
        final String name;
        long seq;
        /** Sorted, disjoint, non-touching [start, end) ranges. */
        final List<int[]> ranges = new ArrayList<>();
        /** Boundary insertion order, shared with marks: key = position * 2 + (tagoff ? 1 : 0). */
        final Map<Long, Long> orders = new LinkedHashMap<>();
        final Map<String, String> options = new LinkedHashMap<>();
        /** The colour options as numbers (options holds them as the program named them). */
        final Map<String, Integer> colors = new HashMap<>();
        boolean underline, overstrike;
        Tag(String name) { this.name = name; }
        int color(String key) { Integer c = colors.get(key); return c == null ? -1 : c; }
        boolean drawn() { return colors.containsKey("background") || colors.containsKey("foreground") || underline || overstrike; }
        boolean contains(int at) {
            int lo = 0, hi = ranges.size() - 1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; int[] r = ranges.get(mid); if (at < r[0]) hi = mid - 1; else if (at >= r[1]) lo = mid + 1; else return true; }
            return false;
        }
        void add(int a, int b) {
            if (b <= a) return;
            List<int[]> out = new ArrayList<>();
            boolean placed = false;
            for (int[] r : ranges) {
                if (r[1] < a) out.add(r);
                else if (r[0] > b) { if (!placed) { out.add(new int[] {a, b}); placed = true; } out.add(r); }
                else { a = Math.min(a, r[0]); b = Math.max(b, r[1]); }
            }
            if (!placed) out.add(new int[] {a, b});
            ranges.clear(); ranges.addAll(out);
        }
        void remove(int a, int b) {
            if (b <= a) return;
            List<int[]> out = new ArrayList<>();
            for (int[] r : ranges) {
                if (r[1] <= a || r[0] >= b) { out.add(r); continue; }
                if (r[0] < a) out.add(new int[] {r[0], a});
                if (r[1] > b) out.add(new int[] {b, r[1]});
            }
            ranges.clear(); ranges.addAll(out);
        }
    }
    /** In priority order, lowest first: sel is created first, as in Tk. */
    final List<Tag> tags = new ArrayList<>(Collections.singletonList(new Tag("sel")));

    // ---- undo ----------------------------------------------------------------------------
    private static final int OTHER = 0, INSERT = 1, DELETE = 2, REPLACE = 3;
    private static final class Atom {
        final int kind, pos; final int[] text;
        Atom(int kind, int pos, int[] text) { this.kind = kind; this.pos = pos; this.text = text; }
    }
    private final Deque<List<Atom>> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    private List<Atom> openUnit;
    private int lastMode = OTHER, undoChars, undoAtoms;
    /** Edits since the text was last marked unmodified, as Tk counts them (undo takes them back). */
    private int dirty;
    private boolean dirtyForced;
    /** While undoing or redoing: nothing is recorded. */
    private boolean replaying;

    private static final Pattern POINT = Pattern.compile("@-?[0-9]+,-?[0-9]+");
    private static final Pattern LINE_INDEX = Pattern.compile("[+-]?[0-9]+\\.(?:[+-]?[0-9]+|end)");
    private static final Pattern NUMBER = Pattern.compile("^([+-])\\s*([0-9]+)\\s*");

    public Text(Frame parent) {
        super(parent.tree, parent, Kind.TEXT); background=0xFFFFFF; border=1; padLeft=padTop=padRight=padBottom=2; relief=Relief.SUNKEN;
        selDefaults();
    }
    /** Tk's sel tag: -selectbackground and -selectforeground, a raised border of 0. */
    private void selDefaults() {
        Tag sel = sel();
        sel.options.put("background", "#c3c3c3"); sel.colors.put("background", 0xC3C3C3);
        sel.options.put("borderwidth", "0");
        sel.options.put("foreground", "#000000"); sel.colors.put("foreground", 0x000000);
        sel.options.put("relief", "raised");
    }
    public int length() { return chars.length; }
    private int clamp(long at) { return (int)Math.max(0, Math.min(chars.length, at)); }
    public String get(int a, int b) { a=clamp(a); b=Math.max(a,clamp(b)); return new String(chars,a,b-a); }
    public String content() { return get(0,length()); }
    public boolean selectionPresent() { return first>=0 && last>first; }
    public String selectedText() {
        if (!selectionPresent()) return "";
        StringBuilder out = new StringBuilder();
        for (int[] r : sel().ranges) out.append(get(r[0], r[1]));
        return out.toString();
    }
    Tag sel() { return tag("sel"); }
    /** The selection's extent (first, last) from the sel tag's ranges. */
    private void syncSel() {
        List<int[]> r = sel().ranges;
        if (r.isEmpty()) first = last = -1; else { first = r.get(0)[0]; last = r.get(r.size() - 1)[1]; }
    }
    public void selection(int a,int b) {
        alive(); a=clamp(a); b=clamp(b);
        sel().ranges.clear(); if (b > a) sel().ranges.add(new int[] {a, b}); syncOrders(sel());
        syncSel(); tree.changed(false);
    }
    public void selectionClear() { sel().ranges.clear(); sel().orders.clear(); syncSel(); tree.changed(false); }
    private int line(int at) { int n=Arrays.binarySearch(starts,clamp(at)); return n>=0?n:-n-2; }
    public String index(int at) { at=clamp(at); int row=line(at); return (row+1)+"."+(at-starts[row]); }

    // ---- indices -------------------------------------------------------------------------

    private static IllegalArgumentException badIndex(String value) { return new IllegalArgumentException("bad text index \"" + value + "\""); }

    /** A Tk index: line.char, line.end, @x,y, end, a mark, tag.first/tag.last, then modifiers. */
    public int index(Object raw) {
        String value = raw instanceof Number ? raw.toString() : TkOptions.text(raw);
        if (value.length() > 256) throw new IllegalArgumentException("Text index too long");
        String text = value.trim();
        int baseEnd;
        Matcher point = POINT.matcher(text);
        Matcher lineIndex = LINE_INDEX.matcher(text);
        if (point.lookingAt()) baseEnd = point.end();
        else if (lineIndex.lookingAt()) baseEnd = lineIndex.end();
        else {
            baseEnd = 0;
            while (baseEnd < text.length() && !Character.isWhitespace(text.charAt(baseEnd)) && (baseEnd == 0 || text.charAt(baseEnd) != '+' && text.charAt(baseEnd) != '-')) baseEnd++;
        }
        String base = text.substring(0, baseEnd);
        int at = base(base, value);
        String rest = text.substring(baseEnd);
        while (true) {
            rest = rest.trim();
            if (rest.isEmpty()) break;
            Matcher number = NUMBER.matcher(rest);
            if (number.find()) {
                long n = Math.min(Integer.MAX_VALUE, Long.parseLong(number.group(2))) * (number.group(1).equals("-") ? -1 : 1);
                rest = rest.substring(number.end());
                String word = firstWord(rest);
                boolean display = word.equals("display");
                if (word.equals("display") || word.equals("any")) { rest = rest.substring(word.length()).trim(); word = firstWord(rest); }
                if (!word.isEmpty() && "chars".startsWith(word) || !word.isEmpty() && "indices".startsWith(word)) at = clamp(at + n);
                else if (word.length() > 0 && "lines".startsWith(word)) at = display ? displayMove(at, n) : lineMove(at, n);
                else throw badIndex(value);
                rest = rest.substring(word.length());
                continue;
            }
            String word = firstWord(rest);
            boolean display = word.equals("display");
            if (word.equals("display") || word.equals("any")) { rest = rest.substring(word.length()).trim(); word = firstWord(rest); }
            if (word.length() >= 5 && "linestart".startsWith(word)) at = display ? displayStart(at) : starts[line(at)];
            else if (word.length() >= 5 && "lineend".startsWith(word) && !word.equals("lines")) {
                if (display) { bounds(); at = displayEnd(rowAt(at)); }
                else { int r = line(at); at = r + 1 < starts.length ? starts[r + 1] - 1 : length(); }
            }
            else if (word.length() >= 5 && "wordstart".startsWith(word)) at = wordStart(at);
            else if (word.length() >= 5 && "wordend".startsWith(word)) at = wordEnd(at);
            else throw badIndex(value);
            rest = rest.substring(word.length());
        }
        return at;
    }

    private static String firstWord(String s) {
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private int base(String base, String value) {
        if (base.isEmpty()) throw badIndex(value);
        if (base.equals("end")) return length();
        if (base.equals("insert")) return cursor;
        if (base.equals("current")) return Math.min(current, length());
        if (base.startsWith("@")) {
            String[] xy = base.substring(1).split(",");
            if (xy.length != 2) throw badIndex(value);
            try { bounds(); return at(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]), false); }
            catch (NumberFormatException bad) { throw badIndex(value); }
        }
        if (Character.isDigit(base.charAt(0)) || base.charAt(0) == '-' || base.charAt(0) == '+') {
            int dot = base.indexOf('.');
            if (dot < 0) throw badIndex(value);
            long row;
            try { row = Long.parseLong(base.substring(0, dot)); } catch (NumberFormatException bad) { throw badIndex(value); }
            String col = base.substring(dot + 1);
            long column;
            if (col.equals("end")) column = Long.MAX_VALUE;
            else { try { column = Long.parseLong(col); } catch (NumberFormatException bad) { throw badIndex(value); } }
            if (row < 1) return 0;
            if (row >= starts.length) return length();
            int r = (int) row - 1;
            return starts[r] + (int) Math.max(0, Math.min(starts[r + 1] - starts[r] - 1, column));
        }
        Mark mark = marks.get(base);
        if (mark != null) return mark.pos;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            String name = base.substring(0, dot), which = base.substring(dot + 1);
            Tag tag = tag(name);
            if (tag != null && (which.equals("first") || which.equals("last"))) {
                if (tag.ranges.isEmpty()) throw new IllegalArgumentException("text doesn't contain any characters tagged with \"" + name + "\"");
                return which.equals("first") ? tag.ranges.get(0)[0] : tag.ranges.get(tag.ranges.size() - 1)[1];
            }
        }
        throw badIndex(value);
    }

    /** +n lines: the same column on line r+n (or its end); past the last line, end (as Tk). */
    private int lineMove(int at, long n) {
        int r = line(at), column = at - starts[r];
        long t = Math.max(0, Math.min(starts.length - 1, r + n));
        if (t == starts.length - 1) return length();
        return starts[(int) t] + Math.min(column, starts[(int) t + 1] - starts[(int) t] - 1);
    }

    private int displayStart(int at) { bounds(); return at == length() ? at : rows.get(rowAt(at)).start; }
    private int displayEnd(int row) {
        Row r = rows.get(row);
        return r.end < length() && chars[r.end] == '\n' ? r.end : Math.max(r.start, r.end - 1);
    }
    private int displayMove(int at, long n) {
        bounds(); layout();
        if (n == 0) return at;
        int row = at == length() ? rows.size() : rowAt(at), column = at - displayStart(at);
        long target = row + n;
        if (target >= rows.size()) return length() - 1;
        if (target < 0) return 0;
        return Math.min(rows.get((int) target).start + column, displayEnd((int) target));
    }
    private int displayRow(int at) { bounds(); layout(); return at == length() ? rows.size() : rowAt(at); }
    private int displayX(int at) { return at == length() ? 0 : width(displayStart(at), at); }

    private boolean wordChar(int at) { return at >= 0 && at < chars.length && (Character.isLetterOrDigit(chars[at]) || chars[at] == '_'); }
    private int wordStart(int at) {
        if (!wordChar(at)) return at;
        while (at > 0 && wordChar(at - 1)) at--;
        return at;
    }
    private int wordEnd(int at) {
        if (!wordChar(at)) return Math.min(length(), at + 1);
        while (at < length() && wordChar(at)) at++;
        return at;
    }

    // ---- editing: the primitives ----------------------------------------------------------

    private void validate(int[] next) {
        if(next.length>MAX_CHARS)throw new IllegalArgumentException("Text character limit exceeded");
        int size=0,count=0;
        for(int cp:next) {
            if(cp<32&&cp!='\n'&&cp!='\t'||cp==127||cp>=0xD800&&cp<=0xDFFF)throw new IllegalArgumentException("Text requires Unicode plain text");
            size+=cp<128?1:cp<2048?2:cp<65536?3:4; if(cp=='\n')count++;
        }
        int total=size; for(TkWidget w:tree.widgets.values())if(w instanceof Text&&w!=this)total+=((Text)w).bytes;
        if(size>MAX_BYTES||total>2*1024*1024||count>MAX_LINES)throw new IllegalArgumentException("Text byte/line budget exceeded");
    }
    private void assign(int[] next) {
        chars=next; bytes=content().getBytes(StandardCharsets.UTF_8).length;
        expandedBytes = bytes;
        for (int cp : chars) if (cp == '\t') expandedBytes += 7;
        int count=0; for(int cp:chars)if(cp=='\n')count++;
        starts=new int[count+1]; int n=1; for(int i=0;i<chars.length;i++)if(chars[i]=='\n')starts[n++]=i+1;
        layoutWidth=-1; goalX=-1; tree.changed(false);
    }

    /** The text with [a, b) replaced by inserted (validated whole first). */
    private int[] spliced(int a, int b, int[] inserted) {
        long size = (long) length() - (b - a) + inserted.length;
        if (size > MAX_CHARS) throw new IllegalArgumentException("Text character limit exceeded");
        int[] next = new int[(int) size];
        System.arraycopy(chars, 0, next, 0, a); System.arraycopy(inserted, 0, next, a, inserted.length); System.arraycopy(chars, b, next, a + inserted.length, length() - b);
        return next;
    }

    /** Insert at: marks with right gravity at it move after the text; tags as Tk (given ones, or those on both sides). */
    private void rawInsert(int at, int[] inserted, List<String> tagNames) {
        int n = inserted.length;
        if (n == 0) return;
        int[] next = spliced(at, at, inserted);
        validate(next);
        List<Tag> planned = planTags(at, at, n, tagNames);
        if (cursor > at || cursor == at && insertRight) cursor += n;
        if (current > at || current == at && currentRight) current += n;
        if (anchor >= at) anchor += n;
        for (Mark m : marks.values()) if (m.pos > at || m.pos == at && m.right) m.pos += n;
        for (Tag p : planned) {
            Tag t = tagOrNew(p.name);
            shiftOrders(t, at, at, n);
            t.ranges.clear(); t.ranges.addAll(p.ranges);
            syncOrders(t);
        }
        syncSel();
        assign(next);
        record(INSERT, at, inserted);
    }

    /** Validate explicit tags and the resulting range budget before touching text or marks. */
    private List<Tag> planTags(int a, int b, int n, List<String> names) {
        return planTags(tags, a, b, n, names);
    }
    private List<Tag> planTags(List<Tag> existing, int a, int b, int n, List<String> names) {
        Set<String> all = new LinkedHashSet<>();
        Map<String, Tag> previous = new LinkedHashMap<>();
        for (Tag t : existing) { all.add(t.name); previous.put(t.name, t); }
        if (names != null) for (String name : names) {
            if (name == null || name.isEmpty() || name.length() > 256) throw new IllegalArgumentException("bad tag name");
            all.add(name);
        }
        if (all.size() > MAX_TAGS) throw new IllegalArgumentException("too many tags");
        List<Tag> planned = new ArrayList<>(); int count = 0;
        for (String name : all) {
            Tag p = new Tag(name), old = previous.get(name);
            if (old != null) for (int[] r : old.ranges) {
                int start = shrink(r[0], a, b), end = shrink(r[1], a, b);
                if (end <= start) continue;
                if (a <= start) { start += n; end += n; } else if (a < end) end += n;
                if (!p.ranges.isEmpty() && p.ranges.get(p.ranges.size() - 1)[1] >= start)
                    p.ranges.get(p.ranges.size() - 1)[1] = end;
                else p.ranges.add(new int[] {start, end});
            }
            if (names != null && n > 0) { p.remove(a, a + n); if (names.contains(name)) p.add(a, a + n); }
            count += p.ranges.size(); planned.add(p);
        }
        if (count > MAX_RANGES) throw new IllegalArgumentException("too many tag ranges");
        return planned;
    }

    /** Delete [a, b): marks inside go to a, tags shrink. */
    private void rawDelete(int a, int b) {
        int n = b - a;
        if (n <= 0) return;
        int[] removed = Arrays.copyOfRange(chars, a, b);
        int[] next = spliced(a, b, new int[0]);
        cursor = shrink(cursor, a, b); current = shrink(current, a, b); anchor = shrink(anchor, a, b);
        for (Mark m : marks.values()) m.pos = shrink(m.pos, a, b);
        for (Tag t : tags) {
            shiftOrders(t, a, b, 0);
            List<int[]> kept = new ArrayList<>();
            for (int[] r : t.ranges) {
                int s = shrink(r[0], a, b), e = shrink(r[1], a, b);
                if (e > s) {
                    if (!kept.isEmpty() && kept.get(kept.size() - 1)[1] >= s) kept.get(kept.size() - 1)[1] = Math.max(e, kept.get(kept.size() - 1)[1]);
                    else kept.add(new int[] {s, e});
                }
            }
            t.ranges.clear(); t.ranges.addAll(kept);
            syncOrders(t);
        }
        syncSel();
        assign(next);
        record(DELETE, a, removed);
    }
    private static int shrink(int pos, int a, int b) { return pos <= a ? pos : pos >= b ? pos - (b - a) : a; }

    private static int[] codePoints(String value) { return value.replace("\r\n","\n").replace('\r','\n').codePoints().toArray(); }

    /** The program's (and a paste's) insert: tagNames null for Tk's inheriting rule. */
    void insert(int at, String value, List<String> tagNames) {
        alive(); if (!enabled) return;
        if (value.length() > MAX_CHARS * 2) throw new IllegalArgumentException("Text input too long");
        at = Math.min(clamp(at), length() - 1);
        rawInsert(at, codePoints(value), tagNames);
    }
    public void insert(int at, String value) { insert(at, value, null); }
    public void delete(int a,int b) {
        alive(); if (!enabled) return;
        a=clamp(a);b=clamp(b);if(b<=a)return;
        // Tk removes the preceding separator when deleting complete final lines through end.
        if(b==length()&&a>0&&a<length()&&chars[a-1]=='\n')a--;
        a=Math.min(a,length()-1); b=Math.max(a,Math.min(b,length()-1));
        rawDelete(a, b);
    }
    /**
     * replace: the range goes and the text comes in its place, one unit for the undo stack (Tk's
     * TextReplaceCmd: a separator before it unless the last edit was a replace, none inside).
     * Where the inserted text ends.
     */
    int replace(int a, int b, String value, List<String> tagNames) {
        alive();
        a = Math.min(clamp(a), length() - 1); b = Math.max(a, Math.min(clamp(b), length() - 1));
        if (!enabled) return a;
        int[] inserted = codePoints(value);
        validate(spliced(a, b, inserted));
        planTags(a, b, inserted.length, tagNames);
        boolean auto = autoseparators;
        if (undo && auto && lastMode != REPLACE) separator();
        autoseparators = false;
        try { rawDelete(a, b); rawInsert(a, inserted, tagNames); }
        finally { autoseparators = auto; }
        if (undo) lastMode = REPLACE;
        return a + inserted.length;
    }

    public void cursor(int at) { alive(); cursor=Math.min(clamp(at),length()-1); goalX=-1; tree.changed(false); }

    // ---- marks ---------------------------------------------------------------------------

    void markSet(String name, int at) {
        alive();
        at = clamp(at);
        if (name.equals("insert")) { cursor(at); insertSeq = markSeq++; return; }
        if (name.equals("current")) { current = at; currentSeq = markSeq++; tree.changed(false); return; }
        if (name.isEmpty() || name.length() > 256 || Character.isDigit(name.charAt(0)) || name.startsWith("@") || name.equals("end")
                || name.indexOf(' ') >= 0 || name.indexOf('+') >= 0 || name.indexOf('-') >= 0)
            throw new IllegalArgumentException("bad mark name \"" + name + "\"");
        Mark m = marks.get(name);
        if (m == null) {
            if (marks.size() >= MAX_MARKS) throw new IllegalArgumentException("too many marks");
            marks.put(name, new Mark(at, markSeq++));
        } else { m.pos = at; m.seq = markSeq++; }
        tree.changed(false);
    }
    void markUnset(String name) { if (marks.remove(name) != null) tree.changed(false); }
    String gravity(String name, String value) {
        boolean special = name.equals("insert") || name.equals("current");
        Mark m = marks.get(name);
        if (m == null && !special) throw new IllegalArgumentException("there is no mark named \"" + name + "\"");
        if (value == null) return (m != null ? m.right : name.equals("insert") ? insertRight : currentRight) ? "right" : "left";
        if (!value.equals("left") && !value.equals("right")) throw new IllegalArgumentException("bad mark gravity \"" + value + "\": must be left or right");
        boolean toRight = value.equals("right");
        if (right(name) != toRight) {
            if (m != null) { m.right = toRight; m.seq = markSeq++; }
            else if (name.equals("insert")) { insertRight = toRight; insertSeq = markSeq++; }
            else { currentRight = toRight; currentSeq = markSeq++; }
        }
        tree.changed(false);
        return "";
    }
    /** Every mark in Tk's order: by position, then as they were set (insert before current). */
    private List<Object[]> orderedMarks() {
        List<Object[]> all = new ArrayList<>();
        all.add(new Object[] {"insert", cursor, insertSeq});
        all.add(new Object[] {"current", Math.min(current, length()), currentSeq});
        for (Map.Entry<String, Mark> e : marks.entrySet()) all.add(new Object[] {e.getKey(), e.getValue().pos, e.getValue().seq});
        all.sort((x, y) -> {
            int pos = Integer.compare((Integer) x[1], (Integer) y[1]);
            if (pos != 0) return pos;
            boolean xr = right((String) x[0]), yr = right((String) y[0]);
            if (xr != yr) return xr ? 1 : -1;
            return xr ? Long.compare((Long) y[2], (Long) x[2]) : Long.compare((Long) x[2], (Long) y[2]);
        });
        return all;
    }
    private boolean right(String name) { return name.equals("insert") ? insertRight : name.equals("current") ? currentRight : marks.get(name).right; }
    List<String> markNames() { List<String> out = new ArrayList<>(); out.add("insert"); out.add("current"); out.addAll(marks.keySet()); return out; }
    /** mark next/previous: the first mark at or after (before) index; from a mark's name, after (before) that mark. */
    String markNext(String index, boolean next) {
        List<Object[]> all = orderedMarks();
        int from = -1;
        for (int i = 0; i < all.size(); i++) if (all.get(i)[0].equals(index)) from = i;
        if (from >= 0) {
            int i = from + (next ? 1 : -1);
            return i >= 0 && i < all.size() ? (String) all.get(i)[0] : "";
        }
        int at = index(index);
        if (next) { for (Object[] m : all) if ((Integer) m[1] >= at) return (String) m[0]; }
        else { for (int i = all.size() - 1; i >= 0; i--) if ((Integer) all.get(i)[1] < at) return (String) all.get(i)[0]; }
        return "";
    }

    /** The pointer is over the text: the current mark follows it (Tk sets it on motion). */
    void hover(int x, int y) { current = at(x - bounds.x, y - bounds.y, false); }

    // ---- tags ----------------------------------------------------------------------------

    Tag tag(String name) { for (Tag t : tags) if (t.name.equals(name)) return t; return null; }
    private void syncOrders(Tag t) {
        Map<Long, Long> next = new LinkedHashMap<>();
        for (int[] r : t.ranges) for (int end = 0; end < 2; end++) {
            long key = 2L * r[end] + end;
            Long order = t.orders.get(key); next.put(key, order == null ? markSeq++ : order);
        }
        t.orders.clear(); t.orders.putAll(next);
    }
    private void shiftOrders(Tag t, int a, int b, int inserted) {
        Map<Long, Long> next = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> e : t.orders.entrySet()) {
            long end = e.getKey() & 1;
            int pos = shrink((int) (e.getKey() / 2), a, b);
            if (pos > a || pos == a && end == 0) pos += inserted;
            next.put(2L * pos + end, e.getValue());
        }
        t.orders.clear(); t.orders.putAll(next);
    }
    private Tag tagOrNew(String name) {
        Tag t = tag(name);
        if (t != null) return t;
        if (name.isEmpty() || name.length() > 256) throw new IllegalArgumentException("bad tag name");
        if (tags.size() >= MAX_TAGS) throw new IllegalArgumentException("too many tags");
        t = new Tag(name); t.seq = tagSeq++; tags.add(t);
        return t;
    }
    private void checkRanges(Tag t) {
        int total = 0; for (Tag x : tags) total += x.ranges.size();
        if (total > MAX_RANGES) throw new IllegalArgumentException("too many tag ranges");
    }
    void tagAdd(String name, int a, int b) {
        alive();
        Tag t = tagOrNew(name);
        a = clamp(a); b = clamp(b);
        if (a >= length() || b <= a) return;
        Tag planned = new Tag(name);
        for (int[] r : t.ranges) planned.ranges.add(r.clone());
        planned.add(a, b);
        int total = planned.ranges.size(); for (Tag other : tags) if (other != t) total += other.ranges.size();
        if (total > MAX_RANGES) throw new IllegalArgumentException("too many tag ranges");
        t.ranges.clear(); t.ranges.addAll(planned.ranges);
        syncOrders(t);
        if (t == sel()) syncSel();
        tree.changed(false);
    }
    void tagRemove(String name, int a, int b) {
        alive();
        Tag t = tag(name);
        if (t == null) return;
        Tag planned = new Tag(name);
        for (int[] r : t.ranges) planned.ranges.add(r.clone());
        planned.remove(clamp(a), clamp(b));
        int total = planned.ranges.size(); for (Tag other : tags) if (other != t) total += other.ranges.size();
        if (total > MAX_RANGES) throw new IllegalArgumentException("too many tag ranges");
        t.ranges.clear(); t.ranges.addAll(planned.ranges);
        syncOrders(t);
        if (t == sel()) syncSel();
        tree.changed(false);
    }
    void tagDelete(String name) {
        Tag t = tag(name);
        if (t == null) return;
        if (t == sel()) { t.ranges.clear(); t.orders.clear(); syncSel(); }
        else tags.remove(t);
        tree.changed(false);
    }
    List<String> tagNames(Integer at) {
        List<String> out = new ArrayList<>();
        for (Tag t : tags) if (at == null || t.contains(at)) out.add(t.name);
        return out;
    }
    /** nextrange: the first range starting at or after a (and before b); prevrange: the last starting before a (and at or after b). */
    int[] tagRange(String name, int a, int b, boolean next) {
        Tag t = tag(name);
        if (t == null) return null;
        if (next) { for (int[] r : t.ranges) if (r[0] >= a && r[0] < b) return r.clone(); }
        else { for (int i = t.ranges.size() - 1; i >= 0; i--) { int[] r = t.ranges.get(i); if (r[0] < a && r[0] >= b) return r.clone(); } }
        return null;
    }
    void tagRaise(String name, String other, boolean raise) {
        Tag t = tag(name), o = other == null ? null : tag(other);
        if (t == null) throw new IllegalArgumentException("tag \"" + name + "\" isn't defined in text widget");
        if (other != null && o == null) throw new IllegalArgumentException("tag \"" + other + "\" isn't defined in text widget");
        if (t == o) return;
        tags.remove(t);
        int at = o == null ? (raise ? tags.size() : 0) : tags.indexOf(o) + (raise ? 1 : 0);
        tags.add(at, t);
        tree.changed(false);
    }
    /**
     * tag configure: options by name, kept as text; "" clears one. A colour comes as {text, 0xRRGGBB}
     * (the program's name for it and its value), as #rgb/#rrggbb, or as a number.
     */
    Map<String, String> tagConfigure(String name, Map<String, Object> options) {
        alive();
        Tag t = tagOrNew(name);
        Map<String, String> next = new LinkedHashMap<>(t.options);
        Map<String, Integer> colors = new HashMap<>(t.colors);
        for (Map.Entry<String, Object> e : options.entrySet()) {
            String key = e.getKey();
            if (!Arrays.asList(TAG_OPTIONS).contains(key)) throw new IllegalArgumentException("unknown option \"-" + key + "\"");
            Object v = e.getValue();
            String text;
            if (TAG_COLORS.contains(key) && (v instanceof Map || v instanceof Object[] || v instanceof List)) {
                Object[] pair = TkOptions.sequence(v, 2);
                if (pair.length != 2) throw new IllegalArgumentException("color name and value expected");
                text = TkOptions.text(pair[0]);
                colors.put(key, TkOptions.integer(pair[1], 0, 0xFFFFFF));
            } else {
                text = v instanceof Number ? (((Number) v).doubleValue() == ((Number) v).longValue() ? String.valueOf(((Number) v).longValue()) : v.toString())
                        : v instanceof Boolean ? ((Boolean) v ? "1" : "0") : TkOptions.text(v);
                if (TAG_COLORS.contains(key)) {
                    if (text.isEmpty()) colors.remove(key);
                    else colors.put(key, v instanceof Number ? TkOptions.integer(v, 0, 0xFFFFFF) : color(text));
                }
            }
            if (text.length() > 256) throw new IllegalArgumentException("tag option too long");
            next.put(key, text);
        }
        boolean underline = bool(next, "underline"), overstrike = bool(next, "overstrike");
        t.options.clear();
        for (Map.Entry<String, String> e : next.entrySet()) if (!e.getValue().isEmpty()) t.options.put(e.getKey(), e.getValue());
        t.colors.clear(); t.colors.putAll(colors);
        t.underline = underline; t.overstrike = overstrike;
        tree.changed(false);
        return t.options;
    }
    /** A colour by its text: #rgb or #rrggbb (names are the program's to resolve). */
    private static int color(String v) {
        try {
            if (v.startsWith("#") && v.length() == 7) return Integer.parseInt(v.substring(1), 16);
            if (v.startsWith("#") && v.length() == 4) { int r = Integer.parseInt(v.substring(1, 2), 16), g = Integer.parseInt(v.substring(2, 3), 16), b = Integer.parseInt(v.substring(3, 4), 16); return r * 0x110000 + g * 0x1100 + b * 0x11; }
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("unknown color name \"" + v + "\"");
    }
    private static boolean bool(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isEmpty()) return false;
        switch (v.toLowerCase(Locale.ROOT)) {
            case "1": case "true": case "yes": case "on": return true;
            case "0": case "false": case "no": case "off": return false;
            default: throw new IllegalArgumentException("expected boolean value but got \"" + v + "\"");
        }
    }

    // ---- undo ----------------------------------------------------------------------------

    /** -undo, -autoseparators, -maxundo, the notice callback and the colours. */
    void options(boolean undo, boolean autoseparators, int maxundo, int notify, int inactiveSelectBackground, int insertBackground) {
        if (this.undo && !undo) resetUndo();   // edits made while it is off would leave the stacks pointing at the wrong text
        this.undo = undo; this.autoseparators = autoseparators; this.maxundo = maxundo; this.notify = notify;
        this.inactiveSelectBackground = inactiveSelectBackground; this.insertBackground = insertBackground;
        trimUndo();
    }

    private void record(int kind, int pos, int[] text) {
        if (replaying) return;
        boolean was = modified;
        dirty++;
        modified = true;
        if (undo) {
            if (autoseparators && lastMode != kind) separator();
            lastMode = kind;
            if (openUnit == null) openUnit = new ArrayList<>();
            openUnit.add(new Atom(kind, pos, text));
            undoChars += text.length;
            undoAtoms++;
            for (List<Atom> unit : redoStack) forget(unit);
            redoStack.clear();
            trimUndo();
        }
        if (!was) tree.notice(this, notify, "modified", true);
    }
    /** edit separator: the edits so far are one unit for undo. */
    void separator() {
        if (openUnit != null && !openUnit.isEmpty()) { undoStack.addLast(openUnit); trimUndo(); }
        openUnit = null;
        lastMode = OTHER;
    }
    private void trimUndo() {
        int open = openUnit != null && !openUnit.isEmpty() && !undoStack.contains(openUnit) ? 1 : 0;
        while (maxundo > 0 && undoStack.size() + open > maxundo) dropOldest();
        while ((undoChars > MAX_UNDO_CHARS || undoAtoms > MAX_UNDO_ATOMS) && !undoStack.isEmpty()) dropOldest();
        if (undoChars > MAX_UNDO_CHARS || undoAtoms > MAX_UNDO_ATOMS) resetUndo();
    }
    private void forget(List<Atom> unit) { for (Atom a : unit) { undoChars -= a.text.length; undoAtoms--; } }
    private void dropOldest() { forget(undoStack.removeFirst()); }

    /** edit undo / redo: false with nothing to do (and undo on). */
    boolean undoRedo(boolean redo) {
        alive();
        if (!undo) return true;
        separator();
        Deque<List<Atom>> from = redo ? redoStack : undoStack, to = redo ? undoStack : redoStack;
        if (from.isEmpty()) return false;
        List<Atom> unit = from.peekLast();
        // Check the whole replay before moving stacks or changing marks/tags. Another Text
        // may now consume the shared byte budget, or saved history may be inconsistent.
        int[] planned = chars;
        List<Tag> plannedTags = tags;
        for (int i = 0; i < unit.size(); i++) {
            Atom a = unit.get(redo ? i : unit.size() - i - 1);
            boolean insert = (a.kind == INSERT) == redo;
            int removed = insert ? 0 : a.text.length;
            if (a.pos < 0 || a.pos > planned.length - 1 || a.pos + removed > planned.length - 1)
                throw new IllegalArgumentException("invalid Text undo history");
            int size = planned.length - removed + (insert ? a.text.length : 0);
            if (size > MAX_CHARS) throw new IllegalArgumentException("Text character limit exceeded");
            int[] next = new int[size];
            System.arraycopy(planned, 0, next, 0, a.pos);
            if (insert) System.arraycopy(a.text, 0, next, a.pos, a.text.length);
            System.arraycopy(planned, a.pos + removed, next, a.pos + (insert ? a.text.length : 0), planned.length - a.pos - removed);
            validate(next); planned = next;
            plannedTags = planTags(plannedTags, a.pos, a.pos + removed, insert ? a.text.length : 0, Collections.emptyList());
        }
        from.removeLast();
        boolean was = modified;
        replaying = true;
        int where = cursor;
        try {
            if (redo) for (Atom a : unit) where = apply(a, false);
            else for (int i = unit.size() - 1; i >= 0; i--) where = apply(unit.get(i), true);
        } finally { replaying = false; }
        to.addLast(unit);
        // Tk's dirty count: undo takes each edit back, redo does it again (TK_TEXT_DIRTY_UNDO).
        dirty += redo ? unit.size() : -unit.size();
        lastMode = OTHER;
        cursor(where); see(cursor);
        modified = dirtyForced || dirty != 0;
        if (modified != was) tree.notice(this, notify, "modified", modified);
        return true;
    }
    /** Do (or undo) an edit: where the insert mark goes. */
    private int apply(Atom a, boolean inverse) {
        boolean insert = (a.kind == INSERT) != inverse;
        if (insert) { rawInsert(a.pos, a.text, Collections.emptyList()); return a.pos + a.text.length; }
        rawDelete(a.pos, a.pos + a.text.length);
        return a.pos;
    }
    void resetUndo() { undoStack.clear(); redoStack.clear(); openUnit = null; undoChars = undoAtoms = 0; lastMode = OTHER; }
    /** edit modified: set the flag (the program's) and tell the program if it changed. */
    void modified(boolean flag) {
        boolean was = modified;
        modified = flag; dirtyForced = flag; dirty = flag ? Math.max(1, dirty) : 0;
        if (was != flag) tree.notice(this, notify, "modified", flag);
        tree.changed(false);
    }

    // ---- search, count, dump -------------------------------------------------------------

    /** Bound regex backtracking on the server thread, including backwards overlapping scans. */
    private static final class SearchText implements CharSequence {
        final String text; int remaining = 4 * 1024 * 1024;
        SearchText(String text) { this.text = text; }
        public int length() { return text.length(); }
        public char charAt(int i) {
            if (--remaining < 0) throw new IllegalArgumentException("Text search work limit exceeded");
            return text.charAt(i);
        }
        public CharSequence subSequence(int start, int end) { return text.subSequence(start, end); }
        @Override public String toString() { return text; }
    }

    /** search: the index of the first match (forwards from index, or backwards before it), or -1; count[0] its length. */
    int search(String pattern, int from, Integer stop, boolean backwards, boolean regexp, boolean nocase, int[] count) {
        try { return searchBounded(pattern, from, stop, backwards, regexp, nocase, count); }
        catch (StackOverflowError tooDeep) { throw new IllegalArgumentException("Text search work limit exceeded"); }
    }
    private int searchBounded(String pattern, int from, Integer stop, boolean backwards, boolean regexp, boolean nocase, int[] count) {
        String text = content();
        // Code point index <-> char offset.
        int[] offsets = new int[chars.length + 1];
        for (int i = 0, off = 0; i <= chars.length; i++) { offsets[i] = off; if (i < chars.length) off += Character.charCount(chars[i]); }
        int textLength = length();
        String haystack = text.substring(0, offsets[textLength]);
        Pattern p;
        try {
            int flags = Pattern.MULTILINE | (nocase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            p = regexp ? Pattern.compile(pattern, flags) : Pattern.compile(Pattern.quote(pattern), flags);
        } catch (PatternSyntaxException bad) {
            String why = bad.getDescription();
            String tcl = why.contains("Unclosed group") || why.contains("Unmatched closing ')'") ? "parentheses () not balanced"
                    : why.contains("Unclosed character class") ? "brackets [] not balanced" : why.contains("Dangling meta character") ? "quantifier operand invalid" : "invalid regular expression";
            throw new IllegalArgumentException("couldn't compile regular expression pattern: " + tcl);
        }
        from = Math.min(from, textLength);
        Matcher m = p.matcher(new SearchText(haystack));
        int start = offsets[from];
        int[] found = null;
        if (!backwards) {
            // The first match starting at or after index (before stop); with no stop, wrapping round to the start.
            int limit = stop == null ? haystack.length() + 1 : offsets[Math.max(0, Math.min(stop, textLength))];
            if (m.find(start) && m.start() < limit) found = new int[] {m.start(), m.end()};
            else if (stop == null && m.find(0) && m.start() < start) found = new int[] {m.start(), m.end()};
        } else {
            // The match starting closest before index (at or after stop); with no stop, wrapping round from the end.
            int limit = stop == null ? 0 : offsets[Math.max(0, Math.min(stop, textLength))];
            found = lastMatch(m, limit, start);
            if (found == null && stop == null) found = lastMatch(m, start, haystack.length() + 1);
        }
        if (found == null) return -1;
        int cs = cpIndex(offsets, found[0]);
        count[0] = cpIndex(offsets, found[1]) - cs;
        return cs;
    }
    /** The last match starting in [from, before) (overlapping ones too), as char offsets. */
    private static int[] lastMatch(Matcher m, int from, int before) {
        int[] found = null;
        int pos = from, steps = 0;
        while (pos <= m.regionEnd() && m.find(pos) && m.start() < before) {
            found = new int[] {m.start(), m.end()};
            pos = m.start() + 1;
            if (++steps > 1000000) break;
        }
        return found;
    }
    private static int cpIndex(int[] offsets, int off) {
        int i = Arrays.binarySearch(offsets, off);
        return i >= 0 ? i : -i - 2;
    }

    /** count: for each option (chars, indices, lines, displaychars, displayindices, displaylines, xpixels, ypixels) its count from a to b. */
    List<Integer> count(int a, int b, List<String> options) {
        List<Integer> out = new ArrayList<>();
        if (options.isEmpty()) options = Collections.singletonList("indices");
        for (String o : options) {
            switch (o) {
                case "chars": case "indices": case "displaychars": case "displayindices": out.add(b - a); break;
                case "lines": out.add(line(b) - line(a)); break;
                case "displaylines": out.add(displayRow(b) - displayRow(a)); break;
                case "ypixels": out.add(Math.abs(displayRow(b) - displayRow(a)) * TkFontMetrics.LINE_HEIGHT); break;
                case "xpixels": out.add(displayX(Math.max(a, b)) - displayX(Math.min(a, b))); break;
                case "update": break;
                default: throw new IllegalArgumentException("bad option \"-" + o + "\" must be -chars, -displaychars, -displayindices, -displaylines, -indices, -lines, -update, -xpixels, or -ypixels");
            }
        }
        return out;
    }

    /** dump: [key, value, index] for text (cut at mark, tag and line boundaries), marks, tagon and tagoff, from a to b. */
    List<Object[]> dump(int a, int b, boolean text, boolean mark, boolean tag) {
        a = clamp(a); b = Math.max(a, clamp(b));
        List<Object[]> out = new ArrayList<>();
        // Tk links every zero-width segment after left-gravity segments and before right-
        // gravity ones (tkTextBTree.c, SplitSeg/TkBTreeLinkSegment). Tagoff is left gravity;
        // tagon is right gravity. Marks and toggles must share insertion order.
        Map<Integer, List<Object[]>> boundaries = new TreeMap<>();
        for (Object[] m : orderedMarks()) {
            int at = (Integer) m[1];
            boundaries.computeIfAbsent(at, k -> new ArrayList<>()).add(new Object[] {"mark", m[0], right((String) m[0]), m[2]});
        }
        for (Tag t : tags) {
            syncOrders(t);
            for (Map.Entry<Long, Long> entry : t.orders.entrySet()) {
                int at = (int) (entry.getKey() / 2); boolean on = (entry.getKey() & 1) == 0;
                boundaries.computeIfAbsent(at, k -> new ArrayList<>()).add(new Object[] {on ? "tagon" : "tagoff", t.name, on, entry.getValue()});
            }
        }
        TreeSet<Integer> cuts = new TreeSet<>();
        cuts.add(a); cuts.add(b);
        for (int at : boundaries.keySet()) if (at >= a && at < b) cuts.add(at);
        for (int i = a; i < b; i++) if (chars[i] == '\n' && i + 1 < b) cuts.add(i + 1);
        for (int at : cuts) {
            List<Object[]> entries = boundaries.get(at);
            if (entries != null && at < b) {
                entries.sort((x, y) -> {
                    boolean xr = (Boolean) x[2], yr = (Boolean) y[2];
                    if (xr != yr) return xr ? 1 : -1;
                    return xr ? Long.compare((Long) y[3], (Long) x[3]) : Long.compare((Long) x[3], (Long) y[3]);
                });
                for (Object[] e : entries) if (e[0].equals("mark") ? mark : tag)
                    out.add(new Object[] {e[0], e[1], index(at)});
            }
            Integer next = cuts.higher(at);
            if (text && next != null && at < b) out.add(new Object[] {"text", get(at, next), index(at)});
        }
        return out;
    }

    // ---- display ---------------------------------------------------------------------------

    int pixels() { return Math.max(1,bounds.width-insetWidth()); }
    int page() { return Math.max(1,(bounds.height-insetHeight())/16); }
    private int advance(int cp,int x) { return cp=='\t'?64-Math.floorMod(x,64):Math.max(0,TkFontMetrics.advance(cp)); }
    private int width(int a,int b) { int x=0; for(int i=a;i<b;i++)x+=advance(chars[i],x); return x; }
    private void layout() {
        int pixels=pixels(); if(layoutWidth==pixels&&layoutWrap.equals(wrap))return;
        rows.clear(); maxWidth=0;
        for(int l=0;l<starts.length-1;l++) {
            int end=starts[l+1]-1,start=starts[l];
            if(start==end) { rows.add(new Row(start,end,0)); continue; }
            while(start<end) {
                int at=start,x=0,brk=-1,brkWidth=0;
                while(at<end) {
                    int step=advance(chars[at],x);
                    if(!wrap.equals("none")&&x+step>pixels&&at>start)break;
                    x+=step; at++;
                    if(Character.isWhitespace(chars[at-1])) { brk=at; brkWidth=x; }
                }
                if(wrap.equals("word")&&at<end&&brk>start) { at=brk; x=brkWidth; }
                rows.add(new Row(start,at,x)); maxWidth=Math.max(maxWidth,x); start=at;
            }
        }
        layoutWidth=pixels; layoutWrap=wrap;
    }
    private int rowAt(int at) {
        layout(); at=Math.min(clamp(at),length()-1); int lo=0,hi=rows.size()-1;
        while(lo<hi) { int mid=(lo+hi+1)/2; if(rows.get(mid).start<=at)lo=mid;else hi=mid-1; } return lo;
    }
    private void clampView() { layout(); top=Math.max(0,Math.min(top,Math.max(0,rows.size()-page()))); scrollX=wrap.equals("none")?Math.max(0,Math.min(scrollX,Math.max(0,maxWidth-pixels()+1))):0; }
    private Object[] fractions(boolean x) {
        int total=x?maxWidth:rows.size(),visible=x?pixels():page(),start=x?scrollX:top;
        return total==0?new Object[]{0.0,1.0}:new Object[]{Math.min(1.0,start/(double)total),Math.min(1.0,(start+visible)/(double)total)};
    }
    void viewport() {
        clampView();
        for(boolean x:new boolean[]{true,false}) {
            Object[] f=fractions(x); int cb=x?xscrollcommand:yscrollcommand; String key=cb+":"+f[0]+":"+f[1];
            if(!key.equals(x?lastX:lastY)) { if(x)lastX=key;else lastY=key; tree.queue(this,cb,f); }
        }
    }
    public Object[] view(boolean x) { bounds(); clampView(); return fractions(x); }
    public void moveto(boolean x,double fraction) {
        if(!Double.isFinite(fraction))throw new IllegalArgumentException("finite fraction required"); bounds(); layout();
        int pos=(int)(Math.max(0,Math.min(1,fraction))*(x?maxWidth:rows.size())); if(x)scrollX=pos;else top=pos; clampView(); tree.changed(false);
    }
    public void scroll(boolean x,int amount,boolean pages) {
        bounds(); layout(); long pos=(x?scrollX:top)+(long)amount*(pages?x?pixels():Math.max(1,page()-1):x?8:1);
        if(x)scrollX=(int)Math.max(0,Math.min(Integer.MAX_VALUE,pos));else top=(int)Math.max(0,Math.min(Integer.MAX_VALUE,pos)); clampView(); tree.changed(false);
    }
    public void see(int at) {
        bounds(); int row=rowAt(at); if(row<top)top=row;else if(row>=top+page())top=row-page()+1;
        if(wrap.equals("none")) { Row r=rows.get(row); int x=width(r.start,Math.min(at,r.end)); if(x<scrollX)scrollX=x;else if(x>=scrollX+pixels())scrollX=x-pixels()+1; }
        clampView(); tree.changed(false);
    }
    /** bbox: the character's box (widget pixels) if it is shown, else null; dlineinfo: its display line's {x, y, width, height, baseline}. */
    int[] bbox(int at, boolean line) {
        bounds(); clampView();
        at = Math.min(clamp(at), length() - 1);
        int row = rowAt(at);
        if (row < top || row >= top + page() || bounds.empty()) return null;
        Row r = rows.get(row);
        int x = insetLeft() - scrollX, y = insetTop() + (row - top) * 16;
        if (line) return new int[] {x, y, r.width, 16, 13};
        int cx = x + width(r.start, Math.min(at, r.end)), w = at < r.end ? advance(chars[at], width(r.start, at)) : 8;
        if (cx + w <= insetLeft() || cx >= insetLeft() + pixels()) return null;
        return new int[] {cx, y, w, 16};
    }
    private int atX(Row row,int x,boolean nearest) {
        int px=0,at=row.start; while(at<row.end) { int step=advance(chars[at],px); if(x<px+(nearest?step/2:step))break; px+=step;at++; } return at;
    }
    int at(int localX,int localY,boolean nearest) {
        clampView(); int row=Math.max(0,Math.min(rows.size()-1,top+Math.floorDiv(localY-insetTop(),16)));
        return atX(rows.get(row),localX-insetLeft()+scrollX,nearest);
    }

    // ---- the player ------------------------------------------------------------------------

    /** The selection the player changed: the program hears <<Selection>>. */
    private void playerSelection(int beforeFirst, int beforeLast) {
        if (first != beforeFirst || last != beforeLast) tree.notice(this, notify, "selection");
    }
    /** The player moved the insert mark: with -autoseparators, the edits so far are one unit (tk::TextSetCursor). */
    private void cursorMoved() { if (undo && autoseparators) separator(); }
    void press(int x,int y,int modifiers) {
        int f = first, l = last;
        hover(x, y);
        int at=at(x-bounds.x,y-bounds.y,true);
        if((modifiers&1)!=0)selection(Math.min(anchor,at),Math.max(anchor,at));else {selectionClear();anchor=at;}
        cursor(at); see(cursor); cursorMoved();
        playerSelection(f, l);
    }
    void drag(int x,int y) {
        int f = first, l = last;
        if(y<bounds.y+insetTop())top=Math.max(0,top-1);else if(y>=bounds.y+bounds.height-insetBottom())top++;
        if(wrap.equals("none")) { if(x<bounds.x+insetLeft())scrollX=Math.max(0,scrollX-16);else if(x>=bounds.x+bounds.width-insetRight())scrollX+=16; }
        int at=at(x-bounds.x,y-bounds.y,true); selection(Math.min(anchor,at),Math.max(anchor,at)); cursor(at); see(cursor);
        playerSelection(f, l);
    }
    private void move(int at,boolean extend,boolean vertical) {
        int f = first, l = last;
        at=Math.min(clamp(at),length()-1); int goal=goalX;
        if(extend) {if(!selectionPresent())anchor=cursor;selection(Math.min(anchor,at),Math.max(anchor,at));}else {selectionClear();anchor=at;}
        cursor(at); if(vertical)goalX=goal; see(at); cursorMoved();
        playerSelection(f, l);
    }
    private boolean word(int at) { return Character.isLetterOrDigit(chars[at])||chars[at]=='_'; }
    private int wordMove(int at,boolean right) {
        if(right) { if(at<length()-1) {boolean word=word(at++);while(word&&at<length()-1&&word(at))at++;}while(at<length()-1&&Character.isWhitespace(chars[at]))at++; }
        else {while(at>0&&Character.isWhitespace(chars[at-1]))at--;if(at>0) {boolean word=word(--at);while(word&&at>0&&word(at-1))at--;}}
        return at;
    }
    void key(int code,int character,int modifiers) {
        // AltGr (Windows: Ctrl+Alt) and Option (macOS: Alt) type characters; only Ctrl
        // without Alt is a shortcut modifier.
        boolean shift=(modifiers&1)!=0,ctrl=(modifiers&2)!=0&&(modifiers&4)==0;
        if(ctrl&&code==30) {int f=first,l=last;anchor=0;selection(0,length());cursor(length()-1);see(cursor);playerSelection(f,l);return;}
        // <<Undo>> (Ctrl+Z) and <<Redo>> (Ctrl+Y, Ctrl+Shift+Z), as Tk's text bindings.
        if(ctrl&&(code==44||code==21)) { if(enabled) undoRedo(code==21||shift); return; }
        if(code==203||code==205) {boolean right=code==205;move(ctrl?wordMove(cursor,right):!shift&&selectionPresent()?right?last:first:cursor+(right?1:-1),shift,false);return;}
        if(code==199||code==207) {int row=line(cursor);move(ctrl?code==199?0:length()-1:code==199?starts[row]:starts[row+1]-1,shift,false);return;}
        if(code==200||code==208||code==201||code==209) {
            int r=rowAt(cursor); if(goalX<0)goalX=width(rows.get(r).start,cursor);
            int step=code==201||code==209?Math.max(1,page()-1):1; if(code==200||code==201)step=-step;
            move(atX(rows.get(Math.max(0,Math.min(rows.size()-1,r+step))),goalX,true),shift,true);return;
        }
        if(!enabled)return;
        if(code==14||code==211) {
            if(code==211&&shift)return;
            int f=first,l=last;
            if(selectionPresent())delete(first,last);
            else if(code==14)delete(ctrl?wordMove(cursor,false):cursor-1,cursor);
            else delete(cursor,ctrl?wordMove(cursor,true):cursor+1);
            see(cursor); playerSelection(f,l); return;
        }
        if(!ctrl&&(code==28||code==156))paste("\n");
        else if(!ctrl&&code==15)paste("\t");
        else if(!ctrl&&character>=32&&character<=0x10FFFF&&character!=127&&(character<0xD800||character>0xDFFF))paste(new String(Character.toChars(character)));
    }
    void paste(String value) {
        if(!enabled)return;
        int f=first,l=last;
        boolean replacing=selectionPresent();
        int a=replacing?first:cursor,b=replacing?last:cursor;
        String accepted=fit(value,a,b);
        if(accepted.isEmpty()&&!value.isEmpty())return; // Nothing insertable: keep the selection, like Entry.
        // Typing over a selection is one unit for undo (tk::TextInsert's compound edit).
        boolean compound = replacing && undo && autoseparators;
        if (compound) { separator(); autoseparators = false; }
        try {
            if (replacing) rawDelete(Math.min(a, length() - 1), Math.max(Math.min(a, length() - 1), Math.min(b, length() - 1)));
            rawInsert(Math.min(clamp(a), length() - 1), codePoints(accepted), null);
        } finally { if (compound) { separator(); autoseparators = true; } }
        cursor=Math.min(length()-1,a+accepted.codePointCount(0,accepted.length())); selectionClear(); anchor=cursor; see(cursor);
        playerSelection(f,l);
    }
    private static int utf8(int cp) { return cp<0x80?1:cp<0x800?2:cp<0x10000?3:4; }
    /**
     * The longest prefix of user input that keeps this Text within its budgets once it
     * replaces [a,b), with control characters other than tab and newline dropped. Like
     * Entry, a paste that does not fit inserts what fits instead of being rejected whole
     * (rejecting threw out of the input handler for a stray control character).
     */
    private String fit(String value,int a,int b) {
        value=value.replace("\r\n","\n").replace('\r','\n');
        a=Math.min(clamp(a),length()-1); b=Math.max(a,Math.min(clamp(b),length()-1));
        int removedBytes=0,removedLines=0;
        for(int i=a;i<b;i++) { removedBytes+=utf8(chars[i]); if(chars[i]=='\n')removedLines++; }
        int others=0; for(TkWidget w:tree.widgets.values())if(w instanceof Text&&w!=this)others+=((Text)w).bytes;
        long keptBytes=bytes-removedBytes, roomChars=MAX_CHARS-(length()-(long)(b-a));
        long roomBytes=Math.min(MAX_BYTES-keptBytes,2L*1024*1024-others-keptBytes), roomLines=MAX_LINES-((starts.length-1)-removedLines);
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();) {
            int cp=value.codePointAt(i); i+=Character.charCount(cp);
            if(cp<32&&cp!='\n'&&cp!='\t'||cp==127||cp>=0xD800&&cp<=0xDFFF)continue;
            int size=utf8(cp);
            if(roomChars<1||roomBytes<size||cp=='\n'&&roomLines<1)break;
            out.appendCodePoint(cp); roomChars--; roomBytes-=size; if(cp=='\n')roomLines--;
        }
        return out.toString();
    }

    // ---- painting --------------------------------------------------------------------------

    /** A character's look from its tags, the highest priority setting each option: {bg, fg, underline colour or -1, overstrike colour or -1}. */
    private int[] style(int at, boolean focused) {
        int bg = -1, fg = -1, under = -1, over = -1;
        boolean underSet = false, overSet = false;
        for (int i = tags.size() - 1; i >= 0; i--) {
            Tag t = tags.get(i);
            if (!t.contains(at)) continue;
            // The selection out of focus: -inactiveselectbackground.
            int tbg = t.color("background"), tfg = t.color("foreground");
            if (t.name.equals("sel") && !focused && tbg >= 0) tbg = inactiveSelectBackground;
            if (bg < 0 && tbg >= 0) bg = tbg;
            if (fg < 0 && tfg >= 0) fg = tfg;
            if (!underSet && t.options.containsKey("underline")) { underSet = true; under = t.underline ? (t.color("underlinefg") >= 0 ? t.color("underlinefg") : -2) : -1; }
            if (!overSet && t.options.containsKey("overstrike")) { overSet = true; over = t.overstrike ? (t.color("overstrikefg") >= 0 ? t.color("overstrikefg") : -2) : -1; }
        }
        int f = fg >= 0 ? fg : foreground;
        return new int[] {bg, f, under == -2 ? f : under, over == -2 ? f : over};
    }

    void paint(List<TkDisplayList.Item> out,int state) {
        viewport(); int x=bounds.x+insetLeft(),y=bounds.y+insetTop(),w=pixels();
        TkRect inner=new TkRect(x,y,w,Math.max(0,bounds.height-insetHeight())).intersect(clip);
        int caretRow=rowAt(cursor);
        boolean focused = (state & TkDisplayList.FOCUSED) != 0;
        boolean anyStyle = styled();
        for(int n=top;n<rows.size()&&y<inner.y+inner.height;n++,y+=16) {
            Row row=rows.get(n); TkRect rect=new TkRect(x,y,w,16),clipped=rect.intersect(inner); if(clipped.empty())continue;
            int px=0,glyphs=0,paintFrom=-1,paintTo=row.start; StringBuilder visible=new StringBuilder(); int tx=x-scrollX;
            int paintLeft=scrollX+clipped.x-x,paintRight=paintLeft+clipped.width;
            // Expand tabs for the existing font renderer and send only horizontal viewport text.
            for(int i=row.start;i<row.end;i++) {
                int step=advance(chars[i],px);
                if(px+step>paintLeft&&px<paintRight) {
                    if (paintFrom < 0) paintFrom = i;
                    paintTo = i + 1;
                    if(visible.length()==0)tx=x+px-scrollX;
                    if(chars[i]=='\t')for(int s=0;s<step;s+=8)visible.append(' ');else visible.appendCodePoint(chars[i]);
                    if(++glyphs>=512)break; // Bound degenerate runs of zero-width marks as well as visible glyphs.
                }
                px+=step;if(px>=paintRight)break;
            }
            int caret=focused&&enabled&&n==caretRow?x-scrollX+width(row.start,cursor):-1;
            if(caret<clipped.x||caret>=clipped.x+clipped.width)caret=-1;
            int base = 1 + (n - top) * (2 + 3 * MAX_RUNS);
            if (!anyStyle) {
                out.add(new TkDisplayList.Item(id,base,Kind.TEXT,rect,clipped,background,foreground,0,state,tx,y,visible.toString(),caret,0,0));
                continue;
            }
            // Styled runs over the plain line: a tag's colours, the selection, underline and overstrike.
            List<int[]> runs = new ArrayList<>();   // {start, end, bg, fg, under, over}
            if (paintFrom < 0) paintFrom = paintTo = row.end;
            int end = paintTo;
            int newlineX = x - scrollX + row.width;
            if (paintTo == row.end && row.end < length() && chars[row.end] == '\n'
                    && newlineX + 8 > clipped.x && newlineX < clipped.x + clipped.width) end++;
            for (int i = paintFrom; i < end; i++) {
                int[] s = style(i, focused);
                if (s[0] < 0 && s[1] == foreground && s[2] < 0 && s[3] < 0) continue;
                int[] lastRun = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                if (lastRun != null && lastRun[1] == i && (runs.size() >= MAX_RUNS || lastRun[2] == s[0] && lastRun[3] == s[1] && lastRun[4] == s[2] && lastRun[5] == s[3])) lastRun[1] = i + 1;
                else if (runs.size() < MAX_RUNS) runs.add(new int[] {i, i + 1, s[0], s[1], s[2], s[3]});
            }
            out.add(new TkDisplayList.Item(id,base,Kind.TEXT,rect,clipped,background,foreground,0,state,tx,y,visible.toString(),runs.isEmpty()?caret:-1,0,0));
            int part = base + 1;
            for (int[] r : runs) {
                int left = x - scrollX + width(row.start, Math.min(r[0], row.end));
                int right = r[1] > row.end ? x - scrollX + width(row.start, row.end) + 8 : x - scrollX + width(row.start, r[1]);
                TkRect box = new TkRect(left, y, Math.max(1, right - left), 16), boxClip = box.intersect(clipped);
                if (boxClip.empty()) { part += 3; continue; }
                StringBuilder seg = new StringBuilder();
                int sx = width(row.start, Math.min(r[0], row.end));
                for (int i = r[0]; i < Math.min(r[1], row.end); i++) {
                    int step = advance(chars[i], sx);
                    if (chars[i] == '\t') for (int s = 0; s < step; s += 8) seg.append(' '); else seg.appendCodePoint(chars[i]);
                    sx += step;
                    if (i - r[0] + 1 >= 512) break;
                }
                out.add(new TkDisplayList.Item(id, part, Kind.LABEL, box, boxClip, r[2] >= 0 ? r[2] : background, r[3], 0, 0, left, y, seg.toString(), -1, 0, 0, null, 0, TkRelief.FLAT));
                for (int decoration = 4; decoration <= 5; decoration++) if (r[decoration] >= 0) {
                    int lineY = decoration == 4 ? y + 14 : y + 8, color = r[decoration];
                    TkRect lineBox = new TkRect(left, lineY, Math.max(1, right - left), 1), lineClip = lineBox.intersect(clipped);
                    if (!lineClip.empty()) out.add(new TkDisplayList.Item(id, part + decoration - 3, Kind.FRAME, lineBox, lineClip, color, color, 0, 0, left, lineY, "", -1, 0, 0, null, 0, TkRelief.FLAT));
                }
                part += 3;
            }
            if (caret >= 0 && !runs.isEmpty()) {
                TkRect c = new TkRect(caret, y, 2, 16), cc = c.intersect(clipped);
                if (!cc.empty()) out.add(new TkDisplayList.Item(id, base + 1 + 3 * MAX_RUNS, Kind.FRAME, c, cc, insertBackground, insertBackground, 0, 0, caret, y, "", -1, 0, 0, null, 0, TkRelief.FLAT));
            }
        }
    }
    /** Most records on a display rows text rows tall. */
    int maxRecords(int displayRows) {
        if (!styled()) return 1 + displayRows;
        int ranges = 0;
        for (Tag t : tags) ranges += t.ranges.size();
        // A row's runs: each range that touches it can start one, and one more where they end.
        return 1 + displayRows * (2 + 3 * Math.min(MAX_RUNS, 2 * ranges + 1));
    }
    /** Most text bytes: the plain lines, and as much again in styled runs over them. */
    long maxBytes(int displayRows) {
        // Tabs expand to up to eight spaces; zero-width glyphs also consume wire bytes.
        // Plain and styled records cover the same bounded set of at most 512 glyphs/row.
        long plain = Math.min(expandedBytes, (long) displayRows * 512 * 8);
        return styled() ? 2 * plain : plain;
    }
    private boolean styled() {
        for (Tag t : tags) if (!t.ranges.isEmpty() && (t.name.equals("sel") || t.drawn())) return true;
        return false;
    }

    // ---- saving ----------------------------------------------------------------------------

    NBTTagCompound save() {
        NBTTagCompound n=new NBTTagCompound(); n.setByteArray("content",content().getBytes(StandardCharsets.UTF_8));
        n.setString("wrap",wrap); n.setBoolean("modified",modified);
        for(String key:new String[]{"columns","lines","cursor","anchor","first","last","top","scrollX","xscrollcommand","yscrollcommand"}) {
            int value;
            switch(key) {case "columns":value=columns;break;case "lines":value=lines;break;case "cursor":value=cursor;break;case "anchor":value=anchor;break;case "first":value=first;break;case "last":value=last;break;case "top":value=top;break;case "scrollX":value=scrollX;break;case "xscrollcommand":value=xscrollcommand;break;default:value=yscrollcommand;}
            n.setInteger(key,value);
        }
        // Optional keys keep old Text saves readable.
        n.setInteger("current", current);
        n.setBoolean("insertRight", insertRight); n.setBoolean("currentRight", currentRight);
        n.setLong("insertSeq", insertSeq); n.setLong("currentSeq", currentSeq);
        n.setInteger("dirty", dirty); n.setBoolean("dirtyForced", dirtyForced);
        n.setTag("undoStack", saveStack(undoStack)); n.setTag("redoStack", saveStack(redoStack));
        n.setTag("openUndo", saveUnit(openUnit)); n.setInteger("lastMode", lastMode);
        n.setIntArray("options", new int[] {undo ? 1 : 0, autoseparators ? 1 : 0, maxundo, notify, inactiveSelectBackground, insertBackground});
        NBTTagList markList = new NBTTagList();
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            NBTTagCompound m = new NBTTagCompound();
            m.setString("name", e.getKey()); m.setInteger("pos", e.getValue().pos); m.setBoolean("right", e.getValue().right);
            m.setLong("seq", e.getValue().seq);
            markList.appendTag(m);
        }
        n.setTag("marks", markList);
        NBTTagList tagList = new NBTTagList();
        for (Tag t : tags) {
            NBTTagCompound c = new NBTTagCompound();
            c.setString("name", t.name);
            c.setLong("seq", t.seq);
            int[] flat = new int[t.ranges.size() * 2];
            for (int i = 0; i < t.ranges.size(); i++) { flat[2 * i] = t.ranges.get(i)[0]; flat[2 * i + 1] = t.ranges.get(i)[1]; }
            c.setIntArray("ranges", flat);
            syncOrders(t);
            java.nio.ByteBuffer orders = java.nio.ByteBuffer.allocate(flat.length * 8);
            for (int i = 0; i < flat.length; i++) orders.putLong(t.orders.get(2L * flat[i] + (i & 1)));
            c.setByteArray("orders", orders.array());
            NBTTagCompound options = new NBTTagCompound(), colors = new NBTTagCompound();
            for (Map.Entry<String, String> e : t.options.entrySet()) options.setString(e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : t.colors.entrySet()) colors.setInteger(e.getKey(), e.getValue());
            c.setTag("options", options);
            c.setTag("colors", colors);
            tagList.appendTag(c);
        }
        n.setTag("tags", tagList);
        return n;
    }
    private static NBTTagList saveUnit(List<Atom> unit) {
        NBTTagList saved = new NBTTagList();
        if (unit != null) for (Atom a : unit) {
            NBTTagCompound n = new NBTTagCompound(); n.setInteger("kind", a.kind); n.setInteger("pos", a.pos);
            n.setByteArray("text", new String(a.text, 0, a.text.length).getBytes(StandardCharsets.UTF_8)); saved.appendTag(n);
        }
        return saved;
    }
    private static NBTTagList saveStack(Deque<List<Atom>> stack) {
        NBTTagList saved = new NBTTagList();
        for (List<Atom> unit : stack) { NBTTagCompound n = new NBTTagCompound(); n.setTag("atoms", saveUnit(unit)); saved.appendTag(n); }
        return saved;
    }
    private List<Atom> loadUnit(NBTTagList list) {
        if (list.tagCount() > MAX_UNDO_ATOMS - undoAtoms) throw new IllegalArgumentException("saved Text undo history too large");
        List<Atom> unit = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i);
            int kind = TkOptions.integer(n.getInteger("kind"), INSERT, DELETE);
            int pos = TkOptions.integer(n.getInteger("pos"), 0, MAX_CHARS - 1);
            byte[] encoded = n.getByteArray("text");
            if (encoded.length == 0 || encoded.length > MAX_BYTES) throw new IllegalArgumentException("invalid saved Text undo atom");
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(encoded)).toString(); }
            catch (java.nio.charset.CharacterCodingException bad) { throw new IllegalArgumentException("invalid saved Text undo UTF-8", bad); }
            int[] data = text.codePoints().toArray();
            for (int cp : data) if (cp < 32 && cp != '\n' && cp != '\t' || cp == 127 || cp >= 0xD800 && cp <= 0xDFFF)
                throw new IllegalArgumentException("invalid saved Text undo character");
            undoChars += data.length; undoAtoms++;
            if (undoChars > MAX_UNDO_CHARS) throw new IllegalArgumentException("saved Text undo history too large");
            unit.add(new Atom(kind, pos, data));
        }
        return unit;
    }
    private void loadStack(NBTTagList list, Deque<List<Atom>> stack) {
        if (list.tagCount() > MAX_UNDO_ATOMS) throw new IllegalArgumentException("saved Text undo history too large");
        for (int i = 0; i < list.tagCount(); i++) {
            List<Atom> unit = loadUnit(list.getCompoundTagAt(i).getTagList("atoms", 10));
            if (unit.isEmpty()) throw new IllegalArgumentException("empty saved Text undo unit");
            stack.addLast(unit);
        }
    }
    private long sequence(NBTTagCompound n, String key, long fallback) {
        long seq = n.hasKey(key, 4) ? n.getLong(key) : fallback;
        if (seq < 0 || seq >= Long.MAX_VALUE - MAX_MARKS - MAX_TAGS) throw new IllegalArgumentException("invalid saved Text sequence");
        return seq;
    }
    void load(NBTTagCompound n) {
        byte[] data=n.getByteArray("content"); if(data.length>MAX_BYTES)throw new IllegalArgumentException("saved Text too large");
        String content;
        try {content=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();}catch(java.nio.charset.CharacterCodingException bad){throw new IllegalArgumentException("invalid saved Text UTF-8",bad);}
        int[] next=content.codePoints().toArray(); if(next.length==0||next[next.length-1]!='\n')throw new IllegalArgumentException("missing final Text newline");
        validate(next); assign(next); wrap=n.getString("wrap"); if(!Arrays.asList("none","char","word").contains(wrap))throw new IllegalArgumentException("invalid wrap");
        columns=TkOptions.integer(n.getInteger("columns"),1,1024); lines=TkOptions.integer(n.getInteger("lines"),1,512);
        cursor=TkOptions.integer(n.getInteger("cursor"),0,length()-1);anchor=TkOptions.integer(n.getInteger("anchor"),0,length()-1);
        int f=n.getInteger("first"),l=n.getInteger("last");if(!(f==-1&&l==-1||f>=0&&l>f&&l<=length()))throw new IllegalArgumentException("invalid Text selection");
        top=Math.max(0,Math.min(MAX_CHARS,n.getInteger("top")));scrollX=Math.max(0,Math.min(MAX_CHARS*64,n.getInteger("scrollX")));
        xscrollcommand=TkOptions.integer(n.getInteger("xscrollcommand"),0,Integer.MAX_VALUE);yscrollcommand=TkOptions.integer(n.getInteger("yscrollcommand"),0,Integer.MAX_VALUE);
        modified=n.getBoolean("modified"); dirty = modified ? 1 : 0; dirtyForced = false;
        current = Math.max(0, Math.min(length(), n.getInteger("current")));
        insertRight = !n.hasKey("insertRight") || n.getBoolean("insertRight");
        currentRight = !n.hasKey("currentRight") || n.getBoolean("currentRight");
        insertSeq = sequence(n, "insertSeq", 1); currentSeq = sequence(n, "currentSeq", 0);
        markSeq = Math.max(insertSeq, currentSeq) + 1; tagSeq = 1;
        tags.clear();
        if (n.hasKey("tags", 9)) {
            NBTTagList list = n.getTagList("tags", 10);
            if (list.tagCount() > MAX_TAGS) throw new IllegalArgumentException("too many saved tags");
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound c = list.getCompoundTagAt(i);
                String name = c.getString("name");
                if (tag(name) != null) throw new IllegalArgumentException("duplicate saved Text tag");
                Tag t = tagOrNew(name);
                t.seq = sequence(c, "seq", t.seq); tagSeq = Math.max(tagSeq, t.seq + 1);
                int[] flat = c.getIntArray("ranges");
                if (flat.length % 2 != 0 || flat.length / 2 > MAX_RANGES) throw new IllegalArgumentException("invalid saved tag ranges");
                for (int k = 0; k < flat.length; k += 2) {
                    if (flat[k] < 0 || flat[k + 1] > length() || flat[k + 1] <= flat[k]) throw new IllegalArgumentException("invalid saved tag ranges");
                    t.add(flat[k], flat[k + 1]);
                }
                if (c.hasKey("orders", 7)) {
                    byte[] encoded = c.getByteArray("orders");
                    if (encoded.length != flat.length * 8) throw new IllegalArgumentException("invalid saved Text boundary order");
                    java.nio.ByteBuffer orders = java.nio.ByteBuffer.wrap(encoded);
                    for (int k = 0; k < flat.length; k++) {
                        long seq = orders.getLong();
                        if (seq < 0 || seq >= Long.MAX_VALUE - 2 * MAX_RANGES - MAX_MARKS)
                            throw new IllegalArgumentException("invalid saved Text boundary sequence");
                        t.orders.put(2L * flat[k] + (k & 1), seq); markSeq = Math.max(markSeq, seq + 1);
                    }
                }
                syncOrders(t);
                NBTTagCompound options = c.getCompoundTag("options"), colors = c.getCompoundTag("colors");
                if (options.func_150296_c().size() > TAG_OPTIONS.length) throw new IllegalArgumentException("invalid saved tag options");
                Map<String, Object> m = new LinkedHashMap<>();
                for (Object key : options.func_150296_c()) {
                    String k = (String) key, text = options.getString(k);
                    m.put(k, TAG_COLORS.contains(k) && colors.hasKey(k, 3) ? new Object[] {text, colors.getInteger(k)} : text);
                }
                t.options.clear(); t.colors.clear();
                if (!m.isEmpty()) tagConfigure(name, m);
            }
            if (sel() == null) throw new IllegalArgumentException("missing saved Text selection tag");
            checkRanges(sel());
        } else { tags.add(new Tag("sel")); selDefaults(); if (f >= 0) sel().ranges.add(new int[] {f, l}); }
        syncSel();
        marks.clear();
        if (n.hasKey("marks", 9)) {
            NBTTagList list = n.getTagList("marks", 10);
            if (list.tagCount() > MAX_MARKS) throw new IllegalArgumentException("too many saved marks");
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound m = list.getCompoundTagAt(i);
                String name = m.getString("name");
                if (name.equals("insert") || name.equals("current") || marks.containsKey(name)) throw new IllegalArgumentException("invalid saved Text mark");
                markSet(name, TkOptions.integer(m.getInteger("pos"), 0, length()));
                Mark mark = marks.get(name); mark.right = m.getBoolean("right");
                mark.seq = sequence(m, "seq", mark.seq); markSeq = Math.max(markSeq, mark.seq + 1);
            }
        }
        if (n.hasKey("options", 11)) {
            int[] o = n.getIntArray("options");
            if (o.length != 6) throw new IllegalArgumentException("invalid saved Text options");
            undo = o[0] != 0; autoseparators = o[1] != 0; maxundo = Math.max(0, o[2]); notify = Math.max(0, o[3]);
            for (int k = 4; k < 6; k++) checkColor(o[k]);
            inactiveSelectBackground = o[4]; insertBackground = o[5];
        }
        resetUndo();
        if (n.hasKey("undoStack", 9)) {
            loadStack(n.getTagList("undoStack", 10), undoStack); loadStack(n.getTagList("redoStack", 10), redoStack);
            List<Atom> unit = loadUnit(n.getTagList("openUndo", 10)); openUnit = unit.isEmpty() ? null : unit;
            lastMode = TkOptions.integer(n.getInteger("lastMode"), OTHER, REPLACE);
            dirty = n.getInteger("dirty"); dirtyForced = n.getBoolean("dirtyForced"); trimUndo();
        }
    }
}
