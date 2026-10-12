package reobf.mpy4oc.tk;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import li.cil.oc.api.Network;
import li.cil.oc.api.detail.Builder;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Analyzable;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.TileEntityEnvironment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import reobf.mpy4oc.tk.net.TkNetwork;
import reobf.mpy4oc.tk.net.TkPackets;

/** Server-owned display state. Mutating callbacks run on the server thread. */
public class TileTkDisplay extends TileEntityEnvironment implements Analyzable, IGuiHolder<PosGuiData> {
    public static final String COMPONENT_NAME = "tk_display";
    /** The resolution dialog's small preset (the canvas starts at its group's largest, see fitResolution). */
    public static final int PRESET_WIDTH = 320;
    public static final int PRESET_HEIGHT = 240;
    public static final int MIN_SIZE = 64;
    /** Hard canvas limit: the largest group (8x8 blocks) at 320 pixels per block. */
    public static final int MAX_SIZE = TkGroup.MAX_BLOCKS * TkGroup.PIXELS_PER_BLOCK;

    private TkScene scene = TkScene.defaults();
    private long sceneRevision = 1;
    private final TkViewers<EntityPlayerMP> viewers = new TkViewers<>();
    private TkTree widgets = new TkTree(this::markDirty);
    private final TkApplication application = new TkApplication(this);

    /*
     * Multiblock group. Every member stores the group rectangle in the master's frame (see
     * TkGroup); slaves also store the master's position. A display that was never merged is
     * its own master of a 1x1 group. Slaves carry no UI state of their own: their component
     * is hidden and right-clicks go to the master.
     */
    private boolean slave;
    private int masterX, masterY, masterZ;
    private TkGroup group = TkGroup.SINGLE;

    public TileTkDisplay() {
        Builder.NodeBuilder builder = Network.newNode(this, Visibility.Network);
        if (builder != null) node = builder.withComponent(COMPONENT_NAME, Visibility.Network).create();
        // OC's builder creates no node on clients; all network state stays on the server.
    }

    @Override
    public void updateEntity() {
        if (worldObj != null && !worldObj.isRemote && node != null) {
            super.updateEntity();
            claimImageStore();
            // A slave whose master changed while it was unloaded (split, broken, replaced)
            // falls back to an independent display. An unloaded master is not judged.
            if (Math.floorMod(worldObj.getTotalWorldTime() + xCoord * 7L + zCoord * 13L, 40L) == 0) {
                if (slave && !masterConsistent()) leaveGroup();
                // A master whose loaded members no longer follow it (one was broken while
                // this chunk was unloaded) splits; the other members notice in turn.
                else if (!slave && !group.single() && !membersConsistent()) TkGroups.split(worldObj, this);
            }
            // An exception escaping a tile entity tick crashes the server (or, with
            // Forge's removeErroringTileEntities, deletes the block). Contain it.
            try { emitCommands(""); }
            catch (RuntimeException error) { reobf.mpy4oc.main.MyMod.LOG.warn("Tk display tick failed", error); }
        }
    }

    @Override
    public void onMessage(li.cil.oc.api.network.Message message) {
        super.onMessage(message);
        // OC's machine announces these to its network (file systems and GPUs react to them
        // too): a program that held this display died with its computer, or a restart left
        // a fresh VM that no longer knows the claim. Either way the display is free again.
        String name = message.name();
        if (("computer.stopped".equals(name) || "computer.started".equals(name)) && message.source() != null)
            application.ownerStopped(message.source().address());
    }

    @Override
    public void onConnect(Node connected) {
        super.onConnect(connected);
        // Joining may assign or remap our address even in an otherwise clean
        // chunk. Persist it without waiting for a canvas setting to change.
        if (node != null && connected == node) { applyVisibility(); markDirty(); }
    }

    // ---- image pixels, stored beside the world (see TkImageStorage) ----------------------

    private String imageStore = "";
    /** The directory this tile has claimed in TkImageStores (server side). */
    private String claimedStore = "";

    private java.io.File imageDirectory() {
        java.io.File root = imageStore.isEmpty() ? null : net.minecraftforge.common.DimensionManager.getCurrentSaveRootDirectory();
        return root == null ? null : new java.io.File(root, "mpy4oc/tk-images/" + imageStore);
    }

    /** This display's image directory; a no-op store when it has none or no world is running. */
    private TkImageStorage imageStorage() {
        java.io.File dir = imageDirectory();
        if (dir != null) return new TkImageStorage.Directory(dir);
        return new TkImageStorage() {
            @Override public byte[] read(int id) { return null; }
            @Override public void write(int id, byte[] packed) throws java.io.IOException {
                throw new java.io.IOException("no world directory to store Tk images in");
            }
            @Override public void retain(java.util.Set<Integer> ids) {}
        };
    }

    /**
     * Makes sure no other loaded display writes this display's image directory: a copy
     * (same directory name in its NBT, somewhere else) forks to a directory of its own and
     * writes all its images there on the next save. Server thread, once the world is known.
     */
    private void claimImageStore() {
        if (imageStore.isEmpty() || imageStore.equals(claimedStore) || worldObj == null || worldObj.isRemote) return;
        if (!claimedStore.isEmpty()) TkImageStores.release(claimedStore, this);
        if (!TkImageStores.claim(imageStore, this)) {
            imageStore = java.util.UUID.randomUUID().toString();
            for (TkImage image : widgets.images()) image.savedVersion = -1;
            TkImageStores.claim(imageStore, this);
            markDirty();
        }
        claimedStore = imageStore;
    }

    private void releaseImageStore() {
        if (!claimedStore.isEmpty()) TkImageStores.release(claimedStore, this);
        claimedStore = "";
    }

