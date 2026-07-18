package reobf.mpy4oc.util;
import li.cil.oc.api.fs.FileSystem;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MapFileSystem extends java.nio.file.FileSystem {

    final FileSystem ocFs;                  
    private final MapFileSystemProvider provider;
    private volatile boolean open = true;

    public MapFileSystem(MapFileSystemProvider provider, FileSystem ocFs) {
        this.provider = provider;
        this.ocFs = ocFs;
    }
 
    final Set<Closeable> openChannels = Collections.newSetFromMap(new ConcurrentHashMap<>());


    public void registerChannel(Closeable channel) {
        openChannels.add(channel);
    }

   
    public void unregisterChannel(Closeable channel) {
        openChannels.remove(channel);
    }
    @Override public FileSystemProvider provider()  { return provider; }
    @Override public boolean isOpen()               { return open; }
    @Override public boolean isReadOnly()           { return ocFs.isReadOnly(); }
    @Override public String getSeparator()          { return "/"; }

    @Override
    public Path getPath(String first, String... more) {
        String full = more.length == 0 ? first
                : first + "/" + String.join("/", more);
        return new MapPath(this, full);
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return java.util.List.of(getPath("/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return java.util.List.of(new FileStore() {
            @Override public String name()       { return "ocfs"; }
            @Override public String type()       { return "ocfs"; }
            @Override public boolean isReadOnly(){ return ocFs.isReadOnly(); }
            @Override public long getTotalSpace() { return ocFs.spaceTotal(); }
            @Override public long getUsableSpace(){
                return ocFs.spaceTotal() - ocFs.spaceUsed();
            }
            @Override public long getUnallocatedSpace() {
                return ocFs.spaceTotal() - ocFs.spaceUsed();
            }
            @Override public boolean supportsFileAttributeView(
                    Class<? extends FileAttributeView> t) { return false; }
            @Override public boolean supportsFileAttributeView(String name) { return false; }
            @Override public <V extends FileStoreAttributeView> V getFileStoreAttributeView(
                    Class<V> t) { return null; }
            @Override public Object getAttribute(String attr) { return null; }
        });
    }

    @Override
    public Set<String> supportedFileAttributeViews() { return Set.of("basic"); }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() throws IOException {
        open = false;
        ocFs.close();
        open = false;
      
        for (Closeable channel : openChannels) {
            try {
                //System.out.println("Force closing channel: " + channel);
                channel.close();
            } catch (IOException e) {
                // ignore
            }
        }
        openChannels.clear();
        ocFs.close();
    }
}