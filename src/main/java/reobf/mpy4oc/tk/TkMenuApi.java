package reobf.mpy4oc.tk;

/**
 * The program's "menu" operation on a Menu: its entries (the whole list, see Menu.entries), post
 * and unpost, the active entry, and Tk's geometry queries (yposition, @y).
 */
final class TkMenuApi {
    private TkMenuApi() {}

    static Object call(Menu menu, String action, Object[] args) {
        TkTree tree = menu.tree;
        switch (action) {
            case "entries":
                count(args, 1);
                menu.entries(args[0]);
                return null;
            case "post": {
                count(args, 3);
                int x = TkOptions.integer(args[0], -8192, 8192), y = TkOptions.integer(args[1], -8192, 8192);
                int entry = TkOptions.integer(args[2], -1, Menu.MAX_ENTRIES);
                tree.postMenu(menu, x, y, entry);
                return null;
            }
            case "unpost":
                count(args, 0);
                tree.unpostMenu(menu);
                return null;
            case "posted":
                count(args, 0);
                return tree.posted(menu);
            case "activate":
                count(args, 1);
                menu.activate(TkOptions.integer(args[0], -1, Menu.MAX_ENTRIES));
                return null;
            case "active":
                count(args, 0);
                return menu.active;
            case "yposition":
                count(args, 1);
                return menu.entryY(TkOptions.integer(args[0], 0, Menu.MAX_ENTRIES));
            case "index_at": {
                count(args, 1);
                return menu.entryAt(TkOptions.integer(args[0], -8192, 8192));
            }
            default: throw new IllegalArgumentException("unsupported menu operation: " + action);
        }
    }

    private static void count(Object[] args, int n) {
        if (args.length != n) throw new IllegalArgumentException("wrong argument count for menu operation");
    }
}