    /** The block was broken: its images go with it, a little later (a move may still load them; see TkImageStores). */
    void deleteImageFiles() {
        java.io.File dir = imageDirectory();
        releaseImageStore();
        if (dir != null) TkImageStores.doom(imageStore, dir, reobf.mpy4oc.tk.net.TkNetwork.currentTick());
    }

    // ---- multiblock group ---------------------------------------------------------------

    public TkGroup group() { return group; }
    public boolean isSlave() { return slave; }

    /** Whether the computer at an address is reachable and running (tests replace it). */
    public java.util.function.Predicate<String> ownerAlive = this::machineRunning;
    boolean ownerRunning(String address) { return ownerAlive.test(address); }
    /** Which run of the machine at address is live now (see MpyArchitecture.bootId); "" if unknown. */
    public java.util.function.Function<String, String> ownerBoot = this::machineBoot;
    String ownerBootOf(String address) { return ownerBoot.apply(address); }
    private String machineBoot(String address) {
        Node found = node == null || node.network() == null ? null : node.network().node(address);
        li.cil.oc.api.network.Environment host = found == null ? null : found.host();
        if (!(host instanceof li.cil.oc.api.machine.Machine)) return "";
        li.cil.oc.api.machine.Architecture architecture = ((li.cil.oc.api.machine.Machine) host).architecture();
        return architecture instanceof reobf.mpy4oc.main.arch.MpyArchitecture
                ? ((reobf.mpy4oc.main.arch.MpyArchitecture) architecture).bootId() : "";
    }
    private boolean machineRunning(String address) {
        Node found = node == null || node.network() == null ? null : node.network().node(address);
        if (found == null) return false;   // removed, unloaded or disconnected: the claim is stale
        li.cil.oc.api.network.Environment host = found.host();
        return !(host instanceof li.cil.oc.api.machine.Machine) || ((li.cil.oc.api.machine.Machine) host).isRunning();
    }
    /** The front face (block metadata 2..5). */
    public int face() { return TkSurfacePolicy.face(worldObj == null ? 2 : getBlockMetadata()); }
    public int[] position() { return new int[] {xCoord, yCoord, zCoord}; }
    public int[] masterPosition() { return slave ? new int[] {masterX, masterY, masterZ} : position(); }

    /**
     * The display running this group: this tile unless it is a slave, else the loaded master
     * that still claims it; null when the master is unloaded or no longer claims this block.
     */
    public TileTkDisplay master() {
        if (!slave) return this;
        if (worldObj == null || !worldObj.blockExists(masterX, masterY, masterZ)) return null;
        net.minecraft.tileentity.TileEntity t = worldObj.getTileEntity(masterX, masterY, masterZ);
        if (!(t instanceof TileTkDisplay)) return null;
        TileTkDisplay m = (TileTkDisplay) t;
        if (m.slave || !m.group.equals(group) || m.face() != face()) return null;
        int[] uv = TkGroup.uv(m.position(), face(), position());
        return uv != null && group.contains(uv[0], uv[1]) ? m : null;
    }

    /** False only when the master is loaded and no longer accounts for this slave. */
    private boolean masterConsistent() {
        if (worldObj == null || !worldObj.blockExists(masterX, masterY, masterZ)) return true;
        return master() != null;
    }

    /** False when a loaded member of this master's group is not one of its slaves. */
    private boolean membersConsistent() {
        for (int[] p : group.members(position(), face())) {
            if ((p[0] == xCoord && p[1] == yCoord && p[2] == zCoord) || !worldObj.blockExists(p[0], p[1], p[2])) continue;
            net.minecraft.tileentity.TileEntity t = worldObj.getTileEntity(p[0], p[1], p[2]);
            if (!(t instanceof TileTkDisplay) || ((TileTkDisplay) t).master() != this) return false;
        }
        return true;
    }

    /** Canvas limit for this display's group: 320 pixels per block on each axis. */
    public int maxWidth() { return group.maxWidth(); }
    public int maxHeight() { return group.maxHeight(); }

    /** Squared distance from {@code player} to the nearest block of this display's group. */
    public double distanceSq(Entity player) {
        double best = player.getDistanceSq(xCoord + .5, yCoord + .5, zCoord + .5);
        if (group.single() || worldObj == null) return best;
        for (int[] p : group.members(masterPosition(), face()))
            best = Math.min(best, player.getDistanceSq(p[0] + .5, p[1] + .5, p[2] + .5));
        return best;
    }

    /**
     * Become a member of a group run by the master at {@code master}. A display that turns
     * into a slave gives up its own program (released, GUIs closed) and resets its canvas:
     * it shows the master's picture now, and starts fresh if the group is split again.
     */
    void joinGroup(int[] master, TkGroup newGroup, boolean asSlave) {
        if (asSlave && !slave) evict();
        slave = asSlave;
        masterX = master[0]; masterY = master[1]; masterZ = master[2];
        group = newGroup;
        if (!slave) fitResolution();
        groupChanged();
    }

    /** Become an independent 1x1 display again (keeping any program a master runs). */
    void leaveGroup() {
        slave = false;
        group = TkGroup.SINGLE;
        fitResolution();
        groupChanged();
    }

