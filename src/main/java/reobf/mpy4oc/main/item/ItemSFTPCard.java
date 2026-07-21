package reobf.mpy4oc.main.item;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;

import li.cil.oc.api.Network;
import li.cil.oc.api.driver.item.HostAware;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.internal.TextBuffer;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.server.component.FileSystem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import reobf.mpy4oc.main.Config;
import reobf.mpy4oc.util.OcSshShell;
import reobf.mpy4oc.util.SSHDServer;
import reobf.mpy4oc.util.SyncedFileSystem;

/**
 * The SFTP / remote-console card.
 *
 * Exposes an in-game filesystem over SFTP and, on the same SSH connection, an
 * interactive terminal showing the computer's screen. Connect with something like
 * MobaXterm and you get the console on one side and the disk on the other.
 *
 * <h3>What changed versus the WASM4OC version</h3>
 * That version tried to be a GPU: a {@code VGPU extends GraphicsCard} that overrode
 * {@code set()} and mirrored the text into a private {@code char[][]}. That cannot
 * work. The GPU's {@code fill}, {@code copy} and {@code bitblt} never touched the
 * mirror, and OpenOS leans on all three constantly — every scroll is a copy, every
 * clear is a fill — so the mirror desynced from the real screen almost immediately.
 * It also printed the buffer to {@code System.out} rather than to any SSH client,
 * and the SSH server never registered a shell factory at all, so no terminal could
 * ever be opened.
 *
 * This version drops the fake GPU. The screen's {@link TextBuffer} is the
 * authoritative state and it exposes per-cell reads, so we simply read it — whatever
 * the GPU did and however it did it, we see the finished result. Keystrokes go back
 * in as {@code computer.signal} messages, which is what a real keyboard sends minus
 * the player permission check.
 */
public class ItemSFTPCard extends Item implements HostAware {

    public class APIEnv implements ManagedEnvironment, OcSshShell.SessionBridge {

        private Node _node = Network.newNode(this, Visibility.Network)
                .withComponent("sftp")
                .create();

        ItemStack stack;
        volatile boolean isAlive;
        volatile String name;
        volatile int lastupdate;

        /**
         * Keystrokes queued by SSH threads, drained on the server thread. The SSH
         * session runs on SSHD's own network threads; the OC network belongs to the
         * server thread, so nothing may cross directly.
         */
        private final Queue<Object[][]> pendingSignals = new ArrayDeque<Object[][]>();

        /**
         * Signals handed to the computer per tick. OC's own queue is capped at 256 and
         * drops silently past that, so we feed it steadily rather than in bursts.
         */
        private static final int KEYS_PER_TICK = 64;

        /** How long a writer waits for room before giving up on a keystroke. */
        private static final long ENQUEUE_TIMEOUT_MS = 2000;

        /** The computer this card is plugged into; the virtual screen needs it. */
        private final EnvironmentHost envHost;

        /**
         * Address of a real, in-world screen to mirror. Set by term(). Mutually
         * exclusive with the virtual screen.
         */
        private volatile String screenAddress;

        public APIEnv(ItemStack stack, EnvironmentHost host) {
            this.stack = stack;
            this.envHost = host;
        }

        // ---- ManagedEnvironment ------------------------------------------

        @Override
        public Node node() {
            return _node;
        }

        @Override
        public void update() {
            lastupdate = MinecraftServer.getServer().getTickCounter();
            // Reload-restore: we carry saved credentials but no live registration,
            // meaning we were serving when the chunk was saved. The resumed
            // program believes the service is up -- make it so. Retried every
            // tick until the filesystem is reachable again (the network may
            // still be assembling right after the reload).
            if (name == null && savedUser != null && isAlive) {
                tryRestore();
            }
            deliverKeys();
        }

