package reobf.mpy4oc.tk;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class TkTextFeaturesTest {
    private Text text() {
        Text text = new Text(new TkTree().root());
        TkOptions.configure(text, Map.of("width", 20, "height", 6));
        text.pack(); text.tree.render(); return text;
    }
    private void undo(Text t, int max) { TkOptions.configure(t, Map.of("undo", true, "maxundo", max)); }

    @Test void selectionIdentitySurvivesPriorityChangesAndSaving() {
        Text t = text(); t.insert(0, "abcdefgh");
        t.tagAdd("red", 0, 6); t.tagConfigure("red", Map.of("foreground", "#ff0000"));
        t.tagAdd("blue", 1, 5); t.tagConfigure("blue", Map.of("background", "#1122aa"));
        t.tagRaise("blue", "red", false); t.tagRaise("sel", null, true); t.selection(2, 4);
        assertEquals("cd", t.selectedText()); assertEquals(List.of("blue", "red", "sel"), t.tagNames(null));
        Text copy = text(); copy.load(t.save());
        assertEquals(t.tagNames(null), copy.tagNames(null)); assertEquals("cd", copy.selectedText());
        copy.tagDelete("sel"); assertFalse(copy.selectionPresent()); assertNotNull(copy.tag("red"));
    }

    @Test void gravityOrderAndDumpFollowTkAtSharedBoundaries() {
        Text t = text(); t.insert(0, "abcd");
        for (String mark : List.of("r1", "r2", "l1", "l2")) { t.markSet(mark, 2); t.gravity(mark, mark.startsWith("l") ? "left" : "right"); }
        t.tagAdd("a", 0, 2); t.tagAdd("b", 0, 2); t.tagAdd("c", 2, 4);
        List<String> rows = new ArrayList<>();
        for (Object[] row : t.dump(2, 3, true, true, true)) rows.add(row[0] + ":" + row[1]);
        assertEquals(List.of("mark:l1", "mark:l2", "tagoff:a", "tagoff:b", "tagon:c", "mark:r2", "mark:r1", "text:c"), rows);
        t.markSet("r1", 2); assertEquals("r1", t.markNext("l2", true));
        t.markSet("insert", 2); t.gravity("insert", "left"); t.insert(2, "XY");
        assertEquals(2, t.cursor); assertEquals("1.2", t.index(t.index("l1"))); assertEquals("1.4", t.index(t.index("r1")));
        Text copy = text(); copy.load(t.save());
        assertEquals(t.dump(0, t.length(), true, true, true).stream().map(Arrays::asList).toList(),
                copy.dump(0, copy.length(), true, true, true).stream().map(Arrays::asList).toList());
        assertEquals(t.markNext("l2", true), copy.markNext("l2", true));
        assertEquals("left", copy.gravity("insert", null));
        copy.insert(2, "Z"); assertEquals(2, copy.cursor);
    }

    @Test void undoKeysAndCleanCheckpointSurviveSavingIncludingRedoAndOpenUnit() {
        Text t = text(); undo(t, 3); t.notify = 17; t.tree.focus(t.id);
        t.insert(0, "abc"); t.separator(); t.modified(false); t.insert(3, "XY"); t.separator();
        t.tree.key(44, 2, 0); assertEquals("abc\n", t.content()); assertFalse(t.modified);
        Text copy = text(); copy.load(t.save()); copy.tree.focus(copy.id);
        copy.tree.key(21, 2, 0); assertEquals("abcXY\n", copy.content()); assertTrue(copy.modified);
        copy.tree.key(44, 2, 0); copy.tree.key(44, 3, 0); assertEquals("abcXY\n", copy.content());
        copy.insert(5, "Z"); Text open = text(); open.load(copy.save());
        assertTrue(open.undoRedo(false)); assertEquals("abcXY\n", open.content());
        assertTrue(t.tree.takeCommands().stream().anyMatch(c -> c.callback == 17));
        open.modified(true); open.undoRedo(false); assertTrue(open.modified, "explicit modified(True) stays set during undo");
    }

    @Test void undoLimitsAndBranchingDiscardRedoWithoutLosingNewHistory() {
        Text t = text(); undo(t, 2);
        for (String value : List.of("a", "b", "c")) { t.insert(t.length(), value); t.separator(); }
        assertTrue(t.undoRedo(false)); assertTrue(t.undoRedo(false)); assertFalse(t.undoRedo(false));
        assertEquals("a\n", t.content()); t.insert(t.length(), "d"); assertFalse(t.undoRedo(true));
        assertTrue(t.undoRedo(false)); assertEquals("a\n", t.content());
        t.resetUndo(); TkOptions.configure(t, Map.of("autoseparators", false));
        for (int i = 0; i < 10; i++) { t.insert(0, "x".repeat(130000)); t.delete(0, 130000); }
        Text copy = text(); assertDoesNotThrow(() -> copy.load(t.save()));
        assertTrue(t.save().getTagList("openUndo", 10).tagCount() < 20, "one growing unit is bounded too");
    }

    @Test void invalidTagsLeaveContentAndMarksIntact() {
        Text t = text(); undo(t, 0); t.insert(0, "original"); t.markSet("m", 3);
        for (int i = 1; i < Text.MAX_TAGS; i++) t.tagConfigure("t" + i, Map.of());
        assertThrows(IllegalArgumentException.class, () -> t.insert(0, "new", List.of("overflow")));
        assertThrows(IllegalArgumentException.class, () -> t.replace(0, 4, "new", List.of("overflow")));
        assertEquals("original\n", t.content()); assertEquals(3, t.index("m"));
        Text range = text(); range.insert(0, "x".repeat(Text.MAX_RANGES * 2 + 2)); range.tagConfigure("tag", Map.of());
        for (int i = 0; i < Text.MAX_RANGES; i++) range.tag("tag").ranges.add(new int[] {2 * i, 2 * i + 1});
        assertThrows(IllegalArgumentException.class, () -> range.tagAdd("tag", Text.MAX_RANGES * 2, Text.MAX_RANGES * 2 + 1));
        assertEquals(Text.MAX_RANGES, range.tag("tag").ranges.size());
        Text split = text(); split.insert(0, "x".repeat(Text.MAX_RANGES * 4)); split.tagConfigure("tag", Map.of());
        for (int i = 0; i < Text.MAX_RANGES; i++) split.tag("tag").ranges.add(new int[] {4 * i, 4 * i + 3});
        assertThrows(IllegalArgumentException.class, () -> split.tagRemove("tag", 1, 2));
        assertArrayEquals(new int[] {0, 3}, split.tag("tag").ranges.get(0));
    }

    @Test void searchCountsUnicodeAndFinalNewlineAndHasTkErrors() {
        Text t = text(); t.insert(0, "A😀ab\nAb 42"); int[] count = {99};
        assertEquals(1, t.search("😀", 0, null, false, false, false, count)); assertEquals(1, count[0]);
        assertEquals(5, t.search("ab", 3, null, false, false, true, count)); assertEquals(2, count[0]);
        assertEquals(t.length() - 1, t.search("\n", t.length() - 1, null, false, false, false, count));
        assertEquals(-1, t.search("42", 0, 4, false, false, false, count));
        assertEquals("couldn't compile regular expression pattern: parentheses () not balanced",
                assertThrows(IllegalArgumentException.class, () -> t.search("(", 0, null, false, true, false, count)).getMessage());
    }

    @Test void undoChecksSharedBudgetBeforeChangingTextOrHistory() {
        Text t = text(); undo(t, 0); t.insert(0, "a".repeat(64)); t.resetUndo(); t.modified(false);
        t.delete(0, 64); t.markSet("m", 0);
        Text other = null;
        for (int i = 0; i < 4; i++) {
            other = new Text(t.tree.root());
            other.insert(0, "€".repeat(174761));
        }
        assertThrows(IllegalArgumentException.class, () -> t.undoRedo(false));
        assertEquals("\n", t.content()); assertEquals(0, t.index("m")); assertTrue(t.modified);
        other.delete(0, other.length());
        assertTrue(t.undoRedo(false)); assertEquals("a".repeat(64) + "\n", t.content()); assertFalse(t.modified);
    }

    @Test void pathologicalRegexStopsWithoutChangingContent() {
        Text t = text(); t.insert(0, "a".repeat(5000));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> t.search("(a+)+b", 0, null, false, true, false, new int[1]));
        assertEquals("Text search work limit exceeded", error.getMessage());
        assertEquals("a".repeat(5000) + "\n", t.content());
    }

    @Test void styledRowsClipTextAndPaintBothDecorationsWithinWireBudgets() {
        Text t = text(); TkOptions.configure(t, Map.of("wrap", "none"));
        t.insert(0, "x".repeat(3000) + "VISIBLE");
        t.tagAdd("style", 0, t.length()); t.tagConfigure("style", Map.of("foreground", "#ff0000", "underline", true, "overstrike", true));
        t.moveto(true, 1); t.tree.focus(t.id); List<TkDisplayList.Item> items = t.tree.render().items;
        assertTrue(items.stream().anyMatch(i -> i.kind == TkWidget.Kind.LABEL && i.text.endsWith("VISIBLE")));
        assertEquals(2, items.stream().filter(i -> i.kind == TkWidget.Kind.FRAME && i.bounds.height == 1 && i.background == 0xff0000).count());
        assertTrue(items.stream().allMatch(i -> i.text.length() < 30));
        int rows = 240 / 16 + 2;
        assertTrue(items.size() <= t.maxRecords(rows));
        assertTrue(items.stream().mapToInt(i -> i.text.getBytes(StandardCharsets.UTF_8).length).sum() <= t.maxBytes(rows));
        assertFalse(t.tree.overflowed());
    }

    @Test void styledTabsAndZeroWidthTextHaveConservativeByteEstimates() {
        Text t = text(); t.insert(0, "\t\t\t\n" + "a" + "\u0301".repeat(5000));
        t.tagAdd("style", 0, t.length()); t.tagConfigure("style", Map.of("background", "#ffff00"));
        List<TkDisplayList.Item> paint = t.tree.render().items;
        int size = paint.stream().mapToInt(i -> i.text.getBytes(StandardCharsets.UTF_8).length).sum();
        assertTrue(size <= t.maxBytes(17), size + " > " + t.maxBytes(17));
        assertTrue(paint.size() <= t.maxRecords(17));
        assertDoesNotThrow(() -> new TkDisplayList(paint)); assertFalse(t.tree.overflowed());
    }

    @Test void bboxDlineinfoAndHoverFollowVisibleRows() {
        Text t = text(); t.insert(0, "abcdefgh\nsecond\n".repeat(12));
        int[] box = t.bbox(1, false); assertNotNull(box); assertEquals(8, box[2]); assertEquals(16, box[3]);
        int[] line = t.bbox(1, true); assertEquals(64, line[2]); assertEquals(13, line[4]);
        t.hover(t.bounds().x + box[0] + 1, t.bounds().y + box[1] + 1); assertEquals(1, t.current);
        t.notify = 19; t.tree.pointerHover(t.bounds().x + box[0] + 1, t.bounds().y + box[1] + 1);
        t.tree.takeCommands(); t.tree.pointerLeft();
        assertTrue(t.tree.takeCommands().stream().anyMatch(c -> c.callback == 19 && c.args[0].equals("leave")));
        assertNull(t.bbox(t.length() - 1, false)); t.see(t.length() - 1); assertNotNull(t.bbox(t.length() - 1, false));
        assertNull(t.bbox(0, false));
    }

    @Test void legacyStateLoadsAndCorruptMetadataIsRejected() {
        Text t = text(); t.insert(0, "abc"); t.selection(0, 2);
        NBTTagCompound legacy = t.save();
        for (String key : List.of("tags", "marks", "options", "undoStack", "redoStack", "openUndo", "insertRight", "currentRight")) legacy.removeTag(key);
        Text old = text(); old.load(legacy); assertEquals("ab", old.selectedText()); assertFalse(old.undo);
        NBTTagCompound bad = t.save(); NBTTagCompound mark = new NBTTagCompound(); mark.setString("name", "insert");
        NBTTagList marks = new NBTTagList(); marks.appendTag(mark); bad.setTag("marks", marks);
        assertThrows(IllegalArgumentException.class, () -> text().load(bad));
        NBTTagCompound duplicate = t.save(); duplicate.getTagList("tags", 10).appendTag(duplicate.getTagList("tags", 10).getCompoundTagAt(0));
        assertThrows(IllegalArgumentException.class, () -> text().load(duplicate));
    }

    @Test void displayIndicesAndCountsUseWrappedRows() {
        Text t = text(); TkOptions.configure(t, Map.of("width", 4, "wrap", "char"));
        t.insert(0, "abcdefgh\nijkl"); t.tree.render();
        assertEquals("1.4", t.index(t.index("1.6 display linestart")));
        assertEquals("1.3", t.index(t.index("1.0 display lineend")));
        assertEquals("1.8", t.index(t.index("1.6 display lineend")));
        assertEquals("1.6", t.index(t.index("1.2 +1 display lines")));
        assertEquals("2.2", t.index(t.index("1.6 +1 display lines")));
        assertEquals("2.4", t.index(t.index("2.1 +1 display lines")));
        assertEquals("2.0", t.index(t.index("end -1 display lines")));
        assertEquals(List.of(3, 48, 0), t.count(0, t.length(), List.of("displaylines", "ypixels", "xpixels")));
        assertEquals(List.of(-3, 48, 0), t.count(t.length(), 0, List.of("displaylines", "ypixels", "xpixels")));
    }
}
