package reobf.mpy4oc.tk;

/**
 * The program's "paned" operation on a Panedwindow: insert (a new pane, or move one), forget,
 * panes, pane options, ttk sashpos, classic sash coord/place, identify. Python checks indices
 * and gives Tk's messages; this checks again.
 */
final class TkPanedApi {
    private TkPanedApi() {}

    static Object call(TkTree tree, Panedwindow p, String action, Object[] args) {
        switch (action) {
            case "insert": {
                count(args, 3);
                int position = TkOptions.integer(args[0], -1, Panedwindow.MAX_PANES);
                TkWidget widget = tree.owned(TkOptions.integer(args[1], 0, Integer.MAX_VALUE));
                p.insert(position < 0 ? p.panes.size() : position, widget, TkOptions.map(args[2]));
                return null;
            }
            case "forget":
                count(args, 1);
                p.forget(index(p, args[0], p.panes.size()));
                return null;
            case "panes": {
                count(args, 0);
                Object[] ids = new Object[p.panes.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = p.panes.get(i).widget.id;
                return ids;
            }
            case "pane": {
                count(args, 2);
                int index = index(p, args[0], p.panes.size());
                java.util.Map<String, Object> options = TkOptions.map(args[1]);
                if (!options.isEmpty()) p.configurePane(index, options);
                return p.paneOptions(index);
            }
            case "sashpos": {
                if (args.length != 1 && args.length != 2) throw new IllegalArgumentException("wrong argument count for paned operation");
                int index = index(p, args[0], p.sashCount());
                if (args.length == 2) return p.sashpos(index, TkOptions.integer(args[1], -1 << 20, 1 << 20));
                tree.render();
                return p.panes.get(index).sashPos;
            }
            case "sash_coord": {
                count(args, 1);
                int index = TkOptions.integer(args[0], -1 << 20, 1 << 20);
                if (!p.validSash(index)) throw new IllegalArgumentException("invalid sash index");
                int[] at = p.sashCoord(index);
                return new Object[] {at[0], at[1]};
            }
            case "sash_place": {
                count(args, 3);
                int index = TkOptions.integer(args[0], -1 << 20, 1 << 20);
                if (!p.validSash(index)) throw new IllegalArgumentException("invalid sash index");
                p.sashPlace(index, TkOptions.integer(args[1], -1 << 20, 1 << 20), TkOptions.integer(args[2], -1 << 20, 1 << 20));
                return null;
            }
            case "identify": {
                count(args, 2);
                tree.render();
                TkRect b = p.bounds;
                return p.sashAt(b.x + TkOptions.integer(args[0], -1 << 20, 1 << 20), b.y + TkOptions.integer(args[1], -1 << 20, 1 << 20));
            }
            default: throw new IllegalArgumentException("unsupported paned operation: " + action);
        }
    }

    private static int index(Panedwindow p, Object value, int count) {
        int index = TkOptions.integer(value, -1 << 20, 1 << 20);
        if (index < 0 || index >= count) throw new IllegalArgumentException("index " + index + " out of range");
        return index;
    }

    private static void count(Object[] args, int n) {
        if (args.length != n) throw new IllegalArgumentException("wrong argument count for paned operation");
    }
}
