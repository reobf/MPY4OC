package reobf.mpy4oc.tk;

/** Read-only wrapping text. Width is pixels, unlike Label's character width. */
public final class Message extends Label {
    public Message(Frame parent) { super(parent, "", Kind.MESSAGE); wraplength = 160; textAnchor = TkPack.Anchor.NW; }
}
