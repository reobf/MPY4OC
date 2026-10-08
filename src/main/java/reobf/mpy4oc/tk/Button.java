package reobf.mpy4oc.tk;

public class Button extends Label {
    private int command;
    public Button(Frame parent, String text) {
        this(parent, text, Kind.BUTTON);
    }
    Button(Frame parent, String text, Kind kind) {
        super(parent, text, kind);
        padx = 4; pady = 3; border = 2;
    }
    public int command() { return command; }
    /** Zero means no Python command; positive IDs identify Python's callback table. */
    public Button command(int callbackId) {
        alive();
        if (callbackId < 0) throw new IllegalArgumentException("negative callback ID");
        command = callbackId; tree.changed(false); return this;
    }
}
