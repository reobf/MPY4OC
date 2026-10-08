package reobf.mpy4oc.tk;

/** MC keeps a process-wide tile mapping; test classes may run in any order. */
public final class TkTestRegistration {
    private static boolean registered;
    public static synchronized void ensure() {
        if (!registered) {
            net.minecraft.tileentity.TileEntity.addMapping(TileTkDisplay.class, "mpy4oc:tk_display");
            registered = true;
        }
    }
}
