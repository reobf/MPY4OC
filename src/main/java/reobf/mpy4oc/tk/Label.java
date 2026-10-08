package reobf.mpy4oc.tk;

public class Label extends TkWidget {
    private String text = "";
    int columns, lines, wraplength;
    private String wrapped;
    public Label(Frame parent, String text) { this(parent, text, Kind.LABEL); }
    Label(Frame parent, String text, Kind kind) {
        super(checkedParent(parent, text).tree, parent, kind);
        padx = pady = 1;
        text(text);
    }
    private static Frame checkedParent(Frame parent, String text) {
        TkFontMetrics.checkText(text); parent.alive(); parent.tree.checkTextBudget(null, text); return parent;
    }
    @Override public final String text() { return text; }
    public Label text(String text) {
        alive(); TkFontMetrics.checkText(text);
        tree.checkTextBudget(this, text);
        this.text = text; wrapped = null; tree.changed(true); return this;
    }

    public void wraplength(int pixels) {
        alive(); if (pixels < 0 || pixels > 8192) throw new IllegalArgumentException("invalid wraplength");
        wraplength = pixels; wrapped = null; tree.changed(true);
    }
    String paintText() {
        if (wraplength == 0 || this instanceof Entry) return text;
        if (wrapped == null) wrapped = TkText.wrap(text, wraplength);
        return wrapped;
    }

    public Label textSize(int columns, int lines) {
        alive();
        if (columns < 0 || columns > 1024 || lines < 0 || lines > 512)
            throw new IllegalArgumentException("invalid text dimensions");
        this.columns = columns; this.lines = lines; width = height = 0; tree.changed(true); return this;
    }
}
