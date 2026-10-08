package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.nbt.NBTTagCompound;
import net.mpy.compiler.MpyCross;
import net.mpy.loader.MpyLoader;
import net.mpy.loader.MpyModule;
import net.mpy.qstr.QstrPool;
import net.mpy.runtime.PyException;
import net.mpy.runtime.PyObj;
import net.mpy.vm.*;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.*;

class TkPythonTest {
    @org.junit.jupiter.api.BeforeAll static void registerTile() {
        TkTestRegistration.ensure();
    }

    static final class Host {
        final Map<String, Object> globals = new HashMap<>();
        final HostRegistry registry = HostRegistry.fromGlobals(globals);
        final Map<String, TileTkDisplay> tiles = new LinkedHashMap<>();
        final PyModule computer = new PyModule("computer", "<host>", null);
        final MpyArchitecture converter = new MpyArchitecture(null);
        Vm vm; double time;
        Host() {
            computer.ns.put("_signals", new PyObj.PyList());
            reg("uptime", args -> time);
            reg("_yield_tick", args -> { vm.requestYield(); return PyObj.NONE; });
            globals.put("computer", computer);
            TkHostApi.install(globals, registry, (address, args) -> {
                TileTkDisplay tile = tiles.get(address);
                if (tile == null) throw new PyException("OSError", "display disconnected");
                Object[] converted = new Object[args.length - 2];
                for (int i = 2; i < args.length; i++) converted[i - 2] = MpyArchitecture.toJava(args[i]);
                try {
                    Object result = tile.application().call("computer", (String) args[0], (String) args[1], converted);
                    // The real Component.invoke normalizes results through OC before the architecture sees them.
                    return py(li.cil.oc.server.driver.Registry$.MODULE$.convert(new Object[] {result})[0]);
                }
                catch (IllegalArgumentException error) { throw new PyException("ValueError", error.getMessage()); }
            }, () -> {
                Map<String, String> components = new LinkedHashMap<>();
                tiles.keySet().forEach(address -> components.put(address, "tk_display")); return components;
            }, () -> vm == null ? computer : vm.globals.get("computer"));
        }
        void reg(String name, HostFunction function) { computer.ns.put(name, function); registry.register("computer." + name, function); }
        Object py(Object value) {
            return converter.toPy(value);
        }
        void start(String source) throws Exception {
            MpyModule main = MpyLoader.load(MpyCross.compile(source, "tk_test.py"), new QstrPool());
            vm = new Vm(main, globals);
            vm.start(new net.mpy.vm.Frame(main.root));
        }
        void runUntil(String marker) {
            for (int i = 0; i < 500_000 && !vm.isFinished() && !Boolean.TRUE.equals(vm.globals.get(marker)); i++) {
                if (i % 100 == 0) for (Map.Entry<String, TileTkDisplay> item : tiles.entrySet())
                    for (TkTree.Command command : item.getValue().widgets().takeCommands()) {
                        emit(item.getValue(), item.getKey(), command, "");
                    }
                vm.step();
            }
            assertEquals(true, vm.globals.get(marker), "program should reach " + marker);
        }
        void signal(Object... parts) { signals().items.add(new PyObj.Tuple(Arrays.stream(parts).map(this::py).toArray())); }
        PyObj.PyList signals() { return (PyObj.PyList) ((PyModule) vm.globals.get("computer")).ns.get("_signals"); }
        void click(TileTkDisplay tile) {
            click(tile, 2);
        }
        void click(TileTkDisplay tile, int id) {
            TkWidget button = tile.widgets().get(id); TkRect r = button.bounds();
            tile.widgets().pointerDown(1, r.x + 1, r.y + 1);
            TkTree.Command command = tile.widgets().pointerUp(1, r.x + 1, r.y + 1);
            assertNotNull(command);
            emit(tile, "screen-a", command, "Player");
        }
        void route(TileTkDisplay tile, String address) {
            try {
                java.lang.reflect.Field node = li.cil.oc.api.prefab.TileEntityEnvironment.class.getDeclaredField("node");
                node.setAccessible(true);
                node.set(tile, java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {li.cil.oc.api.network.Node.class}, (p, m, args) -> {
                            if (m.getName().equals("address")) return address;
                            if (m.getName().equals("host")) return tile;
                            if (m.getName().equals("save")) { ((NBTTagCompound) args[0]).setString("address", address); return null; }
                            if (m.getName().equals("load")) { assertEquals(address, ((NBTTagCompound) args[0]).getString("address")); return null; }
                            if (m.getName().equals("sendToReachable")) {
                                assertEquals("computer.signal", args[0]);
                                Object[] wire = (Object[]) args[1], delivered = new Object[wire.length + 1];
                                // OC Machine.onMessage prepends the SOURCE node's address,
                                // not an address supplied in the payload by the component.
                                delivered[0] = wire[0]; delivered[1] = address;
                                System.arraycopy(wire, 1, delivered, 2, wire.length - 1);
                                signal(delivered); return null;
                            }
                            throw new UnsupportedOperationException(m.getName());
                        }));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        void emit(TileTkDisplay tile, String address, TkTree.Command command, String player) {
            route(tile, address);
            try {
                java.lang.reflect.Method emit = TileTkDisplay.class.getDeclaredMethod("emitCommand", TkTree.Command.class, String.class);
                emit.setAccessible(true); emit.invoke(tile, command, player);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }

    @Test void importDoesNotRequireHardwareAndScreensCanBeSelected() throws Exception {
        Host noHardware = new Host();
        noHardware.start("import tkinter as tk\ntry:\n    tk.Tk()\nexcept OSError:\n    passed = True\n");
        noHardware.runUntil("passed");
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay()); host.tiles.put("screen-b", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "ambiguous = False", "try:", "    tk.Tk(screenName='screen-')",
                "except OSError:", "    ambiguous = True", "assert ambiguous", "root = tk.Tk(screenName='screen-a')",
                "top = tk.Toplevel(root, screen='screen-b')", "assert root.winfo_screen() == 'screen-a'", "assert top.winfo_screenwidth() == 320",
                "top.destroy()", "root.destroy()", "passed = True", ""));
        host.runUntil("passed");
    }

