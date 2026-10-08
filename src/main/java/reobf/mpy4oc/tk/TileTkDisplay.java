package reobf.mpy4oc.tk;

import li.cil.oc.api.Network;
import li.cil.oc.api.detail.Builder;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Analyzable;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.TileEntityEnvironment;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import reobf.mpy4oc.tk.net.TkNetwork;
import reobf.mpy4oc.tk.net.TkPackets;

/** Server-owned display state. Mutating callbacks run on the server thread. */
public class TileTkDisplay extends TileEntityEnvironment implements Analyzable {
    public static final String COMPONENT_NAME = "tk_display";
    public static final int DEFAULT_WIDTH = 320;
    public static final int DEFAULT_HEIGHT = 240;
    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 1024;

    private TkScene scene = TkScene.defaults();
    private long sceneRevision = 1;
    private final TkViewers<EntityPlayerMP> viewers = new TkViewers<>();
    private TkTree widgets = new TkTree(this::markDirty);
    private final TkApplication application = new TkApplication(this);

    public TileTkDisplay() {
        Builder.NodeBuilder builder = Network.newNode(this, Visibility.Network);
        if (builder != null) node = builder.withComponent(COMPONENT_NAME, Visibility.Network).create();
        // OC's builder creates no node on clients; all network state stays on the server.
    }

    @Override
    public void updateEntity() {
        if (worldObj != null && !worldObj.isRemote && node != null) { super.updateEntity(); emitCommands(""); }
    }

    @Override
    public void onConnect(Node connected) {
        super.onConnect(connected);
        // Joining may assign or remap our address even in an otherwise clean
        // chunk. Persist it without waiting for a canvas setting to change.
        if (node != null && connected == node) markDirty();
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
    }

    @Override
    public void invalidate() {
        closeViewers();
        super.invalidate();
    }

    @Override
    public Node[] onAnalyze(EntityPlayer player, int side, float hitX, float hitY, float hitZ) {
        return node == null ? new Node[0] : new Node[] {node};
    }

    @Callback(direct = true, doc = "function():number, number -- Get the canvas width and height in pixels.")
    public synchronized Object[] getResolution(Context context, Arguments args) {
        return new Object[] {scene.width, scene.height};
    }

    @Callback(doc = "function(width:number, height:number):boolean -- Set the canvas size (64..1024 pixels per axis).")
    public synchronized Object[] setResolution(Context context, Arguments args) {
        int newWidth = args.checkInteger(0);
        int newHeight = args.checkInteger(1);
        updateScene(new TkScene(newWidth, newHeight, scene.background, scene.title, scene.surfaceVisible, scene.display));
        return new Object[] {true};
    }

    @Callback(direct = true, doc = "function():boolean -- Get whether world surface rendering is enabled.")
    public synchronized Object[] isSurfaceVisible(Context context, Arguments args) {
        return new Object[] {scene.surfaceVisible};
    }

    @Callback(doc = "function(visible:boolean):boolean -- Enable or disable world surface rendering.")
    public synchronized Object[] setSurfaceVisible(Context context, Arguments args) {
        boolean visible = args.checkBoolean(0);
        updateScene(new TkScene(scene.width, scene.height, scene.background, scene.title, visible, scene.display));
        return new Object[] {true};
    }

    @Callback(direct = true, doc = "function():number -- Get the canvas background as an RGB color.")
    public synchronized Object[] getBackground(Context context, Arguments args) { return new Object[] {scene.background}; }

    @Callback(doc = "function(color:number):boolean -- Set the canvas background (0..0xFFFFFF).")
    public synchronized Object[] setBackground(Context context, Arguments args) {
        updateScene(new TkScene(scene.width, scene.height, args.checkInteger(0), scene.title, scene.surfaceVisible, scene.display));
        return new Object[] {true};
    }

    @Callback(direct = true, doc = "function():string -- Get the window title.")
    public synchronized Object[] getTitle(Context context, Arguments args) { return new Object[] {scene.title}; }

