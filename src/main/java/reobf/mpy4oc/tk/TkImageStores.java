package reobf.mpy4oc.tk;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Which loaded display uses each image directory (server thread only). A display's NBT
 * names its directory, so a copied display (world editors, schematics) arrives with the
 * same name: the second one to claim it forks to a directory of its own. A broken display's
 * directory is deleted a little later, and only if no display claimed it meanwhile (a move
 * breaks the old block and loads the copy, in either order).
 */
public final class TkImageStores {
    private TkImageStores() {}

    /** How long a broken display's directory waits before it is deleted. */
    static final int DELETE_DELAY_TICKS = 100;

    private static final class Owner {
        final WeakReference<TileTkDisplay> tile; final int dimension, x, y, z;
        Owner(TileTkDisplay tile) {
            this.tile = new WeakReference<>(tile);
            dimension = tile.getWorldObj() == null ? 0 : tile.getWorldObj().provider.dimensionId; x = tile.xCoord; y = tile.yCoord; z = tile.zCoord;
        }
        /** Another display really using the directory: loaded, not this tile, and somewhere else. */
        boolean conflicts(TileTkDisplay other) {
            TileTkDisplay t = tile.get();
            if (t == null || t == other || t.isInvalid()) return false;
            // The same position in the same world is the same display, reloaded as a new object.
            int otherDimension = other.getWorldObj() == null ? 0 : other.getWorldObj().provider.dimensionId;
            return dimension != otherDimension || x != other.xCoord || y != other.yCoord || z != other.zCoord;
        }
    }

    private static final Map<String, Owner> owners = new HashMap<>();
    private static final Map<String, File> doomed = new HashMap<>();
    private static final Map<String, Long> deadlines = new HashMap<>();

    /** Claims {@code store} for {@code tile}; false if another loaded display uses it (then {@code tile} must fork). */
    static synchronized boolean claim(String store, TileTkDisplay tile) {
        Owner owner = owners.get(store);
        if (owner != null && owner.conflicts(tile)) return false;
        owners.put(store, new Owner(tile));
        doomed.remove(store); deadlines.remove(store);
        return true;
    }

    static synchronized void release(String store, TileTkDisplay tile) {
        Owner owner = owners.get(store);
        if (owner != null && owner.tile.get() == tile) owners.remove(store);
    }

    /** The display using {@code store} was broken: delete {@code dir} later unless another display claims it first. */
    static synchronized void doom(String store, File dir, long tick) {
        doomed.put(store, dir); deadlines.put(store, tick + DELETE_DELAY_TICKS);
    }

    public static synchronized void tick(long tick) {
        for (Iterator<Map.Entry<String, Long>> it = deadlines.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> e = it.next();
            if (tick < e.getValue()) continue;
            it.remove();
            File dir = doomed.remove(e.getKey());
            Owner owner = owners.get(e.getKey());
            TileTkDisplay user = owner == null ? null : owner.tile.get();
            if (dir != null && (user == null || user.isInvalid())) new TkImageStorage.Directory(dir).deleteAll();
        }
    }

    /** Server stopped. Pending deletions are dropped: with chunks unloading, nothing tells whether a moved display still uses them. */
    public static synchronized void clear() { owners.clear(); doomed.clear(); deadlines.clear(); }
}
