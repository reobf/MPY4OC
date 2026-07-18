package reobf.mpy4oc.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;

public class MapPath implements Path {
    private final MapFileSystem fs;
    private final String[] parts; 
    private final boolean absolute;

    public MapPath(MapFileSystem fs, String path) {
        this.fs = fs;
        this.absolute = path.startsWith("/");

        this.parts = Arrays.stream(path.split("/"))
            .filter(s -> !s.isEmpty())
            .toArray(String[]::new);
    }

    @Override public FileSystem getFileSystem() { return fs; }
    @Override public boolean isAbsolute() { return absolute; }

    @Override
    public Path getFileName() {
        if (parts.length == 0) return null;
        return new MapPath(fs, parts[parts.length - 1]);
    }

    @Override
    public Path getParent() {
        if (parts.length == 0) return null;
        String joined = "/" + String.join("/",
            Arrays.copyOf(parts, parts.length - 1));
        return new MapPath(fs, joined);
    }

    @Override
    public Path resolve(String other) {
        if (other.startsWith("/")) return new MapPath(fs, other);
        return new MapPath(fs, toAbsoluteString() + "/" + other);
    }

    @Override
    public Path resolve(Path other) { return resolve(other.toString()); }

    @Override
    public Path normalize() {

        Deque<String> stack = new ArrayDeque<>();
        for (String p : parts) {
            if (p.equals(".")) continue;
            else if (p.equals("..")) { if (!stack.isEmpty()) stack.pop(); }
            else stack.push(p);
        }
        String[] normalized = stack.toArray(new String[0]);

        Collections.reverse(Arrays.asList(normalized));
        return new MapPath(fs, "/" + String.join("/", normalized));
    }
    public String toAbsoluteString() {
        if (absolute) {
            return "/" + String.join("/", parts);
        } else {
            return String.join("/", parts); // 相对路径不加前缀
        }
    }/*
    public String toAbsoluteString() {
        return "/" + String.join("/", parts);
    }*/

    @Override public String toString() { return toAbsoluteString(); }

    @Override
    public int compareTo(Path other) {
        return toAbsoluteString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MapPath)) return false;
        return toAbsoluteString().equals(((MapPath) o).toAbsoluteString())
            && fs == ((MapPath) o).fs;
    }

    @Override public int hashCode() { return toAbsoluteString().hashCode(); }


    @Override public Iterator<Path> iterator() {
        return Arrays.stream(parts)
            .map(p -> (Path) new MapPath(fs, p))
            .iterator();
    }
    @Override public int getNameCount() { return parts.length; }
    @Override public Path getName(int i) { return new MapPath(fs, parts[i]); }
    @Override public Path subpath(int begin, int end) {
        return new MapPath(fs, "/" + String.join("/",
            Arrays.copyOfRange(parts, begin, end)));
    }
    @Override public boolean startsWith(Path other) {
        return toAbsoluteString().startsWith(other.toString());
    }
    @Override public boolean endsWith(Path other) {
        return toAbsoluteString().endsWith(other.toString());
    }
    @Override public Path relativize(Path other) {
        throw new UnsupportedOperationException();
    }
    @Override
    public URI toUri() {
        try {
            return new URI("ocfs", null, toAbsoluteString(), null);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
    @Override public Path toAbsolutePath() {
        return absolute ? this : new MapPath(fs, "/" + String.join("/", parts));
    }
    @Override public Path toRealPath(LinkOption... opts) { return toAbsolutePath(); }
    @Override public WatchKey register(WatchService w, WatchEvent.Kind<?>... events) {
    	 return null;
    }
    @Override
    public Path getRoot() {
        return absolute ? new MapPath(fs, "/") : null;
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
    	 return null;
    }
    @Override
    public java.io.File toFile() {
        
        return new java.io.File(toAbsoluteString());
    }
}