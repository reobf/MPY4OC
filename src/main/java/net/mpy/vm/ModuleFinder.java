package net.mpy.vm;

/**
 * The virtual filesystem hook: maps extension-less paths ("<v>/game/ai/brain",
 * "gamelib") to .mpy bytes, or null for "not found". A plain map works:
 * {@code vm.setModuleFinder(fs::get)}. Snapshots embed every module actually
 * loaded, so restores resume without the finder; only imports of modules never
 * loaded before the snapshot need it re-provided.
 */
public interface ModuleFinder {
    byte[] find(String path);

    /** Compose finders: the first non-null hit wins. */
    static ModuleFinder chain(ModuleFinder... finders) {
        return path -> {
            for (ModuleFinder f : finders) {
                if (f == null) continue;
                byte[] b = f.find(path);
                if (b != null) return b;
            }
            return null;
        };
    }
}