        private void tryRestore() {
            String err;
            try {
                err = registerService(savedUser, savedPwd, savedAddr);
            } catch (RuntimeException occupied) {
                // The name is taken. If by a dead registration left over from an
                // unclean save, purging frees it; if by a LIVE service (another
                // card restored first, or a genuinely different machine), fall
                // back to user-1, user-2, ... -- the base name stays saved, so a
                // later restore tries the original first. The program can learn
                // the effective name via getCredentials().
                SSHDServer.killDead();
                try {
                    err = registerService(savedUser, savedPwd, savedAddr);
                } catch (RuntimeException stillHeld) {
                    for (int i = 1; i <= 64; i++) {
                        try {
                            if (registerService(savedUser + "-" + i, savedPwd, savedAddr) == null) return;
                        } catch (RuntimeException taken) {
                            // next suffix
                        }
                    }
                    return;   // absurdly crowded; try again next tick
                }
            }
            // err != null means the filesystem is not reachable yet (the network
            // is still assembling after the reload) -- retried next tick, and
            // deliberately NOT suffixed: that would rename on a transient state.
        }

        /**
         * Resolve the filesystem at {@code addr} and register the account
         * serving it (shared by start() and the reload-restore path).
         * Returns null on success, or a reason string when the filesystem is
         * not (yet) reachable. Throws if the username is taken.
         */
        private String registerService(String user, String password, String addr) {
            Node fsNode = node().network() == null ? null : node().network().node(addr);
            final Object hst = fsNode == null ? null : fsNode.host();
            if (!(hst instanceof FileSystem)) {
                return "'" + addr + "' is not a filesystem component!";
            }
            FileSystem v = (FileSystem) hst;

            SyncedFileSystem fs = new SyncedFileSystem(v.fileSystem(), () -> {
                try {
                    return isAlive
                            && ((li.cil.oc.api.network.Environment) hst).node().network() == node().network()
                            && Math.abs(lastupdate - MinecraftServer.getServer().getTickCounter()) < 20;
                } catch (Exception w) {
                    return false;
                }
            });

            // Registering the bridge alongside the filesystem is what gives this
            // account an interactive shell as well as SFTP.
            SSHDServer.register(user, password, fs, this);
            name = user;
            return null;
        }

        /**
         * Hand queued keystrokes to the computer, a bounded number per tick.
         *
         * The rate limit matters. OpenComputers' own signal queue is capped (256 by
         * default) and silently drops anything past it, so emptying our whole queue
         * into it in one go — which a paste or a leaned-on key easily produces —
         * would just move the overflow one step downstream and lose keys there
         * instead. Feeding it steadily keeps every keystroke, just spread over a few
         * ticks.
         *
         * Groups are never split. A Ctrl+C arrives as four signals (lcontrol down,
         * c down, c up, lcontrol up); stopping halfway would leave OpenOS believing
         * control is still held, and every subsequent keystroke would be read as a
         * control chord — a terminal that appears to lock up.
         */
        /**
         * The node key signals must originate from, so their source address is one
         * OpenOS accepts (it compares against the screen's getKeyboards(); see
         * lib/core/cursor.lua). In both modes that is our virtual keyboard: for the
         * virtual screen it is the keyboard the screen reports, and for a mirrored
         * real screen we connect the same virtual keyboard into the network so it
         * shows up as an available keyboard too.
         */
        /**
         * The node key signals must originate from, so their source address is one
         * OpenOS accepts (it compares against tty.keyboard(); see lib/core/cursor.lua).
         * Virtual-screen mode uses our own virtual keyboard. Real-screen mode sends as
         * the screen's own physical keyboard, so that keyboard keeps working and we
         * are not competing for the single slot tty.keyboard() picks.
         */
        private Node keySignalSource() {
            // Send AS the physical keyboard already on the mirrored
            // screen. We do not add a keyboard of our own -- that would fight the real
            // one for the single slot tty.keyboard() picks, and knock out whichever
            // lost. Instead we find the screen's existing keyboard and send from its
            // node, so the signal's source address equals that keyboard's address.
            // OpenOS cannot tell our keystroke from one typed at the block, and the
            // physical keyboard keeps working because it IS that same node.
            Node kb = realScreenKeyboard();
            return kb != null ? kb : null;
        }