    private void groupChanged() {
        applyVisibility();
        markDirty();
        if (worldObj != null && !worldObj.isRemote) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** Slaves are hidden from component.list (and so from Tk()), yet still link the network. */
    private void applyVisibility() {
        if (!(node instanceof li.cil.oc.api.network.Component)) return;
        li.cil.oc.api.network.Component component = (li.cil.oc.api.network.Component) node;
        Visibility wanted = slave ? Visibility.None : Visibility.Network;
        // Changing visibility makes OC signal component_added/removed; only change it.
        if (component.visibility() != wanted) component.setVisibility(wanted);
    }

    /** Drop this display's program and canvas: it is being absorbed into another group. */
    private void evict() {
        closeViewers();
        application.evict();
        scene = TkScene.defaults(maxWidth(), maxHeight());
        sceneRevision++;
        markDirty();
    }

    /** The canvas is its group's largest whenever the group changes (setResolution can lower it until then). */
    private void fitResolution() {
        if (scene.width != maxWidth() || scene.height != maxHeight())
            updateScene(new TkScene(maxWidth(), maxHeight(), scene.background, scene.surfaceVisible, scene.display, scene.images));
    }

    private void writeGroup(NBTTagCompound tag) {
        tag.setBoolean("slave", slave);
        tag.setIntArray("master", new int[] {masterX, masterY, masterZ});
        tag.setIntArray("group", new int[] {group.left, group.top, group.width, group.height});
    }

    private void readGroup(NBTTagCompound tag) {
        TkGroup g = TkGroup.SINGLE;
        int[] r = tag.getIntArray("group"), m = tag.getIntArray("master");
        try { if (r.length == 4) g = new TkGroup(r[0], r[1], r[2], r[3]); }
        catch (IllegalArgumentException invalid) { g = TkGroup.SINGLE; }
        group = g;
        slave = tag.getBoolean("slave") && m.length == 3 && !g.single();
        if (m.length == 3) { masterX = m[0]; masterY = m[1]; masterZ = m[2]; }
    }

    /** Group data for the client (surface rendering, wrench overlay, render bounds). */
    @Override
    public net.minecraft.network.Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeGroup(tag);
        return new net.minecraft.network.play.server.S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(net.minecraft.network.NetworkManager net, net.minecraft.network.play.server.S35PacketUpdateTileEntity packet) {
        readGroup(packet.func_148857_g());
    }

    /** A master draws its picture across the whole group, so its render bounds cover it. */
    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public net.minecraft.util.AxisAlignedBB getRenderBoundingBox() {
        if (slave || group.single() || worldObj == null) return super.getRenderBoundingBox();
        int minX = xCoord, minY = yCoord, minZ = zCoord, maxX = xCoord, maxY = yCoord, maxZ = zCoord;
        for (int[] p : group.members(position(), face())) {
            minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]); minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]); maxZ = Math.max(maxZ, p[2]);
        }
        return net.minecraft.util.AxisAlignedBB.getBoundingBox(minX - 1, minY - 1, minZ - 1, maxX + 2, maxY + 2, maxZ + 2);
    }

    @Override
    public void validate() {
        super.validate();
        addedToNetwork = false;
    }

    @Override
    public void onChunkUnload() {
        closeViewers();
        super.onChunkUnload();
        addedToNetwork = false;
        releaseImageStore();
    }

    @Override
    public void invalidate() {
        closeViewers();
        super.invalidate();
        releaseImageStore();
    }

    @Override
    public Node[] onAnalyze(EntityPlayer player, int side, float hitX, float hitY, float hitZ) {
        return node == null ? new Node[0] : new Node[] {node};
    }

    @Callback(direct = true, doc = "function():number, number -- Get the canvas width and height in pixels.")
    public synchronized Object[] getResolution(Context context, Arguments args) {
        return new Object[] {scene.width, scene.height};
    }

    @Callback(doc = "function(width:number, height:number):boolean -- Set the canvas size: 64 pixels up to getMaxResolution() per axis.")
    public synchronized Object[] setResolution(Context context, Arguments args) {
        setResolution(args.checkInteger(0), args.checkInteger(1));
        return new Object[] {true};
    }

    @Callback(direct = true, doc = "function():number, number -- Largest canvas this display allows: 320 pixels per block of its group on each axis.")
    public synchronized Object[] getMaxResolution(Context context, Arguments args) {
        return new Object[] {maxWidth(), maxHeight()};
    }

    // ---- resolution dialog (ModularUI2; empty-hand sneak right-click on any member) ------

    @Override
    public ModularPanel buildUI(PosGuiData data, PanelSyncManager sync, UISettings settings) {
        TileTkDisplay m = master();
        TileTkDisplay target = m == null ? this : m;
        EntityPlayer player = data.getPlayer();
        // A small dialog with no inventory: keep NEI's item list off the screen.
        settings.getRecipeViewerSettings().disable();
        // Edits travel client to server, which MUI2 refuses unless allowed; the server-side
        // setter checks permission and limits itself.
        IntSyncValue width = new IntSyncValue(() -> target.resolutionValue(true), v -> {
            if (!data.isClient()) target.resizeFromDialog(player, v, target.resolutionValue(false));
        }).allowC2S();
        IntSyncValue height = new IntSyncValue(() -> target.resolutionValue(false), v -> {
            if (!data.isClient()) target.resizeFromDialog(player, target.resolutionValue(true), v);
        }).allowC2S();
        InteractionSyncHandler reset = new InteractionSyncHandler().setOnMousePressed(button -> {
            if (!data.isClient()) target.resizeFromDialog(player, PRESET_WIDTH, PRESET_HEIGHT);
        });
        InteractionSyncHandler fill = new InteractionSyncHandler().setOnMousePressed(button -> {
            if (!data.isClient()) target.resizeFromDialog(player, target.maxWidth(), target.maxHeight());
        });
        TkGroup g = target.group();
        return ModularPanel.defaultPanel("mpy4oc_tk_resolution", 180, 86)
                .child(ButtonWidget.panelCloseButton())
                .child(Flow.col().widthRel(1f).heightRel(1f).padding(7).childPadding(4)
                        .child(IKey.lang("mpy4oc.tk.resolution.title").asWidget().widthRel(1f).height(10))
                        .child(IKey.lang("mpy4oc.tk.resolution.limit", g.width, g.height, g.maxWidth(), g.maxHeight())
                                .asWidget().widthRel(1f).height(10))
                        .child(Flow.row().widthRel(1f).height(14).childPadding(4)
                                .child(new TextFieldWidget().size(56, 14).numbersInt(MIN_SIZE, g.maxWidth()).value(width))
                                .child(IKey.str("\u00d7").asWidget().size(8, 14))
                                .child(new TextFieldWidget().size(56, 14).numbersInt(MIN_SIZE, g.maxHeight()).value(height)))
                        .child(Flow.row().widthRel(1f).height(16).childPadding(4)
                                .child(new ButtonWidget<>().size(80, 16).syncHandler(reset)
                                        .overlay(IKey.str(PRESET_WIDTH + "\u00d7" + PRESET_HEIGHT)))
                                .child(new ButtonWidget<>().size(80, 16).syncHandler(fill)
                                        .overlay(IKey.lang("mpy4oc.tk.resolution.max")))));
    }

    @Override
    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    public com.cleanroommc.modularui.screen.ModularScreen createScreen(PosGuiData data, ModularPanel panel) {
        return new com.cleanroommc.modularui.screen.ModularScreen(reobf.mpy4oc.main.MyMod.MODID, panel);
    }

    private synchronized int resolutionValue(boolean width) { return width ? scene.width : scene.height; }

    /** A change from the dialog: permission and limits are checked here, on the server thread. */
    private void resizeFromDialog(EntityPlayer player, int newWidth, int newHeight) {
        if (!(player instanceof EntityPlayerMP) || isInvalid() || slave) return;
        if (!mayReconfigure((EntityPlayerMP) player)) {
            player.addChatMessage(new ChatComponentTranslation("mpy4oc.tk.notPermitted"));
            return;
        }
        int w = Math.max(MIN_SIZE, Math.min(newWidth, maxWidth())), h = Math.max(MIN_SIZE, Math.min(newHeight, maxHeight()));
        synchronized (this) { if (w == scene.width && h == scene.height) return; }
        setResolution(w, h);
    }

    /** Set the canvas size within this display's limits; server thread. */
    public synchronized void setResolution(int newWidth, int newHeight) {
        if (newWidth < MIN_SIZE || newHeight < MIN_SIZE || newWidth > maxWidth() || newHeight > maxHeight())
            throw new IllegalArgumentException("resolution must be between " + MIN_SIZE + "x" + MIN_SIZE
                    + " and " + maxWidth() + "x" + maxHeight() + " for this display");
        updateScene(new TkScene(newWidth, newHeight, scene.background, scene.surfaceVisible, scene.display, scene.images));
    }

    @Callback(direct = true, doc = "function():boolean -- Get whether world surface rendering is enabled.")
    public synchronized Object[] isSurfaceVisible(Context context, Arguments args) {
        return new Object[] {scene.surfaceVisible};
    }

    @Callback(doc = "function(visible:boolean):boolean -- Enable or disable world surface rendering.")
    public synchronized Object[] setSurfaceVisible(Context context, Arguments args) {
        boolean visible = args.checkBoolean(0);
        updateScene(new TkScene(scene.width, scene.height, scene.background, visible, scene.display, scene.images));
        return new Object[] {true};
    }

    @Callback(direct = true, doc = "function():number -- Get the canvas background as an RGB color.")
    public synchronized Object[] getBackground(Context context, Arguments args) { return new Object[] {scene.background}; }

    @Callback(doc = "function(color:number):boolean -- Set the canvas background (0..0xFFFFFF).")
    public synchronized Object[] setBackground(Context context, Arguments args) {
        updateScene(new TkScene(scene.width, scene.height, args.checkInteger(0), scene.surfaceVisible, scene.display, scene.images));
        return new Object[] {true};
    }

    private void updateScene(TkScene updated) {
        if (!scene.equals(updated)) {
            scene = updated;
            sceneRevision++;
            markDirty();
        }
    }

    /** Java entry point for the forthcoming Python host binding; server thread only. */
    public TkTree widgets() { return widgets; }
    public TkApplication application() { return application; }
    void replaceWidgets(TkTree value) { value.continueImagesFrom(widgets); widgets = value; }
    @Callback(doc = "function(operation:string, token:string, ...):value -- Tk host protocol; synchronized on the server thread.")
    public synchronized Object[] tk(Context context, Arguments args) {
        String operation = args.checkString(0), token = args.checkString(1);
        Object[] raw = args.toArray();
        Object result = application.call(context.node().address(), operation, token, java.util.Arrays.copyOfRange(raw, 2, raw.length));
        return new Object[] {result};
    }

    private void refreshWidgets() {
        widgets.resize(scene.width, scene.height);
        TkDisplayList display = widgets.render();
        java.util.Map<Integer, TkScene.ImageRef> images = widgets.imageTable();
        if (!display.equals(scene.display) || !images.equals(scene.images))
            updateScene(new TkScene(scene.width, scene.height, scene.background, scene.surfaceVisible, display, images));
    }

    public synchronized TkScene scene() { refreshWidgets(); return scene; }
    public synchronized long sceneRevision() { return sceneRevision; }
    public synchronized TkScene surfaceScene() {
        if (scene.surfaceVisible) return scene();
        return new TkScene(scene.width,scene.height,scene.background,false,TkDisplayList.EMPTY);
    }

    public void openFor(EntityPlayerMP player) {
        if (!TkNetwork.canUse(player, this)) return;
        if (!TkNetwork.supports(player)) {
            player.addChatMessage(new ChatComponentTranslation("mpy4oc.tk.clientRequired"));
            return;
        }
        // Vanilla does not throttle right-clicks; each open sends a full scene.
        if (!TkNetwork.allowOpen(player)) return;
        updateEntity();
        if (node == null || node.address() == null) return;
        TkNetwork.beginViewing(player);
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.open(player.getUniqueID(), player, TkNetwork.currentTick());
        TkNetwork.watch(this);
        synchronized (this) {
            refreshWidgets();
            if (TkNetwork.trySend(player, new TkPackets.OpenGui(target(viewer), sceneRevision, scene))) {
                viewer.sent(sceneRevision, scene);
            } else {
                closeFor(player, viewer.session, false);
                player.addChatMessage(new ChatComponentTranslation("mpy4oc.tk.busy"));
            }
        }
    }

    /**
     * Whether {@code player} may operate this display's UI: the same permission the owning
     * computer applies to its own OC screens and keyboards ({@code computer.addUser}).
     * A display with no live owner on this network accepts no input.
     */
    public boolean canControl(EntityPlayerMP player) { return canControl(player.getCommandSenderName()); }
    public boolean canControl(String player) {
        String owner = application.owner();
        if (owner.isEmpty() || node == null || node.network() == null) return false;
        Node machine = node.network().node(owner);
        if (machine == null) return false;
        Object host = machine.host();
        return !(host instanceof Context) || ((Context) host).canInteract(player);
    }

    /**
     * Whether {@code player} may merge, split or resize this display: anyone while no live
     * program claims it, otherwise whoever may operate its UI.
     */
    public boolean mayReconfigure(EntityPlayerMP player) {
        String owner = application.owner();
        if (owner.isEmpty() || node == null || node.network() == null || node.network().node(owner) == null) return true;
        return canControl(player);
    }

    private TkTarget target(TkViewers.Viewer<EntityPlayerMP> viewer) {
        return new TkTarget(worldObj.provider.dimensionId, xCoord, yCoord, zCoord, node.address(), viewer.session);
    }

    public void closeFor(EntityPlayerMP player, long session, boolean notify) {
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.find(player.getUniqueID(), session);
        if (viewer == null || viewer.owner != player) return;
        if (notify) TkNetwork.send(player, new TkPackets.CloseGui(target(viewer)));
        viewers.close(player.getUniqueID(), session);
        widgets.release(session);
        widgets.pointerLeft();
        if (viewers.isEmpty()) TkNetwork.unwatch(this);
    }

    public void closePlayer(EntityPlayerMP player) {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all())
            if (viewer.owner == player) closeFor(player, viewer.session, true);
    }

    private void closeViewers() {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) closeFor(viewer.owner, viewer.session, true);
    }

    /** The last program on the display let it go: its desktop is gone, so close every GUI on it. */
    void applicationReleased() { closeViewers(); }

    /** Close every GUI on this display after a failure: best-effort notify, never throws. */
    public void abortViewers() {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) {
            try { if (node != null && node.address() != null) TkNetwork.send(viewer.owner, new TkPackets.CloseGui(target(viewer))); }
            catch (RuntimeException ignored) { /* the client times out its GUI instead */ }
        }
        discardViewers();
    }

    public void discardViewers() {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) widgets.release(viewer.session);
        viewers.clear();
        TkNetwork.unwatch(this);
    }

    public boolean acceptInput(EntityPlayerMP player, long session, long tick, int cost, boolean edit) {
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.find(player.getUniqueID(), session);
        return viewer != null && viewer.owner == player && viewer.allowInput(tick, cost, edit);
    }

    /** The client lost track of the scene; the next flush sends it in full (rate-limited). */
    public synchronized void requestResync(EntityPlayerMP player, long session, long tick) {
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.find(player.getUniqueID(), session);
        if (viewer != null && viewer.owner == player) viewer.requestResync(tick);
    }

    public synchronized void flushViewers() {
        refreshWidgets();
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) {
            if (!TkNetwork.canUse(viewer.owner, this) || viewer.expired(TkNetwork.currentTick())) {
                closeFor(viewer.owner, viewer.session, true);
                continue;
            }
            if (viewer.stale(sceneRevision)) {
                TkPackets.SceneSync update = viewer.prepare(target(viewer), sceneRevision, scene);
                // Over budget: keep the old base so the next tick sends a combined delta.
                if (update != null && TkNetwork.trySend(viewer.owner, update)) viewer.sent(sceneRevision, scene);
            }
            // Image pixels follow the scene the client has, in pieces, within the same GUI budget.
            for (int pieces = 0; pieces < 4; pieces++) {
                TkImageTransfer.Chunk chunk = viewer.images.next(viewer.lastScene, widgets);
                if (chunk == null || !TkNetwork.trySend(viewer.owner, new TkPackets.ImageData(target(viewer), chunk))) break;
                viewer.images.sent(chunk);
            }
        }
    }

    public void input(EntityPlayerMP player, TkPackets.InputEvent event) {
        TkScene current = scene();
        // Focus 0 means nothing is focused (widget 0 is the root itself).
        TkWidget previousFocus = widgets.focused() == 0 ? null : widgets.get(widgets.focused());
        String previousText = previousFocus instanceof Entry ? previousFocus.text() : null;
        boolean key = event.kind == TkPackets.InputEvent.KEY_DOWN || event.kind == TkPackets.InputEvent.KEY_UP;
        boolean inside = event.x >= 0 && event.y >= 0 && event.x < current.width && event.y < current.height;
        // The client's one motion as the pointer leaves the display.
        if (!inside && event.kind == TkPackets.InputEvent.MOTION) { widgets.pointerLeft(); emitCommands(player); return; }
        if (!key && !inside && event.kind != TkPackets.InputEvent.DRAG && event.kind != TkPackets.InputEvent.MOUSE_UP
                && event.kind != TkPackets.InputEvent.MOUSE_CANCEL) return;
        // Title bars, close boxes and window edges belong to the window manager, not the program.
        boolean decoration = !key && inside && widgets.zoneAt(event.x, event.y) != TkWindow.Zone.NONE;
        // A Sizegrip takes only the left button's drag: motion, the wheel and the right button
        // still reach the program (its bindings and its window's), as in Tk.
        boolean frame = decoration && !(widgets.hit(event.x, event.y) instanceof Sizegrip);
        // With nothing focused, keys go to the active (topmost) window, as on a desktop.
        TkWidget target = key ? (previousFocus != null ? previousFocus : widgets.activeWindow()) : widgets.hit(event.x, event.y);
        TkWidget selectionTarget = event.kind == TkPackets.InputEvent.DRAG ? widgets.capture(event.target.session) : target;
        long selectionBefore = selectionTarget instanceof Listbox ? ((Listbox) selectionTarget).selectionVersion : -1;
        boolean moved = widgets.pointerMoved(event.target.session, event.x, event.y);
        // Releasing a window move/resize or a title bar/taskbar button is the window manager's, too.
        boolean managed = event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0 && widgets.managing(event.target.session);
        if (event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0) target = widgets.capture(event.target.session);
        // A program's grab (a modal dialog): its other windows hear nothing; only the frames (move, close) still work.
        boolean barPress = !key && inside && widgets.zoneAt(event.x, event.y) == TkWindow.Zone.MENUBAR;
        if ((!decoration || barPress) && !widgets.listOpen() && widgets.blocked(key ? target : widgets.hit(event.x, event.y))
                && !(event.kind == TkPackets.InputEvent.DRAG || event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0) ) {
            if (event.kind == TkPackets.InputEvent.MOUSE_DOWN) widgets.raiseGrab(widgets.hit(event.x, event.y));
            emitCommands(player);
            return;
        }
        int canvasItem = event.kind == TkPackets.InputEvent.DRAG || event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0
                ? widgets.capturedItem(event.target.session) : target instanceof Canvas && !key ? ((Canvas) target).hitItem(event.x,event.y) : 0;
        TkTree.Command command = null;
        // A Combobox's open list has the pointer and the keyboard (Tk grabs them): the tree gives
        // them to it, and the program hears none of this event.
        boolean list = widgets.listOpen();
        if (list && (event.kind == TkPackets.InputEvent.MOUSE_DOWN && event.code == 1 || event.kind == TkPackets.InputEvent.MOTION)) {
            if (event.kind == TkPackets.InputEvent.MOTION) widgets.pointerHover(event.x, event.y);
            else widgets.listPress(event.target.session, event.x, event.y, 1);
            changedEntry(previousFocus, previousText, player);
            emitCommands(player);
            return;
        }
        if (event.kind == TkPackets.InputEvent.MOUSE_DOWN && event.code == 0)
            widgets.pointerDown(event.target.session, event.x, event.y, event.modifiers);
        else if (event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0)
            command = widgets.pointerUp(event.target.session, event.x, event.y);
        else if (event.kind == TkPackets.InputEvent.KEY_DOWN) {
            if (!modifierKey(event.code)) widgets.interruptClick(event.target.session);
            command = widgets.key(event.code, event.modifiers, event.character);
        }
        else if (event.kind == TkPackets.InputEvent.MOUSE_CANCEL && event.code == 0) widgets.release(event.target.session);
        else if (event.kind == TkPackets.InputEvent.DRAG) target = widgets.pointerDrag(event.target.session, event.x, event.y);
        else if (event.kind == TkPackets.InputEvent.WHEEL) widgets.wheel(event.x, event.y, event.code);
        else if (event.kind == TkPackets.InputEvent.MOTION) widgets.pointerHover(event.x, event.y);
        changedEntry(previousFocus, previousText, player);
        if (command != null) emitCommand(command, player);
        emitCommands(player);
        if (list) return;
        if (selectionTarget instanceof Listbox && ((Listbox) selectionTarget).selectionVersion != selectionBefore)
            boundEvent(selectionTarget, "<<ListboxSelect>>", event, player);
        for (int window : widgets.takeCloseRequests()) {
            String token = application.tokenOf(widgets.get(window));
            if (!token.isEmpty()) playerSignal(player, "tk_close", token, window, player.getCommandSenderName());
        }
        if (event.kind == TkPackets.InputEvent.MOUSE_DOWN && event.code == 1)
            widgets.rightPress(event.target.session, frame || target == null ? -1 : target.id);
        if (event.kind == TkPackets.InputEvent.MOUSE_DOWN && (event.code == 0 ? !decoration : event.code == 1 && !frame))
            // The second click of a double click is <Double-Button-1> wherever that is bound (Tk's more specific pattern).
            boundEvent(target, event.code == 0 ? "<Button-1>" : "<Button-3>", event, player, canvasItem,
                    event.code == 0 && widgets.doubleClicked(event.target.session) ? "<Double-Button-1>" : null);
        if (event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 1) {
            // A right release goes where its press went (Tk's implicit grab): nowhere if the window manager had it.
            Integer press = widgets.rightRelease(event.target.session);
            if (press != null) {
                target = press < 0 ? null : widgets.get(press);
                canvasItem = target instanceof Canvas ? ((Canvas) target).hitItem(event.x, event.y) : 0;
            } else if (frame) target = null;
        }
        if (event.kind == TkPackets.InputEvent.MOUSE_UP && (event.code == 0 || event.code == 1) && target != null && !managed)
            boundEvent(target, event.code == 0 ? "<ButtonRelease-1>" : "<ButtonRelease-3>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.MOTION && !frame) boundEvent(target, "<Motion>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.DRAG && target != null && moved) {
            boundEvent(target, "<Motion>", event, player, canvasItem); boundEvent(target, "<B1-Motion>", event, player, canvasItem);
        }
        if (event.kind == TkPackets.InputEvent.WHEEL && !frame) boundEvent(target, "<MouseWheel>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.KEY_UP) boundEvent(target, "<KeyRelease>", event, player, canvasItem);
        // A key's own binding (<Return>, <Up>, ...) where a tag has one, else its <Key>: never both.
        // Escape reaches the display only to close an open list (the GUI keeps it otherwise): never the program.
        if (event.kind == TkPackets.InputEvent.KEY_DOWN && event.code != 1) boundEvent(target, "<Key>", event, player, canvasItem, keySequence(event.code));
        // High-frequency pointer events are delivered only to registered bindings.
        // (No raw per-input signal: nothing consumes one, and every unread signal is
        // carried in the VM snapshot, outside the machine's memory accounting.)
        if (!key) widgets.updateCanvasCurrent(event.target.session, event.x, event.y);
    }

    private void emitCommands(String player) {
        for (TkTree.Command command : widgets.takeCommands()) emitCommand(command, player);
        emitConfigures(null, player);
    }
    private void emitCommands(EntityPlayerMP player) {
        for (TkTree.Command command : widgets.takeCommands()) emitCommand(command, player);
        emitConfigures(player, player.getCommandSenderName());
    }
    /** {@code <Configure>} events (a widget's new size), in the tk_event layout plus width and height. */
    private void emitConfigures(EntityPlayerMP player, String name) {
        java.util.List<int[]> events = widgets.takeConfigures();
        if (events.isEmpty() || node == null) return;
        for (int[] c : events) {
            TkWidget widget = widgets.get(c[0]);
            Integer callback = widget == null ? null : widget.bindings.get("<Configure>");
            String token = application.tokenOf(widget);
            if (callback == null || token.isEmpty()) continue;
            Object[] signal = {"tk_event", token, widget.id, callback, "<Configure>", c[1], c[2], 0, 0, 0,
                    name, 0, widget.id, false, 0, c[3], c[4]};
            if (player != null) playerSignal(player, signal); else node.sendToReachable("computer.signal", signal);
        }
    }
    /** A command no player caused (a Python change, a scroll callback): plain signal. */
    private void emitCommand(TkTree.Command command, String player) {
        Object[] signal = commandSignal(command, player);
        if (node != null && signal != null) node.sendToReachable("computer.signal", signal);
    }
    /** A command a player caused: OC delivers it only to computers this player may use. */
    private void emitCommand(TkTree.Command command, EntityPlayerMP player) {
        Object[] signal = commandSignal(command, player.getCommandSenderName());
        if (node != null && signal != null) playerSignal(player, signal);
    }
    /** The tk_command signal for the program the widget belongs to; null if that program is gone. */
    private Object[] commandSignal(TkTree.Command command, String player) {
        String token = application.tokenOf(widgets.get(command.widget));
        if (token.isEmpty()) return null;
        // OC Machine.onMessage inserts message.source.address before our args.
        // Including it here too shifts the token/widget/callback positions.
        Object[] signal = new Object[5 + command.args.length];
        signal[0] = "tk_command"; signal[1] = token;
        signal[2] = command.widget; signal[3] = command.callback; signal[4] = player;
        System.arraycopy(command.args, 0, signal, 5, command.args.length);
        return signal;
    }
    /**
     * {@code computer.checked_signal}: like OC's own screens and keyboards, each receiving
     * computer drops the signal unless {@code canInteract(player)} (its user list).
     */
    private void playerSignal(EntityPlayerMP player, Object... signal) {
        Object[] data = new Object[signal.length + 1];
        data[0] = player;
        System.arraycopy(signal, 0, data, 1, signal.length);
        node.sendToReachable("computer.checked_signal", data);
    }

    /** Shift, Control, Alt and the meta keys (LWJGL codes): they do not break a double click. */
    private static boolean modifierKey(int code) {
        return code == 42 || code == 54 || code == 29 || code == 157 || code == 56 || code == 184 || code == 219 || code == 220;
    }

    private String keySequence(int code) {
        switch (code) {
            case 28: case 156: return "<Return>";
            case 57: return "<space>";
            case 15: return "<Tab>";
            case 14: return "<BackSpace>";
            case 211: return "<Delete>";
            case 203: return "<Left>";
            case 205: return "<Right>";
            case 199: return "<Home>";
            case 207: return "<End>";
            case 200: return "<Up>";
            case 208: return "<Down>";
            case 201: return "<Prior>";
            case 209: return "<Next>";
            default: return null;
        }
    }

    public void copy(EntityPlayerMP player, TkPackets.CopyRequest request) {
        TkWidget focus = widgets.get(widgets.focused()); String before = focus instanceof Entry ? focus.text() : null;
        // Up to 512 KiB of Text selection: metered like every other GUI payload. Send the
        // text first and cut only once it is on its way, so a refused reply cannot lose it.
        String text = widgets.copySelection(false);
        if (!TkNetwork.trySend(player, new TkPackets.CopyResult(request.target, request.request, text))) return;
        if (request.cut) widgets.copySelection(true);
        changedEntry(focus, before, player);
        emitCommands(player);
        boundEvent(focus, request.cut ? "<<Cut>>" : "<<Copy>>", null, player);
    }

    public void clipboard(EntityPlayerMP player, String text) {
        TkWidget focus = widgets.get(widgets.focused()); String previous = focus instanceof Entry ? focus.text() : null;
        widgets.paste(text); changedEntry(focus, previous, player);
        emitCommands(player); // view callbacks the paste queued (e.g. a Text's yscrollcommand)
        boundEvent(focus, "<<Paste>>", null, player);
    }

    private void changedEntry(TkWidget widget, String previous) {
        if (previous != null && !previous.equals(widget.text()) && !application.tokenOf(widget).isEmpty())
            node.sendToReachable("computer.signal", "tk_changed", application.tokenOf(widget), widget.id, widget.text());
    }
    private void changedEntry(TkWidget widget, String previous, EntityPlayerMP player) {
        if (previous != null && !previous.equals(widget.text()) && !application.tokenOf(widget).isEmpty())
            playerSignal(player, "tk_changed", application.tokenOf(widget), widget.id, widget.text());
    }

    private void boundEvent(TkWidget target, String sequence, TkPackets.InputEvent event, EntityPlayerMP player) {
        boundEvent(target, sequence, event, player, 0);
    }
    private void boundEvent(TkWidget target, String sequence, TkPackets.InputEvent event, EntityPlayerMP player, int item) {
        boundEvent(target, sequence, event, player, item, null);
    }
    /** As above; {@code preferred} replaces {@code sequence} for each binding tag that binds it. */
    private void boundEvent(TkWidget target, String sequence, TkPackets.InputEvent event, EntityPlayerMP player, int item, String preferred) {
        // The desktop is nobody's: nothing there is bound. Each signal goes to the program the widget is in.
        if (target == null || !widgets.available(target)) return;
        String token = application.tokenOf(target);
        if (token.isEmpty()) return;
        TkRect r = target.bounds();
        if (target instanceof Canvas && event != null) for (Object[] bound : ((Canvas) target).callbacks(item, sequence, preferred))
            playerSignal(player, "tk_event", token, target.id, bound[0], bound[1],
                    event.x-r.x, event.y-r.y,event.code,event.character,event.modifiers,player.getCommandSenderName(),
                    event.kind == TkPackets.InputEvent.WHEEL ? event.code : 0,target.id,event.kind == TkPackets.InputEvent.DRAG,item);
        // Tk's bindtags: the widget, then its toplevel (the window it is in).
        TkWidget toplevel = TkWindow.of(target);
        for (TkWidget widget : target == toplevel ? new TkWidget[] {target} : new TkWidget[] {target, toplevel}) {
            String used = preferred != null && widget.bindings.containsKey(preferred) ? preferred : sequence;
            Integer callback = widget.bindings.get(used);
            if (callback == null || !widgets.available(widget)) continue;
            playerSignal(player, "tk_event", token, widget.id, callback, used,
                    event == null ? 0 : event.x - r.x, event == null ? 0 : event.y - r.y,
                    event == null ? 0 : event.code, event == null ? 0 : event.character, event == null ? 0 : event.modifiers,
                    player.getCommandSenderName(), event != null && event.kind == TkPackets.InputEvent.WHEEL ? event.code : 0,
                    target.id, event != null && event.kind == TkPackets.InputEvent.DRAG, item);
        }
    }

    @Override
    public synchronized void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        // Normally this runs once, before anyone watches. A live reload (e.g. a world
        // editor) replaces the tree under open GUIs: close them, and keep the revision
        // increasing so no client mistakes the new scene for one it already has.
        if (!viewers.isEmpty()) abortViewers();
        replaceWidgets(new TkTree(this::markDirty)); application.reset();
        scene = TkScene.defaults(maxWidth(), maxHeight());
        sceneRevision++;
        if (nbt.hasKey("mpy4oc:display", 10)) {
            NBTTagCompound state = nbt.getCompoundTag("mpy4oc:display");
            int savedWidth = state.getInteger("width");
            int savedHeight = state.getInteger("height");
            boolean saved = TkScene.validSize(savedWidth) && TkScene.validSize(savedHeight);
            int background = state.hasKey("background", 3) ? state.getInteger("background") : TkScene.DEFAULT_BACKGROUND;
            if (background < 0 || background > 0xFFFFFF) background = TkScene.DEFAULT_BACKGROUND;
            boolean surface = !state.hasKey("surfaceVisible", 1) || state.getBoolean("surfaceVisible");
            if (state.hasKey("group", 10)) readGroup(state.getCompoundTag("group"));
            // Older saves allowed up to 1024 on any display; the limit is now per group.
            int width = saved ? Math.min(savedWidth, maxWidth()) : maxWidth(), height = saved ? Math.min(savedHeight, maxHeight()) : maxHeight();
            scene = new TkScene(width, height, background, surface);
            imageStore = state.getString("imageStore");
            if (!imageStore.isEmpty() && !imageStore.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) imageStore = "";
            if (state.hasKey("tree", 10)) {
                try {
                    TkTree restored = TkTreePersistence.load(state.getCompoundTag("tree"), width, height, this::markDirty, imageStorage());
                    application.load(state.getCompoundTag("application"));
                    // The desktop holds only the saved programs' windows (an older save's lone program is dropped).
                    if (!application.accepts(restored)) { restored = new TkTree(this::markDirty); application.reset(); }
                    replaceWidgets(restored);
                } catch (RuntimeException invalid) {
                    // Also catches ReportedException from mistyped NBT: letting it escape
                    // makes Forge drop the whole tile entity, including its OC address.
                    replaceWidgets(new TkTree(this::markDirty)); application.reset();
                    reobf.mpy4oc.main.MyMod.LOG.warn("Ignoring invalid saved Tk tree", invalid);
                }
            }
        }
    }

    @Override
    public synchronized void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        NBTTagCompound state = new NBTTagCompound();
        state.setInteger("width", scene.width);
        state.setInteger("height", scene.height);
        state.setBoolean("surfaceVisible", scene.surfaceVisible);
        state.setInteger("background", scene.background);
        NBTTagCompound groupTag = new NBTTagCompound();
        writeGroup(groupTag);
        state.setTag("group", groupTag);
        try {
            // Lay the tree out at the size saved above: the loader re-lays it out at
            // that size and restores focus against the resulting clip rectangles.
            // (Layout only: publishing a new scene is left to the next tick.)
            widgets.resize(scene.width, scene.height);
            if (imageStore.isEmpty() && !widgets.images().isEmpty()) imageStore = java.util.UUID.randomUUID().toString();
            claimImageStore();
            NBTTagCompound tree = TkTreePersistence.save(widgets, imageStorage());
            state.setString("imageStore", imageStore);
            state.setTag("tree", tree);
            state.setTag("application", application.save());
        } catch (RuntimeException error) {
            // Without this the exception drops the tile entity (and its OC address)
            // from the chunk save. The display itself still persists, minus its UI.
            reobf.mpy4oc.main.MyMod.LOG.warn("Could not save the Tk widget tree", error);
        }
        nbt.setTag("mpy4oc:display", state);
    }
}
