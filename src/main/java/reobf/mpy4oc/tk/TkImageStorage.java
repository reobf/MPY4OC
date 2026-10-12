package reobf.mpy4oc.tk;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * Where a display keeps its image pixels, outside the chunk's NBT: photos easily exceed the
 * region file's 1 MiB per chunk, and an oversized chunk loses everything in it on save. The
 * NBT keeps only id, size and version. {@link Files} keeps one directory per display under
 * the world: {@code mpy4oc/tk-images/<store id>/<image id>.tki}.
 */
public interface TkImageStorage {
    byte[] read(int id) throws IOException;
    void write(int id, byte[] packed) throws IOException;
    /** Delete the stored images not in {@code ids}. */
    void retain(Set<Integer> ids) throws IOException;

    final class Directory implements TkImageStorage {
        private final File dir;
        public Directory(File dir) { this.dir = dir; }
        private File file(int id) { return new File(dir, id + ".tki"); }

        /** The file's bytes, or null if there is none; never reads more than any image can need. */
        @Override public byte[] read(int id) throws IOException {
            java.nio.file.Path path = file(id).toPath();
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null;
            if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("not a regular file: " + path);
            int limit = TkImageCodec.maxPacked(TkImage.MAX_SIDE, TkImage.MAX_SIDE);
            try (java.io.InputStream in = Files.newInputStream(path)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0;) {
                    if (out.size() + n > limit) throw new IOException("image file too large: " + path);
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        }
        @Override public void write(int id, byte[] packed) throws IOException {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            File temp = new File(dir, id + ".tki.tmp");
            Files.write(temp.toPath(), packed);
            Files.move(temp.toPath(), file(id).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        @Override public void retain(Set<Integer> ids) {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                if (!name.endsWith(".tki") && !name.endsWith(".tki.tmp")) continue;
                String stem = name.substring(0, name.indexOf('.'));
                boolean keep = name.endsWith(".tki") && stem.matches("[0-9]{1,10}") && Long.parseLong(stem) <= Integer.MAX_VALUE
                        && ids.contains((int) Long.parseLong(stem));
                if (!keep && !f.delete()) reobf.mpy4oc.main.MyMod.LOG.warn("Could not delete unused Tk image file {}", f);
            }
            String[] left = dir.list();
            if (left != null && left.length == 0) dir.delete();
        }
        /** Remove the whole directory (the display was broken). */
        public void deleteAll() {
            try { retain(java.util.Collections.emptySet()); } catch (RuntimeException ignored) {}
        }
    }
}