        /**
         * The node of the keyboard the mirrored screen reports first -- the same one
         * tty.keyboard() will settle on, so our keystrokes land on the keyboard the
         * terminal is actually listening to.
         *
         * We resolve it exactly as OpenOS does: ask the screen for getKeyboards() and
         * take the first address. Cached, because that call is documented as slow.
         */
        private Node realScreenKeyboard() {
            if (screenAddress == null || _node.network() == null) return null;
            try {
                if (cachedKbAddress == null) {
                    Node screenNode = _node.network().node(screenAddress);
                    if (screenNode == null) return null;
                    for (Node nb : screenNode.network().nodes(screenNode)) {
                        if (nb.host() instanceof li.cil.oc.server.component.Keyboard) {
                            cachedKbAddress = nb.address();
                            break;
                        }
                    }
                }
                if (cachedKbAddress == null) return null;
                return _node.network().node(cachedKbAddress);
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** Resolved keyboard address for the mirrored screen; cleared when term() changes. */
        private volatile String cachedKbAddress;

        private void deliverKeys() {
            synchronized (pendingSignals) {
                if (pendingSignals.isEmpty()) return;
            }
            // Resolve the target keyboard once. If it is not available yet (the
            // network is still settling right after start()/term()), do NOT drain the
            // queue -- leave the keystrokes queued and try again next tick. Draining
            // them with nowhere to send is exactly what dropped the first characters
            // of a line, turning "hello" into "lo".
            Node source = keySignalSource();
            if (source == null) return;

            int budget = KEYS_PER_TICK;
            while (budget > 0) {
                Object[][] group;
                synchronized (pendingSignals) {
                    if (pendingSignals.isEmpty()) return;
                    group = pendingSignals.peek();
                    // Never start a group we cannot finish this tick.
                    if (group.length > budget) return;
                    pendingSignals.poll();
                    pendingSignals.notifyAll();          // a writer may be blocked
                }
                for (Object[] sig : group) {
                    try {
                        source.sendToReachable("computer.signal", sig);
                    } catch (Throwable ignored) {
                    }
                }
                budget -= group.length;
            }
        }

        @Override
        public boolean canUpdate() {
            return true;
        }

        /**
         * Service credentials that survive a save/load cycle. The MPY snapshot
         * resumes the Python program mid-execution -- it believes start() is
         * still in effect -- so on UNLOAD we keep these and silently restore the
         * registration after reload. On SHUTDOWN they are cleared: a rebooted
         * program re-runs init.py and calls start() itself.
         */
        private volatile String savedUser, savedPwd, savedAddr;

        @Override
        public void onConnect(Node node) {
            isAlive = true;
        }

        @Override
        public void onDisconnect(Node node) {
            // Unload / card removed: cut the account and every live connection
            // NOW, but keep the saved credentials -- a later reload restores the
            // service to match the resumed program's belief that it is running.
            if (node == _node) {
                isAlive = false;
                if (name != null) {
                    SSHDServer.unregister(name);   // also kills live sessions
                    name = null;
                }
                SSHDServer.killDead();             // sweep anything else stale
            }
        }

        @Override
        public void onMessage(Message message) {
            // The computer powering off takes the SFTP service down for good:
            // kill the account and its connections, and forget the credentials
            // so power-on does NOT auto-restore (the rebooted program calls
            // start() itself). The broadcast reaches every card on the network,
            // so make sure it is OUR computer that stopped.
            if ("computer.stopped".equals(message.name())) {
                Object h = message.source().host();
                if (h instanceof li.cil.oc.api.machine.Machine
                        && ((li.cil.oc.api.machine.Machine) h).host() == envHost) {
                    if (name != null) {
                        SSHDServer.unregister(name);
                        name = null;
                    }
                    savedUser = savedPwd = savedAddr = null;
                }
            }
        }

        @Override
        public void load(NBTTagCompound nbt) {
            Optional.ofNullable(nbt.getTag("node"))
                    .ifPresent(s -> { if (node() != null) node().load((NBTTagCompound) s); });

            if (nbt.hasKey("screenAddress")) screenAddress = nbt.getString("screenAddress");

            // Saved-while-running: restore the service once we are back on a
            // network (see tryRestore, driven from update()).
            if (nbt.hasKey("svcUser")) {
                savedUser = nbt.getString("svcUser");
                savedPwd = nbt.getString("svcPwd");
                savedAddr = nbt.getString("svcAddr");
            }
        }

        @Override
        public void save(NBTTagCompound nbt) {
            NBTTagCompound t = new NBTTagCompound();
            Optional.ofNullable(node()).ifPresent(s -> s.save(t));
            nbt.setTag("node", t);

            if (screenAddress != null) nbt.setString("screenAddress", screenAddress);

            // Persist the running service so an unload/reload cycle restores it.
            // (Cleared on computer shutdown and stop(), so a saved-while-off card
            // carries nothing.) The password is stored as plain NBT, same trust
            // model as the EEPROM data it sits next to.
            if (savedUser != null && savedPwd != null && savedAddr != null) {
                nbt.setString("svcUser", savedUser);
                nbt.setString("svcPwd", savedPwd);
                nbt.setString("svcAddr", savedAddr);
            }
        }

        // ---- SessionBridge (called from SSH threads) ----------------------

        /**
         * The screen the SSH session mirrors. Either a real in-world screen chosen
         * chosen with term().
         *
         * Note we never reconstruct the picture: in both cases we are reading the
         * authoritative TextBuffer that the GPU already drew into with its own
         * set/fill/copy/bitblt. There is no mirror to fall out of sync.
         */
        @Override
        public TextBuffer screen() {
            if (screenAddress == null) return null;
            try {
                // Resolved fresh each poll: the screen can be broken or unloaded, and
                // caching the instance across that would be a leak.
                Node n = _node.network().node(screenAddress);
                if (n != null && n.host() instanceof TextBuffer) {
                    return (TextBuffer) n.host();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        @Override
        public void keyStroke(Object[][] signals) {
            enqueue(signals);
        }

        @Override
        public void clipboard(String text) {
            enqueue(new Object[][] { { "clipboard", text } });
        }

        /** SSH terminal is now cols x rows. Called on an SSHD IO thread.
         *  A real in-world screen has whatever size its blocks give it -- we cannot
         *  resize it from here, so this only tells the shell "no". */
        @Override
        public boolean resize(int cols, int rows) {
            return false;
        }

        /**
         * Queue one indivisible group of signals.
         *
         * When the queue is full we make the SSH thread wait instead of dropping the
         * keystroke. Dropping is what a leaned-on key or a big paste used to do, and
         * it loses input silently -- the user sees characters simply missing from
         * what they typed. Blocking the reader applies backpressure to the SSH
         * connection instead, which is exactly where it belongs: TCP will stall the
         * client, and nothing is lost. We only give up if the game side has clearly
         * stopped draining (unloaded, crashed), and then we end the session.
         */
        private void enqueue(Object[][] group) {
            long deadline = System.currentTimeMillis() + ENQUEUE_TIMEOUT_MS;
            synchronized (pendingSignals) {
                while (pendingSignals.size() >= MAX_PENDING_GROUPS) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0 || !isAlive) return;   // game side is not draining
                    try {
                        pendingSignals.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                pendingSignals.add(group);
            }
        }

        private static final int MAX_PENDING_GROUPS = 64;

        @Override
        public boolean alive() {
            try {
                return isAlive
                        && Math.abs(lastupdate - MinecraftServer.getServer().getTickCounter()) < 40;
            } catch (Throwable t) {
                return false;
            }
        }

        // ---- callbacks ----------------------------------------------------

        @Callback(doc = "getPort():number -- The SFTP/SSH port. -1 means an admin disabled it.",
                direct = true, limit = 1)
        public Object[] getPort(Context context, Arguments arguments) throws Exception {
            return new Object[] { Double.valueOf(SSHDServer.reportedPort()) };
        }

        @Callback(doc = "getCredentials():string, string -- The username and password the card "
                + "is currently serving with, or nil if the service is stopped. The username can "
                + "differ from what start() was given: a collision during the automatic "
                + "reload-restore is resolved by appending -1, -2, ...",
                direct = true, limit = 8)
        public Object[] getCredentials(Context context, Arguments arguments) throws Exception {
            String u = name;                  // volatile read; may race a shutdown, so
            String p = savedPwd;              // snapshot both before the null check
            if (u == null) return new Object[] { null };
            return new Object[] { u, p };
        }

        @Callback(doc = "term():string -- Mirror the real, in-world screen the GPU is "
                + "currently bound to onto the SSH terminal. What you see over SSH is exactly "
                + "what is on that screen.",
                direct = false, limit = 1)
        public Object[] term(Context context, Arguments arguments) throws Exception {
            String addr = boundScreenOf(context);
            if (addr == null) {
                throw new RuntimeException("No GPU with a bound screen found. "
                        + "Place a screen and bind the GPU to it first.");
            }

            Node n = _node.network().node(addr);
            if (n == null) throw new RuntimeException("No such component: " + addr);
            if (!(n.host() instanceof TextBuffer)) {
                throw new RuntimeException("'" + addr + "' is not a screen.");
            }

            screenAddress = addr;
            cachedKbAddress = null;   // re-resolve the keyboard for the new screen

            // A heads-up rather than a silent half-working state: mirroring shows the
            // screen regardless, but typing needs a keyboard attached to it.
            boolean hasKb = realScreenKeyboard() != null;
            return new Object[] { hasKb
                    ? "Mirroring screen " + addr
                    : "Mirroring screen " + addr + " (read-only: no keyboard on this screen)" };
        }

        /**
         * The address of the screen the GPU is currently bound to, or null.
         *
         * We ask the GPU itself rather than guessing "the first screen on the network":
         * with more than one screen present those are different answers, and the one
         * the user means is the one they are actually looking at.
         */
        private String boundScreenOf(Context context) {
            if (!(context instanceof li.cil.oc.api.machine.Machine)) return null;
            li.cil.oc.api.machine.Machine m = (li.cil.oc.api.machine.Machine) context;
            for (java.util.Map.Entry<String, String> e : m.components().entrySet()) {
                if (!"gpu".equals(e.getValue())) continue;
                try {
                    Object[] r = m.invoke(e.getKey(), "getScreen", new Object[0]);
                    if (r != null && r.length > 0 && r[0] instanceof String) {
                        return (String) r[0];
                    }
                } catch (Throwable ignored) {
                    // this GPU has no screen bound; try the next one
                }
            }
            return null;
        }

        @Callback(doc = "stop():string -- Stop the server for this card and disconnect its clients.",
                direct = false, limit = 1)
        public Object[] stop(Context context, Arguments arguments) throws Exception {
            if (name == null) throw new RuntimeException("Nothing to stop!");
            SSHDServer.unregister(name);   // also kills live sessions
            name = null;
            savedUser = savedPwd = savedAddr = null;   // an explicit stop() is not restored
            return new Object[] { "Stopped." };
        }

        @Callback(doc = "start(user:string, password:string[, address:string]):string -- Serve a "
                + "filesystem over SFTP, plus an SSH terminal mirroring the screen. Without an "
                + "address the computer's boot filesystem is used. The address may be given in "
                + "any position; the remaining two arguments are user and password, in that order. "
                + "Powering the computer off stops the service and disconnects clients; an "
                + "unload/reload cycle restores it automatically.",
                direct = false, limit = 1)
        public Object[] start(Context context, Arguments arguments) throws Exception {
            if (Config.port == -1) throw new RuntimeException("SFTP disabled by an admin!");
            if (name != null) throw new RuntimeException("Already started!");

            // Sort the arguments out ourselves rather than fixing their order: the
            // address is recognisable (it resolves to a filesystem component), so
            // whichever argument is one IS the address and the other two are the
            // credentials. That makes start(user, pass), start(addr, user, pass) and
            // start(user, pass, addr) all mean the obvious thing.
            String addr = null;
            java.util.List<String> rest = new java.util.ArrayList<String>();
            for (int i = 0; i < arguments.count(); i++) {
                String a;
                try {
                    a = arguments.checkString(i);
                } catch (Throwable t) {
                    throw new RuntimeException("start() takes strings: user, password[, address]");
                }
                if (addr == null && isFilesystemAddress(a)) addr = a;
                else rest.add(a);
            }
            if (rest.size() != 2) {
                throw new RuntimeException("start() needs a user and a password"
                        + (addr == null ? "" : " besides the address")
                        + "; got " + rest.size() + " non-address argument(s).");
            }
            String user = rest.get(0);
            String password = rest.get(1);

            if (addr == null) {
                addr = bootFilesystemAddress(context);
                if (addr == null) {
                    throw new RuntimeException("No boot filesystem found; "
                            + "pass a filesystem address explicitly.");
                }
            }

            String err = registerService(user, password, addr);
            if (err != null) throw new RuntimeException(err);

            // Remember the service so an unload/reload cycle restores it (the
            // snapshot-resumed program believes it is still running). Cleared on
            // computer shutdown and stop().
            savedUser = user;
            savedPwd = password;
            savedAddr = addr;
            return new Object[] { "Started, serving " + addr };
        }

        /** True if {@code a} is the address of a filesystem component we can reach. */
        private boolean isFilesystemAddress(String a) {
            try {
                if (a == null || node().network() == null) return false;
                Node n = node().network().node(a);
                return n != null && n.host() instanceof FileSystem;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * The filesystem the computer boots from, resolved the same way the BIOS does:
         * the EEPROM's data area holds the boot address (that is what setBootAddress
         * writes). If it is unset or stale, fall back to the first filesystem on the
         * network so a machine with a single disk just works.
         */
        private String bootFilesystemAddress(Context context) {
            // 1. The EEPROM's boot address, if it is set and still valid. This is
            //    authoritative when the player pinned a boot disk.
            try {
                for (Node n : node().network().nodes(node())) {
                    if (!(n instanceof li.cil.oc.api.network.Component)) continue;
                    li.cil.oc.api.network.Component c = (li.cil.oc.api.network.Component) n;
                    if (!"eeprom".equals(c.name())) continue;
                    Object[] r = c.invoke("getData", context, new Object[0]);
                    if (r == null || r.length == 0 || r[0] == null) break;
                    String s = r[0] instanceof byte[]
                            ? new String((byte[]) r[0], "UTF-8")
                            : String.valueOf(r[0]);
                    if (!s.isEmpty() && isFilesystemAddress(s)) return s;
                    break;
                }
            } catch (Throwable ignored) {
            }

            // 2. No pinned boot address -- which is the normal case, since the MPYOS
            //    BIOS never writes one: it just scans. Mirror exactly what the BIOS
            //    does (see bios.py _find_boot_fs) and take the first filesystem that
            //    actually carries /init.py. Picking "any filesystem" here is what made
            //    this land on the machine's ramfs/tmpfs, which is never what you want
            //    to serve over SFTP.
            String tmp = tmpAddressOf(context);
            try {
                for (Node n : node().network().nodes(node())) {
                    if (!(n.host() instanceof FileSystem)) continue;
                    if (n.address() != null && n.address().equals(tmp)) continue;   // skip ramfs
                    if (hasInitPy((FileSystem) n.host())) return n.address();
                }
            } catch (Throwable ignored) {
            }

            // 3. Nothing bootable: fall back to any real disk, still avoiding the
            //    ramfs, so a machine with one blank disk is at least usable.
            try {
                for (Node n : node().network().nodes(node())) {
                    if (!(n.host() instanceof FileSystem)) continue;
                    if (n.address() != null && n.address().equals(tmp)) continue;
                    return n.address();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** The machine's temporary (ram) filesystem address, or null if unknown. */
        private String tmpAddressOf(Context context) {
            try {
                if (context instanceof li.cil.oc.api.machine.Machine) {
                    return ((li.cil.oc.api.machine.Machine) context).tmpAddress();
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        /** True if this filesystem carries /init.py, i.e. it is an MPYOS boot disk. */
        private boolean hasInitPy(FileSystem host) {
            try {
                li.cil.oc.api.fs.FileSystem raw = host.fileSystem();
                if (raw == null) return false;
                // Same monitor OC's own component uses for every filesystem call.
                synchronized (raw) {
                    return raw.exists("/init.py");
                }
            } catch (Throwable t) {
                return false;
            }
        }
    }

    // ---- item driver ------------------------------------------------------

    @Override
    public boolean worksWith(ItemStack stack) {
        return stack.getItem() instanceof ItemSFTPCard;
    }

    @Override
    public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
        return new APIEnv(stack, host);
    }

    @Override
    public String slot(ItemStack stack) {
        return Slot.Card;
    }

    @Override
    public int tier(ItemStack stack) {
        return 0;
    }

    @Override
    public NBTTagCompound dataTag(ItemStack stack) {
        return null;
    }

    @Override
    public boolean worksWith(ItemStack stack, Class<? extends EnvironmentHost> host) {
        return stack.getItem() instanceof ItemSFTPCard;
    }
}
