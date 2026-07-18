package reobf.mpy4oc.util;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.function.Supplier;

import li.cil.oc.api.fs.FileSystem;
import li.cil.oc.api.fs.Handle;
import li.cil.oc.api.fs.Mode;
import net.minecraft.nbt.NBTTagCompound;

public class SyncedFileSystem implements FileSystem{
	public  SyncedFileSystem(FileSystem wrapped,Supplier<Boolean> isAlivePredicate) {this.wrapped=wrapped;
	this.isAlivePredicate=isAlivePredicate;}
	Supplier<Boolean> isAlivePredicate;
	FileSystem wrapped;
	
	@SuppressWarnings("unchecked")
	public static <E extends Throwable> void throwUnchecked(Throwable exception) throws E {
	    throw (E) exception;
	}
	
	@Override
	public void load(NBTTagCompound nbt) {
		
		
	}

	@Override
	public void save(NBTTagCompound nbt) {
		
		
	}
	
	@Override
	public boolean isReadOnly() {synchronized (wrapped) {
		
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.isReadOnly();}
	}

	@Override
	public long spaceTotal() {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.spaceTotal();}
	}

	@Override
	public long spaceUsed() {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.spaceUsed();}
	}

	@Override
	public boolean exists(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.exists(path);}
	}

	@Override
	public long size(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.size(path);}
	}

	@Override
	public boolean isDirectory(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.isDirectory(path);}
	}

	@Override
	public long lastModified(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return wrapped.lastModified(path);}
	}

	@Override
	public String[] list(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return  wrapped.list(path);}
	}

	@Override
	public boolean delete(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return  wrapped.delete(path);}
	}

	@Override
	public boolean makeDirectory(String path) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return  wrapped.makeDirectory(path);}
	}

	@Override
	public boolean rename(String from, String to) throws FileNotFoundException {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return  wrapped.rename(from,to);}
	}

	@Override
	public boolean setLastModified(String path, long time) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return  wrapped.setLastModified(path,time);}
	}

	@Override
	public int open(String path, Mode mode) throws FileNotFoundException {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		return  wrapped.open(path,mode);}
	}

	@Override
	public Handle getHandle(int handle) {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		Handle h = wrapped.getHandle(handle);
		return h == null ? null : new SyncedHandle(h);}
	}

	/**
	 * A Handle whose every operation takes the same lock the rest of this class
	 * takes -- the underlying FileSystem object.
	 *
	 * This matters for correctness, not just tidiness. OpenComputers' own
	 * filesystem component runs EVERY callback inside {@code fileSystem.synchronized}
	 * (see li.cil.oc.server.component.FileSystem), including the handle I/O in
	 * read/write/seek. Handing the raw Handle to the SFTP threads would let their
	 * read/write/seek/close run with no lock at all, interleaving with the game's
	 * locked I/O on the same file: torn reads, lost writes, and a shared seek
	 * position moving under whoever is mid-transfer. Locking here puts both sides
	 * on the same monitor, which is what makes concurrent access safe.
	 *
	 * The lock is taken per operation (not per transfer), so a large upload can
	 * still interleave with the game between chunks rather than stalling the
	 * server thread for the whole file.
	 */
	private final class SyncedHandle implements Handle {
		private final Handle inner;
		SyncedHandle(Handle inner) { this.inner = inner; }

		@Override
		public long position() {synchronized (wrapped) {
			if(!isAlivePredicate.get())throwUnchecked(new IOException());
			return inner.position();}
		}

		@Override
		public long length() {synchronized (wrapped) {
			if(!isAlivePredicate.get())throwUnchecked(new IOException());
			return inner.length();}
		}

		@Override
		public void close() {synchronized (wrapped) {
			// No liveness check: closing a handle after the machine went away is
			// exactly when we most want the underlying file released.
			inner.close();}
		}

		@Override
		public int read(byte[] into) throws IOException {synchronized (wrapped) {
			if(!isAlivePredicate.get())throw new IOException();
			return inner.read(into);}
		}

		@Override
		public long seek(long to) throws IOException {synchronized (wrapped) {
			if(!isAlivePredicate.get())throw new IOException();
			return inner.seek(to);}
		}

		@Override
		public void write(byte[] value) throws IOException {synchronized (wrapped) {
			if(!isAlivePredicate.get())throw new IOException();
			inner.write(value);}
		}
	}

	@Override
	public void close() {synchronized (wrapped) {
		if(!isAlivePredicate.get())throwUnchecked(new IOException());
		wrapped.close();}
		
	}

}
