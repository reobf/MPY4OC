package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Tk Text widget command: indices, marks, tags, search, count, dump, the undo stack and the view. */
final class TkTextApi {
    private TkTextApi() {}

    private static void between(Object[] a, int min, int max) {
        if (a.length < min || a.length > max) throw new IllegalArgumentException("wrong # args");
    }
    private static String name(Object value) { return TkOptions.text(value); }
    /** An insert's tag list: false for none given (Tk's inheriting rule: null), else the names. */
    private static List<String> tagList(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : TkOptions.sequence(value, 64)) out.add(name(o));
        return out;
    }

    static Object call(Text t, String action, Object[] a) {
        switch (action) {
            case "index": TkControlApi.count(a, 1); return t.index(t.index(a[0]));
            case "get": {
                between(a, 1, 2);
                int from = t.index(a[0]), to = a.length == 1 ? from + 1 : t.index(a[1]);
                return to <= from ? "" : t.get(from, to);
            }
            case "insert": case "replace": {
                // index [index2] chars tags chars tags ...: each piece goes after the one before.
                boolean replace = action.equals("replace");
                int head = replace ? 2 : 1;
                if (a.length < head + 2 || (a.length - head) % 2 != 0) throw new IllegalArgumentException("wrong # args");
                int at = t.index(a[0]);
                if (replace) {
                    int end = t.index(a[1]);
                    if (end < at) throw new IllegalArgumentException("Index \"" + name(a[1]) + "\" before \"" + name(a[0]) + "\" in the text");
                    at = t.replace(at, end, name(a[head]), tagList(a[head + 1]));
                    head += 2;
                }
                at = Math.min(at, t.length() - 1);
                for (int i = head; i < a.length; i += 2) {
                    String chars = name(a[i]);
                    int before = t.length();
                    t.insert(at, chars, tagList(a[i + 1]));
                    at += t.length() - before;
                }
                return null;
            }
            case "delete": {
                between(a, 1, 2);
                int start = t.index(a[0]);
                t.delete(start, a.length == 1 ? start + 1 : t.index(a[1]));
                return null;
            }
            // ---- marks ----
            case "mark_set": TkControlApi.count(a, 2); t.markSet(name(a[0]), t.index(a[1])); return null;
            case "mark_unset": for (Object o : a) t.markUnset(name(o)); return null;
            case "mark_names": TkControlApi.count(a, 0); return t.markNames().toArray();
            case "mark_gravity": between(a, 1, 2); return t.gravity(name(a[0]), a.length == 2 ? name(a[1]) : null);
            case "mark_next": case "mark_previous": {
                TkControlApi.count(a, 1);
                String mark = t.markNext(name(a[0]), action.equals("mark_next"));
                return mark.isEmpty() ? null : mark;
            }
            // ---- tags ----
            case "tag_add": case "tag_remove": {
                // tag index1 [index2 index1 index2 ...]: an index alone is its one character.
                if (a.length < 2) throw new IllegalArgumentException("wrong # args");
                String tag = name(a[0]);
                if (action.equals("tag_remove") && t.tag(tag) == null) return null;
                for (int i = 1; i < a.length; i += 2) {
                    int from = t.index(a[i]), to = i + 1 < a.length ? t.index(a[i + 1]) : from + 1;
                    if (action.equals("tag_add")) t.tagAdd(tag, from, to); else t.tagRemove(tag, from, to);
                }
                return null;
            }
            case "tag_delete": for (Object o : a) t.tagDelete(name(o)); return null;
            case "tag_names": between(a, 0, 1); return t.tagNames(a.length == 0 ? null : t.index(a[0])).toArray();
            case "tag_ranges": {
                TkControlApi.count(a, 1);
                Text.Tag tag = t.tag(name(a[0]));
                if (tag == null) return new Object[0];
                List<Object> out = new ArrayList<>();
                for (int[] r : tag.ranges) { out.add(t.index(r[0])); out.add(t.index(r[1])); }
                return out.toArray();
            }
            case "tag_nextrange": case "tag_prevrange": {
                between(a, 2, 3);
                boolean next = action.equals("tag_nextrange");
                int from = t.index(a[1]), to = a.length == 3 ? t.index(a[2]) : next ? t.length() : 0;
                int[] r = t.tagRange(name(a[0]), from, to, next);
                return r == null ? new Object[0] : new Object[] {t.index(r[0]), t.index(r[1])};
            }
            case "tag_raise": case "tag_lower":
                between(a, 1, 2); t.tagRaise(name(a[0]), a.length == 2 ? name(a[1]) : null, action.equals("tag_raise")); return null;
            case "tag_configure": {
                TkControlApi.count(a, 2);
                Map<String, Object> options = new LinkedHashMap<>();
                if (a[1] != null && !(a[1] instanceof Object[] && ((Object[]) a[1]).length == 0)) {
                    if (!(a[1] instanceof Map)) throw new IllegalArgumentException("options expected");
                    Map<?, ?> given = (Map<?, ?>) a[1];
                    if (given.size() > Text.TAG_OPTIONS.length) throw new IllegalArgumentException("too many options");
                    for (Map.Entry<?, ?> e : given.entrySet()) options.put(name(e.getKey()), e.getValue());
                }
                Map<String, String> result = t.tagConfigure(name(a[0]), options);
                List<Object> flat = new ArrayList<>();
                for (Map.Entry<String, String> e : result.entrySet()) { flat.add(e.getKey()); flat.add(e.getValue()); }
                return flat.toArray();
            }
            case "tag_cget": {
                TkControlApi.count(a, 2);
                Text.Tag tag = t.tag(name(a[0]));
                if (tag == null) throw new IllegalArgumentException("tag \"" + name(a[0]) + "\" isn't defined in text widget");
                String option = name(a[1]);
                if (!java.util.Arrays.asList(Text.TAG_OPTIONS).contains(option)) throw new IllegalArgumentException("unknown option \"-" + option + "\"");
                String value = tag.options.get(option);
                return value == null ? "" : value;
            }
            // ---- search, count, dump, geometry ----
            case "search": {
                // pattern index stopindex-or-"" backwards regexp nocase
                TkControlApi.count(a, 6);
                String pattern = name(a[0]);
                if (pattern.length() > 4096) throw new IllegalArgumentException("search pattern too long");
                int from = t.index(a[1]);
                Integer stop = a[2] == null || "".equals(a[2]) ? null : t.index(a[2]);
                int[] count = new int[1];
                int at = t.search(pattern, from, stop, TkOptions.bool(a[3]), TkOptions.bool(a[4]), TkOptions.bool(a[5]), count);
                return at < 0 ? new Object[0] : new Object[] {t.index(at), count[0]};
            }
            case "count": {
                if (a.length < 2 || a.length > 12) throw new IllegalArgumentException("wrong # args");
                int from = t.index(a[0]), to = t.index(a[1]);
                List<String> options = new ArrayList<>();
                for (int i = 2; i < a.length; i++) options.add(name(a[i]));
                List<Integer> counts = t.count(Math.min(from, to), Math.max(from, to), options);
                if (to < from) counts.replaceAll(n -> -n);
                return counts.toArray();
            }
            case "dump": {
                // index1 index2-or-"" text mark tag
                TkControlApi.count(a, 5);
                int from = t.index(a[0]), to = a[1] == null || "".equals(a[1]) ? from + 1 : t.index(a[1]);
                List<Object> out = new ArrayList<>();
                for (Object[] row : t.dump(from, to, TkOptions.bool(a[2]), TkOptions.bool(a[3]), TkOptions.bool(a[4]))) out.add(row);
                if (out.size() > 65536) throw new IllegalArgumentException("dump too long");
                return out.toArray();
            }
            case "bbox": case "dlineinfo": {
                TkControlApi.count(a, 1);
                int[] box = t.bbox(t.index(a[0]), action.equals("dlineinfo"));
                if (box == null) return null;
                Object[] out = new Object[box.length];
                for (int i = 0; i < box.length; i++) out[i] = box[i];
                return out;
            }
            case "selection_get": TkControlApi.count(a, 0); if (!t.selectionPresent()) throw new IllegalArgumentException("no selection"); return t.selectedText();
            case "see": TkControlApi.count(a, 1); t.see(t.index(a[0])); return null;
            case "compare": {
                TkControlApi.count(a, 3); int left = t.index(a[0]), right = t.index(a[2]);
                switch (name(a[1])) {
                    case "<": return left < right; case "<=": return left <= right; case "==": return left == right;
                    case "!=": return left != right; case ">": return left > right; case ">=": return left >= right;
                    default: throw new IllegalArgumentException("bad comparison operator \"" + name(a[1]) + "\": must be <, <=, ==, >=, >, or !=");
                }
            }
            // ---- the undo stack ----
            case "edit_modified":
                if (a.length == 0) return t.modified ? 1 : 0;
                TkControlApi.count(a, 1); t.modified(TkOptions.bool(a[0])); return null;
            case "edit_undo": case "edit_redo": {
                TkControlApi.count(a, 0);
                boolean redo = action.equals("edit_redo");
                if (!t.undoRedo(redo)) throw new IllegalArgumentException(redo ? "nothing to redo" : "nothing to undo");
                return null;
            }
            case "edit_reset": TkControlApi.count(a, 0); t.resetUndo(); return null;
            case "edit_separator": TkControlApi.count(a, 0); if (t.undo) t.separator(); return null;
            // ---- the view ----
            case "xview": case "yview": {
                boolean x = action.equals("xview"); if (a.length == 0) return t.view(x);
                String op = name(a[0]);
                if (op.equals("moveto")) { TkControlApi.count(a, 2); t.moveto(x, TkControlApi.number(a[1])); }
                else if (op.equals("scroll")) {
                    TkControlApi.count(a, 3); String units = name(a[2]);
                    if (!units.equals("units") && !units.equals("pages")) throw new IllegalArgumentException("units or pages required");
                    t.scroll(x, TkOptions.integer(a[1], Integer.MIN_VALUE, Integer.MAX_VALUE), units.equals("pages"));
                } else throw new IllegalArgumentException("moveto or scroll required");
                return null;
            }
            default: throw new IllegalArgumentException("unsupported Text operation: " + action);
        }
    }
}
