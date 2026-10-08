package reobf.mpy4oc.tk;

/** Immutable grid placement. Padding is in pixels, indices are zero based. */
public final class TkGrid {
    public static final int MAX_SLOTS = 128;
    public final int row, column, rowspan, columnspan, left, right, top, bottom, ipadx, ipady;
    public final String sticky;
    public TkGrid(int row, int column, int rowspan, int columnspan, String sticky,
                  int left, int right, int top, int bottom, int ipadx, int ipady) {
        if (row < 0 || column < 0 || rowspan < 1 || columnspan < 1
                || row + (long) rowspan > MAX_SLOTS || column + (long) columnspan > MAX_SLOTS)
            throw new IllegalArgumentException("grid indices and spans must fit 128 rows/columns");
        String flags = sticky.replace(" ", "").replace(",", "");
        for (int i = 0; i < flags.length(); i++) if ("nsew".indexOf(flags.charAt(i)) < 0)
            throw new IllegalArgumentException("sticky must contain n, s, e, w");
        this.sticky = flags;
        for (int pad : new int[] {left, right, top, bottom, ipadx, ipady})
            if (pad < 0 || pad > 1024) throw new IllegalArgumentException("padding must be 0..1024");
        this.row = row; this.column = column; this.rowspan = rowspan; this.columnspan = columnspan;
        this.left = left; this.right = right; this.top = top; this.bottom = bottom; this.ipadx = ipadx; this.ipady = ipady;
    }
    public TkGrid(int row, int column) { this(row, column, 1, 1, "", 0, 0, 0, 0, 0, 0); }
    boolean has(char flag) { return sticky.indexOf(flag) >= 0; }

    public static final class Slot {
        public static final Slot DEFAULT = new Slot(0, 0, 0);
        public final int weight, minsize, pad;
        public Slot(int weight, int minsize, int pad) {
            this.weight = TkOptions.integer(weight, 0, 1000);
            this.minsize = TkOptions.integer(minsize, 0, 8192);
            this.pad = TkOptions.integer(pad, 0, 1024);
        }
        boolean empty() { return weight == 0 && minsize == 0 && pad == 0; }
    }
}
