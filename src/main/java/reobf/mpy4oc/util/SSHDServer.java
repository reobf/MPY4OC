package reobf.mpy4oc.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.AbstractSftpEventListenerAdapter;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;


import li.cil.oc.server.component.FileSystem;
import li.cil.oc.server.fs.FileSystem.RamFileSystem;
import reobf.mpy4oc.main.Config;

public class SSHDServer {
	public static int findAvailablePort(int start) throws IOException {
	    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
	        socket.setReuseAddress(true);
	        return socket.getLocalPort();
	    }
	}
	static {init();}
	static Map<String,SyncedFileSystem> users=new ConcurrentHashMap<>();
	static Map<String,String> userpasswd=new ConcurrentHashMap<String, String>();
	/** Per-user bridge to the in-game machine, for the interactive shell. */
	static Map<String,OcSshShell.SessionBridge> shells=new ConcurrentHashMap<String, OcSshShell.SessionBridge>();
	static SshServer sshd;
	
	public static void register(String user,String pwd,SyncedFileSystem fs) {
		register(user, pwd, fs, null);
	}

	/**
	 * Register a user. {@code bridge} may be null, in which case the account is
	 * SFTP-only; supply it and the same account also gets an interactive shell
	 * showing the machine's screen.
	 */
	public static void register(String user,String pwd,SyncedFileSystem fs,OcSshShell.SessionBridge bridge) {
		if(users.containsKey(user))throw new RuntimeException("Username occupied!");
		users.put(user, fs);
		userpasswd.put(user, pwd);
		if (bridge != null) shells.put(user, bridge);
	}
	public static void unregister(String user) {
		users.remove(user);
		userpasswd.remove(user);
		shells.remove(user);
		
	}
	
	static public void init() {
	if(Config.port==-1)return;
	if(sshd!=null)return;
	 {

	sshd = SshServer.setUpDefaultServer();
	sshd.setPort(Config.port);
	sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
	sshd.setPasswordAuthenticator((u, p2, s) -> {
		killDead();
		if(Objects.equals(p2, userpasswd.get(u))) {
			return users.containsKey(u);
		}return false;
		
	});
	sshd.setNioWorkers(4);
	
	/*li.cil.oc.api.fs.FileSystem ocFs =ofs.fileSystem();
	MapFileSystemProvider provider = new MapFileSystemProvider();
	java.nio.file.FileSystem nioFs = provider.newFileSystem(
	    URI.create("ocfs://myfs"),
	    Map.of("ocFs", ocFs)
	);*/

	sshd.setFileSystemFactory(new FileSystemFactory() {
		
		@Override
		public Path getUserHomeDir(SessionContext session) throws IOException {
			
			return null;
		}
		
		@Override
		public java.nio.file.FileSystem createFileSystem(SessionContext session) throws IOException {
		
			
			li.cil.oc.api.fs.FileSystem ocFs =users.get(session.getUsername());
			if(ocFs==null)throw new IOException("no fs");
			MapFileSystemProvider provider = new MapFileSystemProvider();
			java.nio.file.FileSystem nioFs = provider.newFileSystem(
			    URI.create("ocfs://myfs"),
			    Map.of("ocFs", ocFs)
			);
			return nioFs;
		}
	});
	
	sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()
			{
		
		
		
			}
			
			
			));

	// The interactive shell. Without this an SSH client can only do SFTP and any
	// attempt to open a terminal is refused — which is why remote control did not
	// work before. Each session attaches to the bridge registered for that user.
	sshd.setShellFactory(new org.apache.sshd.server.shell.ShellFactory() {
		@Override
		public org.apache.sshd.server.command.Command createShell(
				org.apache.sshd.server.channel.ChannelSession channel) throws IOException {
			String user = channel.getSession().getUsername();
			OcSshShell.SessionBridge bridge = shells.get(user);
			if (bridge == null) throw new IOException("no machine attached to this account");
			return new OcSshShell(bridge);
		}
	});
	try {
		sshd.start();
		// With Config.port == 0 the OS picks a free port, so read back what we
		// actually bound to — otherwise getPort() would just report 0.
		actualPort = sshd.getPort();
	} catch (IOException e) {
		
		e.printStackTrace();
	}}
	
}
	/** The port the server is really listening on (-1 if disabled/not started). */
	public static volatile int actualPort = -1;

	/** The port to report to in-game code. */
	public static int reportedPort() {
		if (Config.port == -1) return -1;
		return actualPort > 0 ? actualPort : Config.port;
	}
	public static void killDead() {
		var k=users.entrySet().iterator();
		while(k.hasNext()) {
			var n=k.next();
			if(n.getValue().isAlivePredicate.get()==false) {
				userpasswd.remove(n.getKey());
				k.remove();
				
			}
			
		}
		
	}
}
