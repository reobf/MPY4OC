package reobf.mpy4oc.util;
import li.cil.oc.api.fs.FileSystem;
import li.cil.oc.api.fs.Handle;
import li.cil.oc.api.fs.Mode;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MapFileSystemProvider extends FileSystemProvider {

    private final Map<URI, MapFileSystem> fileSystems = new ConcurrentHashMap<>();

    @Override
    public String getScheme() { return "ocfs"; }

    @Override
    public java.nio.file.FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        FileSystem ocFs = (FileSystem) env.get("ocFs");
        if (ocFs == null) throw new IllegalArgumentException("env must contain 'ocFs'");
        MapFileSystem fs = new MapFileSystem(this, ocFs);
        fileSystems.put(uri, fs);
        return fs;
    }

    @Override
    public java.nio.file.FileSystem getFileSystem(URI uri) {
        MapFileSystem fs = fileSystems.get(uri);
        if (fs == null) throw new FileSystemNotFoundException(uri.toString());
        return fs;
    }

    @Override
    public Path getPath(URI uri) {
        URI fsUri = URI.create(uri.getScheme() + "://" + uri.getHost());
        return getFileSystem(fsUri).getPath(uri.getPath());
    }


    @Override
    public SeekableByteChannel newByteChannel(Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {

        FileSystem ocFs = ocFs(path);
        //MapFileSystem mfs = (MapFileSystem) path.getFileSystem();
        String p = normalize(path);

        if (!ocFs(path).exists(p) && !options.contains(StandardOpenOption.CREATE)
                && !options.contains(StandardOpenOption.CREATE_NEW)) {
            throw new NoSuchFileException(p);
        }

        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean write  = options.contains(StandardOpenOption.WRITE)
                      || options.contains(StandardOpenOption.CREATE)
                      || options.contains(StandardOpenOption.CREATE_NEW);

        Mode mode = append ? Mode.Append : write ? Mode.Write : Mode.Read;

        return new OcSeekableByteChannel(ocFs, p, mode);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
            DirectoryStream.Filter<? super Path> filter) throws IOException {

        FileSystem ocFs = ocFs(dir);
        String p = normalize(dir);
        String[] names = ocFs.list(p);
        if (names == null) throw new NotDirectoryException(p);

        MapFileSystem mfs = (MapFileSystem) dir.getFileSystem();
        List<Path> children = new ArrayList<>();
        for (String name : names) {

            String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            Path child = mfs.getPath(p.equals("/") ? "/" + clean : p + "/" + clean);
            try {
                if (filter.accept(child)) children.add(child);
            } catch (IOException ignored) {}
        }

        return new DirectoryStream<>() {
            @Override public Iterator<Path> iterator() { return children.iterator(); }
            @Override public void close() {}
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        FileSystem ocFs = ocFs(dir);
        String p = normalize(dir);
        if (!ocFs.makeDirectory(p))
            throw new IOException("Cannot create directory: " + p);
    }

    @Override
    public void delete(Path path) throws IOException {
        FileSystem ocFs = ocFs(path);
        String p = normalize(path);
        if (!ocFs.delete(p))
            throw new IOException("Cannot delete: " + p);
 
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {

        try (SeekableByteChannel r = newByteChannel(source, Set.of(StandardOpenOption.READ));
             SeekableByteChannel w = newByteChannel(target,
                     Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE))) {
            ByteBuffer buf = ByteBuffer.allocate(4096);
            while (r.read(buf) != -1) {
                buf.flip();
                w.write(buf);
                buf.clear();
            }
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        FileSystem ocFs = ocFs(source);
        String from = normalize(source);
        String to   = normalize(target);
        try {
            if (!ocFs.rename(from, to))
                throw new IOException("Cannot move: " + from + " -> " + to);
        } catch (FileNotFoundException e) {
            throw new NoSuchFileException(from);
        }
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return path.toAbsolutePath().equals(path2.toAbsolutePath());
    }

    @Override
    public boolean isHidden(Path path) { return false; }

    @Override
    public FileStore getFileStore(Path path) {
        return ((MapFileSystem) path.getFileSystem()).getFileStores().iterator().next();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        FileSystem ocFs = ocFs(path);
        String p = normalize(path);
        if (!ocFs.exists(p)) throw new NoSuchFileException(p);
        if (ocFs.isReadOnly()) {
            for (AccessMode m : modes)
                if (m == AccessMode.WRITE) throw new AccessDeniedException(p);
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(
            Path path, Class<A> type, LinkOption... options) throws IOException {

        FileSystem ocFs = ocFs(path);
        String p = normalize(path);

        if (!ocFs.exists(p)) throw new NoSuchFileException(p);

        boolean isDir  = ocFs.isDirectory(p);
        long size      = ocFs.size(p);
        long modified  = ocFs.lastModified(p);

        return (A) new BasicFileAttributes() {
            @Override public FileTime lastModifiedTime() { return FileTime.fromMillis(modified); }
            @Override public FileTime lastAccessTime()   { return FileTime.fromMillis(modified); }
            @Override public FileTime creationTime()     { return FileTime.fromMillis(modified); }
            @Override public boolean isRegularFile()     { return !isDir; }
            @Override public boolean isDirectory()       { return isDir; }
            @Override public boolean isSymbolicLink()    { return false; }
            @Override public boolean isOther()           { return false; }
            @Override public long size()                 { return size; }
            @Override public Object fileKey()            { return p; }
        };
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes,
            LinkOption... options) {
        return Map.of();
    }

    @Override
    public void setAttribute(Path path, String attribute,
            Object value, LinkOption... options) {}


    private static FileSystem ocFs(Path path) {
        return ((MapFileSystem) path.getFileSystem()).ocFs;
    }

/*
    private static String normalize(Path path) {
        String s = path.toAbsolutePath().toString();
        if (s.length() > 1 && s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        return s;
    }
*/
    private static String normalize(Path path) {
        String s = path.toAbsolutePath().normalize().toString(); // ← 加 .normalize()
      
        if (s.length() > 1 && s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
       
        while (s.startsWith("/"))
            s = s.substring(1);
        return s;
    }
    private static class OcSeekableByteChannel implements SeekableByteChannel {


        private final FileSystem ocFs;
        private final String path;
        private final Mode mode;
        

        private Handle handle = null;
        private int handleId = -1;
        private boolean open = true;

        OcSeekableByteChannel(FileSystem mapFs, String path, Mode mode) throws IOException {
        
            this.ocFs  = mapFs;
            this.path  = path;
            this.mode  = mode;
            if (mode == Mode.Write || mode == Mode.Append) {
                try {
                	 int id = ocFs.open(path, mode);
                     Handle h = ocFs.getHandle(id);
                     h.close(); 
                     // create an empty file then close it
                     // 傻逼sshd 新建空文件 不会close掉这个Channel 会泄露OC handle
                     // 所以只能懒加载Handle
                } catch (java.io.FileNotFoundException e) {
                    throw new IOException(e);
                }
            }
        }

        private Handle getOrOpenHandle() throws IOException {
            if (handle == null) {
                try {
                    handleId = ocFs.open(path, mode);
                    handle = ocFs.getHandle(handleId);
                } catch (java.io.FileNotFoundException e) {
                    throw new IOException(e);
                }
            }
            return handle;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            ensureOpen();
            byte[] buf = new byte[dst.remaining()];
            int n = getOrOpenHandle().read(buf);
            if (n == -1) return -1;
            dst.put(buf, 0, n);
            return n;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            ensureOpen();
            byte[] buf = new byte[src.remaining()];
            src.get(buf);
            getOrOpenHandle().write(buf);
            return buf.length;
        }

        @Override
        public long position() throws IOException {
            ensureOpen();
            if (handle == null) return 0; // handle 还没开，position 就是 0
            return handle.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            getOrOpenHandle().seek(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            ensureOpen();
            if (handle == null) return ocFs.size(path); // 不开 handle 直接查大小
            return handle.length();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() { return open; }

        @Override
        public void close() throws IOException {
            if (open) {
                if (handle != null) { // 只有真正开过才需要关
                    handle.close();
                    handle = null;
                }
                open = false;
              
            }
        }

        private void ensureOpen() throws IOException {
            if (!open) throw new ClosedChannelException();
        }
    }
    @Override
    public FileChannel newFileChannel(Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {

        SeekableByteChannel channel = newByteChannel(path, options, attrs);
        
        return new FileChannel() {
            @Override
            public int read(ByteBuffer dst) throws IOException {
                return channel.read(dst);
            }

            @Override
            public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
                long total = 0;
                for (int i = offset; i < offset + length; i++) {
                    int n = channel.read(dsts[i]);
                    if (n == -1) break;
                    total += n;
                }
                return total;
            }

            @Override
            public int write(ByteBuffer src) throws IOException {
                return channel.write(src);
            }

            @Override
            public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
                long total = 0;
                for (int i = offset; i < offset + length; i++) {
                    total += channel.write(srcs[i]);
                }
                return total;
            }

            @Override
            public long position() throws IOException {
                return channel.position();
            }

            @Override
            public FileChannel position(long newPosition) throws IOException {
                channel.position(newPosition);
                return this;
            }

            @Override
            public long size() throws IOException {
                return channel.size();
            }

            @Override
            public FileChannel truncate(long size) throws IOException {
                channel.truncate(size);
                return this;
            }

            @Override
            public void force(boolean metaData) {}

            @Override
            public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
                channel.position(position);
                long transferred = 0;
                ByteBuffer buf = ByteBuffer.allocate(4096);
                while (transferred < count) {
                    buf.clear();
                    int limit = (int) Math.min(buf.capacity(), count - transferred);
                    buf.limit(limit);
                    int n = channel.read(buf);
                    if (n == -1) break;
                    buf.flip();
                    target.write(buf);
                    transferred += n;
                }
                return transferred;
            }

            @Override
            public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
                channel.position(position);
                long transferred = 0;
                ByteBuffer buf = ByteBuffer.allocate(4096);
                while (transferred < count) {
                    buf.clear();
                    int limit = (int) Math.min(buf.capacity(), count - transferred);
                    buf.limit(limit);
                    int n = src.read(buf);
                    if (n == -1) break;
                    buf.flip();
                    channel.write(buf);
                    transferred += n;
                }
                return transferred;
            }

            @Override
            public int read(ByteBuffer dst, long position) throws IOException {
                channel.position(position);
                return channel.read(dst);
            }

            @Override
            public int write(ByteBuffer src, long position) throws IOException {
                channel.position(position);
                return channel.write(src);
            }

            @Override
            public MappedByteBuffer map(MapMode mode, long position, long size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileLock lock(long position, long size, boolean shared) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FileLock tryLock(long position, long size, boolean shared) {
                throw new UnsupportedOperationException();
            }

            @Override
            protected void implCloseChannel() throws IOException {
                channel.close();
            }

        };
    }
    }