    @Callback(doc = "function(title:string):boolean -- Set the window title (one line, up to 512 UTF-8 bytes).")
    public synchronized Object[] setTitle(Context context, Arguments args) {
        updateScene(new TkScene(scene.width, scene.height, scene.background, args.checkString(0), scene.surfaceVisible, scene.display));
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
    void replaceWidgets(TkTree value) { widgets = value; }
    void configureScene(int width, int height, String title, int background) {
        updateScene(new TkScene(width, height, background, title, scene.surfaceVisible, scene.display));
    }

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
        if (!display.equals(scene.display))
            updateScene(new TkScene(scene.width, scene.height, scene.background, scene.title, scene.surfaceVisible, display));
    }

    public synchronized TkScene scene() { refreshWidgets(); return scene; }
    public synchronized long sceneRevision() { return sceneRevision; }
    public synchronized TkScene surfaceScene() {
        if (scene.surfaceVisible) return scene();
        return new TkScene(scene.width,scene.height,scene.background,scene.title,false,TkDisplayList.EMPTY);
    }

    public void openFor(EntityPlayerMP player) {
        if (!TkNetwork.canUse(player, this)) return;
        if (!TkNetwork.supports(player)) {
            player.addChatMessage(new ChatComponentTranslation("mpy4oc.tk.clientRequired"));
            return;
        }
        updateEntity();
        if (node == null || node.address() == null) return;
        TkNetwork.beginViewing(player);
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.open(player.getUniqueID(), player, TkNetwork.currentTick());
        TkNetwork.watch(this);
        synchronized (this) {
            refreshWidgets();
            viewer.lastScene = scene;
            viewer.revision = sceneRevision;
            TkNetwork.send(player, new TkPackets.OpenGui(target(viewer), sceneRevision, scene));
        }
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
        if (viewers.isEmpty()) TkNetwork.unwatch(this);
    }