    @Test void entryChangeFromOcNetworkReachesPythonVariable() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("import tkinter as tk\nr = tk.Tk()\nv = tk.StringVar(value='before')\ne = tk.Entry(r, textvariable=v)\ne.pack()\narmed = True\nwhile v.get() != 'after':\n    r.update()\npassed = True\n");
        host.runUntil("armed"); host.route(tile, "screen-a");
        Entry entry = (Entry) tile.widgets().get(1); entry.text("after");
        java.lang.reflect.Method changed = TileTkDisplay.class.getDeclaredMethod("changedEntry", TkWidget.class, String.class);
        changed.setAccessible(true); changed.invoke(tile, entry, "before");
        host.runUntil("passed");
    }

    @Test void frozenImportPrecedesDiskCopyAndCanStartAfterSnapshot() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start("armed = True\nimport tkinter as tk\nr = tk.Tk()\nassert r.winfo_screen() == 'screen-a'\npassed = True\n");
        host.runUntil("armed");
        Host restored = new Host(); restored.tiles.putAll(host.tiles);
        restored.vm = Vm.fromSnapshot(host.vm.saveSnapshot(host.registry), restored.globals, restored.registry);
        restored.vm.setModuleFinder(name -> { throw new AssertionError("frozen import consulted disk: " + name); });
        restored.runUntil("passed");
    }

    @Test void preFreezeDiskModuleAndCallbacksSurviveUpgrade() throws Exception {
        // Model the pre-freeze build: same host ABI, disk filename and embedded
        // bytecode distinct from the new frozen module. The marker detects an
        // accidental reload of the new library instead of the snapshot's code.
        byte[] legacy = MpyCross.compile(Files.readString(Path.of("stdlib/tkinter.py"))
                + "\nlegacy_marker = 'disk-copy'\n", "tkinter.py");
        Host host = new Host(); TileTkDisplay original = new TileTkDisplay(); host.tiles.put("screen-a", original);
        host.start("""
                import tkinter as tk
                root = tk.Tk()
                value = tk.StringVar(value='before')
                label = tk.Label(root, textvariable=value)
                label.pack()
                button = tk.Button(root, text='resume', command=lambda: value.set('after'))
                button.pack()
                armed = True
                while value.get() != 'after':
                    root.update()
                assert tk.legacy_marker == 'disk-copy'
                assert label['text'] == 'after'
                passed = True
                """);
        host.vm.setStdLibEnabled(false);
        host.vm.setModuleFinder(name -> name.equals("tkinter") ? legacy : null);
        host.runUntil("armed");
        host.vm.setStdLibEnabled(true);
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        NBTTagCompound nbt = new NBTTagCompound(); original.writeToNBT(nbt);
        Host restored = new Host(); TileTkDisplay tile = new TileTkDisplay(); tile.readFromNBT(nbt); restored.tiles.put("screen-a", tile);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.vm.setModuleFinder(name -> { throw new AssertionError("restore consulted disk: " + name); });
        restored.click(tile, 2);
        restored.runUntil("passed");
    }

    @Test void callbacksVariablesTimersAndEntrySurviveJointVmAndTileRestore() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "root.geometry('400x300')", "counter = tk.StringVar(value='0')",
                "label = tk.Label(root, textvariable=counter)", "label.pack()", "def click():", "    counter.set(str(int(counter.get()) + 1))",
                "button = tk.Button(root, text='count', command=click)", "button.pack()", "value = tk.StringVar(value='中文')",
                "entry = tk.Entry(root, textvariable=value)", "entry.pack()", "entry.insert(tk.END, '😀')", "assert entry.get() == '中文😀'",
                "assert entry.index(tk.END) == 3", "entry.icursor(1)", "entry.delete(1)", "assert value.get() == '中😀'",
                "echo = tk.Label(root, textvariable=value)", "echo.pack()", "entry.focus_set()", "seen = []",
                "def on_enter(event):", "    seen.append(event.keysym)", "    counter.set(str(int(counter.get()) + 10))",
                "entry.bind('<Return>', on_enter)", "cancelled = root.after(0, counter.set, 'bad')", "root.after_cancel(cancelled)",
                "root.after(1000, counter.set, '20')", "armed = True", "while counter.get() != '1':", "    root.update()",
                "clicked = True", "while not seen:", "    root.update()", "bound = True", "while counter.get() != '20':", "    root.update()",
                "assert value.get() == '恢复后'", "assert echo['text'] == '恢复后'", "assert seen == ['Return']", "passed = True", ""));
        host.runUntil("armed"); TileTkDisplay original = host.tiles.get("screen-a");
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        NBTTagCompound nbt = new NBTTagCompound(); original.writeToNBT(nbt);
        Host restored = new Host(); TileTkDisplay tile = new TileTkDisplay(); tile.readFromNBT(nbt); restored.tiles.put("screen-a", tile);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        assertEquals(original.scene(), tile.scene());
        restored.signal("key_down", "keyboard", 65, 30, "Player");
        restored.signal("tk_command", "screen-a", "obsolete-token", 2, 1, "Player");
        restored.click(tile); restored.runUntil("clicked");
        assertEquals("1", tile.widgets().get(1).text());
        restored.signal("tk_event", "screen-a", tile.application().token(), 3, 2, "<Return>", 0, 0, 28, 13, 0, "Player");
        restored.runUntil("bound"); assertEquals("11", tile.widgets().get(1).text());
        ((Entry) tile.widgets().get(3)).text("恢复后");
        restored.signal("tk_changed", "screen-a", tile.application().token(), 3, "stale text");
        restored.time = 2; restored.runUntil("passed");
        assertEquals("20", tile.widgets().get(1).text());
        assertEquals(1, restored.signals().items.size(), "Tk poll must preserve unrelated OC signals");
        assertEquals("0", original.widgets().get(1).text(), "only the restored machine receives events");
    }

    @Test void reconfigurationBindingsAndDestroyReleasePythonReferences() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "frame = tk.Frame(root)", "frame.pack()",
                "value = tk.StringVar(value='value')", "label = tk.Label(frame, textvariable=value, bg='#123')", "label.pack(padx=(2, 4))",
                "assert label['background'] == 0x112233", "button = tk.Button(frame, command=lambda: None)", "button.pack()",
                "button.configure(command=lambda: None)", "assert len(root._callbacks) == 1", "seen = []",
                "first = button.bind('<Return>', lambda e: seen.append(1))", "button.bind('<Return>', lambda e: seen.append(2), '+')",
                "root._callbacks[button._bindings['<Return>']](None)", "assert seen == [1, 2]", "button.unbind('<Return>')",
                "button.after(50, seen.append, 3)", "frame.destroy()", "assert not label.winfo_exists()", "assert not value._widgets",
                "assert not root._callbacks", "assert not root._timers", "root.destroy()", "replacement = tk.Tk()",
                "assert replacement.winfo_screen() == 'screen-a'", "passed = True", ""));
        host.runUntil("passed");
    }

    @Test void gridPlaceQueriesAndCallbacksSurviveResizeAndJointRestore() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "root.geometry('320x240')",
                "root.columnconfigure((0, 1), weight=1)", "root.rowconfigure(0, weight=1)",
                "a = tk.Frame(root, width=40, height=20)", "a.grid(row=0, column=0, sticky='nsew', padx=(2, 3), pady=4)",
                "b = tk.Frame(root, width=40, height=20)", "b.grid(row=0, column=1, sticky='nsew')",
                "assert a.winfo_manager() == 'grid'", "assert root.grid_size() == (2, 1)",
                "assert root.columnconfigure(1, 'weight') == 1", "assert root.grid_slaves() == [b, a]",
                "assert a.grid_info()['padx'] == (2, 3)", "assert a.grid_info()['in'] is root",
                "assert a.winfo_x() == 2 and a.winfo_y() == 4", "assert a.winfo_width() == 157", "assert a.winfo_height() == 232",
                "assert root.grid_bbox() == (0, 0, 320, 240)", "assert root.grid_bbox(1, 0) == (162, 0, 158, 240)",
                "a.grid_remove()", "assert not a.winfo_ismapped()", "assert a.grid_info() == {}", "a.grid()",
                "assert a.grid_info()['padx'] == (2, 3)", "a.grid_remove()",
                "overlay = tk.Frame(root, width=30, height=10)", "overlay.place(relx=.5, rely=.5, anchor='center')",
                "assert overlay.winfo_x() == 145 and overlay.winfo_y() == 115", "assert overlay.place_info()['width'] == ''",
                "overlay.place_configure(width=50)", "assert overlay.winfo_width() == 50",
                "overlay.place_configure(width='')", "assert overlay.winfo_reqwidth() == 30",
                "overlay.pack_forget()", "assert overlay.winfo_manager() == 'place'",
                "fixed = tk.Frame(b, width=70, height=50)", "fixed.pack()", "fixed.grid_propagate(False)",
                "assert fixed.grid_propagate() == False and fixed.pack_propagate() == True",
                "label = tk.Label(fixed, text='a very long label')", "label.grid()", "assert fixed.winfo_reqwidth() == 70",
                "clicked = False", "def resized():", "    global clicked", "    root.geometry('400x300')", "    a.grid()", "    clicked = True",
                "button = tk.Button(root, text='resize', command=resized)", "button.place(x=10, y=180)", "armed = True",
                "while not clicked:", "    root.update()", "assert a.winfo_ismapped()", "assert a.grid_info()['column'] == 0",
                "assert overlay.winfo_x() == 185 and overlay.winfo_y() == 145",
                "assert a.winfo_height() == 292", "assert root.grid_bbox() == (0, 0, 400, 300)",
                "a.grid_forget()", "a.grid(row=1)", "assert a.grid_info()['padx'] == 0", "assert a.grid_info()['sticky'] == ''",
                "overlay.place_forget()", "assert overlay.place_info() == {}", "assert overlay.winfo_manager() == ''",
                "overlay.grid(row=2, column=0, columnspan=2, rowspan=2)",
                "assert overlay in root.grid_slaves(column=1)", "assert overlay in root.grid_slaves(row=3)", "passed = True", ""));
        host.runUntil("armed"); byte[] snapshot = host.vm.saveSnapshot(host.registry);
        NBTTagCompound nbt = new NBTTagCompound(); tile.writeToNBT(nbt);
        Host restored = new Host(); TileTkDisplay copy = new TileTkDisplay(); copy.readFromNBT(nbt); restored.tiles.put("screen-a", copy);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        assertEquals(tile.scene(), copy.scene()); restored.click(copy, 6); restored.runUntil("passed");
    }

    @Test void entrySelectionAndMotionWheelKeyEventsReachPythonWithTkFields() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "entry = tk.Entry(root, width=4)", "entry.pack()",
                "entry.insert(0, 'a😀中文abcdefghijk')", "entry.selection_range(1, 4)", "assert entry.selection_present()",
                "assert entry.selection_get() == '😀中文'", "assert entry.index('sel.first') == 1", "assert entry.index('sel.last') == 4",
                "assert entry.index(-5) == 0", "assert entry.index(1000) == 15", "entry.selection_from(2)", "entry.selection_to(5)",
                "assert entry.selection_get() == '中文a'", "entry.xview_moveto(.5)", "assert entry.xview()[0] > 0",
                "entry.xview_scroll(-100, 'units')", "assert entry.xview()[0] == 0", "assert entry.index('@0') == 0",
                "events = []", "def event(e):", "    events.append((e.widget is entry, e.x, e.y, e.delta, e.state, e.char, e.keysym))",
                "root.bind('<Motion>', event)", "entry.bind('<MouseWheel>', event)", "entry.bind('<KeyRelease>', event)", "armed = True",
                "while len(events) < 3:", "    root.update()",
                "assert events[0] == (True, -10, 3, 0, 261, '', '')",
                "assert events[1] == (True, 20, 4, -120, 8, '', '')",
                "assert events[2] == (True, 0, 0, 0, 4, '😀', '😀')", "passed = True", ""));
        host.runUntil("armed");
        String token = tile.application().token();
        host.signal("tk_event", "screen-a", token, 0, 1, "<Motion>", -10, 3, 0, 0, 3, "Player", 0, 1, true);
        host.signal("tk_event", "screen-a", token, 1, 2, "<MouseWheel>", 20, 4, -120, 0, 4, "Player", -120, 1, false);
        host.signal("tk_event", "screen-a", token, 1, 3, "<KeyRelease>", 0, 0, 0, 0x1F600, 2, "Player", 0, 1, false);
        host.runUntil("passed");
    }

    @Test void typedVariablesControlsAndScrollCommandsRunThroughRealPythonVm() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "seen = []", "flag = tk.BooleanVar(value=False)",
                "check = tk.Checkbutton(root, text='check', variable=flag, command=lambda: seen.append(flag.get()))", "check.pack()",
                "check.invoke()", "assert flag.get() is True", "assert seen == [True]", "check.toggle()", "assert flag.get() is False",
                "choice = tk.IntVar(value=1)", "a = tk.Radiobutton(root, variable=choice, value=1)", "b = tk.Radiobutton(root, variable=choice, value=2)",
                "a.pack()", "b.pack()", "b.invoke()", "assert choice.get() == 2", "assert not a._call('cget', a._id, 'selected')",
                "value = tk.DoubleVar(value=3.5)", "scale = tk.Scale(root, variable=value, from_=0, to=10, resolution=.5, orient=tk.HORIZONTAL, command=lambda v: seen.append(float(v)))",
                "scale.pack()", "scale.set(7.2)", "assert value.get() == 7.0", "root.update()", "assert 7.0 in seen",
                "box = tk.Listbox(root, width=10, height=3, selectmode=tk.EXTENDED)", "box.pack()",
                "bar = tk.Scrollbar(root, command=box.yview)", "bar.pack()", "box.configure(yscrollcommand=bar.set)",
                "box.insert(tk.END, *[str(i) for i in range(300)])", "assert box.size() == 300", "assert box.get(299) == '299'",
                "box.selection_set(20, 22)", "assert box.curselection() == (20, 21, 22)", "box.delete(0, 9)", "assert box.curselection() == (10, 11, 12)",
                "box.yview_moveto(.5)", "root.update()", "assert bar.get()[0] > .4", "assert bar.get()[1] < .6",
                "box.yview_scroll(-1, 'pages')", "assert box.yview()[0] < .5",
                "box.insert(-100, *[str(i) for i in range(300)])", "assert box.get(255, 257) == ('255', '256', '257')",
                "check.destroy()", "assert not flag._value_widgets", "passed = True", ""));
        host.runUntil("passed");
    }

    @Test void newControlsAndBoundMethodsSurviveJointSnapshotRestore() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "flag = tk.IntVar(value=0)",
                "check = tk.Checkbutton(root, variable=flag)", "check.pack()", "box = tk.Listbox(root, height=3)", "box.pack()",
                "bar = tk.Scrollbar(root, command=box.yview)", "bar.pack()", "box.configure(yscrollcommand=bar.set)",
                "box.insert(tk.END, *[str(i) for i in range(100)])", "armed = True", "while flag.get() == 0:", "    root.update()",
                "assert flag.get() == 1", "box.yview_moveto(.5)", "root.update()", "assert bar.get()[0] >= .5", "passed = True", ""));
        host.runUntil("armed"); byte[] vm = host.vm.saveSnapshot(host.registry); NBTTagCompound saved = new NBTTagCompound(); tile.writeToNBT(saved);
        Host restored = new Host(); TileTkDisplay loaded = new TileTkDisplay(); loaded.readFromNBT(saved); restored.tiles.put("screen-a", loaded);
        restored.vm = Vm.fromSnapshot(vm, restored.globals, restored.registry);
        TkWidget check = loaded.widgets().get(1); TkRect r = check.bounds(); loaded.widgets().pointerDown(1, r.x + 2, r.y + 2);
        TkTree.Command command = loaded.widgets().pointerUp(1, r.x + 2, r.y + 2);
        restored.signal("tk_command", "screen-a", loaded.application().token(), command.widget, command.callback, "Player");
        restored.runUntil("passed"); assertEquals(100, ((Listbox) loaded.widgets().get(2)).size());
    }

    @Test void architectureDefersTkCallsToSynchronizedPhaseAndExecutesEachOnlyOnce() throws Exception {
        TileTkDisplay tile = new TileTkDisplay();
        int[] calls = {0};
        li.cil.oc.api.network.Node node = (li.cil.oc.api.network.Node) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {li.cil.oc.api.network.Node.class}, (p, m, a) -> m.getName().equals("address") ? "computer" : null);
        li.cil.oc.api.machine.Machine machine = (li.cil.oc.api.machine.Machine) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {li.cil.oc.api.machine.Machine.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "components": return Map.of("screen-a", "tk_display");
                        case "node": return node;
                        case "upTime": return 0.0;
                        case "invoke":
                            assertEquals("tk", a[1]); calls[0]++;
                            Object[] raw = (Object[]) a[2];
                            li.cil.oc.api.machine.Arguments arguments = (li.cil.oc.api.machine.Arguments) java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(), new Class<?>[] {li.cil.oc.api.machine.Arguments.class},
                                    (ap, am, aa) -> am.getName().equals("toArray") ? raw : raw[(Integer) aa[0]]);
                            return tile.tk((li.cil.oc.api.machine.Context) p, arguments);
                        default: throw new UnsupportedOperationException(m.getName());
                    }
                });
        MpyArchitecture architecture = new MpyArchitecture(machine);
        Map<String, Object> globals = new HashMap<>(); HostRegistry hosts = HostRegistry.fromGlobals(globals);
        set(architecture, "hosts", hosts); set(architecture, "async", true); set(architecture, "syncCallLimit", 1);
        java.lang.reflect.Method install = MpyArchitecture.class.getDeclaredMethod("installHostApi", Map.class); install.setAccessible(true);
        install.invoke(architecture, globals);
        MpyModule main = MpyLoader.load(MpyCross.compile("import tkinter as tk\nassert component.list('tk_display') == {'screen-a': 'tk_display'}\nr = tk.Tk()\nl = tk.Label(r, text='async')\nl.pack()\npassed = True\n", "async_tk.py"), new QstrPool());
        Vm vm = new Vm(main, globals); set(architecture, "vm", vm);
        vm.start(new net.mpy.vm.Frame(main.root));
        java.lang.reflect.Field phase = MpyArchitecture.class.getDeclaredField("phase"); phase.setAccessible(true);
        Object threaded = phase.get(architecture), sync = Arrays.stream(phase.getType().getEnumConstants()).filter(v -> v.toString().equals("SYNC")).findFirst().orElseThrow();
        int windows = 0;
        for (int i = 0; i < 100 && !vm.isFinished(); i++) {
            phase.set(architecture, threaded); int before = calls[0];
            vm.step(new int[] {100_000}); assertEquals(before, calls[0], "worker phase must not mutate the display");
            if (!vm.isFinished()) { phase.set(architecture, sync); vm.step(new int[] {100_000}); windows++; }
        }
        assertTrue(vm.isFinished()); assertEquals(true, vm.globals.get("passed"));
        assertEquals(4, calls[0], "claim/create/configure/pack each execute once, despite replay");
        assertEquals(4, windows); assertEquals(1, tile.scene().display.items.size());
    }

    @Test void canvasApiAndTagCallbacksSurviveJointVmAndTileRestore() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                root = tk.Tk()
                c = tk.Canvas(root, width=320, height=240, bg='#123', highlightthickness=0)
                c.pack()
                a = c.create_rectangle((40, 40, 10, 10), fill='red', tags=('group', 'first'))
                b = c.create_line([0, 0, 100, 80], fill='#0f0', width=3, tags='group')
                c.create_oval(50, 50, 90, 90, fill='')
                c.create_polygon(100, 100, 120, 100, 100, 120)
                label = c.create_text(160, 30, text='中文', anchor='nw')
                assert c.coords(a) == [10.0, 10.0, 40.0, 40.0]
                assert c.find_withtag('group') == (a, b)
                c.itemconfig('group', fill='blue')
                assert c.itemcget(a, 'fill') == '#0000ff'
                c.move('group', 2, 3)
                assert c.coords(a) == [12.0, 13.0, 42.0, 43.0]
                c.tag_raise(a)
                assert c.find_all()[-1] == a
                c.tag_lower(a, b)
                c.addtag_withtag('new', a)
                c.dtag(a, 'first')
                assert c.gettags(a) == ('group', 'new')
                assert c.type(label) == 'text'
                assert c.bbox(label) == (160, 30, 192, 46)
                seen = []
                def click(event):
                    assert event.widget is c
                    assert event.item == a
                    assert c.find_withtag('current') == (a,)
                    c.itemconfig(label, text='已点击')
                    seen.append((event.x, event.y))
                c.tag_bind('group', '<Button-1>', click)
                c.tag_bind('group', '<Button-1>', lambda e: seen.append('second'), '+')
                armed = True
                while not seen:
                    root.update()
                assert seen == [(20, 25), 'second']
                assert c.itemcget(label, 'text') == '已点击'
                c.tag_unbind('group', '<Button-1>')
                assert not root._callbacks
                c.tag_bind(a, '<Button-1>', click)
                c.delete(a)
                assert not root._callbacks
                c._event_item = 0
                c.create_text(0, 0, tags='__empty_current__')
                assert c.find_withtag('current') == ()
                c._event_item = None
                c.destroy()
                assert not root._callbacks
                passed = True
                """);
        host.runUntil("armed"); byte[] snapshot=host.vm.saveSnapshot(host.registry); NBTTagCompound nbt=new NBTTagCompound();tile.writeToNBT(nbt);
        Host restored=new Host();TileTkDisplay copy=new TileTkDisplay();copy.readFromNBT(nbt);restored.tiles.put("screen-a",copy);
        restored.vm=Vm.fromSnapshot(snapshot,restored.globals,restored.registry);assertEquals(tile.scene(),copy.scene());
        restored.signal("tk_event","screen-a",copy.application().token(),1,2,"<Button-1>",20,25,0,0,0,"Player",0,1,false,1);
        restored.runUntil("passed");
    }

    @Test void canvasDashboardInitializesAndRefreshesThroughPython() throws Exception {
        Host host=new Host();host.tiles.put("screen-a",new TileTkDisplay());
        host.reg("uptime",args -> { host.time += .01; return host.time; });
        String demo=Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkcanvas.py"));
        host.start(demo.replace("root.mainloop()","armed = True\nwhile frames < 3:\n    root.update()\npassed = True\n"));
        host.runUntil("armed");host.time=1;host.runUntil("passed");
    }

    @Test void textApiAndCallbacksSurviveJointSnapshotRestore() throws Exception {
        Host host=new Host();TileTkDisplay tile=new TileTkDisplay();host.tiles.put("screen-a",tile);
        host.start("""
                import tkinter as tk
                root = tk.Tk()
                text = tk.Text(root, width=20, height=5, wrap=tk.WORD)
                text.pack()
                bar = tk.Scrollbar(root, command=text.yview)
                bar.pack()
                text.configure(yscrollcommand=bar.set)
                text.insert('1.0', '中文😀\\nsecond\\n')
                assert text.get('1.0', '1.end') == '中文😀'
                assert text.index('1.end') == '1.3'
                assert text.index('end-1c') == '3.0'
                text.mark_set(tk.INSERT, '2.2')
                text.tag_add(tk.SEL, '1.1', '2.2')
                assert text.tag_ranges(tk.SEL) == ('1.1', '2.2')
                assert text.selection_get() == '文😀\\nse'
                assert text.compare('1.0', '<', tk.END)
                text.edit_modified(False)
                assert not text.edit_modified()
                text.configure(state=tk.DISABLED)
                text.insert(tk.END, 'bad')
                text.delete('1.0', tk.END)
                assert text.get('1.0', '1.end') == '中文😀'
                seen = []
                def clicked():
                    assert text.index(tk.INSERT) == '2.2'
                    assert text.selection_get() == '文😀\\nse'
                    text.configure(state=tk.NORMAL)
                    text.tag_remove(tk.SEL, '1.0', tk.END)
                    text.insert(tk.INSERT, '恢复')
                    assert text.get('2.0', '2.end') == 'se恢复cond'
                    text.yview_moveto(1)
                    text.xview_scroll(1, 'units')
                    text.see('1.0')
                    assert text.yview()[0] == 0
                    assert text.edit_modified()
                    seen.append(True)
                button = tk.Button(root, text='restore', command=clicked)
                button.pack()
                armed = True
                while not seen:
                    root.update()
                text.destroy()
                button.destroy()
                bar.destroy()
                assert not root._callbacks
                passed = True
                """);
        host.runUntil("armed");byte[] saved=host.vm.saveSnapshot(host.registry);NBTTagCompound nbt=new NBTTagCompound();tile.writeToNBT(nbt);
        Host restored=new Host();TileTkDisplay copy=new TileTkDisplay();copy.readFromNBT(nbt);restored.tiles.put("screen-a",copy);
        restored.vm=Vm.fromSnapshot(saved,restored.globals,restored.registry);assertEquals(tile.scene(),copy.scene());
        restored.click(copy,3);restored.runUntil("passed");
    }

    @Test void textEditorDemoFeedsReadOnlyLogAndSnapshotsEditor() throws Exception {
        Host host=new Host();TileTkDisplay tile=new TileTkDisplay();host.tiles.put("screen-a",tile);
        host.reg("uptime",args->{host.time+=.01;return host.time;});
        String demo=Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tktext.py"));
        host.start(demo.replace("root.mainloop()","while log_count < 1000:\n    root.update()\nassert log['state'] == tk.DISABLED\nassert log.index('end-1c') == '1001.0'\nsnapshot()\nassert status['text'].startswith('已记录快照')\nclear_log()\nassert log.get('1.0', tk.END) == '\\n'\npassed = True\n"));
        host.runUntil("passed");
    }

    private static void set(Object object, String name, Object value) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
}
