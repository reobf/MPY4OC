package reobf.mpy4oc.tk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Frame extends TkWidget {
    final List<TkWidget> children = new ArrayList<>();
    final List<TkWidget> packed = new ArrayList<>();
    final List<TkWidget> gridded = new ArrayList<>();
    final java.util.Map<Integer, TkGrid.Slot> rows = new java.util.TreeMap<>(), columns = new java.util.TreeMap<>();
    boolean gridPropagate = true;
    TkPack.Anchor gridAnchor = TkPack.Anchor.NW;
    int[] gridColumns = new int[0], gridRows = new int[0];
    int gridX, gridY;
    boolean propagate = true;

    public Frame(Frame parent) { this(parent.tree, parent); }
    Frame(TkTree tree, Frame parent) { super(tree, parent, Kind.FRAME); }
    public List<TkWidget> children() { return Collections.unmodifiableList(children); }
    public Frame propagate(boolean value) { alive(); propagate = value; tree.changed(true); return this; }
    public Frame gridPropagate(boolean value) { alive(); gridPropagate = value; tree.changed(true); return this; }
    public Frame gridAnchor(TkPack.Anchor value) { alive(); gridAnchor = java.util.Objects.requireNonNull(value); tree.changed(true); return this; }
    public Frame columnconfigure(int index, TkGrid.Slot value) { return slot(columns, index, value); }
    public Frame rowconfigure(int index, TkGrid.Slot value) { return slot(rows, index, value); }
    private Frame slot(java.util.Map<Integer, TkGrid.Slot> slots, int index, TkGrid.Slot value) {
        alive(); TkOptions.integer(index, 0, TkGrid.MAX_SLOTS - 1); java.util.Objects.requireNonNull(value);
        if (value.empty()) slots.remove(index); else slots.put(index, value);
        tree.changed(true); return this;
    }
}
