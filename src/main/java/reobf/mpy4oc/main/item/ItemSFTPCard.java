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
        boolean isAlive;
        String name;
        int lastupdate;

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
            deliverKeys();
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

        @Override
        public void onConnect(Node node) {
            isAlive = true;
        }

        @Override
        public void onDisconnect(Node node) {
            if (node == _node) {
                isAlive = false;
                SSHDServer.killDead();
                name = null;
            }
        }

        @Override
        public void onMessage(Message message) {
        }

        @Override
        public void load(NBTTagCompound nbt) {
            Optional.ofNullable(nbt.getTag("node"))
                    .ifPresent(s -> { if (node() != null) node().load((NBTTagCompound) s); });

            if (nbt.hasKey("screenAddress")) screenAddress = nbt.getString("screenAddress");

        }

        @Override
        public void save(NBTTagCompound nbt) {
            NBTTagCompound t = new NBTTagCompound();
            Optional.ofNullable(node()).ifPresent(s -> s.save(t));
            nbt.setTag("node", t);

            if (screenAddress != null) nbt.setString("screenAddress", screenAddress);

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

        @Callback(doc = "stop():string -- Stop the server for this card.",
                direct = false, limit = 1)
        public Object[] stop(Context context, Arguments arguments) throws Exception {
            if (name == null) throw new RuntimeException("Nothing to stop!");
            SSHDServer.unregister(name);
            name = null;
            return new Object[] { "Stopped." };
        }

        @Callback(doc = "start(user:string, password:string[, address:string]):string -- Serve a "
                + "filesystem over SFTP, plus an SSH terminal mirroring the screen. Without an "
                + "address the computer's boot filesystem is used. The address may be given in "
                + "any position; the remaining two arguments are user and password, in that order. "
                + "Shuts down automatically when unloaded.",
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

            Node fsNode = node().network().node(addr);
            final Object hst = fsNode == null ? null : fsNode.host();
            if (!(hst instanceof FileSystem)) {
                throw new RuntimeException("'" + addr + "' is not a filesystem component!");
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