    public void closePlayer(EntityPlayerMP player) {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all())
            if (viewer.owner == player) closeFor(player, viewer.session, true);
    }

    private void closeViewers() {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) closeFor(viewer.owner, viewer.session, true);
    }

    public void discardViewers() {
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) widgets.release(viewer.session);
        viewers.clear();
        TkNetwork.unwatch(this);
    }

    public boolean acceptInput(EntityPlayerMP player, long session, long tick, int cost) {
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.find(player.getUniqueID(), session);
        return viewer != null && viewer.owner == player && viewer.allowInput(tick, cost);
    }

    public synchronized void sendFullScene(EntityPlayerMP player, long session) {
        TkViewers.Viewer<EntityPlayerMP> viewer = viewers.find(player.getUniqueID(), session);
        if (viewer == null || viewer.owner != player) return;
        refreshWidgets();
        viewer.lastScene = scene;
        viewer.revision = sceneRevision;
        TkNetwork.send(player, new TkPackets.SceneSync(target(viewer), -1, sceneRevision, TkPackets.ALL, scene));
    }

    public synchronized void flushViewers() {
        refreshWidgets();
        for (TkViewers.Viewer<EntityPlayerMP> viewer : viewers.all()) {
            if (!TkNetwork.canUse(viewer.owner, this) || viewer.expired(TkNetwork.currentTick())) {
                closeFor(viewer.owner, viewer.session, true);
                continue;
            }
            if (viewer.revision != sceneRevision) {
                TkPackets.SceneSync update = viewer.update(target(viewer), sceneRevision, scene);
                if (update != null) TkNetwork.send(viewer.owner, update);
            }
        }
    }

    public void input(EntityPlayerMP player, TkPackets.InputEvent event) {
        TkScene current = scene();
        TkWidget previousFocus = widgets.get(widgets.focused());
        String previousText = previousFocus instanceof Entry ? previousFocus.text() : null;
        boolean key = event.kind == TkPackets.InputEvent.KEY_DOWN || event.kind == TkPackets.InputEvent.KEY_UP;
        boolean inside = event.x >= 0 && event.y >= 0 && event.x < current.width && event.y < current.height;
        if (!key && !inside && event.kind != TkPackets.InputEvent.DRAG && event.kind != TkPackets.InputEvent.MOUSE_UP
                && event.kind != TkPackets.InputEvent.MOUSE_CANCEL) return;
        TkWidget target = key ? previousFocus : widgets.hit(event.x, event.y);
        TkWidget selectionTarget = event.kind == TkPackets.InputEvent.DRAG ? widgets.capture(event.target.session) : target;
        long selectionBefore = selectionTarget instanceof Listbox ? ((Listbox) selectionTarget).selectionVersion : -1;
        boolean moved = widgets.pointerMoved(event.target.session, event.x, event.y);
        if (event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0) target = widgets.capture(event.target.session);
        int canvasItem = event.kind == TkPackets.InputEvent.DRAG || event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0
                ? widgets.capturedItem(event.target.session) : target instanceof Canvas && !key ? ((Canvas) target).hitItem(event.x,event.y) : 0;
        TkTree.Command command = null;
        if (event.kind == TkPackets.InputEvent.MOUSE_DOWN && event.code == 0)
            widgets.pointerDown(event.target.session, event.x, event.y, event.modifiers);
        else if (event.kind == TkPackets.InputEvent.MOUSE_UP && event.code == 0)
            command = widgets.pointerUp(event.target.session, event.x, event.y);
        else if (event.kind == TkPackets.InputEvent.KEY_DOWN) command = widgets.key(event.code, event.modifiers, event.character);
        else if (event.kind == TkPackets.InputEvent.MOUSE_CANCEL && event.code == 0) widgets.release(event.target.session);
        else if (event.kind == TkPackets.InputEvent.DRAG) target = widgets.pointerDrag(event.target.session, event.x, event.y);
        else if (event.kind == TkPackets.InputEvent.WHEEL) widgets.wheel(event.x, event.y, event.code);
        changedEntry(previousFocus, previousText);
        if (command != null) emitCommand(command, player.getCommandSenderName());
        emitCommands(player.getCommandSenderName());
        if (selectionTarget instanceof Listbox && ((Listbox) selectionTarget).selectionVersion != selectionBefore)
            boundEvent(selectionTarget, "<<ListboxSelect>>", event, player);
        if (event.kind == TkPackets.InputEvent.MOUSE_DOWN && (event.code == 0 || event.code == 1))
            boundEvent(target, event.code == 0 ? "<Button-1>" : "<Button-3>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.MOUSE_UP && (event.code == 0 || event.code == 1) && target != null)
            boundEvent(target, event.code == 0 ? "<ButtonRelease-1>" : "<ButtonRelease-3>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.MOTION) boundEvent(target, "<Motion>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.DRAG && target != null && moved) {
            boundEvent(target, "<Motion>", event, player, canvasItem); boundEvent(target, "<B1-Motion>", event, player, canvasItem);
        }
        if (event.kind == TkPackets.InputEvent.WHEEL) boundEvent(target, "<MouseWheel>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.KEY_UP) boundEvent(target, "<KeyRelease>", event, player, canvasItem);
        if (event.kind == TkPackets.InputEvent.KEY_DOWN) {
            boundEvent(target, "<Key>", event, player, canvasItem);
            String sequence = keySequence(event.code);
            if (sequence != null) boundEvent(target, sequence, event, player, canvasItem);
        }
        // High-frequency pointer events are delivered only to registered bindings.
        if (!key) widgets.updateCanvasCurrent(event.target.session, event.x, event.y);
        if (event.kind != TkPackets.InputEvent.MOTION && event.kind != TkPackets.InputEvent.DRAG)
            node.sendToReachable("computer.signal", "tk_input", event.kind, event.x, event.y,
                    event.code, event.character, event.modifiers, player.getCommandSenderName());
    }

    private void emitCommands(String player) { for (TkTree.Command command : widgets.takeCommands()) emitCommand(command, player); }
    private void emitCommand(TkTree.Command command, String player) {
        if (node == null) return;
        // OC Machine.onMessage inserts message.source.address before our args.
        // Including it here too shifts the token/widget/callback positions.
        Object[] signal = new Object[5 + command.args.length];
        signal[0] = "tk_command"; signal[1] = application.token();
        signal[2] = command.widget; signal[3] = command.callback; signal[4] = player;
        System.arraycopy(command.args, 0, signal, 5, command.args.length);
        node.sendToReachable("computer.signal", signal);
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
            default: return null;
        }
    }

    public void copy(EntityPlayerMP player, TkPackets.CopyRequest request) {
        TkWidget focus = widgets.get(widgets.focused()); String before = focus instanceof Entry ? focus.text() : null;
        String text = widgets.copySelection(request.cut); changedEntry(focus, before);
        TkNetwork.send(player, new TkPackets.CopyResult(request.target, request.request, text));
        boundEvent(focus, request.cut ? "<<Cut>>" : "<<Copy>>", null, player);
    }

    public void clipboard(EntityPlayerMP player, String text) {
        TkWidget focus = widgets.get(widgets.focused()); String previous = focus instanceof Entry ? focus.text() : null;
        widgets.paste(text); changedEntry(focus, previous);
        boundEvent(focus, "<<Paste>>", null, player);
        node.sendToReachable("computer.signal", "tk_clipboard", text, player.getCommandSenderName());
    }

    private void changedEntry(TkWidget widget, String previous) {
        if (previous != null && !previous.equals(widget.text())) node.sendToReachable("computer.signal", "tk_changed",
                application.token(), widget.id, widget.text());
    }

    private void boundEvent(TkWidget target, String sequence, TkPackets.InputEvent event, EntityPlayerMP player) {
        boundEvent(target, sequence, event, player, 0);
    }
    private void boundEvent(TkWidget target, String sequence, TkPackets.InputEvent event, EntityPlayerMP player, int item) {
        if (target == null) target = widgets.root();
        if (!widgets.available(target)) return;
        TkRect r = target.bounds();
        if (target instanceof Canvas && event != null) for (int callback : ((Canvas) target).callbacks(item, sequence))
            node.sendToReachable("computer.signal", "tk_event", application.token(), target.id, callback, sequence,
                    event.x-r.x, event.y-r.y,event.code,event.character,event.modifiers,player.getCommandSenderName(),
                    event.kind == TkPackets.InputEvent.WHEEL ? event.code : 0,target.id,event.kind == TkPackets.InputEvent.DRAG,item);
        for (TkWidget widget : target == widgets.root() ? new TkWidget[] {target} : new TkWidget[] {target, widgets.root()}) {
            Integer callback = widget.bindings.get(sequence);
            if (callback == null || !widgets.available(widget)) continue;
            node.sendToReachable("computer.signal", "tk_event", application.token(), widget.id, callback, sequence,
                    event == null ? 0 : event.x - r.x, event == null ? 0 : event.y - r.y,
                    event == null ? 0 : event.code, event == null ? 0 : event.character, event == null ? 0 : event.modifiers,
                    player.getCommandSenderName(), event != null && event.kind == TkPackets.InputEvent.WHEEL ? event.code : 0,
                    target.id, event != null && event.kind == TkPackets.InputEvent.DRAG, item);
        }
    }

    @Override
    public synchronized void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        widgets = new TkTree(this::markDirty); application.reset();
        scene = TkScene.defaults();
        sceneRevision = 1;
        if (nbt.hasKey("mpy4oc:display", 10)) {
            NBTTagCompound state = nbt.getCompoundTag("mpy4oc:display");
            int savedWidth = state.getInteger("width");
            int savedHeight = state.getInteger("height");
            int width = TkScene.validSize(savedWidth) && TkScene.validSize(savedHeight) ? savedWidth : DEFAULT_WIDTH;
            int height = TkScene.validSize(savedWidth) && TkScene.validSize(savedHeight) ? savedHeight : DEFAULT_HEIGHT;
            int background = state.hasKey("background", 3) ? state.getInteger("background") : TkScene.DEFAULT_BACKGROUND;
            if (background < 0 || background > 0xFFFFFF) background = TkScene.DEFAULT_BACKGROUND;
            String title = state.hasKey("title", 8) ? state.getString("title") : TkScene.DEFAULT_TITLE;
            if (!TkScene.validTitle(title)) title = TkScene.DEFAULT_TITLE;
            boolean surface = !state.hasKey("surfaceVisible", 1) || state.getBoolean("surfaceVisible");
            scene = new TkScene(width, height, background, title, surface);
            if (state.hasKey("tree", 10)) {
                try {
                    TkTree restored = TkTreePersistence.load(state.getCompoundTag("tree"), width, height, this::markDirty);
                    application.load(state.getCompoundTag("application")); widgets = restored;
                } catch (IllegalArgumentException invalid) {
                    application.reset();
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
        state.setString("title", scene.title);
        state.setTag("tree", TkTreePersistence.save(widgets));
        state.setTag("application", application.save());
        nbt.setTag("mpy4oc:display", state);
    }
}
