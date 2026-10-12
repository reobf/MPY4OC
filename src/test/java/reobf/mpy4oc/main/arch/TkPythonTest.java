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

    /** The token of the one program on {@code tile} (the tests open one unless they say otherwise). */
    static String token(TileTkDisplay tile) {
        java.util.List<String> tokens = tile.application().tokens();
        assertEquals(1, tokens.size(), "one program on the display");
        return tokens.get(0);
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
        /** Install the architecture's real computer bootstrap (pullSignal, time.sleep) before the program. */
        boolean bootstrap;
        void start(String source) throws Exception {
            MpyModule main = MpyLoader.load(MpyCross.compile(source, "tk_test.py"), new QstrPool());
            vm = new Vm(main, globals);
            if (bootstrap) {
                reg("_poll_signal", args -> MpyArchitecture.pollSignal(signals(), args.length > 0 && net.mpy.runtime.Ops.isTrue(args[0]),
                        args.length > 1 && net.mpy.runtime.Ops.isTrue(args[1]), args.length > 2 && net.mpy.runtime.Ops.isTrue(args[2])));
                java.lang.reflect.Field field = MpyArchitecture.class.getDeclaredField("vm");
                field.setAccessible(true);
                field.set(converter, vm);
                java.lang.reflect.Method install = MpyArchitecture.class.getDeclaredMethod("installComputerBootstrap", Map.class);
                install.setAccessible(true);
                install.invoke(converter, globals);
            }
            vm.start(new net.mpy.vm.Frame(main.root));
        }
        /** Instructions per step; above 1 the VM's tasks (threads) get a share too. */
        int stepBudget = 1;
        void runUntil(String marker) {
            for (int i = 0; i < 2_000_000 && !vm.isFinished() && !Boolean.TRUE.equals(vm.globals.get(marker)); i++) {
                if (i % 100 == 0) for (Map.Entry<String, TileTkDisplay> item : tiles.entrySet())
                    for (TkTree.Command command : item.getValue().widgets().takeCommands()) {
                        emit(item.getValue(), item.getKey(), command, "");
                    }
                try { if (stepBudget > 1) vm.step(new int[] {stepBudget}); else vm.step(); }
                catch (net.mpy.runtime.PyException error) {
                    // With the Python traceback (file/line), which the exception message alone lacks.
                    throw new AssertionError(error.getMessage() + "\n" + error.pyTraceback(), error);
                }
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
                            if (m.getName().equals("network")) return null;   // no machines to look up: boot ids are ""
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
        Entry entry = (Entry) tile.widgets().get(2); entry.text("after");   // 1 is the root window
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
        restored.click(tile, 3);   // the button: 1 is the root window, 2 the label
        restored.runUntil("passed");
    }

    @Test void callbacksVariablesTimersAndEntrySurviveJointVmAndTileRestore() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "root.geometry('300x200')", "counter = tk.StringVar(value='0')",
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
        restored.signal("tk_command", "screen-a", "obsolete-token", 3, 1, "Player");
        restored.click(tile, 3); restored.runUntil("clicked");   // 1 is the root window, 2 the label
        assertEquals("1", tile.widgets().get(2).text());
        restored.signal("tk_event", "screen-a", token(tile), 4, 2, "<Return>", 0, 0, 28, 13, 0, "Player");
        restored.runUntil("bound"); assertEquals("11", tile.widgets().get(2).text());
        ((Entry) tile.widgets().get(4)).text("恢复后");
        restored.signal("tk_changed", "screen-a", token(tile), 4, "stale text");
        restored.time = 2; restored.runUntil("passed");
        assertEquals("20", tile.widgets().get(2).text());
        assertEquals(1, restored.signals().items.size(), "Tk poll must preserve unrelated OC signals");
        assertEquals("0", original.widgets().get(2).text(), "only the restored machine receives events");
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

    @Test void toplevelsAreDraggableWindowsOnTheSameDesktop() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "root.geometry('200x150+5+5')",
                "assert root.geometry() == '200x150+5+5' and root.title() == 'tk'",   // the root is a window on the desktop too
                "top = tk.Toplevel(root)", "assert top.winfo_screen() == 'screen-a'", "assert top.state() == 'normal'",
                "top.title('设置')", "assert top.title() == '设置'",
                "assert top.geometry('120x80+10+20') == '120x80+10+20'",
                "assert top.geometry('-5-5') == '120x80+195+199'",  // -Y from the work area (320x320 display), above the taskbar
                "ok = tk.Button(top, text='ok')", "ok.pack()",
                "assert ok.winfo_toplevel() is top and top.winfo_toplevel() is top and root.winfo_toplevel() is root",
                "assert top.winfo_rootx() == 195 and top.winfo_rooty() == 217",
                "second = tk.Toplevel(root, bg='#123456')", "assert second['background'] == 0x123456",
                "nested = tk.Toplevel(second)", "third = tk.Toplevel(root)", "third.title('third')",
                "second.lift()", "top.lift()",
                "top.withdraw()", "assert top.state() == 'withdrawn' and not top.winfo_ismapped()",
                "top.deiconify()", "assert top.winfo_ismapped() and top.geometry() == '120x80+195+199'",
                "top.geometry('')", "assert top.geometry().split('+')[0] == str(top.winfo_reqwidth()) + 'x' + str(top.winfo_reqheight())",
                "try:", "    top.geometry('nonsense')", "    raise AssertionError('accepted a bad geometry')", "except ValueError:", "    pass",
                "try:", "    top.pack()", "    raise AssertionError('packed a Toplevel')", "except ValueError:", "    pass",
                "closed = []", "top.protocol('WM_DELETE_WINDOW', lambda: closed.append(1))",
                "armed = True", "while not closed:", "    root.update()",
                "assert top.winfo_exists()", "handled = True",
                "while third.winfo_exists():", "    root.update()",
                "second.destroy()", "assert not second.winfo_exists() and not nested.winfo_exists()",
                "passed = True", ""));
        host.runUntil("armed");
        reobf.mpy4oc.tk.Frame top = null, third = null;
        for (TkWidget child : tile.widgets().root().children()) if (child instanceof reobf.mpy4oc.tk.Frame && ((reobf.mpy4oc.tk.Frame) child).isWindow()) {
            if (((reobf.mpy4oc.tk.Frame) child).windowTitle().equals("设置")) top = (reobf.mpy4oc.tk.Frame) child;
            if (((reobf.mpy4oc.tk.Frame) child).windowTitle().equals("third")) third = (reobf.mpy4oc.tk.Frame) child;
        }
        assertNotNull(top); assertNotNull(third);
        java.util.List<TkWidget> stack = tile.widgets().root().children();
        assertSame(top, stack.get(stack.size() - 1), "lift() put the window on top");
        host.signal("tk_close", "screen-a", token(tile), top.id, "Player");
        host.runUntil("handled");
        host.signal("tk_close", "screen-a", token(tile), third.id, "Player");
        host.runUntil("passed");
        long windows = tile.widgets().root().children().stream().filter(c -> c instanceof reobf.mpy4oc.tk.Frame && ((reobf.mpy4oc.tk.Frame) c).isWindow()).count();
        assertEquals(2, windows, "third closed by default, second and its nested window destroyed: the root window and top are left");
    }

    @Test void toplevelScreensDestroyAndGeometryEdgeCases() throws Exception {
        Host host = new Host(); TileTkDisplay a = new TileTkDisplay(), b = new TileTkDisplay();
        host.tiles.put("screen-a", a); host.tiles.put("screen-b", b);
        host.start(String.join("\n", "import tkinter as tk",
                "other = tk.Toplevel(screen='screen-b')",
                "assert other._root is other and other.winfo_screen() == 'screen-b' and list(tk._roots) == [other._token]",
                "assert tk.Tk().winfo_screen() == 'screen-b', 'a display this computer uses comes first'",
                "root = tk.Tk('screen-a')", "assert root.winfo_screen() == 'screen-a'",
                "root.geometry(' 200 x 150 ')", "assert root.geometry().startswith('200x150+')",
                "same = tk.Toplevel(root, 'screen-a')", "assert same._window",
                "for bad in ('10x10+5 5', '+100x50', '10x10+5,5', 'x', '100x'):",
                "    try:", "        same.geometry(bad)", "        raise AssertionError('accepted ' + bad)", "    except ValueError:", "        pass",
                "same.geometry('100x40+30+50')", "assert (same.winfo_x(), same.winfo_y()) == (30, 50)",
                "same.geometry('+' + str(same.winfo_x()) + '+' + str(same.winfo_y()))", "assert same.geometry() == '100x40+30+50'",
                "foreign = tk.Frame(other)",
                "try:", "    same.lift(foreign)", "    raise AssertionError('stacked against another display')", "except ValueError:", "    pass",
                "f = tk.Frame(root)", "g = tk.Frame(f)", "deep = tk.Toplevel(g)", "inner = tk.Frame(same)", "deeper = tk.Toplevel(inner)",
                "f.destroy()", "same.destroy()",
                "assert not deep.winfo_exists() and not deeper.winfo_exists()",
                "passed = True", ""));
        host.runUntil("passed");
        long windows = a.widgets().root().children().stream().filter(c -> c instanceof reobf.mpy4oc.tk.Frame && ((reobf.mpy4oc.tk.Frame) c).isWindow()).count();
        assertEquals(1, windows, "windows below destroyed widgets are destroyed on the display too: the root window is left");
    }

    @Test void toplevelsMinimizeMaximizeAndResizeThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "root.geometry('200x150')",
                "top = tk.Toplevel(root)", "top.geometry('120x80+10+20')",
                "assert top.state() == 'normal' and top.resizable() == (1, 1)",
                "assert root.attributes() == ('-zoomed', 0) and root.winfo_height() == 150",
                "top.state('zoomed')",
                "assert top.state() == 'zoomed' and top.attributes('-zoomed') == 1 and top.attributes() == ('-zoomed', 1)",
                "assert top.geometry() == '320x284+0+0'",   // the 320x320 display less the taskbar and its title bar
                "top.attributes('-zoomed', 0)", "assert top.geometry() == '120x80+10+20'",
                "top.wm_iconify()", "assert top.state() == 'iconic' and not top.winfo_ismapped()",
                "top.deiconify()", "assert top.state() == 'normal' and top.winfo_ismapped()",
                "top.state('iconic')", "top.state('normal')", "assert top.winfo_ismapped()",
                "top.resizable(False, True)", "assert top.resizable() == (0, 1)",
                "top.wm_minsize(60, 40)", "assert top.minsize() == (60, 40)",
                "assert top.maxsize() == (320, 320), 'the display, like Tk'",
                "top.maxsize(100, 70)", "assert top.geometry() == '100x70+10+20'",
                "top.attributes('-topmost', 1, '-alpha', 0.5)", "assert top.attributes('-alpha') == 1.0",
                "for bad in (lambda: top.attributes('-bogus'), lambda: top.state('big'), lambda: root.attributes('-taskbar', 'maybe'),",
                "            lambda: top.attributes('-zoomed', 1, '-alpha')):",
                "    try:", "        bad()", "        raise AssertionError('accepted a bad request')", "    except ValueError:", "        pass",
                "root.state('zoomed')", "assert root.state() == 'zoomed' and root.geometry() == '320x284+0+0'",
                "root.state('normal')", "assert root.geometry().startswith('200x150+')",
                "sizes = []",
                "top.bind('<Configure>', lambda e: sizes.append((e.widget is top, e.type, e.x, e.y, e.width, e.height)))",
                "tk._dispatch(('tk_event', 'screen-a', root._token, top._id, top._bindings['<Configure>'], '<Configure>',",
                "              10, 38, 0, 0, 0, '', 0, top._id, False, 0, 140, 90))",
                "assert sizes == [(True, '<Configure>', 10, 38, 140, 90)], sizes",
                "passed = True", ""));
        host.runUntil("passed");
    }

    /** A filesystem component holding {@code files}, as OC's filesystem API presents it (reads of at most 2048 bytes). */
    static net.mpy.vm.PyModule filesystem(Map<String, byte[]> files) {
        Map<Long, int[]> handles = new HashMap<>();
        Map<Long, byte[]> open = new HashMap<>();
        net.mpy.vm.PyModule component = new net.mpy.vm.PyModule("component", "<host>", null);
        component.ns.put("list", (net.mpy.vm.HostFunction) args -> { PyObj.PyList list = new PyObj.PyList(); list.items.add("fs-1"); return list; });
        component.ns.put("invoke", (net.mpy.vm.HostFunction) args -> {
            String method = (String) args[1];
            switch (method) {
                case "exists": return files.containsKey((String) args[2]);
                case "isDirectory": return false;
                case "size": { byte[] data = files.get((String) args[2]); return data == null ? 0.0 : (double) data.length; }
                case "open": { long handle = handles.size() + 1; handles.put(handle, new int[] {0}); open.put(handle, files.get((String) args[2])); return handle; }
                case "read": {
                    long handle = ((Number) args[2]).longValue(); byte[] data = open.get(handle); int[] at = handles.get(handle);
                    if (at[0] >= data.length) return PyObj.NONE;
                    int n = Math.min(Math.min(2048, ((Number) args[3]).intValue()), data.length - at[0]);
                    byte[] chunk = java.util.Arrays.copyOfRange(data, at[0], at[0] + n); at[0] += n; return new PyObj.Bytes(chunk);
                }
                case "close": return PyObj.NONE;
                default: throw new PyException("ValueError", "unexpected " + method);
            }
        });
        return component;
    }

    static byte[] png(java.awt.image.BufferedImage picture) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, "png", out);
        return out.toByteArray();
    }

    @Test void photoImagesFromDataFilesAndPixels() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 30; y++) for (int x = 0; x < 40; x++) picture.setRGB(x, y, 0xFF000000 | x << 16 | y << 8);
        byte[] png = png(picture);
        Map<String, byte[]> files = new HashMap<>(); files.put("/pics/cat.png", png);
        host.globals.put("component", filesystem(files));
        host.reg("getBootAddress", args -> "fs-1");
        host.globals.put("png", new PyObj.Bytes(png));
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()",
                "img = tk.PhotoImage(data=png)", "assert (img.width(), img.height()) == (40, 30)",
                "assert img.get(39, 29) == (39, 29, 0)",
                "small = tk.PhotoImage(file='/pics/cat.png', fit=(20, 20))", "assert (small.width(), small.height()) == (20, 15)",
                "blank = tk.PhotoImage(width=4, height=2)", "blank.put('{#ff0000 #00ff00} {#0000ff #ffffff}', to=(1, 0))",
                "assert blank.get(2, 0) == (0, 255, 0) and blank.get(1, 1) == (0, 0, 255)",
                "assert blank.transparency_get(0, 0) and not blank.transparency_get(1, 0)",
                "z = blank.zoom(2)", "assert (z.width(), z.height()) == (8, 4)",
                "label = tk.Label(root, image=img, text='hidden')", "label.pack()",
                "assert label['image'] == img.name == str(img)",
                "assert len(set([img.name, small.name, blank.name, z.name])) == 4",
                "c = tk.Canvas(root, width=100, height=60)", "c.pack()",
                "item = c.create_image(10, 10, image=small, anchor='nw')",
                "assert c.bbox(item) == (10, 10, 30, 25)", "assert c.itemcget(item, 'image') == small.name",
                "button = tk.Button(root, image=small.name)", "button.pack()",
                "try:", "    tk.PhotoImage(file='/missing.png')", "    raise AssertionError('opened a missing file')", "except OSError:", "    pass",
                "try:", "    tk.PhotoImage(data=b'not an image')", "    raise AssertionError('decoded garbage')", "except ValueError:", "    pass",
                "z.delete()", "z.delete()",
                "try:", "    z.width() and z.get(0, 0)", "    raise AssertionError('used a deleted image')", "except ValueError:", "    pass",
                // As in Tk: a new image under a taken name replaces the old one; widgets showing a deleted image go blank.
                "old = tk.PhotoImage(name='logo', width=2, height=2)", "new = tk.PhotoImage(name='logo', width=3, height=3)",
                "assert old._id is None and tk.Label(root, image='logo').cget('image') == 'logo'", "new.delete()",
                "shown = tk.PhotoImage(width=5, height=5)", "user = tk.Label(root, image=shown)", "user.pack()", "shown.delete()",
                "user.configure(text='still works')", "assert user['image'] == ''",
                "passed = True", ""));
        host.runUntil("passed");
        TkScene scene = tile.scene();
        assertEquals(3, scene.images.size(), "img, small, blank (z, logo and shown deleted)");
        assertTrue(scene.display.items.stream().anyMatch(i -> i.image != 0 && i.vector != null), "the canvas image item");
        assertEquals(2, scene.display.items.stream().filter(i -> i.image != 0 && i.vector == null).count(), "label and button");
    }

    @Test void photoImageFilesUnderMpyosStreamPastTheVmMemoryLimits() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        java.util.Random random = new java.util.Random(5);
        java.awt.image.BufferedImage noise = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) noise.setRGB(x, y, random.nextInt(0x1000000));
        byte[] big = png(noise);
        assertTrue(big.length > 160_000, "larger than the VM's largest single allocation: " + big.length);
        Map<String, byte[]> files = new HashMap<>(); files.put("/pics/big.png", big);
        host.globals.put("component", filesystem(files));
        // MPYOS's view: /mnt/abc is that disk; relative paths start in /mnt/abc.
        byte[] osfs = MpyCross.compile(String.join("\n",
                "class _Disk:", "    def __init__(self, address):", "        self.address = address",
                "class _Vfs:",
                "    def exists(self, path):", "        return component.invoke('fs-1', 'exists', path[len('/mnt/abc'):])",
                "    def is_directory(self, path):", "        return False",
                "    def _route(self, path):", "        return _Disk('fs-1'), path[len('/mnt/abc'):]",
                "def shared_vfs():", "    return _Vfs()",
                "def _abs(path):", "    return path if path.startswith('/') else '/mnt/abc/' + path", ""), "osfs.py");
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()",
                "big = tk.PhotoImage(file='pics/big.png', fit=(64, 64))", "assert (big.width(), big.height()) == (64, 64)",
                "try:", "    tk.PhotoImage(file='pics/none.png')", "    raise AssertionError('opened a missing file')", "except OSError:", "    pass",
                "passed = True", ""));
        host.vm.setModuleFinder(path -> path.endsWith("osfs") ? osfs : null);
        // The in-game sandbox: 1 MiB of memory, at most 128 Ki elements in one allocation.
        long saved = net.mpy.runtime.Ops.MAX_SEQ_LEN;
        net.mpy.vm.Limits limits = host.vm.limits();
        limits.memoryLimit = 1L << 20; limits.maxSeqLength = (1L << 20) / 8;
        try {
            host.vm.setLimits(limits);
            host.runUntil("passed");
        } finally {
            net.mpy.runtime.Ops.MAX_SEQ_LEN = saved;
        }
        assertEquals(Boolean.TRUE, host.vm.globals.get("passed"), "finished: " + host.vm.isFinished());
        assertEquals(1, tile.scene().images.size());
    }

    @Test void ttkProgressbarsAndScrolledCanvasesThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "from tkinter import ttk", "root = tk.Tk()",
                "v = tk.IntVar(value=20)",
                "pb = ttk.Progressbar(root, length=120, maximum=50, variable=v)", "pb.pack()",
                "assert pb['value'] == 20.0 and pb['maximum'] == 50.0 and pb['mode'] == 'determinate'",
                "v.set(30)", "assert pb['value'] == 30.0",
                "pb.step(25)", "assert pb['value'] == 5.0 and v.get() == 5, (pb['value'], v.get())",
                "pb['value'] = 12", "assert v.get() == 12",
                "pb.step()", "assert v.get() == 13",
                "spin = ttk.Progressbar(root, mode='indeterminate', orient=tk.VERTICAL)", "spin.pack()",
                "spin.start(100)",
                "c = tk.Canvas(root, width=200, height=100, scrollregion=(0, 0, 200, 500))",
                "bar = tk.Scrollbar(root, command=c.yview)", "c.configure(yscrollcommand=bar.set)",
                "c.pack()", "bar.pack()",
                "item = c.create_rectangle(10, 300, 50, 340, fill='red')",
                "assert c['scrollregion'] == '0 0 200 500'",
                "c.yview_moveto(0.5)",
                "assert c.canvasy(20) == 270.0 and c.canvasx(7) == 7.0 and c.canvasy(21, 10) == 270.0",
                "assert c.yview() == (0.5, 0.7), c.yview()",
                "c.yview('scroll', 1, 'units')", "assert c.yview() == (0.52, 0.72), c.yview()",
                "armed = True",
                "while bar.get() != (0.52, 0.72):", "    root.update()",
                "scrolled = True",
                "while spin['value'] < 1:", "    root.update()",
                "stopped = spin['value']", "spin.stop()", "assert spin['value'] == 0.0, 'stop() resets the value, as Tk'",
                "again = ttk.Progressbar(root)", "again.start(1000)", "assert again['value'] == 1.0, 'start() steps at once'", "again.start(10)", "assert again['value'] == 1.0, 'a second start() changes nothing'", "again.destroy()", "again.stop()",
                "assert tk.UNITS == 'units' and tk.PAGES == 'pages' and tk.MOVETO == 'moveto' and tk.SCROLL == 'scroll'",
                "plain = tk.Canvas(root)", "assert plain.canvasx(10.6) == 11.0 and plain.canvasx(-2.5) == -3.0 and plain.canvasx(-15, 10) == -20.0",
                "passed = True", ""));
        host.runUntil("scrolled");
        assertEquals(Boolean.TRUE, host.vm.globals.get("scrolled"), "the scrollbar followed the canvas");
        reobf.mpy4oc.tk.Frame window = (reobf.mpy4oc.tk.Frame) tile.widgets().root().children().get(0);   // the root's window
        assertEquals(2, window.children().stream().filter(w -> w instanceof reobf.mpy4oc.tk.Progressbar).count());
        host.time = 1;
        host.runUntil("passed");
        assertEquals(Boolean.TRUE, host.vm.globals.get("passed"));
        assertTrue(((Number) host.vm.globals.get("stopped")).doubleValue() >= 1, "start() stepped it on a timer");
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
                "clicked = False", "def resized():", "    global clicked", "    root.geometry('280x300')", "    a.grid()", "    clicked = True",
                "button = tk.Button(root, text='resize', command=resized)", "button.place(x=10, y=180)", "armed = True",
                "while not clicked:", "    root.update()", "assert a.winfo_ismapped()", "assert a.grid_info()['column'] == 0",
                "assert overlay.winfo_x() == 125 and overlay.winfo_y() == 145",
                "assert a.winfo_height() == 292", "assert root.grid_bbox() == (0, 0, 280, 300)",
                "a.grid_forget()", "a.grid(row=1)", "assert a.grid_info()['padx'] == 0", "assert a.grid_info()['sticky'] == ''",
                "overlay.place_forget()", "assert overlay.place_info() == {}", "assert overlay.winfo_manager() == ''",
                "overlay.grid(row=2, column=0, columnspan=2, rowspan=2)",
                "assert overlay in root.grid_slaves(column=1)", "assert overlay in root.grid_slaves(row=3)", "passed = True", ""));
        host.runUntil("armed"); byte[] snapshot = host.vm.saveSnapshot(host.registry);
        NBTTagCompound nbt = new NBTTagCompound(); tile.writeToNBT(nbt);
        Host restored = new Host(); TileTkDisplay copy = new TileTkDisplay(); copy.readFromNBT(nbt); restored.tiles.put("screen-a", copy);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        assertEquals(tile.scene(), copy.scene()); restored.click(copy, 7); restored.runUntil("passed");   // the button (1 is the root window)
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
        String token = token(tile);
        // 1 is the root window (its <Motion> binding), 2 the entry.
        host.signal("tk_event", "screen-a", token, 1, 1, "<Motion>", -10, 3, 0, 0, 3, "Player", 0, 2, true);
        host.signal("tk_event", "screen-a", token, 2, 2, "<MouseWheel>", 20, 4, -120, 0, 4, "Player", -120, 2, false);
        host.signal("tk_event", "screen-a", token, 2, 3, "<KeyRelease>", 0, 0, 0, 0x1F600, 2, "Player", 0, 2, false);
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
        TkWidget check = loaded.widgets().get(2); TkRect r = check.bounds(); loaded.widgets().pointerDown(1, r.x + 2, r.y + 2);   // 1 is the root window
        TkTree.Command command = loaded.widgets().pointerUp(1, r.x + 2, r.y + 2);
        restored.signal("tk_command", "screen-a", token(loaded), command.widget, command.callback, "Player");
        restored.runUntil("passed"); assertEquals(100, ((Listbox) loaded.widgets().get(3)).size());
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
        assertEquals(4, calls[0], "claim_free/create/configure/pack each execute once, despite replay");
        assertEquals(4, windows);
        assertEquals(1, tile.scene().display.items.stream().filter(item -> item.text.equals("async")).count(), "the label, in its window");
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
        // Callback 1: both handlers of the item's <Button-1> share one display binding.
        restored.signal("tk_event","screen-a",token(copy),2,1,"<Button-1>",20,25,0,0,0,"Player",0,2,false,1);   // the canvas (1 is the root window)
        restored.runUntil("passed");
    }

    @Test void canvasDashboardInitializesAndRefreshesThroughPython() throws Exception {
        // A 520x320 window with its title bar and the taskbar: a 2x2 display.
        Host host=new Host();host.tiles.put("screen-a",reobf.mpy4oc.tk.TkTestGroups.joined(2,2));
        host.reg("uptime",args -> { host.time += .01; return host.time; });
        String demo=Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkcanvas.py"));
        host.start(demo.replace("root.mainloop()","armed = True\nwhile frames < 3:\n    root.update()\npassed = True\n"));
        host.runUntil("armed");host.time=1;host.runUntil("passed");
    }

    @Test void scrollAndProgressDemosRunThroughPython() throws Exception {
        Host scroll=new Host();scroll.tiles.put("screen-a",new TileTkDisplay());
        String demo=Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkscroll.py"));
        scroll.start(demo.replace("root.mainloop()","c.xview_moveto(0.5)\nc.yview_scroll(2, 'pages')\nwhile xs.get()[0] < 0.4 or ys.get()[0] == 0:\n    root.update()\npassed = True\n"));
        scroll.runUntil("passed");assertEquals(Boolean.TRUE,scroll.vm.globals.get("passed"),"the scrollbars followed the canvas");
        Host progress=new Host();progress.tiles.put("screen-a",new TileTkDisplay());
        progress.reg("uptime",args -> { progress.time += .01; return progress.time; });
        demo=Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkprogress.py"));
        progress.start(demo.replace("root.mainloop()","bar.step(10)\nassert value.get() == 40.0, value.get()\ntoggle()\nwhile spin['value'] < 3:\n    root.update()\ntoggle()\npassed = True\n"));
        progress.runUntil("passed");assertEquals(Boolean.TRUE,progress.vm.globals.get("passed"),"the indeterminate bar ran on its timer");
    }

    @Test void desktopDemoRunsThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        String demo = Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkdesktop.py"));
        host.start(demo.replace("root.mainloop()", String.join("\n",
                "windows = [w for w in root._widgets.values() if w._window and w is not root]",
                "assert len(windows) == 3 and windows[1].resizable() == (0, 0)",
                "windows[0].iconify()",
                "passed = True", "")));
        host.runUntil("passed");
        assertTrue(tile.widgets().taskbarShown(), "the program's window and three more, one minimized: the taskbar lists them");
        assertEquals(4, tile.widgets().render().items.stream().filter(i -> i.part == 4).count(), "a taskbar button per window");
    }

    @Test void ttkDemoRunsThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        String demo = Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkttk.py"));
        host.start(demo.replace("root.mainloop()", String.join("\n",
                "level.set(0.75)", "assert shown.cget('text') == '0.75' and bar['value'] == 0.75",
                "assert check.state() == ('alternate',) and button.winfo_class() == 'TButton'",
                "button.event_generate('<Double-1>')", "assert status.cget('text') == 'Double-clicked TButton'",
                "button.invoke()", "assert clicked.cget('text') == 'Clicks: 1' and status.cget('text') == 'Double-clicked TButton', 'a click does not hide the double click'",
                "open_window()",
                // Everything on each page fits the default 320x240 display, also with the taskbar the window brings.
                "def fits():",
                "    root.update_idletasks()",
                "    for w in list(root._widgets.values()):",
                "        if w._alive and w.winfo_manager() in ('pack', 'grid') and w.winfo_ismapped():",
                "            assert w.winfo_height() >= w.winfo_reqheight() and w.winfo_width() >= w.winfo_reqwidth(), (w.winfo_class(), w.winfo_width(), w.winfo_height(), w.winfo_reqwidth(), w.winfo_reqheight())",
                "fits()",
                "assert not board.winfo_ismapped(), 'the second page is not shown yet'",
                "book.select(1)",
                "fits()",
                "root.update()",
                "assert root.title() == 'ttk: Borders' and classic.cget('labelanchor') == 'n'",
                "board.focus_set()", "board.event_generate('<Up>')", "assert board.coords(dot) == [55.0, 15.0, 65.0, 25.0], board.coords(dot)",
                "book.select(2)", "fits()",
                "count.event_generate('<<Increment>>')", "assert count.get() == '1.5' and said.cget('text') == 'Count: 1.5'",
                "digits.insert(0, '12a')", "assert digits.get() == '' and said.cget('text') == 'Digits only'",
                "digits.insert(0, '12')", "assert digits.get() == '12'",
                "colour.current(2)", "assert colour.get() == 'blue'",
                "theme['menu'].invoke(theme['menu'].index('clam'))", "assert style.theme_use() == 'clam' and theme.cget('text') == 'clam'", "root.update()", "fits()",
                "thememenu.invoke(thememenu.index('default'))", "assert style.theme_use() == 'default' and theme.cget('text') == 'default'",
                "assert root.cget('menu') == str(menubar) and filemenu.entrycget('New*', 'label') == 'New window'",
                "book.select(3)", "fits()",
                "lib = files.get_children()[1]",
                "sort_by_size()", "assert [files.item(i, 'text') for i in files.get_children(lib)] == ['shell.py', 'motd.py']",
                "files.selection_set(files.get_children(lib)[0])", "root.update()", "assert picked.cget('text') == 'shell.py'",
                "assert files.tag_has('big') == ('I002', 'I006')",
                "book.select(4)", "fits()", "root.update()",
                "assert panes.panes() == (str(left), str(right)) and panes.pane(1, 'weight') == 1",
                "panes.sashpos(0, 40)", "root.update()", "assert left.winfo_width() == 40",
                "amount.value = 7", "root.update()", "assert amount.label['text'] == 7",
                "passed = True", "")));
        host.runUntil("passed");
        long grips = tile.widgets().render().items.stream().filter(i -> i.kind == reobf.mpy4oc.tk.TkWidget.Kind.FRAME && i.bounds.width == 2 && i.bounds.height == 2).count();
        assertEquals(6, grips, "the window's sizegrip draws its six dots");
    }

    @Test void configureFiresForTheFirstBindingAndSizeChangesOnly() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()", "root.geometry('320x240')",
                "assert root.minsize() == (1, 1) and root.attributes('-zoomed') == 0", "root.attributes('-zoomed', 1)",
                "c = tk.Canvas(root, width=50, height=40)", "c.pack()",
                "def handler(event):", "    pass",
                "c.bind('<Configure>', handler)", "stage = 1", "bound = True",
                "while stage == 1:", "    root.update()",
                "c.bind('<Configure>', handler)", "c.unbind('<Configure>')", "c.bind('<Configure>', handler, '+')",
                "rebound = True",
                "while stage == 2:", "    root.update()",
                "c.configure(width=80)", "resized = True",
                "while stage == 3:", "    root.update()",
                "passed = True", ""));
        host.runUntil("bound");
        java.util.List<int[]> events = tile.widgets().takeConfigures();
        assertEquals(1, events.size(), "the first binding hears the current size");
        int canvas = events.get(0)[0], width = events.get(0)[3];
        host.vm.globals.put("stage", 2L);
        host.runUntil("rebound");
        assertTrue(tile.widgets().takeConfigures().isEmpty(), "re-binding, unbinding and binding again do not fire it");
        host.vm.globals.put("stage", 3L);
        host.runUntil("resized");
        events = tile.widgets().takeConfigures();
        assertEquals(1, events.size());
        assertEquals(canvas, events.get(0)[0]);
        assertEquals(width + 30, events.get(0)[3], "a real size change does");
        host.vm.globals.put("stage", 4L);
        host.runUntil("passed");
    }

    @Test void ttkWidgetsStatesAndStylesThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n",
                "import tkinter as tk",
                "import tkinter.ttk",
                "from tkinter import ttk",
                "from tkinter.ttk import *",
                "assert ttk is tkinter.ttk and tk.ttk is ttk and Button is ttk.Button",
                "root = tk.Tk()", "root.geometry('320x240')",
                "b = ttk.Button(root, text='hi', style='Big.TButton', cursor='hand2')", "b.pack()",
                "assert isinstance(b, tk.Button) and isinstance(b, ttk.Widget) and ttk.Button is not tk.Button",
                "assert b.winfo_class() == 'TButton' and b.cget('class') == '' and b['style'] == 'Big.TButton' and b.cget('cursor') == 'hand2'",
                "assert ttk.Button(root, class_='Fancy').winfo_class() == 'Fancy'",
                "assert b.cget('default') == 'normal' and b.cget('takefocus') == 'ttk::takefocus' and tk.Button(root).cget('takefocus') == ''",
                "try:", "    b.configure(class_='X')", "    raise AssertionError('class_ is read-only')", "except tk.TclError:", "    pass",
                // state()/instate(), as Tk 8.6
                "assert b.state() == ()",
                "assert b.state(['disabled']) == ('!disabled',) and b.state() == ('disabled',)",
                "assert b.cget('state') == 'normal', 'state() leaves the -state option alone'",
                "assert b.instate(['disabled']) and not b.instate(['!disabled'])",
                "assert b.instate(['disabled'], lambda x: x * 2, 21) == 42",
                "assert b.state(['!disabled', 'user1']) == ('disabled', '!user1') and b.state() == ('user1',)",
                "assert b.state('!user1') == ('user1',)",
                "try:", "    b.state(['bogus'])", "    raise AssertionError('accepted a bad state')", "except tk.TclError as e:", "    assert str(e) == 'Invalid state name bogus'",
                "b.configure(state='disabled')", "assert b.instate(['disabled']) and b.cget('state') == 'disabled'",
                "b.configure(state='normal')",
                "c = ttk.Checkbutton(root, text='c')", "c.pack()",
                "assert c.state() == ('alternate',), 'no variable: the tri-state look'",
                "c.invoke()", "assert c.state() == ('selected',)",
                "v = tk.IntVar()", "assert ttk.Checkbutton(root, variable=v).state() == ()",
                "e = ttk.Entry(root)", "e.pack()",
                "assert e.state(['readonly']) == ('!readonly',) and e.cget('state') == 'normal' and e.winfo_class() == 'TEntry'",
                "e.focus_set()", "assert 'focus' in e.state()",
                "assert e.identify(3, 3) == 'textarea' and e.identify(-1, 3) == ''",
                // padding, extra options
                "f = ttk.Frame(root, padding='3 3 12 12')",
                "assert f.cget('padding') == (3, 3, 12, 12) and ttk.Frame(root, padding=5).cget('padding') == (5,) and ttk.Frame(root).cget('padding') == ''",
                // Tk 8.6: the four sides as given; a 50x20 child sits at (3, 3) in a 65x35 frame.
                "inner = tk.Frame(f, width=50, height=20)", "inner.pack()", "f.pack()", "root.update_idletasks()",
                "assert (f.winfo_reqwidth(), f.winfo_reqheight(), inner.winfo_x(), inner.winfo_y()) == (65, 35, 3, 3), (f.winfo_reqwidth(), f.winfo_reqheight(), inner.winfo_x(), inner.winfo_y())",
                "f.pack_forget()",
                "l = ttk.Label(root, text='x', font=('TkDefaultFont', 12), relief='sunken', compound='left')",
                "assert l.cget('relief') == 'sunken' and l.cget('font') == ('TkDefaultFont', 12) and l.winfo_class() == 'TLabel'",
                // Scale: ttk defaults
                "s = ttk.Scale(root)", "s.pack()",
                "assert s.cget('from') == 0 and s.cget('to') == 1.0 and s.cget('orient') == 'horizontal' and s.cget('length') == 100",
                "assert s.cget('showvalue') is False and s.get() == 0",
                "ranges = []", "s.bind('<<RangeChanged>>', lambda e: ranges.append(e.widget))",
                "s.configure(to=10)", "assert ranges == [s]",
                "s2 = ttk.Scale(root, from_=0, to=100, value=40)", "s2.pack()", "assert s2.get() == 40 and s2['value'] == 40",
                "assert s2.identify(*s2.coords()) == 'slider'",
                "assert abs(s2.get(*s2.coords(25)) - 25) < 1.5",
                // Scrollbar, Progressbar, Separator, Sizegrip
                "sb = ttk.Scrollbar(root)", "sb.pack(side='right', fill='y')", "sb.set(0.2, 0.4)",
                "assert sb.identify(5, 3) == 'uparrow' and sb.identify(5, sb.winfo_height() - 2) == 'downarrow'",
                "classic = tk.Scrollbar(root)", "classic.pack(side='left', fill='y')",
                "assert classic.identify(5, 3) == 'arrow1' and classic.identify(5, -1) == ''",
                "p = ttk.Progressbar(root, value=50, length=100)", "p.pack()",
                "assert p.identify(10, 5) == 'pbar' and p.identify(95, 5) == 'trough' and p.cget('phase') == 0 and p.winfo_class() == 'TProgressbar'",
                "sep = ttk.Separator(root)", "sep.pack(fill='x')",
                "assert sep.winfo_class() == 'TSeparator' and sep.cget('orient') == 'horizontal' and sep.winfo_reqheight() == 2",
                "assert sep.identify(1, 0) == 'separator'",
                "grip = ttk.Sizegrip(root)", "grip.pack()",
                "assert grip.winfo_class() == 'TSizegrip' and grip.winfo_reqwidth() == 15 and grip.identify(3, 3) == 'sizegrip'",
                // classic winfo_class
                "assert tk.Label(root).winfo_class() == 'Label' and root.winfo_class() == 'Tk' and tk.Frame(root).winfo_class() == 'Frame'",
                // Style, as Tk answers (golden values from CPython 3.12 + Tk 8.6)
                "st = ttk.Style()",
                "assert st.theme_use() == 'default' and set(st.theme_names()) == {'clam', 'alt', 'default', 'classic'}",
                "assert st.configure('X.TButton', foreground='red', padding=5) is None",
                "assert st.configure('X.TButton') == {'padding': 5, 'foreground': 'red'}",
                "assert st.configure('X.TButton', 'foreground') == 'red'",
                "assert st.map('X.TButton', background=[('pressed', 'blue'), ('active', '!disabled', 'green')]) == {}",
                "assert st.map('X.TButton') == {'background': [('pressed', 'blue'), ('active', '!disabled', 'green')]}",
                "assert st.map('X.TButton', 'background') == [('pressed', 'blue'), ('active', '!disabled', 'green')]",
                "assert st.lookup('X.TButton', 'background', ['pressed']) == 'blue' and st.lookup('X.TButton', 'foreground') == 'red'",
                "assert st.lookup('X.TButton', 'nothing', None, 'dflt') == 'dflt' and st.lookup('X.TButton', 'nothing') == ''",
                "assert st.lookup('Big.X.TButton', 'foreground') == 'red', 'a style inherits from the name after its first dot'",
                "assert st.lookup('X.TButton', 'background', ['active']) == 'green' and st.lookup('X.TButton', 'background', ['active', 'disabled']) == '#d9d9d9'",   // the built-in '.' map
                "st.configure('.', background='grey')", "assert st.lookup('X.TButton', 'background') == 'grey'",
                "assert st.theme_use('clam') is None and st.theme_use() == 'clam'",
                "assert st.lookup('X.TButton', 'foreground') == 'black', 'a theme does not see the settings of another (clam has its own: black)'",
                "assert st.theme_create('mine', 'clam', {'TButton': {'configure': {'padding': 9}}}) is None",
                "assert 'mine' in st.theme_names()",
                "changed = []", "b.bind('<<ThemeChanged>>', lambda e: changed.append(1))",
                "st.theme_use('mine')", "assert st.lookup('TButton', 'padding') == 9 and st.configure('TButton') == {'padding': 9} and changed == []",
                "while not changed:", "    root.update()",
                "assert changed == [1], 'queued, as Tk queues <<ThemeChanged>>'",
                "assert st.element_create('X.border', 'from', 'default') is None and 'X.border' in st.element_names()",
                "for bad in (lambda: st.theme_use('nope'), lambda: st.element_create('Y', 'image', 'img'), lambda: st.theme_create('mine')):",
                "    try:", "        bad()", "        raise AssertionError('accepted')", "    except tk.TclError:", "        pass",
                "st.layout('Plain.TButton', [('Button.label', {'sticky': 'nswe'})])",
                "assert st.layout('Plain.TButton') == [('Button.label', {'sticky': 'nswe'})] and st.layout('Nothing.TButton')[0][0] == 'Button.border'",
                "try:", "    st.layout('Zzz')", "    raise AssertionError('no layout')", "except tk.TclError as e:", "    assert str(e) == 'Layout Zzz not found'",
                "assert st.layout('Big.Plain.TButton') == [('Button.label', {'sticky': 'nswe'})], 'the style chain'",
                "st.theme_use('default')",
                "passed = True", ""));
        host.runUntil("passed");
    }

    @Test void virtualEventsTracesAndTheNewSequencesThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "root = tk.Tk()",
                "f = tk.Frame(root)", "f.pack()", "b = tk.Button(f, text='b')", "b.pack()",
                "seen = []",
                "b.bind('<<Ping>>', lambda e: seen.append(('b', e.type, e.x)))",
                "b.bind('<<Ping>>', lambda e: seen.append(('b2',)), '+')",
                "root.bind('<<Ping>>', lambda e: seen.append(('root', e.widget is b)))",
                "b.event_generate('<<Ping>>', x=7)",
                "assert seen == [('b', '<<Ping>>', 7), ('b2',), ('root', True)], seen",
                "seen.clear()", "b.bind('<<Stop>>', lambda e: 'break')", "root.bind('<<Stop>>', lambda e: seen.append('root'))",
                "b.event_generate('<<Stop>>')", "assert seen == [], \"'break' stops the window's binding\"",
                "b.unbind('<<Ping>>')", "seen.clear()", "b.event_generate('<<Ping>>')", "assert seen == [('root', True)]",
                "clicks = []", "b.bind('<Button-1>', lambda e: clicks.append(e.num))", "b.event_generate('<Button-1>')", "assert clicks == [1]",
                "for bad in (lambda: b.event_generate('<Nope>'), lambda: b.bind('<Nope>', print), lambda: b.event_generate('<<X>>', bogus=1)):",
                "    try:", "        bad()", "        raise AssertionError('accepted')", "    except ValueError:", "        pass",
                // traces (CPython: callback(name, '', 'write'))
                "v = tk.StringVar()", "assert str(v).startswith('PY_VAR')",
                "log = []", "name = v.trace_add('write', lambda *a: log.append(a))",
                "assert v.trace_info() == [(('write',), name)]",
                "v.set('x')", "assert log == [(str(v), '', 'write')]",
                "def again(*a):", "    log.append('again')", "    v.set('y')",
                "v.trace_add(['write'], again)", "log.clear()", "v.set('z')",
                "assert log == ['again', (str(v), '', 'write')] and v.get() == 'y', 'the newest trace first; traces do not fire inside traces'",
                "v.trace_remove('write', name)", "assert len(v.trace_info()) == 1",
                "old = []", "cb = v.trace_variable('w', lambda *a: old.append(a[2]))", "v.set('q')", "assert old == ['w']",
                "assert v.trace_vinfo()[0] == ('w', cb) and len(v.trace_vinfo()) == 2, 'one list: the trace_add trace shows too'",
                "v.trace_vdelete('w', cb)", "assert len(v.trace_vinfo()) == 1",
                "reads = []", "n = tk.IntVar(value=3)", "n.trace_add('read', lambda *a: reads.append(a[2]))", "assert n.get() == 3 and reads == ['read']",
                "try:", "    v.trace_add('bogus', print)", "    raise AssertionError('accepted')", "except ValueError:", "    pass",
                // register / after_idle / focus alias
                "cmd = root.register(lambda value: value)", "assert isinstance(cmd, str) and root._commands[cmd]('ok') == 'ok'",
                "ran = []", "root.after_idle(ran.append, 1)",
                "while not ran:", "    root.update()",
                "e = tk.Entry(root)", "e.pack()", "e.focus()",
                // arrows and double clicks, as the display sends them
                "keys = []", "e.bind('<Up>', lambda ev: keys.append(ev.keysym))", "e.bind('<Key-Next>', lambda ev: keys.append(ev.keysym))",
                "doubles = []", "b.bind('<Double-1>', lambda ev: doubles.append((ev.type, ev.num)))",
                "e.configure(takefocus=0)", "assert e.cget('takefocus') == 0",
                "eid, bid = e._id, b._id",
                "up, nxt, dbl = e._bindings['<Up>'], e._bindings['<Next>'], b._bindings['<Double-Button-1>']",
                "armed = True",
                "while len(keys) < 2 or not doubles:", "    root.update()",
                "assert keys == ['Up', 'Next'] and doubles == [('<Double-Button-1>', 1)], (keys, doubles)",
                "passed = True", ""));
        host.runUntil("armed");
        String token = token(tile);
        java.util.function.Function<String, Integer> id = name -> ((Number) host.vm.globals.get(name)).intValue();
        int eid = id.apply("eid"), bid = id.apply("bid");
        host.signal("tk_event", "screen-a", token, eid, id.apply("up"), "<Up>", 0, 0, 200, 0, 0, "Player", 0, eid, false, 0);
        host.signal("tk_event", "screen-a", token, eid, id.apply("nxt"), "<Next>", 0, 0, 209, 0, 0, "Player", 0, eid, false, 0);
        host.signal("tk_event", "screen-a", token, bid, id.apply("dbl"), "<Double-Button-1>", 3, 3, 0, 0, 0, "Player", 0, bid, false, 0);
        host.runUntil("passed");
    }

    /** Traces, event_generate and bindings as CPython 3.12 + Tk 8.6 behave (golden values from WSL). */
    @Test void tracesEventsAndBindingsMatchTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                root = tk.Tk()
                # Traces: one list for both forms, the newest first, modes in Tcl's order.
                v = tk.StringVar()
                log = []
                a = v.trace_add(('write', 'read'), lambda *x: log.append(('a',) + x))
                b = v.trace_variable('wr', lambda *x: log.append(('b',) + x))
                assert v.trace_info() == [(('read', 'write'), b), (('read', 'write'), a)], v.trace_info()
                assert v.trace_vinfo() == [('rw', b), ('rw', a)]
                v.set('q')
                v.get()
                assert log == [('b', str(v), '', 'w'), ('a', str(v), '', 'write'), ('b', str(v), '', 'r'), ('a', str(v), '', 'read')], log
                v.trace_remove('write', a)
                assert len(v.trace_info()) == 2, 'only an exact set of modes removes a trace'
                v.trace_remove(('read', 'write'), a)
                v.trace_remove(('write', 'read'), b)
                assert v.trace_info() == [], 'trace_remove takes a trace_variable trace too'
                try:
                    v.trace_remove('write', a)
                    raise AssertionError('removed twice')
                except tk.TclError as error:
                    assert str(error) == "can't delete Tcl command"
                c = v.trace_variable('a', print)
                assert v.trace_info() == [(('array',), c)] and v.trace_vinfo() == [('a', c)]
                v.trace_vdelete('a', c)
                # A trace removed by a newer one in the same round does not run.
                w = tk.IntVar()
                ran = []
                first = w.trace_add('write', lambda *x: ran.append('first'))
                def second(*x):
                    ran.append('second')
                    w.trace_remove('write', first)
                w.trace_add('write', second)
                w.set(1)
                assert ran == ['second'], ran
                # A failing trace is reported; the others and the widget's command still run.
                n = tk.IntVar()
                out = []
                n.trace_add('write', lambda *x: out.append('older'))
                def boom(*x):
                    raise RuntimeError('boom')
                n.trace_add('write', boom)
                n.set(3)
                assert out == ['older'] and n.get() == 3
                check = tk.Checkbutton(root, variable=n, command=lambda: out.append('command'))
                check.pack()
                check.invoke()
                assert out[-1] == 'command', out

                # event_generate: when=, Tk-only fields, key events.
                f = tk.Frame(root)
                f.pack()
                seen = []
                f.bind('<<Done>>', lambda e: seen.append('done'))
                f.event_generate('<<Done>>', when='tail', rootx=5, time=9, serial=1)
                assert seen == [], 'when=tail runs after the queued events'
                while not seen:
                    root.update()
                try:
                    f.event_generate('<<Done>>', when='later')
                    raise AssertionError('accepted')
                except ValueError:
                    pass
                e = tk.Entry(root)
                e.pack()
                keys = []
                e.bind('<Key>', lambda ev: keys.append(('key', ev.keysym, ev.char)))
                e.bind('<Up>', lambda ev: keys.append(('up', ev.keysym, ev.char, ev.keycode)))
                e.bind('<Return>', lambda ev: keys.append(('ret', ev.keysym, ev.char)))
                e.event_generate('<Up>')
                e.event_generate('<Return>')
                e.event_generate('<Key>', keysym='Up')
                e.event_generate('<Key-space>')
                e.event_generate('<Tab>')
                e.event_generate('<Key>', keysym='a')
                assert keys == [('up', 'Up', '', 200), ('ret', 'Return', '\\r'), ('up', 'Up', '', 200),
                                ('key', 'space', ' '), ('key', 'Tab', '\\t'), ('key', 'a', 'a')], keys

                # 'break' from a handler added with '+' stops the rest, the window's included.
                canvas = tk.Canvas(root, width=40, height=40)
                canvas.pack()
                order = []
                canvas.bind('<Button-1>', lambda ev: (order.append('first'), 'break')[1])
                canvas.bind('<Button-1>', lambda ev: order.append('second'), '+')
                root.bind('<Button-1>', lambda ev: order.append('root'))
                canvas.event_generate('<Button-1>')
                assert order == ['first'], order
                # A failing handler is reported; the next one runs.
                failed = []
                canvas.bind('<<Fail>>', lambda ev: 1 / 0)
                canvas.bind('<<Fail>>', lambda ev: failed.append('next'), '+')
                canvas.event_generate('<<Fail>>')
                assert failed == ['next']

                # unbind(sequence, funcid) removes that handler only.
                g1 = f.bind('<<X>>', lambda ev: seen.append('x1'))
                g2 = f.bind('<<X>>', lambda ev: seen.append('x2'), '+')
                assert g1 != g2
                f.unbind('<<X>>', g2)
                seen.clear()
                f.event_generate('<<X>>')
                assert seen == ['x1'], seen
                try:
                    f.unbind('<<X>>', 'bogus')
                    raise AssertionError('accepted')
                except tk.TclError as error:
                    assert str(error) == "can't delete Tcl command"
                h1 = canvas.bind('<Motion>', lambda ev: seen.append('m1'))
                h2 = canvas.bind('<Motion>', lambda ev: seen.append('m2'), '+')
                canvas.unbind('<Motion>', h1)
                seen.clear()
                canvas.event_generate('<Motion>')
                assert seen == ['m2'], seen
                canvas.unbind('<Motion>', h2)
                assert '<Motion>' not in canvas._bindings, 'the last handler gone: the display forgets the binding'
                t1 = canvas.tag_bind('all', '<Button-3>', print)
                t2 = canvas.tag_bind('all', '<Button-3>', print, '+')
                canvas.tag_unbind('all', '<Button-3>', t1)
                assert len(root._callbacks[canvas._tag_bindings[('all', '<Button-3>')]].entries) == 1
                cmd = root.register(print)
                root.deletecommand(cmd)
                try:
                    root.deletecommand(cmd)
                    raise AssertionError('deleted twice')
                except tk.TclError:
                    pass
                passed = True
                """);
        host.runUntil("passed");
        assertEquals(Boolean.TRUE, host.vm.globals.get("passed"));
    }

    /** ttk's state flags, -state, invoke, Scale and Style as CPython 3.12 + Tk 8.6 behave (golden values from WSL). */
    @Test void ttkStatesStylesAndScaleMatchTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                root.geometry('320x240')
                # invoke() follows the disabled flag, not the -state option.
                called = []
                b = ttk.Button(root, command=lambda: called.append(1) or 'ok')
                b.state(['disabled'])
                assert b.invoke() == '' and called == []
                b.state(['!disabled'])
                assert b.invoke() == 'ok' and called == [1]
                d = ttk.Button(root, command=lambda: called.append(2), state='disabled')
                d.state(['!disabled'])
                d.invoke()
                assert called == [1, 2]
                cb = ttk.Checkbutton(root, command=lambda: called.append(3))
                cb.state(['disabled'])
                assert cb.invoke() == '' and called == [1, 2] and cb.state() == ('disabled', 'alternate')
                # 'selected' is a flag only: the variable and the other radiobuttons keep theirs.
                v = tk.IntVar()
                c1 = ttk.Checkbutton(root, variable=v)
                assert c1.state(['selected']) == ('!selected',) and v.get() == 0 and c1.instate(['selected'])
                s = tk.StringVar(value='a')
                r1 = ttk.Radiobutton(root, variable=s, value='a')
                r2 = ttk.Radiobutton(root, variable=s, value='b')
                r2.state(['selected'])
                assert s.get() == 'a' and r1.state() == ('selected',) and r2.state() == ('selected',)
                v.set(1)
                assert c1.state() == ('selected',)
                v.set(0)
                assert c1.state() == (), 'a new value decides again'
                c2 = ttk.Checkbutton(root)
                c2.configure(variable=tk.IntVar(value=1))
                assert c2.state() == ('selected',) and c2.instate(['!alternate']), 'a variable ends the tri-state look'
                # Tk's bit order and names.
                bb = ttk.Button(root)
                assert bb.state(['user1', 'user3']) == ('!user3', '!user1')
                assert bb.state(['focus', 'pressed', 'active']) == ('!active', '!focus', '!pressed')
                assert bb.state(['reserved1']) == ('!reserved1',)
                for bad in ('user4', 'user6'):
                    try:
                        bb.state([bad])
                        raise AssertionError(bad)
                    except tk.TclError as error:
                        assert str(error) == 'Invalid state name ' + bad
                # The -state option sets one of disabled/readonly/active and clears the others.
                x = ttk.Button(root, state='active')
                assert x.state() == ('active',)
                x.configure(state='normal')
                assert x.state() == ()
                x.state(['disabled', 'readonly'])
                x.configure(state='active')
                assert x.state() == ('active',)
                x.configure(state='foo')
                assert x.state() == () and x.cget('state') == 'foo'
                # A disabled Frame does not disable what is in it; it has no -state option.
                fr = ttk.Frame(root)
                fr.pack()
                inner = ttk.Button(fr, command=lambda: called.append(9))
                inner.pack()
                fr.state(['disabled'])
                assert fr.state() == ('disabled',) and inner.state() == () and fr._call('flags', fr._id) & 1 == 0
                inner.invoke()
                assert called[-1] == 9
                for make in (lambda: ttk.Frame(root, state='disabled'), lambda: ttk.Separator(root).configure(state='disabled'),
                             lambda: ttk.Progressbar(root).cget('state'), lambda: ttk.Scrollbar(root).configure(state='normal')):
                    try:
                        make()
                        raise AssertionError('accepted -state')
                    except tk.TclError as error:
                        assert str(error) == 'unknown option "-state"', str(error)
                # takefocus defaults per class.
                for cls in (ttk.Label, ttk.Frame, ttk.Separator, ttk.Sizegrip, ttk.Scrollbar, ttk.Progressbar):
                    assert cls(root).cget('takefocus') == '', cls
                for cls in (ttk.Button, ttk.Checkbutton, ttk.Radiobutton, ttk.Entry, ttk.Scale):
                    assert cls(root).cget('takefocus') == 'ttk::takefocus', cls

                # Scale: variable= wins over value=; set() calls command at once; value= never does.
                dv = tk.DoubleVar(value=0.2)
                traces = []
                dv.trace_add('write', lambda *a: traces.append(1))
                sc = ttk.Scale(root, variable=dv, value=0.7)
                assert dv.get() == 0.2 and sc.get() == 0.2 and traces == []
                got = []
                sc2 = ttk.Scale(root, command=lambda x: got.append(x))
                sc2.set(0.5)
                assert got == ['0.5']
                sc2.configure(value=0.3)
                assert got == ['0.5'] and sc2.get() == 0.3
                sc3 = ttk.Scale(root, value=0.5, state='disabled')
                assert sc3.get() == 0.5
                sc3.set(0.9)
                assert sc3.get() == 0.5, 'set() does nothing while disabled'
                sc3.configure(value=0.1)
                assert sc3.get() == 0.1, 'value= still applies'
                sc4 = ttk.Scale(root, from_=0, to=10)
                sc4.set(20)
                assert sc4.get() == 10

                # Style: settings belong to the theme in use; only layouts fall back to the parent.
                st = ttk.Style()
                st.configure('X.TButton', foreground='red', myopt='x')
                st.map('X.TButton', foreground=[('!disabled', 'blue')])
                assert st.configure('X.TButton', 'foreground') == 'red', 'the setting, not the map'
                assert st.configure('Big.X.TButton', 'foreground') == '' and st.configure('Nothing.TButton') is None
                assert st.lookup('Big.X.TButton', 'foreground') == 'blue', 'lookup: the maps along the chain first'
                st.theme_create('kid', 'default')
                st.theme_use('kid')
                assert st.lookup('X.TButton', 'myopt') == '' and st.configure('X.TButton') is None and st.map('X.TButton', 'foreground') == []
                st.theme_use('default')
                assert st.lookup('X.TButton', 'myopt') == 'x'
                assert st.layout('Plain2', [('Button.label', {'sticky': 'nswe'})]) == []
                assert st.layout('A.Plain2') == [('Button.label', {'sticky': 'nswe'})]
                st.theme_use('kid')
                assert st.layout('A.Plain2') == [('Button.label', {'sticky': 'nswe'})], 'a layout comes from the parent theme'
                st.theme_use('default')
                st.layout('Gone', '')
                assert st.layout('Gone') == [('null', {'sticky': 'nswe'})]
                st.element_create('Q.border', 'from', 'default')
                assert 'Q.border' in st.element_names()
                st.theme_use('kid')
                assert 'Q.border' not in st.element_names(), 'elements belong to their theme'
                st.theme_use('default')
                for bad in (lambda: st.theme_create('t3', parent='nope'), lambda: st.theme_settings('nope', {}),
                            lambda: st.element_create('Q', 'from', 'nope')):
                    try:
                        bad()
                        raise AssertionError('accepted')
                    except tk.TclError as error:
                        assert str(error) == 'theme "nope" doesn\\'t exist', str(error)
                assert st.theme_settings('default', {'TButton': {'bogus': {}}}) is None
                # <<ThemeChanged>>: queued, to every widget, each running its window's binding too.
                changed = []
                bb.bind('<<ThemeChanged>>', lambda e: changed.append('bb'))
                root.bind('<<ThemeChanged>>', lambda e: changed.append('root'))
                st.theme_use('clam')
                assert changed == []
                while not changed:
                    root.update()
                assert changed.count('bb') == 1 and changed.count('root') > 10, changed
                # Each Tk() has its own themes, as each has its own Tcl interpreter.
                st.theme_create('dark', parent='clam')
                st.theme_use('dark')
                root.destroy()
                root = tk.Tk()
                st2 = ttk.Style()
                assert st2.theme_use() == 'default' and st2.theme_names() == ('clam', 'alt', 'default', 'classic')
                st2.theme_create('dark', parent='clam')
                passed = True
                """);
        host.runUntil("passed");
        assertEquals(Boolean.TRUE, host.vm.globals.get("passed"));
    }

    /** -relief and four-sided -padding through Python, answering as Tk 8.6 does. */
    @Test void reliefAndPaddingThroughPython() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                label = tk.Label(root, text='x', relief=tk.GROOVE, bd=2)
                assert label.cget('relief') == 'groove' and tk.Label(root).cget('relief') == 'flat'
                assert tk.Button(root).cget('relief') == 'raised' and tk.Entry(root).cget('relief') == 'sunken'
                try:
                    tk.Frame(root, relief='bogus')
                    raise AssertionError('a bad relief was accepted')
                except tk.TclError as error:
                    assert str(error) == 'bad relief "bogus": must be flat, groove, raised, ridge, solid, or sunken', str(error)
                try:
                    tk.Frame(root, padding=5)
                    raise AssertionError('a classic Frame took -padding')
                except tk.TclError as error:
                    assert str(error) == 'unknown option "-padding"', str(error)
                hello = ttk.Label(root, text='Hello')
                assert hello.cget('relief') == '' and hello.winfo_reqwidth() == 42, hello.winfo_reqwidth()
                hello.configure(relief='sunken', padding=5)
                assert hello.cget('relief') == 'sunken' and hello.cget('padding') == (5,)
                assert (hello.winfo_reqwidth(), hello.winfo_reqheight()) == (52, 28), (hello.winfo_reqwidth(), hello.winfo_reqheight())
                frame = ttk.Frame(root, padding=(1, 2, 3, 4), borderwidth=2, relief='raised', width=100, height=60)
                child = tk.Frame(frame, width=50, height=20)
                child.place(x=0, y=0)
                frame.pack()
                root.update_idletasks()
                assert (child.winfo_x(), child.winfo_y()) == (3, 4), (child.winfo_x(), child.winfo_y())
                passed = True
                """);
        host.runUntil("passed");
    }

    /** Path names (str(), nametowidget, name=) and LabelFrame / ttk.Labelframe, answering as CPython 3.12 + Tk 8.6. */
    @Test void pathNamesAndLabelframesThroughPython() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                frame = tk.Frame(root)
                first, second = tk.Label(frame), tk.Label(frame)
                assert (str(root), str(frame), str(first), str(second)) == ('.', '.!frame', '.!frame.!label', '.!frame.!label2')
                assert root.nametowidget('.!frame.!label2') is second and frame.nametowidget('!label') is first
                assert root.nametowidget(frame) is frame and first.winfo_name() == '!label'
                named = tk.Button(frame, name='ok')
                assert str(named) == '.!frame.ok' and frame.children['ok'] is named
                named.destroy()
                assert 'ok' not in frame.children and list(frame.children) == ['!label', '!label2']
                window = tk.Toplevel(root)
                assert str(window) == '.!toplevel' and str(tk.Label(window)) == '.!toplevel.!label'
                window.destroy()
                lf = tk.LabelFrame(root, text='Title')
                assert str(lf) == '.!labelframe' and lf.winfo_class() == 'Labelframe'
                values = (lf.cget('labelanchor'), lf.cget('text'), lf.cget('labelwidget'), lf.cget('relief'), lf.cget('borderwidth'))
                assert values == ('nw', 'Title', '', 'groove', 2), values
                inner = tk.Frame(lf, width=50, height=20)
                inner.pack()
                lf.pack()
                root.update_idletasks()
                assert (inner.winfo_x(), inner.winfo_y(), lf.winfo_reqwidth()) == (2, 18, 54), (inner.winfo_x(), inner.winfo_y(), lf.winfo_reqwidth())
                label = tk.Label(lf, text='L')
                lf.configure(labelwidget=label)
                assert lf.cget('labelwidget') == '.!labelframe.!label' and label.winfo_manager() == 'labelframe'
                lf.configure(labelwidget='')
                assert lf.cget('labelwidget') == '' and label.winfo_manager() == ''
                lf['labelwidget'] = '.!labelframe.!label'
                assert lf['labelwidget'] == str(label)
                def error(call):
                    try:
                        call()
                    except tk.TclError as e:
                        return str(e)
                    raise AssertionError('no error')
                outside = tk.Label(root)
                assert error(lambda: lf.configure(labelwidget=outside)) == "can't use .!label as label in this frame"
                assert error(lambda: lf.configure(labelwidget='.nonexist')) == 'bad window path name ".nonexist"'
                assert error(lambda: lf.configure(labelanchor='bogus')) == 'bad labelanchor "bogus": must be e, en, es, n, ne, nw, s, se, sw, w, wn, or ws'
                themed = ttk.Labelframe(root, text='T', padding=5)
                assert ttk.LabelFrame is ttk.Labelframe and isinstance(themed, tk.LabelFrame) and themed.winfo_class() == 'TLabelframe'
                assert str(themed) == '.!labelframe2' and themed.cget('relief') == '' and themed.cget('padding') == (5,)
                assert error(lambda: themed.configure(labelanchor='bogus')) == 'Bad label anchor specification bogus'
                assert error(lambda: themed.configure(labelwidget=outside)) == "can't add .!label as slave of .!labelframe2"
                child = ttk.Frame(themed, width=50, height=20)
                child.pack()
                themed.pack()
                root.update_idletasks()
                assert (child.winfo_x(), child.winfo_y()) == (7, 23), (child.winfo_x(), child.winfo_y())
                passed = True
                """);
        host.runUntil("passed");
    }

    /** ttk.Notebook through Python: return values, error strings and <<NotebookTabChanged>> as CPython 3.12 + Tk 8.6 give them. */
    @Test void notebookThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                events = []
                nb = ttk.Notebook(root)
                nb.bind('<<NotebookTabChanged>>', lambda e: events.append(('nb', nb.index('current'))))
                root.bind('<<NotebookTabChanged>>', lambda e: events.append(('root', str(e.widget))))
                def error(call):
                    try:
                        call()
                    except tk.TclError as e:
                        return str(e)
                    raise AssertionError('no error')
                f1 = ttk.Frame(nb, width=100, height=60)
                f2 = ttk.Frame(nb, width=80, height=20)
                f3 = ttk.Frame(nb, width=10, height=60)
                assert nb.add(f1, text='One') is None
                assert events == [] and nb.select() == '.!notebook.!frame', 'queued: delivered by the event loop'
                root.update()
                assert events == [('nb', 0), ('root', '.!notebook')], events
                del events[:]
                nb.add(f2, text='Two')
                nb.add(f3, text='Three')
                root.update()
                assert events == [], 'adding more tabs changes nothing'
                assert nb.select(1) == '' and events == []
                root.update()
                assert events == [('nb', 1), ('root', '.!notebook')], events
                del events[:]
                nb.select(1)
                root.update()
                assert events == [], 'the selected tab again'
                assert nb.tabs() == (str(f1), str(f2), str(f3)) and nb.index('end') == 3 and nb.index('current') == 1
                assert (nb.index(f2), nb.index(str(f2)), nb.index(1), nb.index('1')) == (1, 1, 1, 1)
                assert nb.tab(0) == {'padding': [0], 'sticky': 'nsew', 'state': 'normal', 'text': 'One', 'image': '', 'compound': '', 'underline': -1}, nb.tab(0)
                assert (nb.tab(0, 'text'), nb.tab(0, 'padding'), nb.tab(f1, 'sticky'), nb.tab('current', 'text')) == ('One', (0,), 'nsew', 'Two')
                assert nb.tab(0, text='Uno') == {} and nb.tab(0, 'text') == 'Uno'
                nb.tab(2, padding=(1, 2))
                assert nb.tab(2)['padding'] == [1, 2] and nb.tab(2, 'padding') == (1, 2)
                assert nb.add(f1, text='One') is None and nb.tabs()[0] == str(f1), 'add() of a page reconfigures it in place'
                nb.insert(0, f2)
                assert nb.tabs() == (str(f2), str(f1), str(f3)) and nb.select() == str(f2), 'the selection follows the page'
                nb.insert('end', f2)
                assert nb.tabs() == (str(f1), str(f3), str(f2))
                nb.insert(1, f2)
                assert nb.tabs() == (str(f1), str(f2), str(f3))
                root.update()
                del events[:]
                nb.hide(1)
                assert nb.tab(1, 'state') == 'hidden' and nb.index('end') == 3 and f2.winfo_manager() == 'notebook'
                root.update()
                assert events == [('nb', 2), ('root', '.!notebook')] and nb.select() == str(f3), events
                nb.add(f2)
                assert nb.tab(1, 'state') == 'normal'
                nb.tab(0, state='disabled')
                assert nb.select(0) == '' and nb.select() == str(f3), 'a disabled tab cannot be selected'
                nb.tab(0, state='normal')
                nb.forget(0)
                assert nb.tabs() == (str(f2), str(f3)) and f1.winfo_manager() == '' and f1.winfo_exists()
                assert error(lambda: nb.index(f1)) == '.!notebook.!frame is not managed by .!notebook'
                assert error(lambda: nb.index(5)) == 'Slave index 5 out of bounds'
                assert error(lambda: nb.tab(-1)) == 'Slave index -1 out of bounds'
                assert error(lambda: nb.tab('foo')) == 'Invalid slave specification foo'
                assert error(lambda: nb.select('end')) == 'Invalid slave specification end'
                assert error(lambda: nb.index('@a,b')) == 'Invalid slave specification @a,b'
                assert error(lambda: nb.tab(0, 'bogus')) == 'unknown option "-bogus"'
                assert error(lambda: nb.add(f2, bogus=1)) == 'unknown option "-bogus"'
                assert error(lambda: nb.tab(0, state='bogus')) == 'bad state "bogus": must be normal, disabled, or hidden'
                assert error(lambda: nb.tab(0, sticky='q')) == 'Bad -sticky specification q'
                other = ttk.Frame(root)
                assert error(lambda: nb.add(other)) == "can't add .!frame as slave of .!notebook"
                empty = ttk.Notebook(root)
                assert empty.select() == '' and empty.tabs() == () and empty.index('end') == 0
                assert error(lambda: empty.tab('current')) == "tab 'current' not found"
                assert error(lambda: empty.index('current')) == 'expected integer but got ""'
                nb.pack()
                root.update_idletasks()
                assert nb.identify(5, 5) == 'focus' and nb.index('@5,5') == 0 and nb.identify(5, 200) == ''
                assert error(lambda: nb.index('@5,500')) == 'expected integer but got ""'
                assert nb.winfo_class() == 'TNotebook' and nb.cget('takefocus') == 'ttk::takefocus' and nb.enable_traversal() is None
                # Destroying the selected page selects another; packing a page elsewhere takes its tab.
                root.update()
                del events[:]
                f3.destroy()
                root.update()
                assert nb.select() == str(f2) and len(events) == 2, (nb.select(), events)
                f4 = ttk.Frame(nb)
                nb.add(f4, text='Four')
                f4.pack()
                assert nb.tabs() == (str(f2),) and f4.winfo_manager() == 'pack'
                f3b = ttk.Frame(nb, width=30, height=30)
                nb.add(f3b, text='Back')
                root.update()
                del events[:]
                NB_ID = nb._id
                ready = True
                while len(events) < 2:
                    root.update()
                    computer._yield_tick()
                assert events == [('nb', 1), ('root', '.!notebook')] and nb.select() == str(f3b), events
                passed = True
                """);
        host.runUntil("ready");
        TkWidget book = tile.widgets().get(((Number) host.vm.globals.get("NB_ID")).intValue());
        TkRect b = book.bounds();
        tile.widgets().pointerDown(1, b.x + 36 + 10, b.y + 8);   // the second tab: 'Two' is 36 px wide
        tile.widgets().pointerUp(1, b.x + 36 + 10, b.y + 8);
        host.runUntil("passed");
    }

    /**
     * A Host running the real MPYOS shell.py (with the real fs, osfs and event libraries and the
     * architecture's pullSignal) over an in-memory /bin, with threads given a share of each step.
     */
    private static Host shellHost(String program, TileTkDisplay... displays) throws Exception {
        Host host = new Host(); host.stepBudget = 300; host.bootstrap = true;
        char name = 'a';
        for (TileTkDisplay display : displays) host.tiles.put("screen-" + name++, display);
        Map<String, byte[]> modules = new HashMap<>();
        for (String lib : new String[] {"shell", "fs", "osfs", "ospath", "event"})
            modules.put(lib, MpyCross.compile(Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/lib/" + lib + ".py")), lib + ".py"));
        host.start(String.join("\n",
                "import tkinter as tk",
                "import _program",
                "import threading",
                "class FakeVfs:",
                "    def __init__(self):",
                "        self.files = {}",
                "        self.dirs = ['/', '/home']",
                "    def exists(self, path):",
                "        return path in self.files or path in self.dirs",
                "    def is_directory(self, path):",
                "        return path in self.dirs",
                "    def read_file(self, path):",
                "        return self.files.get(path)",
                "    def read_bytes(self, path):",
                "        data = self.files.get(path)",
                "        return None if data is None else data.encode('utf-8')",
                "    def refresh(self):",
                "        pass",
                "FAKE_VFS = FakeVfs()",
                "import osfs, os",
                "osfs.install(os, FAKE_VFS)",
                "SHARED = {}",
                "class Tty:",
                "    def __init__(self):",
                "        self.out = []",
                "    def print(self, *parts):",
                "        self.out.append(' '.join(str(p) for p in parts))",
                "    def write(self, text):",
                "        self.out.append(text.rstrip('\\n'))",
                // As the architecture's bootstrap: time.sleep is a poll point that yields a tick
                // (the Host's clock does not move, so one tick stands for the whole sleep).
                "def _test_sleep(ms):",
                "    _program.check()",
                "    computer._yield_tick()",
                "globals()['__time_sleep'] = _test_sleep",
                "import shell",
                "tty = Tty()",
                "sh = shell.Shell(tty, 'boot', globals())",
                "def wait(condition):",
                "    for i in range(3000):",
                "        if condition():",
                "            return",
                "        computer._yield_tick()",
                "    raise AssertionError(('timed out', tty.out, [(j['id'], j['done'], j['thread'].is_alive()) for j in sh.jobs], SHARED, list(_program._tasks.values()), _program._stopping, [(t._program, t._daemon, t.is_alive()) for t in _program._threads]))",
                program));
        host.vm.setModuleFinder(modules::get);
        host.vm.setCompiler((text, filename, mode) -> MpyCross.compile(text, filename));   // exec_sandbox
        return host;
    }

    /**
     * The real MPYOS shell with background jobs: `cmd &` and tkedit run in a thread, a job keeps
     * its windows while foreground commands close theirs, kill ends it, and Tk() with no display
     * named takes one no running computer holds.
     */
    @Test void shellRunsTkProgramsInTheBackground() throws Exception {
        TileTkDisplay a = new TileTkDisplay(), b = new TileTkDisplay();
        b.ownerAlive = address -> true;
        b.application().call("other-computer", "claim", "");   // another computer's program is on screen-b
        Host host = shellHost(String.join("\n",
                "FAKE_VFS.files['/bin/gui.py'] = \"import tkinter as tk\\nroot = tk.Tk()\\ntk.Label(root, text='job').pack()\\nroot.mainloop()\\n\"",
                "FAKE_VFS.files['/bin/bad.py'] = \"raise ValueError('boom')\\n\"",
                "FAKE_VFS.files['/bin/tkedit.py'] = TKEDIT",
                "FAKE_VFS.files['/home/notes.txt'] = 'hello\\n'",
                // A background GUI job: the prompt comes back at once; its window opens on the free display.
                "assert sh.spawn('gui', []) == 1 and tty.out[-1] == '[1] gui'",
                "wait(lambda: tk._roots and list(tk._roots.values())[0]._token)",
                "job_root = list(tk._roots.values())[0]",
                "assert job_root._screen == 'screen-a', 'screen-b is held by another running computer'",
                "assert computer._tk_live and job_root._owner == 'job:1'",
                "sh.run_command('jobs', [])",
                "assert tty.out[-1] == '[1] running  gui'",
                // A foreground Tk program opens beside the job, on the display this computer uses.
                "FAKE_VFS.files['/bin/fg.py'] = \"import tkinter as tk\\nroot = tk.Tk()\\ntk.Label(root, text='fg').pack()\\nSHARED['fg'] = root.winfo_screen()\\n\"",
                "sh.run_command('fg', [])",
                "assert SHARED['fg'] == 'screen-a', SHARED",
                "assert job_root._alive and list(tk._roots) == [job_root._token], 'the foreground window closed with its command; the job kept its own'",
                // kill stops the job (its mainloop is a poll point) and closes its window.
                "sh.run_command('kill', ['%1'])",
                "wait(lambda: sh.jobs[0]['done'])",
                "assert not job_root._alive and not tk._roots and not computer._tk_live",
                "sh._report_jobs()",
                "assert tty.out[-1] == '[1] done  gui' and sh.jobs == []",
                // A failing job reports its error before the next prompt; unknown jobs and commands are reported.
                "sh.spawn('bad', [])",
                "wait(lambda: sh.jobs[0]['done'])",
                "sh._report_jobs()",
                "assert tty.out[-1] == '[2] bad: boom', tty.out[-1]",
                "sh.run_command('kill', ['9'])",
                "assert tty.out[-1] == 'kill: no such job: 9'",
                "assert sh.spawn('nope', []) is None and tty.out[-1] == 'nope: command not found'",
                // tkedit always runs in the background: the command returns at once, the editor runs as job 3.
                "sh.cwd = '/home'",
                "sh.run_command('tkedit', ['notes.txt'])",
                "assert tty.out[-1] == '[3] tkedit notes.txt' and len(sh.jobs) == 1",
                "sh.cwd = '/'",
                "wait(lambda: tk._roots and list(tk._roots.values())[0]._token and list(tk._roots.values())[0].title() == 'notes.txt - tkedit')",
                "sh.run_command('kill', ['3'])",
                "wait(lambda: sh.jobs[0]['done'])",
                "assert not tk._roots",
                "sh._report_jobs()",
                // The old -w is accepted; started as a job (`tkedit &`) it runs in that job, not in another one.
                "sh.run_command('tkedit', ['-w', '/home/notes.txt'])",
                "assert tty.out[-1] == '[4] tkedit /home/notes.txt' and len(sh.jobs) == 1",
                "sh.spawn('tkedit', ['/home/notes.txt'])",
                "wait(lambda: len(tk._roots) == 2 and all(r._token for r in tk._roots.values()))",
                "assert [j['id'] for j in sh.jobs] == [4, 5], 'no third job'",
                "sh.run_command('kill', ['4'])",
                "sh.run_command('kill', ['5'])",
                "wait(lambda: sh.jobs[0]['done'] and sh.jobs[1]['done'])",
                "assert not tk._roots",
                "passed = True", ""), a, b);
        host.vm.globals.put("TKEDIT", Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkedit.py")));
        host.runUntil("passed");
        assertTrue(a.application().tokens().isEmpty(), "every program let screen-a go");
        assertEquals("other-computer", b.application().owner(), "screen-b stayed the other computer's");
    }

    @Test void bundledTkCommandsDefaultToBackgroundWithoutSpawningTwice() throws Exception {
        Path bin = Path.of("src/main/resources/assets/mpy4oc/mpyos/bin");
        List<Path> commands;
        try (var files = Files.list(bin)) {
            commands = files.filter(p -> p.getFileName().toString().matches("tk.*\\.py")).sorted().toList();
        }
        assertFalse(commands.isEmpty());
        for (Path command : commands) {
            TileTkDisplay display = TkTestGroups.joined(2, 2);
            Host host = shellHost("""
                    FAKE_VFS.files['/bin/' + COMMAND + '.py'] = SOURCE
                    for explicit_background in (False, True):
                        SHARED.clear()
                        if explicit_background:
                            sh.spawn(COMMAND, [])
                        else:
                            sh.run_command(COMMAND, [])
                        assert len(sh.jobs) == 1, (COMMAND, tty.out)
                        job = sh.jobs[0]
                        wait(lambda: 'root' in SHARED or job['done'])
                        assert not job['done'], (COMMAND, job['error'])
                        root = SHARED['root']
                        assert root._alive and root._owner == job['program'], COMMAND
                        root.after_idle(lambda: SHARED.update({'idle': True}))
                        wait(lambda: SHARED.get('idle'))
                        assert len(sh.jobs) == 1, (COMMAND, 'spawned twice')
                        root.destroy()
                        wait(lambda: job['done'])
                        assert job['error'] is None, (COMMAND, job['error'])
                        assert not tk._roots and not computer._tk_live, COMMAND
                        sh._report_jobs()
                        assert not sh.jobs, COMMAND
                    passed = True
                    """, display);
            String filename = command.getFileName().toString();
            host.vm.globals.put("COMMAND", filename.substring(0, filename.length() - 3));
            // Observe completion of real widget setup without replacing the demo's event loop.
            String source = Files.readString(command);
            assertTrue(source.contains("root.mainloop()"), filename);
            host.vm.globals.put("SOURCE", source.replace("root.mainloop()", "SHARED['root'] = root\nroot.mainloop()"));
            try {
                host.runUntil("passed");
                assertTrue(display.application().tokens().isEmpty(), filename);
            } catch (AssertionError error) {
                throw new AssertionError(filename + ": " + error.getMessage(), error);
            }
        }
    }

    /** A command's threads do not outlive it: non-daemon ones are waited for (Ctrl+C stops them), the rest stopped. */
    @Test void aCommandsThreadsEndWithIt() throws Exception {
        Host host = shellHost(String.join("\n",
                "FAKE_VFS.files['/bin/leak.py'] = '''",
                "import threading, _program",
                "def spin():",
                "    try:",
                "        while True:",
                "            _program.check()",
                "            computer._yield_tick()",
                "    finally:",
                "        SHARED['spin'] = _program.current()",
                "threading.Thread(target=spin, daemon=True).start()",
                "'''",
                "FAKE_VFS.files['/bin/worker.py'] = '''",
                "import threading",
                "def work():",
                "    for i in range(30):",
                "        computer._yield_tick()",
                "    SHARED['worker'] = 'done'",
                "threading.Thread(target=work).start()",
                "'''",
                "FAKE_VFS.files['/bin/forever.py'] = '''",
                "import threading, _program",
                "def loop():",
                "    while True:",
                "        _program.check()",
                "        computer._yield_tick()",
                "threading.Thread(target=loop).start()",
                "'''",
                // A daemon thread is stopped when its command ends; its finally runs as that command.
                "sh.run_command('leak', [])",
                "wait(lambda: 'spin' in SHARED and _program.live('cmd:1') == 0)",
                "assert SHARED['spin'] == 'cmd:1'",
                // A non-daemon thread keeps the command running, as it keeps a CPython process alive.
                "sh.run_command('worker', [])",
                "assert SHARED.get('worker') == 'done' and tty.out[-1] == '(waiting for 1 thread(s); Ctrl+C stops them)', tty.out",
                // Ctrl+C while waiting stops it.
                "computer._signals.append(('key_down', 'kb', 3, 46))",
                "sh.run_command('forever', [])",
                "assert tty.out[-1] == '^C' and not computer._signals",
                "wait(lambda: _program.live('cmd:3') == 0)",
                "sh._report_jobs()",
                "assert _program._stopping == [] and threading._active == [] and _program._threads == [], (_program._stopping, threading._active)",
                "passed = True", ""));
        host.runUntil("passed");
    }

    /** kill stops a job without windows; a job cannot read the terminal; its output waits for the prompt; its cwd is its own. */
    @Test void backgroundJobsAreStoppableAndKeptOffTheTerminal() throws Exception {
        Host host = shellHost(String.join("\n",
                "FAKE_VFS.files['/bin/bgloop.py'] = 'import _program\\nwhile True:\\n    _program.check()\\n    computer._yield_tick()\\n'",
                "FAKE_VFS.files['/bin/reader.py'] = \"tty.read('> ')\\n\"",
                "FAKE_VFS.files['/bin/talker.py'] = \"tty.print('hello from job')\\n\"",
                "FAKE_VFS.files['/bin/where.py'] = \"for i in range(10):\\n    computer._yield_tick()\\nSHARED['where'] = sh.resolve('x')\\n\"",
                "sh.spawn('bgloop', [])",
                "wait(lambda: _program.live('job:1') == 1)",
                "assert _program.in_background() is False, 'the shell itself is in front'",
                "sh.run_command('kill', ['1'])",
                "wait(lambda: sh.jobs[0]['done'])",
                "sh._report_jobs()",
                "assert tty.out[-1] == '[1] done  bgloop'",
                "sh.spawn('reader', [])",
                "wait(lambda: sh.jobs[0]['done'])",
                "sh._report_jobs()",
                "assert tty.out[-1] == '[2] reader: a background job cannot read the terminal', tty.out[-1]",
                "sh.spawn('talker', [])",
                "wait(lambda: sh.jobs[0]['done'])",
                "assert 'hello from job' not in tty.out, 'not written while the prompt may be open'",
                "sh.run_command('jobs', [])",
                "assert tty.out[-2:] == ['hello from job', '[3] done  talker'], tty.out",
                "count = len(tty.out)",
                "sh._report_jobs()",
                "assert len(tty.out) == count, 'reported once'",
                "sh.cwd = '/home'",
                "sh.spawn('where', [])",
                "sh.cwd = '/'",
                "wait(lambda: 'where' in SHARED)",
                "assert SHARED['where'] == '/home/x', 'a job keeps the directory it started in'",
                "passed = True", ""));
        host.runUntil("passed");
    }

    /** Each program runs its own windows' events: a click on a job's window is not run by another program's loop. */
    @Test void eachProgramRunsItsOwnTkEvents() throws Exception {
        Host host = shellHost(String.join("\n",
                "seen = []",
                "state = {}",
                "def job():",
                "    _program.set_current('job:x')",
                "    root = tk.Tk()",
                "    button = tk.Button(root, text='b', command=lambda: seen.append(_program.current()))",
                "    button.pack()",
                "    state['button'] = button",
                "    while not state.get('go'):",
                "        computer._yield_tick()",
                "    root.mainloop()",
                "threading.Thread(target=job, daemon=True).start()",
                "wait(lambda: 'button' in state)",
                "main_root = tk.Tk()",
                "button = state['button']",
                // The two programs' windows share the display; each has its own token.
                "assert main_root._screen == button._root._screen and main_root._owner is None",
                "assert main_root._token != button._root._token",
                "computer._signals.append(('tk_command', button._root._screen, button._root._token, button._id, button._command_id, 'Player'))",
                "main_root.update()",
                "assert seen == [] and len(tk._pending[button._root._token]) == 1, 'kept for the job'",
                "state['go'] = True",
                "wait(lambda: seen)",
                "assert seen == ['job:x'], seen",
                // Stopping the job: its mainloop is a poll point; its window is then released by owner.
                "_program.stop('job:x')",
                "wait(lambda: _program.live('job:x') == 0)",
                "assert tk._release_owned('job:x') == 1 and list(tk._roots) == [main_root._token]",
                "_program.ended('job:x')",
                // asyncio: a job's loop runs in its thread, for it. Stopping the job ends asyncio.run with
                // SystemExit, and closing the loop cancels the coroutine (CancelledError, as in CPython).
                "import asyncio",
                "log = SHARED['log'] = []",
                "async def main():",
                "    log.append('running')",
                "    try:",
                "        while True:",
                "            await asyncio.sleep(0)",
                "    except asyncio.CancelledError:",
                "        log.append('cancelled')",
                "        raise",
                "    finally:",
                "        log.append(_program.current())",
                "def job2():",
                "    _program.set_current('job:y')",
                "    try:",
                "        asyncio.run(main())",
                "    except SystemExit:",
                "        log.append('SystemExit')",
                "worker = threading.Thread(target=job2)",
                "worker.start()",
                "wait(lambda: log)",
                "_program.stop('job:y')",
                "wait(lambda: not worker.is_alive() and _program.live('job:y') == 0)",
                "assert log == ['running', 'cancelled', 'job:y', 'SystemExit'], log",
                "passed = True", ""), new TileTkDisplay(), new TileTkDisplay());
        host.runUntil("passed");
    }

    /**
     * Ctrl+C reaches the shell while it waits for a command's threads, even threads that pull
     * signals; _thread threads and children of daemon threads are daemons (nothing waits for them),
     * while threads a background job starts are its own non-daemon ones.
     */
    @Test void theShellsWaitForThreadsKeepsItsCtrlC() throws Exception {
        Host host = shellHost("""
                FAKE_VFS.files['/bin/listen.py'] = '''
                import threading
                def listen():
                    got = SHARED.setdefault('got', [])
                    while True:
                        got.append(computer.pullSignal())
                threading.Thread(target=listen).start()
                '''
                def press():
                    while not (tty.out and tty.out[-1].startswith('(waiting')):
                        computer._yield_tick()
                    for i in range(10):
                        computer._yield_tick()
                    computer._signals.append(('key_down', 'kb', 3, 46))
                    computer._signals.append(('key_down', 'kb', 97, 30))
                threading.Thread(target=press, daemon=True).start()
                sh.run_command('listen', [])
                assert tty.out[-1] == '^C', tty.out
                wait(lambda: _program.live('cmd:1') == 0)
                assert ('key_down', 'kb', 3, 46) not in SHARED.get('got', []), SHARED
                sh._report_jobs()
                assert _program._waited == [] and _program._stopping == [], (_program._waited, _program._stopping)
                FAKE_VFS.files['/bin/blink.py'] = '''
                import _thread, threading, time
                def loop():
                    while True:
                        time.sleep(1)
                _thread.start_new_thread(loop, ())
                def parent():
                    SHARED['inherited'] = threading.Thread(target=print).daemon
                threading.Thread(target=parent, daemon=True).start()
                '''
                count = len(tty.out)
                sh.run_command('blink', [])
                assert len(tty.out) == count, ('nothing to wait for', tty.out[count:])
                wait(lambda: 'inherited' in SHARED and _program.live('cmd:2') == 0)
                assert SHARED['inherited'] is True
                FAKE_VFS.files['/bin/kids.py'] = '''
                import threading
                def kid():
                    for i in range(20):
                        computer._yield_tick()
                    SHARED['kid'] = 'done'
                t = threading.Thread(target=kid)
                SHARED['kid_daemon'] = t.daemon
                t.start()
                '''
                sh.spawn('kids', [])
                wait(lambda: sh.jobs[-1]['done'])
                assert SHARED['kid_daemon'] is False and SHARED.get('kid') == 'done', SHARED
                passed = True
                """);
        host.runUntil("passed");
    }

    /**
     * A stopped program leaves nothing behind: a window its thread opens after the clean-up, an
     * update() loop, the code after mainloop(), the rest of a job's nested commands, its event
     * listeners and timers (which run as their program, keyboard ones not for a background job).
     */
    @Test void aStoppedProgramLeavesNothingBehind() throws Exception {
        Host host = shellHost("""
                import event
                FAKE_VFS.files['/bin/late.py'] = '''
                import threading, tkinter as tk
                def gui():
                    root = tk.Tk()
                    tk.Label(root, text='late').pack()
                    root.mainloop()
                threading.Thread(target=gui, daemon=True).start()
                '''
                sh.run_command('late', [])
                wait(lambda: _program.live('cmd:1') == 0)
                sh._report_jobs()
                assert not tk._roots, tk._roots
                FAKE_VFS.files['/bin/poller.py'] = '''
                import tkinter as tk
                root = tk.Tk()
                while True:
                    root.update()
                    computer._yield_tick()
                '''
                sh.spawn('poller', [])
                wait(lambda: tk._roots)
                sh.run_command('kill', ['1'])
                wait(lambda: sh.jobs[0]['done'])
                FAKE_VFS.files['/bin/after.py'] = '''
                import tkinter as tk
                root = tk.Tk()
                root.mainloop()
                SHARED['after'] = True
                '''
                sh.spawn('after', [])
                wait(lambda: tk._roots)
                sh.run_command('kill', ['2'])
                wait(lambda: sh.jobs[-1]['done'])
                sh._report_jobs()
                assert 'after' not in SHARED and tty.out[-2:] == ['[1] done  poller', '[2] done  after'], tty.out
                FAKE_VFS.files['/bin/step.py'] = '''
                import time
                SHARED['steps'] = SHARED.get('steps', 0) + 1
                while True:
                    time.sleep(1)
                '''
                FAKE_VFS.files['/bin/steps.py'] = '''
                for i in range(3):
                    sh.run_command('step', [])
                SHARED['steps_end'] = True
                '''
                sh.spawn('steps', [])
                wait(lambda: SHARED.get('steps'))
                sh.run_command('kill', ['3'])
                wait(lambda: sh.jobs[-1]['done'])
                assert SHARED['steps'] == 1 and 'steps_end' not in SHARED, SHARED
                FAKE_VFS.files['/bin/hook.py'] = '''
                import event, time, _program
                event.listen('key_down', lambda *a: SHARED.setdefault('keys', []).append(a))
                event.listen('ping', lambda *a: SHARED.setdefault('pings', []).append(_program.current()))
                event.timer(0, lambda: SHARED.setdefault('ticks', []).append(_program.current()), 0)
                SHARED['hooked'] = True
                while True:
                    time.sleep(1)
                '''
                sh.spawn('hook', [])
                wait(lambda: SHARED.get('hooked'))
                event._dispatch(('key_down', 'kb', 97, 30))
                event._dispatch(('ping', 1))
                event._run_due_timers()
                event._run_due_timers()
                assert 'keys' not in SHARED and SHARED['pings'] == ['job:4'] and SHARED['ticks'] == ['job:4', 'job:4'], SHARED
                assert _program.current() is None
                sh.run_command('kill', ['4'])
                wait(lambda: sh.jobs[-1]['done'])
                assert not event._listeners and not event._timers and not event._timer_ids, (event._listeners, event._timers)
                FAKE_VFS.files['/bin/fghook.py'] = '''
                import event
                event.listen('ping', print)
                '''
                sh.run_command('fghook', [])
                assert not event._listeners, "a command's listeners end with it"
                passed = True
                """, new TileTkDisplay());
        host.runUntil("passed");
    }

    /**
     * A job's working directory is its own (os.getcwd() too) and so is a command's os.chdir; a job
     * starting another reports to its own output, which is capped; exit warns about running jobs.
     */
    @Test void jobsKeepTheirOwnDirectoryAndOutput() throws Exception {
        Host host = shellHost("""
                FAKE_VFS.files['/bin/where.py'] = '''
                import os
                while not SHARED.get('go'):
                    computer._yield_tick()
                SHARED['where'] = (sh.resolve('x'), os.getcwd())
                while not SHARED.get('go2'):
                    computer._yield_tick()
                sh.run_command('cd', ['/'])
                SHARED['moved'] = (sh.cwd, os.getcwd())
                '''
                sh.run_command('cd', ['/home'])
                assert os.getcwd() == '/home'
                sh.spawn('where', [])
                sh.run_command('cd', ['/'])
                SHARED['go'] = True
                wait(lambda: 'where' in SHARED)
                assert SHARED['where'] == ('/home/x', '/home'), SHARED
                sh.run_command('cd', ['/home'])
                SHARED['go2'] = True
                wait(lambda: 'moved' in SHARED)
                assert SHARED['moved'] == ('/', '/') and sh.cwd == '/home' and os.getcwd() == '/home', (SHARED, sh.cwd, os.getcwd())
                wait(lambda: sh.jobs[0]['done'])
                sh._report_jobs()
                FAKE_VFS.files['/bin/chdir.py'] = '''
                import os
                os.chdir('/')
                SHARED['inside'] = os.getcwd()
                '''
                sh.run_command('chdir', [])
                assert SHARED['inside'] == '/' and os.getcwd() == '/home', "a command's os.chdir is its own"
                FAKE_VFS.files['/bin/chatty.py'] = '''
                for i in range(300):
                    tty.print('line', i)
                '''
                sh.spawn('chatty', [])
                wait(lambda: sh.jobs[-1]['done'])
                sh._report_jobs()
                assert '(44 earlier lines of background output dropped)' in tty.out and 'line 43' not in tty.out, tty.out[-3:]
                assert tty.out[-2:] == ['line 299', '[2] done  chatty'], tty.out[-3:]
                FAKE_VFS.files['/bin/talker.py'] = '''
                tty.print('hello')
                '''
                FAKE_VFS.files['/bin/launcher.py'] = '''
                sh.spawn('talker', [])
                '''
                count = len(tty.out)
                sh.spawn('launcher', [])
                wait(lambda: len(sh.jobs) == 2 and sh.jobs[0]['done'] and sh.jobs[1]['done'])
                assert tty.out[count:] == ['[3] launcher'], tty.out[count:]
                sh._report_jobs()
                assert '[4] talker' in tty.out[count:] and 'hello' in tty.out[count:], tty.out[count:]
                computer.shutdown = lambda reboot=False: SHARED.setdefault('shutdown', []).append(reboot)
                FAKE_VFS.files['/bin/bgloop.py'] = '''
                import time
                try:
                    while True:
                        time.sleep(1)
                finally:
                    SHARED['cleaned'] = True
                '''
                sh.spawn('bgloop', [])
                wait(lambda: _program.live(sh.jobs[-1]['program']) == 1)
                sh.run_command('exit', [])
                assert tty.out[-1] == 'there are running jobs (see jobs); exit again to stop them' and sh.running, tty.out[-1]
                assert 'shutdown' not in SHARED
                sh.run_command('exit', [])
                assert SHARED['shutdown'] == [False] and SHARED.get('cleaned') and not sh.running, SHARED
                passed = True
                """);
        host.runUntil("passed");
    }

    /** Several programs polling the same queue: a window's events still run in the order they came. */
    @Test void commandsRunAndSpawnPathsUsingTheirOwnCwd() throws Exception {
        Host host = shellHost("""
                FAKE_VFS.dirs += ['/home/work', '/tools']
                child = "SHARED.setdefault('runs', []).append((__file__, os.getcwd(), sh.cwd, list(args)))\\n"
                FAKE_VFS.files['/home/child.py'] = child
                FAKE_VFS.files['/tools/exact'] = child
                FAKE_VFS.files['/tools/exact.py'] = "raise AssertionError('exact filename should win')"
                FAKE_VFS.files['/bin/launcher.py'] = '''
                assert __file__ == '/bin/launcher.py' and __name__ == '__main__'
                assert os.getcwd() == '/home/work' and sh.cwd == '/home/work'
                os.chdir('/tools')
                assert sh.cwd == '/tools' and sh.resolve('file') == '/tools/file'
                sh.run_command('./exact', ['nested'])
                sh.spawn('./exact', ['background'])
                sh.run_command('cd', ['/home'])
                assert os.getcwd() == '/home' and sh.cwd == '/home'
                sh.spawn('./child.py', ['after-cd'])
                '''
                sh.run_command('cd', ['/home/work'])
                sh.run_command('../child', ['relative'])
                sh.run_command('/home/child.py', ['absolute'])
                sh.run_command('launcher', [])
                assert sh.cwd == '/home/work' and os.getcwd() == '/home/work'
                sh.spawn('./child', ['explicit-cwd'], cwd='..')
                wait(lambda: len(sh.jobs) == 3 and all(j['done'] for j in sh.jobs))
                assert all(j['error'] is None for j in sh.jobs), sh.jobs
                assert ('/home/child.py', '/home/work', '/home/work', ['relative']) in SHARED['runs'], SHARED
                assert ('/home/child.py', '/home/work', '/home/work', ['absolute']) in SHARED['runs'], SHARED
                assert ('/tools/exact', '/tools', '/tools', ['nested']) in SHARED['runs'], SHARED
                assert ('/tools/exact', '/tools', '/tools', ['background']) in SHARED['runs'], SHARED
                assert ('/home/child.py', '/home', '/home', ['after-cd']) in SHARED['runs'], SHARED
                assert ('/home/child.py', '/home', '/home', ['explicit-cwd']) in SHARED['runs'], SHARED
                assert len(SHARED['runs']) == 6
                assert sh.cwd == '/home/work' and os.getcwd() == '/home/work'
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void backgroundChildrenInheritCwdAndSearchPathAndWhichAgrees() throws Exception {
        Host host = shellHost("""
                FAKE_VFS.dirs += ['/tools']
                FAKE_VFS.files['/bin/which.py'] = WHICH
                FAKE_VFS.files['/bin/python.py'] = PYTHON
                FAKE_VFS.files['/home/worker.py'] = "SHARED['wrong'] = True"
                FAKE_VFS.files['/tools/worker.py'] = "SHARED.setdefault('workers', []).append((__file__, os.getcwd(), sh.path))"
                FAKE_VFS.files['/tools/probe.py'] = "SHARED['python_cwd'] = os.getcwd()"
                FAKE_VFS.files['/home/launcher.py'] = '''
                while not SHARED.get('go'):
                    computer._yield_tick()
                assert os.getcwd() == '/home' and sh.cwd == '/home' and sh.path == '.:/bin'
                os.chdir('/tools')
                assert sh.cwd == '/tools' and sh.command_path('worker') == '/tools/worker.py'
                sh.run_command('which', ['worker'])
                sh.run_command('which', ['./worker.py'])
                sh.run_command('which', ['cd'])
                sh.run_command('which', ['./missing'])
                sh.run_command('python', ['probe.py'])
                sh.run_command('worker', [])
                sh.spawn('worker', [])
                sh.spawn('../tools/worker', [])
                '''
                sh.run_command('cd', ['/home'])
                sh.path = '.:/bin'
                sh.spawn('launcher', [])
                sh.run_command('cd', ['/'])
                sh.path = '/bin'
                SHARED['go'] = True
                wait(lambda: len(sh.jobs) == 3 and all(j['done'] for j in sh.jobs))
                assert all(j['error'] is None for j in sh.jobs), sh.jobs
                assert SHARED.get('workers') == [('/tools/worker.py', '/tools', '.:/bin')] * 3, SHARED
                assert 'wrong' not in SHARED and SHARED['python_cwd'] == '/tools', SHARED
                sh._report_jobs()
                assert tty.out.count('/tools/worker.py') == 2, tty.out
                assert 'cd: shell builtin' in tty.out and './missing: not found' in tty.out, tty.out
                assert sh.cwd == '/' and sh.path == '/bin' and os.getcwd() == '/'
                passed = True
                """);
        Path bin = Path.of("src/main/resources/assets/mpy4oc/mpyos/bin");
        host.vm.globals.put("WHICH", Files.readString(bin.resolve("which.py")));
        host.vm.globals.put("PYTHON", Files.readString(bin.resolve("python.py")));
        host.runUntil("passed");
    }

    @Test void shellProgramsInheritEnvironmentsAndThreadsShareOnlyTheirProgramsCopy() throws Exception {
        Host host = shellHost("""
                os.environ['MARK'] = 'root'
                FAKE_VFS.files['/bin/child.py'] = '''
                import os
                from os import environ
                before = environ['MARK']
                while not SHARED.get('go'):
                    computer._yield_tick()
                assert environ['MARK'] == before, 'parent edits after spawn must not leak'
                environ['MARK'] = args[0]
                def worker():
                    assert environ['MARK'] == args[0]
                    environ['THREAD'] = 'shared'
                thread = threading.Thread(target=worker)
                thread.start()
                thread.join()
                assert os.getenv('THREAD') == 'shared'
                SHARED.setdefault('children', []).append((before, os.getenv('MARK'), os.getenv('PATH')))
                '''
                FAKE_VFS.files['/bin/parent.py'] = '''
                assert os.getenv('MARK') == 'root'
                os.environ['MARK'] = 'foreground'
                os.environ['PATH'] = '/bin:.'
                assert sh.path == '/bin:.'
                sh.run_command('/bin/nested.py', [])
                assert os.getenv('MARK') == 'nested'
                sh.spawn('child', ['one'])
                os.environ['MARK'] = 'later'
                del os.environ['PATH']
                sh.spawn('/bin/child.py', ['two'])
                '''
                FAKE_VFS.files['/bin/nested.py'] = "assert os.getenv('MARK') == 'foreground'\\nos.environ['MARK'] = 'nested'"
                sh.run_command('parent', [])
                assert os.getenv('MARK') == 'root' and os.getenv('THREAD') is None
                assert sh.path == '/bin:/usr/bin:/home/bin:.'
                assert len(sh.jobs) == 2, tty.out
                os.environ['MARK'] = 'root-after-launch'
                SHARED['go'] = True
                wait(lambda: all(j['done'] for j in sh.jobs))
                assert all(j['error'] is None for j in sh.jobs), sh.jobs
                assert ('nested', 'one', '/bin:.') in SHARED['children'], SHARED
                assert ('later', 'two', None) in SHARED['children'], SHARED
                assert os.getenv('MARK') == 'root-after-launch' and os.getenv('THREAD') is None
                wait(lambda: not _program._tasks)
                sh._report_jobs()
                assert list(os._environments) == [None], os._environments
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void stoppedProgramKeepsEnvironmentForItsThreadsFinallyBlocks() throws Exception {
        Host host = shellHost("""
                os.environ['MARK'] = 'root'
                FAKE_VFS.files['/bin/cleanup.py'] = '''
                import time
                os.environ['MARK'] = 'daemon'
                def worker():
                    try:
                        SHARED['ready'] = True
                        while True:
                            time.sleep(0.01)
                    finally:
                        time.sleep(0.01)
                        SHARED['cleaned'] = os.getenv('MARK')
                threading.Thread(target=worker, daemon=True).start()
                while not SHARED.get('ready'):
                    computer._yield_tick()
                '''
                sh.run_command('cleanup', [])
                assert os.getenv('MARK') == 'root'
                wait(lambda: SHARED.get('cleaned') is not None)
                assert SHARED['cleaned'] == 'daemon', SHARED
                wait(lambda: not _program._tasks)
                sh._report_jobs()
                assert list(os._environments) == [None], os._environments
                passed = True
                """);
        host.runUntil("passed");
    }

    @Test void aWindowsEventsRunInOrderWhileOtherProgramsPoll() throws Exception {
        Host host = shellHost("""
                try:
                    tk.Tk(nonsense=1)
                    raise AssertionError('an unknown option was accepted')
                except ValueError:
                    pass
                assert not tk._roots, 'a failed Tk() leaves no display reserved'
                order = []
                state = {}
                def job():
                    _program.set_current('job:o')
                    root = tk.Tk()
                    buttons = []
                    for i in range(6):
                        b = tk.Button(root, text=str(i))
                        b.configure(command=lambda i=i, b=b: (b.configure(text='x'), order.append(i)))
                        b.pack()
                        buttons.append(b)
                    state['buttons'] = buttons
                    while not state.get('go'):
                        computer._yield_tick()
                    root.mainloop()
                threading.Thread(target=job, daemon=True).start()
                wait(lambda: 'buttons' in state)
                main_root = tk.Tk()
                def click(b):
                    computer._signals.append(('tk_command', b._root._screen, b._root._token, b._id, b._command_id, 'Player'))
                buttons = state['buttons']
                click(buttons[0])
                click(buttons[1])
                main_root.update()
                state['go'] = True
                for b in buttons[2:]:
                    click(b)
                    main_root.update()
                    computer._yield_tick()
                wait(lambda: len(order) == 6)
                assert order == [0, 1, 2, 3, 4, 5], order
                passed = True
                """, new TileTkDisplay(), new TileTkDisplay());
        host.runUntil("passed");
    }

    /**
     * asyncio and programs: no loop runs unless asyncio.run (or a loop method) runs one, each thread
     * runs its own, for its own program, even while another program's coroutine is cut off mid-step;
     * micropython.schedule leaves no books open; a stopped program's sleeping coroutine is cancelled
     * at once (not when its timer is due), and its cleanup can still await.
     */
    @Test void coroutinesFollowTheirProgram() throws Exception {
        Host host = new Host(); host.stepBudget = 300;
        host.start("""
                import _program, asyncio, threading, micropython, time
                def _test_sleep(ms):
                    _program.check()
                    computer._yield_tick()
                globals()['__time_sleep'] = _test_sleep
                async def nothing():
                    pass
                coro = nothing()
                try:
                    asyncio.create_task(coro)
                    raise AssertionError('a task outside a loop')
                except RuntimeError as error:
                    assert str(error) == 'no running event loop', str(error)
                coro.close()
                flag = [False]
                loops = {}
                async def heavy():
                    loops['A'] = asyncio.get_running_loop()
                    flag[0] = True
                    s = 0
                    for i in range(30000):
                        s += i
                    flag[0] = False
                    await asyncio.sleep(0)
                    return _program.current()
                def job_a():
                    _program.set_current('job:A')
                    loops['A result'] = asyncio.run(heavy())
                worker = threading.Thread(target=job_a)
                worker.start()
                while not flag[0]:
                    pass
                _program.set_current('cmd:B')
                async def mine():
                    loops['B'] = asyncio.get_running_loop()
                    return _program.current()
                assert asyncio.run(mine()) == 'cmd:B'
                worker.join()
                assert loops['A result'] == 'job:A' and loops['A'] is not loops['B'], loops
                assert asyncio._running_loops == {}, asyncio._running_loops
                _program.set_current('cmd:C')
                hits = []
                for i in range(5):
                    micropython.schedule(hits.append, i)
                while len(hits) < 5:
                    computer._yield_tick()
                for i in range(5):
                    computer._yield_tick()
                assert _program.live('cmd:C') == 0, list(_program._tasks.values())
                _program.set_current(None)
                log = []
                async def sleeper():
                    log.append('sleeping')
                    try:
                        await asyncio.sleep(30)
                        log.append('woke')
                    except asyncio.CancelledError:
                        log.append('cancelled')
                        raise
                    finally:
                        await asyncio.sleep(0.05)
                        log.append('cleaned')
                def job_s():
                    _program.set_current('job:S')
                    try:
                        asyncio.run(sleeper())
                    except SystemExit:
                        log.append('SystemExit')
                runner = threading.Thread(target=job_s, daemon=True)
                runner.start()
                while not log:
                    computer._yield_tick()
                for i in range(5):
                    computer._yield_tick()
                start = time.ticks_ms()
                _program.stop('job:S')
                while runner.is_alive() or _program.live('job:S'):
                    computer._yield_tick()
                assert time.ticks_diff(time.ticks_ms(), start) < 5000, 'stopped at once, not when its 30 s sleep was over'
                assert log == ['sleeping', 'cancelled', 'cleaned', 'SystemExit'], log
                _program.ended('job:S')
                assert not _program._tasks and not _program._delivered and _program._stopping == [], (_program._tasks, _program._delivered)
                assert asyncio._running_loops == {}, asyncio._running_loops
                assert [key for key in asyncio._current_loops if key[0] is not None] == [], asyncio._current_loops
                passed = True
                """);
        host.runUntil("passed");
    }

    /** /bin/tkedit.py against an in-memory MPYOS shell: files, find/replace, dialogs, shortcuts. */
    @Test void tkeditEditsFilesThroughPython() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        String shell = """
                class FakeFs:
                    def __init__(self):
                        self.files = {'/home/a.txt': 'hello world\\n第二行 world\\n😀 world\\n'.encode('utf-8'),
                                      '/home/crlf.txt': b'x\\r\\ny\\r\\n', '/home/bin.dat': b'\\xff\\xfe', '/home/ctl.txt': b'a\\x01b',
                                      '/home/mixed.txt': b'a\\r\\nb\\nc\\rd', '/home/turk.txt': 'İstanbul\\nHello world\\n'.encode('utf-8'),
                                      '/home/tab.txt': b'\\tfoo bar\\n', '/home/abcdefghijklmn😀.txt': b'x'}
                        self.dirs = {'/', '/home', '/home/sub', '/mnt', '/mnt/disk'}
                        self.unlisted = {'/mnt'}   # as MPYOS's Vfs: /mnt is a directory that '/' does not list
                        self.readonly = False
                        self.full = False
                    def exists(self, path):
                        return path in self.files or path in self.dirs
                    def is_directory(self, path):
                        return path in self.dirs
                    def read_bytes(self, path):
                        return self.files.get(path)
                    def write_file(self, path, data):
                        if self.readonly:
                            raise OSError('read-only filesystem')
                        if self.full:
                            self.files[path] = b''   # OC truncates on open, then the write fails
                            raise OSError('not enough space')
                        self.files[path] = data.encode('utf-8') if isinstance(data, str) else data
                    def rename(self, frm, to):
                        self.files[to] = self.files.pop(frm)
                        return True
                    def remove(self, path):
                        self.files.pop(path, None)
                        return True
                    def list(self, path):
                        prefix = path.rstrip('/') + '/'
                        names = []
                        for entry in list(self.files) + list(self.dirs):
                            if entry != path and entry not in self.unlisted and entry.startswith(prefix) and '/' not in entry[len(prefix):]:
                                names.append(entry[len(prefix):] + ('/' if entry in self.dirs else ''))
                        return names
                class FakeShell:
                    def __init__(self):
                        self.fs = FakeFs()
                        self.cwd = '/home'
                    def resolve(self, path):
                        if not path.startswith('/'):
                            path = self.cwd + '/' + path
                        parts = []
                        for part in path.split('/'):
                            if part == '..':
                                if parts:
                                    parts.pop()
                            elif part and part != '.':
                                parts.append(part)
                        return '/' + '/'.join(parts)
                sh = FakeShell()
                args = ['a.txt']
                """;
        String checks = """
                def find(win, kind, label=None):
                    stack = [win]
                    while stack:
                        w = stack.pop(0)
                        if isinstance(w, kind) and (label is None or w.cget('text') == label):
                            return w
                        stack.extend(w._children)
                    raise AssertionError((kind, label))
                class Key:
                    def __init__(self, state, keycode):
                        self.state, self.keycode = state, keycode
                text = editor.text
                content = lambda: text.get('1.0', 'end-1c')
                stored = sh.fs.files  # (the editor's own global is `files`)
                assert editor.path == '/home/a.txt' and content() == 'hello world\\n第二行 world\\n😀 world\\n'
                assert not text.edit_modified() and root.title() == 'a.txt - tkedit'
                original = content()
                assert text.cget('undo')
                text.insert('end-1c', 'undo check')
                text.edit_undo()
                assert content() == original and not text.edit_modified()
                text.edit_redo()
                assert content() == original + 'undo check' and text.edit_modified()
                assert editor.load(editor.path) and content() == original
                try:
                    text.edit_undo()
                    raise AssertionError('opening a file kept the old undo history')
                except tk.TclError:
                    pass
                # Everything fits a single display block, the find bar too once shown.
                def fits(hidden):
                    for w in list(root._widgets.values()):
                        if not w._alive or w._window or w.winfo_manager() not in ('pack', 'grid') or isinstance(w, (tk.Text, tk.Entry, tk.Label)):
                            continue
                        m = w
                        while m is not None and m is not hidden:
                            m = m.master
                        if m is None:
                            assert w.winfo_width() >= w.winfo_reqwidth() and w.winfo_height() >= w.winfo_reqheight(), (w.winfo_class(), w.cget('text') if isinstance(w, tk.Button) else '', w.winfo_width(), w.winfo_reqwidth(), root.winfo_width())
                fits(editor.findbar)
                editor.show_find()
                fits(None)
                assert editor.text.winfo_height() >= 100, editor.text.winfo_height()
                editor.hide_find()

                # Find: forward, wrapping, backward, case, a line with a surrogate pair.
                editor.find_entry.insert(0, 'world')
                assert editor.find(1) and text.tag_ranges('sel') == ('1.6', '1.11') and text.index('insert') == '1.11'
                assert editor.find(1) and text.tag_ranges('sel') == ('2.4', '2.9')
                assert editor.find(1) and text.tag_ranges('sel') == ('3.2', '3.7'), text.tag_ranges('sel')
                assert editor.find(1) and text.tag_ranges('sel') == ('1.6', '1.11') and editor.message == 'Wrapped around to the start'
                assert editor.find(-1) and text.tag_ranges('sel') == ('3.2', '3.7') and editor.message == 'Wrapped around to the end'
                assert editor.find(-1) and text.tag_ranges('sel') == ('2.4', '2.9')
                # From inside a match: forward finds the next one, backward the one the cursor is in.
                text.tag_remove('sel', '1.0', 'end')
                text.mark_set('insert', '1.8')
                assert editor.find(1) and text.tag_ranges('sel') == ('2.4', '2.9')
                text.tag_remove('sel', '1.0', 'end')
                text.mark_set('insert', '1.8')
                assert editor.find(-1) and text.tag_ranges('sel') == ('1.6', '1.11'), text.tag_ranges('sel')
                editor.find_entry.delete(0, 'end')
                editor.find_entry.insert(0, 'hello')
                text.tag_remove('sel', '1.0', 'end')
                text.mark_set('insert', '1.2')
                assert editor.find(1) and text.tag_ranges('sel') == ('1.0', '1.5') and editor.message == 'Wrapped around to the start', 'the only match, around the cursor'
                editor.find_entry.delete(0, 'end')
                editor.find_entry.insert(0, 'WORLD')
                assert editor.find(1)
                editor.case.set(1)
                assert not editor.find(1) and editor.message == 'Not found: WORLD'
                editor.case.set(0)
                # Replace one (the selected match, then on to the next), then all.
                text.tag_remove('sel', '1.0', 'end')
                text.mark_set('insert', '1.0')
                editor.replace_entry.insert(0, 'there')
                editor.replace_one()
                assert text.tag_ranges('sel') == ('1.6', '1.11') and content().startswith('hello world'), 'the first press only finds'
                editor.replace_one()
                assert content().startswith('hello there\\n') and text.tag_ranges('sel') == ('2.4', '2.9')
                editor.replace_all()
                assert content() == 'hello there\\n第二行 there\\n😀 there\\n' and editor.message == 'Replaced 2 matches'
                assert text.edit_modified()
                editor.refresh()
                assert root.title() == 'a.txt * - tkedit'
                # Ctrl+S saves.
                assert editor.on_key(Key(4, 31)) == 'break'
                assert stored['/home/a.txt'] == 'hello there\\n第二行 there\\n😀 there\\n'.encode('utf-8') and not text.edit_modified()

                # What cannot be edited is refused and the document kept.
                text.mark_set('insert', '2.3')
                text.tag_add('sel', '1.0', '1.5')
                for path, message in (('/home/bin.dat', 'Not UTF-8 text: cannot edit it'), ('/home/sub', '/home/sub is a directory'),
                                      ('/home/ctl.txt', 'Cannot edit: control characters, or over the Text limits (262143 characters / 512 KB / 20000 lines)')):
                    assert not editor.load(path) and editor.message == message, (path, editor.message)
                    assert editor.path == '/home/a.txt' and content().startswith('hello there') and not text.edit_modified()
                    assert text.index('insert') == '2.3' and text.tag_ranges('sel') == ('1.0', '1.5'), 'the cursor and selection too'
                # CRLF stays CRLF.
                assert editor.load('/home/crlf.txt') and content() == 'x\\ny\\n' and editor.crlf
                text.insert('end', 'z')
                editor.save()
                assert stored['/home/crlf.txt'] == b'x\\r\\ny\\r\\nz'
                # A failed save keeps the changes marked unsaved, and the file on disk as it was.
                text.insert('end', '!')
                sh.fs.full = True
                editor.save()
                assert editor.message == 'Save failed: not enough space' and text.edit_modified()
                assert stored['/home/crlf.txt'] == b'x\\r\\ny\\r\\nz' and '/home/crlf.txt.tkedit-tmp' not in stored
                sh.fs.full = False
                sh.fs.readonly = True
                editor.save()
                assert editor.message == 'Save failed: read-only filesystem' and text.edit_modified()
                sh.fs.readonly = False

                # Unsaved changes: cancel keeps them, discard drops them.
                editor.new()
                win = editor.dialog_win
                assert win is not None and win.title() == 'Unsaved'
                find(win, tk.Button, 'Cancel').invoke()
                assert editor.dialog_win is None and content() == 'x\\ny\\nz!'
                editor.new()
                find(editor.dialog_win, tk.Button, "Don't save").invoke()
                editor.refresh()
                assert editor.path is None and content() == '' and root.title() == 'untitled - tkedit'
                # A new document is saved through the dialog; an existing name needs a second OK.
                text.insert('1.0', 'abc')
                editor.on_key(Key(4, 31))
                win = editor.dialog_win
                assert win.title() == 'Save as'
                name = find(win, tk.Entry)
                name.insert(0, 'a.txt')
                find(win, tk.Button, 'OK').invoke()
                assert find(win, tk.Label, 'It exists: OK again replaces it') and editor.dialog_win is win
                name.delete(0, 'end')
                name.insert(0, 'sub/../b.txt')
                find(win, tk.Button, 'OK').invoke()
                assert stored['/home/b.txt'] == b'abc' and editor.path == '/home/b.txt' and editor.dialog_win is None

                # The open dialog: folders first, double click to enter, select a file and open it.
                editor.open()
                win = editor.dialog_win
                listing = find(win, tk.Listbox)
                items = tuple(listing.get(0, 'end'))
                assert items == ('../', 'sub/', 'a.txt', 'abcdefghijklmn😀.txt', 'b.txt', 'bin.dat', 'crlf.txt', 'ctl.txt', 'mixed.txt', 'tab.txt', 'turk.txt'), items
                listing.selection_set(1)
                listing.event_generate('<Double-Button-1>')
                assert tuple(listing.get(0, 'end')) == ('../',) and find(win, tk.Label, '/home/sub')
                listing.selection_set(0)
                listing.event_generate('<Double-Button-1>')
                items = tuple(listing.get(0, 'end'))
                listing.selection_set(items.index('a.txt'))
                listing.event_generate('<<ListboxSelect>>')
                assert find(win, tk.Entry).get() == 'a.txt'
                listing.event_generate('<Double-Button-1>')
                assert editor.path == '/home/a.txt' and content().startswith('hello there') and editor.dialog_win is None
                editor.open()
                find(editor.dialog_win, tk.Entry).insert(0, 'nope.txt')
                find(editor.dialog_win, tk.Button, 'OK').invoke()
                assert find(editor.dialog_win, tk.Label, 'No such file')
                editor.close_dialog()

                # Go to line, wrap modes.
                editor.goto()
                entry = find(editor.dialog_win, tk.Entry)
                entry.delete(0, 'end')
                entry.insert(0, '99')
                find(editor.dialog_win, tk.Button, 'Go').invoke()
                assert text.index('insert') == '4.0' and editor.dialog_win is None, text.index('insert')
                assert text.cget('wrap') == 'word'
                editor.cycle_wrap()
                editor.cycle_wrap()
                assert text.cget('wrap') == 'none' and editor.xbar.winfo_manager() == 'grid'
                editor.cycle_wrap()
                assert text.cget('wrap') == 'word' and editor.xbar.winfo_manager() == ''

                # Mixed line endings: saved with the ending most lines had, and said so.
                assert editor.load('/home/mixed.txt') and content() == 'a\\nb\\nc\\nd' and not editor.crlf
                assert editor.message == 'mixed.txt: 4 lines (mixed line endings: saving uses LF)', editor.message
                # Case-insensitive search with an İ in the text.
                assert editor.load('/home/turk.txt')
                editor.find_entry.delete(0, 'end')
                editor.find_entry.insert(0, 'hello')
                assert editor.find(1) and text.tag_ranges('sel') == ('2.0', '2.5'), text.tag_ranges('sel')
                # A selection with a tab is not copied into the find box (an Entry takes no tab).
                assert editor.load('/home/tab.txt')
                text.tag_add('sel', '1.0', '1.4')
                editor.show_find()
                assert editor.find_entry.get() == 'hello' and root.focus_get() is editor.find_entry
                editor.hide_find()
                assert root.focus_get() is text
                # The unsaved-changes prompt: a long name cut before an emoji; the keyboard on Save.
                assert editor.load('/home/abcdefghijklmn😀.txt')
                text.insert('1.0', 'y')
                editor.new()
                win = editor.dialog_win
                assert find(win, tk.Label, 'abcdefghijklmn… was changed') and root.focus_get() is find(win, tk.Button, 'Save')
                # The dialog (its title bar too) lies within the editor's window.
                w, h, x, y = [int(v) for v in win.geometry().replace('+', 'x').split('x')[:4]]
                rx, ry, rw, rh = root.winfo_rootx(), root.winfo_rooty(), root.winfo_width(), root.winfo_height()
                assert rx <= x and x + w <= rx + rw and ry <= y and y + 18 + h <= ry + rh, (win.geometry(), root.geometry())
                find(win, tk.Button, "Don't save").invoke()
                assert editor.path is None and content() == ''
                # A save-as that fails keeps its dialog open with the reason.
                text.insert('1.0', 'q')
                sh.fs.readonly = True
                editor.save()
                win = editor.dialog_win
                find(win, tk.Entry).insert(0, 'r.txt')
                find(win, tk.Button, 'OK').invoke()
                assert editor.dialog_win is win and find(win, tk.Label, 'Save failed: read-only filesystem')
                sh.fs.readonly = False
                find(win, tk.Button, 'OK').invoke()
                assert stored['/home/r.txt'] == b'q' and editor.dialog_win is None and editor.path == '/home/r.txt'
                # The disks under /mnt can be reached from '/', which does not list /mnt itself.
                editor.open()
                listing = find(editor.dialog_win, tk.Listbox)
                listing.selection_set(0)
                listing.event_generate('<Double-Button-1>')
                assert tuple(listing.get(0, 'end')) == ('home/', 'mnt/'), tuple(listing.get(0, 'end'))
                listing.selection_set(1)
                listing.event_generate('<Double-Button-1>')
                assert tuple(listing.get(0, 'end')) == ('../', 'disk/')
                editor.close_dialog()
                # Buttons and scrollbars do not take the keyboard from the text.
                for w in list(root._widgets.values()):
                    if isinstance(w, (tk.Button, tk.Scrollbar)) and not isinstance(w, tk.Toplevel):
                        assert w.cget('takefocus') == 0, w.cget('text') if isinstance(w, tk.Button) else 'scrollbar'
                # Ctrl+F from the display: the shortcut opens the find bar; keysym is the letter, as in Tk.
                keys = []
                def spy(event):
                    keys.append((event.keysym, event.state))
                    return editor.on_key(event)
                root.unbind('<Key>')
                root.bind('<Key>', spy)
                editor.hide_find()
                key_callback = root._bindings['<Key>']
                armed = True
                while not keys:
                    root.update()
                assert keys == [('f', 4)] and editor.findbar.winfo_manager() == 'grid', keys
                # Quitting with nothing unsaved closes the editor.
                editor.quit()
                assert not root._alive
                passed = True
                """;
        String source = Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkedit.py"));
        assertTrue(source.contains("root.mainloop()"));
        host.start(shell + source.replace("root.mainloop()", checks));
        host.runUntil("armed");
        String token = token(tile);
        int callback = ((Number) host.vm.globals.get("key_callback")).intValue();
        int window = tile.widgets().root().children().get(0).id;   // the editor's window, where <Key> is bound
        host.signal("tk_event", "screen-a", token, window, callback, "<Key>", 0, 0, 33, 6, 2, "Player", 0, window, false, 0);
        host.runUntil("passed");
        assertEquals(Boolean.TRUE, host.vm.globals.get("passed"));
    }

    /** ttk stage 3 through Python: -show/-justify, validation, Spinbox and Combobox; values measured in Tk 8.6. */
    @Test void entriesSpinboxesAndComboboxesMatchTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                root.geometry('320x240')
                def fails(f, message):
                    try:
                        f()
                    except tk.TclError as error:
                        assert str(error) == message, str(error)
                        return
                    raise AssertionError('no error: ' + message)
                e = tk.Entry(root)
                assert e.cget('show') == '' and e.cget('justify') == 'left'
                e.configure(show='ab')
                e.insert(0, 'hello')
                e.selection_range(1, 3)
                assert e.cget('show') == 'ab' and e.get() == 'hello' and e.selection_get() == 'aa'
                fails(lambda: e.configure(justify='middle'), 'bad justification "middle": must be left, right, or center')
                fails(lambda: e.configure(justify='CENTER'), 'bad justification "CENTER": must be left, right, or center')
                e.configure(justify='c')
                assert e.cget('justify') == 'center'
                # Classic validation.
                log = []
                inv = []
                allow = [True]
                v = tk.Entry(root)
                v.configure(validate='key', validatecommand=(v.register(lambda *a: log.append(a) or allow[0]), '%d', '%i', '%P', '%s', '%S', '%v', '%V', '%W'),
                            invalidcommand=(v.register(lambda *a: inv.append(a)), '%d', '%i', '%P', '%s', '%S', '%v', '%V'))
                v.insert(0, 'abc')
                allow[0] = False
                v.insert(1, 'XY')
                v.delete(0, 2)
                W = str(v)
                assert log == [('1', '0', 'abc', '', 'abc', 'key', 'key', W), ('1', '1', 'aXYbc', 'abc', 'XY', 'key', 'key', W),
                               ('0', '0', 'c', 'abc', 'ab', 'key', 'key', W)], log
                assert inv == [('1', '1', 'aXYbc', 'abc', 'XY', 'key', 'key'), ('0', '0', 'c', 'abc', 'ab', 'key', 'key')] and v.get() == 'abc'
                del log[:]
                allow[0] = True
                v.delete(0, 2)
                v.delete(5)
                v.delete(0, 0)
                v.insert(0, '')
                assert log == [('0', '0', 'c', 'abc', 'ab', 'key', 'key', W)] and v.get() == 'c', log
                allow[0] = None
                v.insert(0, 'q')
                assert v.get() == 'c' and v.cget('validate') == 'none', 'not a boolean: rejected, and validation stops'
                for result, text, mode in (('0', '', 'key'), ('no', '', 'key'), ('yes', 'z', 'key'), ('off', '', 'key'), (1, 'z', 'key'),
                                           ('maybe', '', 'none'), ('', '', 'none')):
                    v.configure(validate='none')
                    v.delete(0, 'end')
                    v.configure(validate='key', validatecommand=(v.register(lambda *a, r=result: r), '%P'))
                    v.insert(0, 'z')
                    assert (v.get(), v.cget('validate')) == (text, mode), (result, v.get(), v.cget('validate'))
                calls = []
                v.configure(validate='key', validatecommand=lambda *a: calls.append(a) or True)
                v.insert('end', 'k')
                assert calls == [()]
                log2 = []
                h = tk.Entry(root)
                h.configure(validate='all', validatecommand=(h.register(lambda *a: log2.append(a) or a[0] != 'no'), '%P', '%V', '%d'),
                            invalidcommand=(h.register(lambda *a: log2.append(('inv',) + a)), '%P', '%V'))
                var = tk.StringVar(value='v1')
                h.configure(textvariable=var)
                var.set('no')
                assert log2 == [('v1', 'forced', '-1'), ('no', 'forced', '-1')] and h.get() == 'no' and h.cget('validate') == 'none', log2
                log3 = []
                m = tk.Entry(root)
                m.insert(0, 'base')
                def modifying(*a):
                    log3.append(a)
                    m.delete(0, 'end')
                    return True
                m.configure(validate='key', validatecommand=(m.register(modifying), '%P'))
                m.insert('end', 'Z')
                assert log3 == [('baseZ',)] and m.get() == '' and m.cget('validate') == 'none'
                log4 = []
                n = tk.Entry(root)
                n.insert(0, 'hello')
                n.configure(validate='key', validatecommand=(n.register(lambda *a: log4.append(a) or True), '%d', '%i', '%S', '%P', '%%', '%x', 'a%Pb'))
                n.delete(1, 3)
                assert log4 == [('0', '1', 'el', 'hlo', '%', 'x', 'ahlob')], log4
                assert n.cget('validatecommand').endswith(' %d %i %S %P %% %x a%Pb') and n.cget('invalidcommand') == ''
                fails(lambda: n.configure(validate='sometimes'), 'bad validate "sometimes": must be all, key, focus, focusin, focusout, or none')
                fails(lambda: n.configure(validate='foc'), 'ambiguous validate "foc": must be all, key, focus, focusin, focusout, or none')
                ro = tk.Entry(root)
                ro.insert(0, 'ro')
                ro.configure(state='readonly')
                ro.insert(0, 'x')
                ro.delete(0, 'end')
                assert ro.get() == 'ro'
                # ttk validation: errors are raised, values set from outside are not validated, 'invalid' follows.
                inv5 = []
                t = ttk.Entry(root)
                t.configure(validate='key', validatecommand=(t.register(lambda p: p != 'bad'), '%P'),
                            invalidcommand=(t.register(lambda *a: inv5.append(a)), '%P'))
                t.insert(0, 'bad')
                assert t.get() == '' and inv5 == [('bad',)]
                t.configure(validatecommand=(t.register(lambda *a: None), '%P'))
                fails(lambda: t.insert(0, 'q'), 'expected boolean value but got "None"')
                assert t.cget('validate') == 'none' and t.get() == ''
                tv = tk.StringVar(value='x')
                t2 = ttk.Entry(root, textvariable=tv)
                log6 = []
                t2.configure(validate='all', validatecommand=(t2.register(lambda *a: log6.append(a) or a[0] != 'no'), '%P', '%V', '%d'),
                             invalidcommand=(t2.register(lambda *a: log6.append(('inv',) + a)), '%P', '%V'))
                tv.set('no')
                assert log6 == [] and t2.get() == 'no' and t2.cget('validate') == 'all'
                assert t2.validate() is False and log6 == [('no', 'forced', '-1'), ('inv', 'no', 'forced')] and t2.state() == ('invalid',), log6
                t2.configure(validate='none')
                t2.delete(0, 'end')
                t2.insert(0, 'yes')
                assert t2.validate() is True and t2.state() == ()
                # Classic Spinbox.
                s = tk.Spinbox(root, from_=0, to=3)
                seq = []
                for i in range(5):
                    s.invoke('buttonup')
                    seq.append(s.get())
                assert seq == ['1', '2', '3', '3', '3'], seq
                calls = []
                c = tk.Spinbox(root, from_=0, to=2, command=lambda: calls.append(c.get()))
                for i in range(3):
                    c.invoke('buttonup')
                assert calls == ['1', '2', '2'], 'the command runs after every step, at the end too'
                assert (c.cget('from'), c.cget('to'), c.cget('increment'), c.cget('values'), c.cget('wrap'), c.cget('format')) == (0.0, 2.0, 1.0, '', 0, '')
                d = tk.Spinbox(root)
                assert d.get() == '' and d.cget('width') == 20 and d.cget('command') == '' and d.selection_element() == 'none'
                fails(lambda: d.invoke('nope'), 'bad element "nope": must be none, buttondown, or buttonup')
                assert d.invoke('buttonup') == ''
                w = tk.Spinbox(root, values='p q {r s}')
                assert w.get() == 'p' and w.cget('values') == 'p q {r s}'
                w.invoke('buttonup')
                w.invoke('buttonup')
                assert w.get() == 'r s'
                assert tk.Spinbox(root, values=('x', 'y z')).cget('values') == 'x {y z}'
                fails(lambda: tk.Spinbox(root, from_=5, to=1), '-to value must be greater than -from value')
                fails(lambda: tk.Spinbox(root, format='%d'), 'bad spinbox format specifier "%d"')
                sv = tk.StringVar(value='4')
                x = tk.Spinbox(root, from_=0, to=10, textvariable=sv)
                assert x.get() == '4' and sv.get() == '4'
                x.invoke('buttonup')
                assert x.get() == '5' and sv.get() == '5'
                lg = []
                y = tk.Spinbox(root, from_=0, to=5)
                y.configure(validate='all', validatecommand=(y.register(lambda *a: lg.append(a) or True), '%d', '%i', '%P', '%s', '%S', '%V'))
                y.invoke('buttonup')
                assert lg == [('-1', '-1', '1', '0', '', 'forced')] and y.get() == '1', lg
                assert y.winfo_class() == 'Spinbox' and not isinstance(y, tk.Entry)
                # ttk Spinbox: <<Increment>>/<<Decrement>> step, and a binding can stop them.
                ts = ttk.Spinbox(root, from_=0, to=3)
                assert ts.get() == ''
                seq = []
                for i in range(5):
                    ts.event_generate('<<Increment>>')
                    seq.append(ts.get())
                assert seq == ['0', '1', '2', '3', '3'], seq
                assert (ts.cget('from'), ts.cget('to'), ts.cget('increment'), ts.cget('values'), ts.cget('wrap'), ts.cget('format')) == (0, 3, 1, '', 0, '')
                t3 = ttk.Spinbox(root, from_=0.5, to='7', increment='0.25')
                assert (t3.cget('from'), t3.cget('to'), t3.cget('increment')) == (0.5, 7, 0.25)
                stopped = []
                ts2 = ttk.Spinbox(root, values=('a', 'b', 'c'))
                ts2.bind('<<Increment>>', lambda e: stopped.append(1) or 'break')
                ts2.event_generate('<Up>')
                assert ts2.get() == '' and stopped == [1] and ts2._delegating
                ts2.unbind('<<Increment>>')
                assert not ts2._delegating
                ts2.event_generate('<Up>')
                assert ts2.get() == 'a' and ts2.cget('values') == ('a', 'b', 'c')
                ts2.set('zzz')
                assert ts2.get() == 'zzz'
                calls = []
                ts3 = ttk.Spinbox(root, from_=0, to=2, command=lambda: calls.append(ts3.get()))
                for i in range(3):
                    ts3.event_generate('<<Increment>>')
                assert calls == ['0', '1', '2'] and ts3.winfo_class() == 'TSpinbox' and isinstance(ts3, ttk.Entry)
                # Combobox.
                cb = ttk.Combobox(root, values=('one', 'two', 'three'))
                assert cb.get() == '' and cb.current() == -1
                assert cb.current(1) == '' and cb.get() == 'two' and cb.current() == 1
                cb.set('two')
                assert cb.current() == 1
                cb.set('nope')
                assert cb.current() == -1
                fails(lambda: cb.current(5), 'Index 5 out of range')
                fails(lambda: cb.current(-1), 'Index -1 out of range')
                assert cb.cget('values') == ('one', 'two', 'three') and cb.cget('height') == 10 and cb.cget('width') == 20 and cb.cget('postcommand') == ''
                cb.configure(values=['a b', 'c'])
                assert cb.cget('values') == ('a b', 'c')
                cb.configure(values='x y z')
                assert cb.cget('values') == ('x', 'y', 'z') and ttk.Combobox(root).cget('values') == ''
                rc = ttk.Combobox(root, values=('a', 'b'), state='readonly')
                rc.insert(0, 'zz')
                assert rc.get() == ''
                rc.set('b')
                assert rc.get() == 'b' and rc.current() == 1 and rc.instate(['readonly']) and rc.identify(5, 5) == ''
                assert cb.winfo_class() == 'TCombobox'
                # A player chooses from the list, and types into a validating Entry.
                posted = []
                chosen = []
                cv = tk.StringVar()
                pick = ttk.Combobox(root, values=('red', 'green', 'blue'), textvariable=cv, postcommand=lambda: posted.append(1))
                pick.bind('<<ComboboxSelected>>', lambda e: chosen.append(pick.get()))
                pick.pack()
                pv = tk.StringVar()
                typed = tk.Entry(root, textvariable=pv)
                typed.configure(validate='key', validatecommand=(typed.register(lambda p: p.isdigit() or p == ''), '%P'))
                typed.pack()
                pick_id, typed_id = pick._id, typed._id
                armed = True
                while not posted:
                    root.update()
                opened = True
                while not chosen or pv.get() != '12':
                    root.update()
                assert posted == [1] and chosen == ['green'] and cv.get() == 'green' and typed.get() == '12'
                assert pick.state() == ('focus',) or 'pressed' not in pick.state()
                passed = True
                """);
        host.runUntil("armed");
        TkTree tree = tile.widgets();
        int pick = ((Number) host.vm.globals.get("pick_id")).intValue(), typed = ((Number) host.vm.globals.get("typed_id")).intValue();
        tree.focus(pick);
        tree.key(208, 0, 0);
        assertFalse(tree.listOpen(), "-postcommand runs first");
        host.runUntil("opened");
        assertTrue(tree.listOpen());
        tree.key(208, 0, 0);
        tree.key(28, 0, 0);
        assertFalse(tree.listOpen());
        tree.focus(typed);
        tree.key(2, 0, '1'); tree.key(45, 0, 'x'); tree.key(3, 0, '2');
        assertEquals("", tree.get(typed).text(), "the keys wait for the program");
        host.runUntil("passed");
        assertEquals("12", tree.get(typed).text());
    }

    /** ttk.Treeview through Python: return values, errors and events measured in Tk 8.6 (tree1.py, tree2.py, tree3.py). */
    @Test void fileDialogsFitASingleDisplayIncludingLongDirectoryAndFilterNames() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start("""
                import tkinter as tk
                from tkinter import filedialog as fd
                from tkinter.commondialog import _place
                root = tk.Tk()
                root.geometry('280x170+10+24')
                path = '/' + 'long_directory_' * 12
                class Files:
                    def getcwd(self):
                        return path
                    def listdir(self, path):
                        return []
                    def stat(self, path):
                        return (16384, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                fd._os = Files()
                def check(w, parent, focus):
                    _place(w, parent)
                    w.deiconify()
                    w.update_idletasks()
                    def visit(widget):
                        for c in widget._children:
                            if c.winfo_class() == 'TButton':
                                x, y = c.winfo_rootx(), c.winfo_rooty()
                                width, height = c.winfo_width(), c.winfo_height()
                                assert width >= c.winfo_reqwidth() and height >= c.winfo_reqheight(), (c['text'], width, height)
                                assert x >= 0 and x + width <= w.winfo_screenwidth(), (c['text'], x, width)
                                assert y >= 18 and y + height <= w.winfo_screenheight() - 18, (c['text'], y, height)
                            visit(c)
                    visit(w)
                    w.destroy()
                fd._modal = check
                for mode in ('open', 'save', 'dir'):
                    fd._FileBox(root, mode, {'filetypes': [('A very long filter description ' * 8, '*.txt')]})
                root.destroy()
                passed = True
                """);
        host.runUntil("passed");
    }

    /** Step 29 through Python: messagebox, simpledialog, filedialog, commondialog; answers and messages as in Tk 8.6 (dialog1.py). */
    @Test void dialogsMatchTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                import os
                from tkinter import messagebox, simpledialog, filedialog, commondialog
                root = tk.Tk()
                root.geometry('300x200+10+30')
                root.update()
                def fails(f, message):
                    try:
                        f()
                    except tk.TclError as error:
                        assert str(error) == message, str(error)
                        return
                    raise AssertionError('no error: ' + message)
                def walk(w):
                    for c in list(w._children):
                        yield c
                        yield from walk(c)
                def button(top, text):
                    for c in walk(top):
                        if c.winfo_class() in ('Button', 'TButton') and str(c.cget('text')) == text:
                            return c
                    raise AssertionError('no button ' + text)
                def dialog(name):
                    # The dialog's window once it holds the grab (shown, modal).
                    for w in walk(root):
                        if w._name.startswith(name) and w._alive and w.grab_status() == 'local':
                            return w
                    return None
                seen = {}
                def when(name, act):
                    # After the dialog is up: look at it, then answer it.
                    def go():
                        w = dialog(name)
                        if w is None:
                            root.after(0, go)
                            return
                        act(w)
                    root.after(0, go)
                def press(name, text, look=None):
                    def act(w):
                        if look:
                            look(w)
                        button(w, text).invoke()
                    when(name, act)
                MB = '__tk__messagebox'
                def look(w):
                    seen['title'] = w.title()
                    seen['transient'] = w.transient()
                    seen['grab'] = w.grab_current() is w
                    seen['focus'] = str(w.focus_get())
                    seen['labels'] = [str(c.cget('text')) for c in walk(w) if c.winfo_class() == 'TLabel']
                press(MB, 'OK', look)
                assert messagebox.showinfo('T', 'Hello world', detail='More words') == 'ok'
                assert seen == {'title': 'T', 'transient': '.', 'grab': True, 'focus': '.__tk__messagebox.bot.ok',
                                'labels': ['i', 'Hello world', 'More words']}, seen
                assert root.grab_current() is None, 'the grab is released'
                press(MB, 'Yes'); assert messagebox.askyesno('T', 'Q?') is True
                press(MB, 'No'); assert messagebox.askquestion('T', 'Q?') == 'no'
                press(MB, 'Cancel'); assert messagebox.askyesnocancel('T', 'Q?') is None
                press(MB, 'Retry'); assert messagebox.askretrycancel('T', 'Q?') is True
                press(MB, 'Cancel'); assert messagebox.askokcancel('T', 'Q?') is False
                press(MB, 'Ignore')
                assert messagebox.Message(title='T', message='m', type='abortretryignore', icon='error').show() == 'ignore'
                fails(lambda: messagebox.Message(title='T', message='m', type='zz').show(), 'bad -type value "zz": must be abortretryignore, ok, okcancel, retrycancel, yesno, or yesnocancel')
                fails(lambda: messagebox.Message(title='T', message='m', icon='zz').show(), 'bad -icon value "zz": must be error, info, question, or warning')
                fails(lambda: messagebox.Message(title='T', message='m', type='ok', default='yes').show(), 'bad -default value "yes": must be abort, retry, ignore, ok, cancel, no, or yes')
                seen.clear()
                press(MB, 'No', look)
                assert messagebox.askyesno('T', 'Q?', default='no') is False and seen['focus'] == '.__tk__messagebox.bot.no'
                when(MB, lambda w: w._delete_window())
                assert messagebox.askyesno('T', 'Q?') is False, 'the close box: the cancel answer (no)'
                when(MB, lambda w: w._delete_window())
                assert messagebox.askyesnocancel('T', 'Q?') is None
                # simpledialog.
                seen.clear()
                def look_query(w):
                    entry = [c for c in walk(w) if c._name == 'entry'][0]
                    seen['title'], seen['entry'], seen['value'] = w.title(), str(entry), entry.get()
                    seen['selected'], seen['focus'], seen['grab'] = entry.selection_present(), w.focus_get() is entry, w.grab_current() is w
                press('!_querystring', 'OK', look_query)
                assert simpledialog.askstring('Name', 'Your name?', initialvalue='bob') == 'bob'
                assert seen == {'title': 'Name', 'entry': '.!_querystring.!frame.entry', 'value': 'bob', 'selected': True, 'focus': True, 'grab': True}, seen
                press('!_queryinteger', 'Cancel'); assert simpledialog.askinteger('N', 'Number?') is None
                def typed(text, then='OK'):
                    def act(w):
                        entry = [c for c in walk(w) if c._name == 'entry'][0]
                        entry.delete(0, 'end'); entry.insert(0, text)
                        button(w, then).invoke()
                    return act
                when('!_queryinteger', typed('42')); assert simpledialog.askinteger('N', 'Number?', minvalue=0, maxvalue=100) == 42
                when('!_queryfloat', typed('2.5')); assert simpledialog.askfloat('N', 'Number?') == 2.5
                # A bad value: Tk's warning (a messagebox over the dialog), then the dialog has the grab again.
                warned = []
                def bad_then_good(w):
                    def warning(m):
                        warned.append([str(c.cget('text')) for c in walk(m) if c.winfo_class() == 'TLabel'][1])
                        def after_warning():
                            if w.grab_status() != 'local':
                                root.after(0, after_warning)
                                return
                            warned.append('grab back')
                            typed('7')(w)
                        root.after(0, after_warning)
                        button(m, 'OK').invoke()
                    when(MB, warning)
                    typed('abc')(w)
                when('!_queryinteger', bad_then_good)
                assert simpledialog.askinteger('N', 'Number?') == 7
                assert warned == ['Not an integer.\\nPlease try again', 'grab back'], warned
                warned.clear()
                def too_big(w):
                    def warning(m):
                        warned.append([str(c.cget('text')) for c in walk(m) if c.winfo_class() == 'TLabel'][1])
                        button(m, 'OK').invoke()
                        root.after(0, lambda: button(w, 'Cancel').invoke())
                    when(MB, warning)
                    typed('500')(w)
                when('!_queryinteger', too_big)
                assert simpledialog.askinteger('N', 'Number?', maxvalue=100) is None
                assert warned == ['The allowed maximum value is 100. Please try again.'], warned
                # filedialog, on a filesystem made up here.
                files = {'/': ['home'], '/home': ['a.txt', 'b.py', 'sub', '.hidden'], '/home/sub': ['c.txt']}
                def listdir(path='.'):
                    if path not in files:
                        raise OSError('not a directory')
                    return list(files[path])
                def stat(path):
                    if path in files:
                        return (0x4000, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    head, name = path.rsplit('/', 1)
                    if name in files.get(head or '/', ()):
                        return (0x8000, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    raise OSError('no such file')
                os.listdir, os.stat, os.getcwd = listdir, stat, lambda: '/home'
                FD = '__tk_filedialog'
                def look_fd(w):
                    lb = [c for c in walk(w) if c.winfo_class() == 'Listbox'][0]
                    seen['title'], seen['shown'] = w.title(), list(lb.get(0, 'end'))
                    seen['menus'] = [str(c.cget('text')) for c in walk(w) if c.winfo_class() == 'TMenubutton']
                    seen['labels'] = [str(c.cget('text')) for c in walk(w) if c.winfo_class() == 'TLabel']
                seen.clear()
                press(FD, 'Cancel', look_fd)
                assert filedialog.askopenfilename(title='Pick', filetypes=[('Text', '*.txt'), ('All', '*')]) == ()
                assert seen == {'title': 'Pick', 'shown': ['sub/', 'a.txt'], 'menus': ['/home', 'Text (*.txt)'],
                                'labels': ['Directory:', 'File name:', 'Files of type:']}, seen
                def type_open(text, then='Open'):
                    def act(w):
                        entry = [c for c in walk(w) if c.winfo_class() == 'TEntry'][0]
                        entry.delete(0, 'end'); entry.insert(0, text)
                        button(w, then).invoke()
                    return act
                when(FD, type_open('a.txt'))
                assert filedialog.askopenfilename() == '/home/a.txt'
                def missing(w):
                    def warning(m):
                        warned.append([str(c.cget('text')) for c in walk(m) if c.winfo_class() == 'TLabel'][1])
                        button(m, 'OK').invoke()
                        root.after(0, lambda: button(w, 'Cancel').invoke())
                    when(MB, warning)
                    type_open('zz.txt')(w)
                warned.clear()
                when(FD, missing)
                assert filedialog.askopenfilename() == ()
                assert warned == ['File "/home/zz.txt" does not exist.'], warned
                def overwrite(w):
                    when(MB, lambda m: button(m, 'Yes').invoke())
                    type_open('a.txt', 'Save')(w)
                when(FD, overwrite)
                assert filedialog.asksaveasfilename() == '/home/a.txt'
                when(FD, type_open('new', 'Save'))
                assert filedialog.asksaveasfilename(defaultextension='.txt') == '/home/new.txt'
                press(FD, 'Cancel'); assert filedialog.asksaveasfilename() == ''
                def into_sub(w):
                    lb = [c for c in walk(w) if c.winfo_class() == 'Listbox'][0]
                    lb.selection_set(0); lb.event_generate('<Double-Button-1>')
                    seen['menus'] = [str(c.cget('text')) for c in walk(w) if c.winfo_class() == 'TMenubutton']
                    seen['shown'] = list(lb.get(0, 'end'))
                    lb.selection_clear(0, 'end'); lb.selection_set(0); lb.event_generate('<<ListboxSelect>>')
                    button(w, 'Open').invoke()
                seen.clear()
                when(FD, into_sub)
                assert filedialog.askopenfilename() == '/home/sub/c.txt'
                assert seen == {'menus': ['/home/sub', 'All Files (*)'], 'shown': ['c.txt']}, seen
                def several(w):
                    lb = [c for c in walk(w) if c.winfo_class() == 'Listbox'][0]
                    lb.selection_set(1, 2); lb.event_generate('<<ListboxSelect>>')
                    button(w, 'Open').invoke()
                when(FD, several)
                assert filedialog.askopenfilenames() == ('/home/a.txt', '/home/b.py')
                DD = '__tk_choosedir'
                press(DD, 'Cancel'); assert filedialog.askdirectory() == ()
                press(DD, 'OK'); assert filedialog.askdirectory() == '/home'
                def pick_sub(w):
                    lb = [c for c in walk(w) if c.winfo_class() == 'Listbox'][0]
                    assert list(lb.get(0, 'end')) == ['sub/']
                    lb.selection_set(0); lb.event_generate('<Double-Button-1>')
                    button(w, 'OK').invoke()
                when(DD, pick_sub)
                assert filedialog.askdirectory(initialdir='/home') == '/home/sub'
                fails(lambda: filedialog.askopenfilename(zz=1), 'bad option "-zz": must be -defaultextension, -filetypes, -initialdir, -initialfile, -multiple, -parent, -title, or -typevariable')
                assert commondialog.Dialog.command is None
                assert root.grab_current() is None and root.winfo_exists()
                passed = True
                """);
        host.runUntil("passed");
    }

    /** ttk stage 7 through Python: ttk.Panedwindow, tk.PanedWindow, ttk.LabeledScale; values measured in Tk 8.6 (paned1/2.py). */
    @Test void panedwindowsMatchTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                root.geometry('300x200+0+30')
                def fails(f, message):
                    try:
                        f()
                    except tk.TclError as error:
                        assert str(error) == message, str(error)
                        return
                    raise AssertionError('no error: ' + message)
                pw = ttk.Panedwindow(root)
                assert (pw.cget('orient'), pw.cget('width'), pw.cget('height'), pw.cget('takefocus'), pw.winfo_class()) == ('vertical', 0, 0, '', 'TPanedwindow')
                assert sorted(pw.keys()) == ['class', 'cursor', 'height', 'orient', 'style', 'takefocus', 'width']
                pw.destroy()
                pw = ttk.Panedwindow(root, orient='horizontal')
                a = tk.Frame(pw, width=80, height=50)
                b = tk.Frame(pw, width=60, height=40)
                c = tk.Frame(pw, width=30, height=30)
                pw.add(a)
                pw.add(b, weight=1)
                assert pw.panes() == (str(a), str(b)) and str(pw) == '.!panedwindow2'
                assert pw.pane(0) == {'weight': 0} and pw.pane(b, 'weight') == 1
                fails(lambda: pw.pane(5), 'Slave index 5 out of bounds')
                fails(lambda: pw.pane(0, 'zzz'), 'unknown option "-zzz"')
                pw.update_idletasks()
                assert (pw.winfo_reqwidth(), pw.winfo_reqheight()) == (145, 50)
                pw.pack(fill='both', expand=True)
                root.update()
                geom = lambda *ws: [(w.winfo_x(), w.winfo_y(), w.winfo_width(), w.winfo_height()) for w in ws]
                assert geom(pw, a, b) == [(0, 0, 300, 200), (0, 0, 80, 200), (85, 0, 215, 200)], geom(pw, a, b)
                assert pw.sashpos(0) == 80 and pw.sashpos(0, 100) == 100
                root.update()
                assert geom(a, b) == [(0, 0, 100, 200), (105, 0, 195, 200)]
                assert (pw.sashpos(0, 1000), pw.sashpos(0, -5)) == (295, 0)
                fails(lambda: pw.sashpos(3), 'sash index 3 out of range')
                assert pw.identify(pw.sashpos(0) + 1, 10) == 0 and pw.identify(200, 10) == ''
                pw.sashpos(0, 80)
                root.geometry('400x200')
                root.update()
                assert geom(a, b) == [(0, 0, 80, 200), (85, 0, 315, 200)]
                root.geometry('300x200')
                root.update()
                pw.insert(0, c)
                root.update()
                assert pw.panes() == (str(c), str(a), str(b)) and [(w.winfo_x(), w.winfo_width()) for w in (c, a, b)] == [(0, 30), (35, 80), (120, 180)]
                assert (pw.sashpos(0), pw.sashpos(1)) == (30, 115)
                pw.forget(1)
                root.update()
                assert pw.panes() == (str(c), str(b)) and not a.winfo_ismapped() and a.winfo_manager() == '' and b.winfo_manager() == 'panedwindow'
                fails(lambda: pw.forget(7), 'Slave index 7 out of bounds')
                other = tk.Frame(root)
                pw.add(other)
                fails(lambda: pw.add(tk.Toplevel(root)), "can't add .!toplevel as slave of .!panedwindow2")
                fails(lambda: pw.add(b, weight=3), str(b) + ' already added')
                assert [pw.pane(i, 'weight') for i in range(3)] == [0, 1, 0]
                fails(lambda: pw.insert(9, tk.Frame(pw)), 'Slave index 9 out of bounds')
                pw.insert('end', a)
                assert pw.panes() == (str(c), str(b), str(other), str(a))
                assert pw.pane(0, weight=5) == {} and pw.pane(0) == {'weight': 5}
                fails(lambda: pw.pane(0, weight=-1), '-weight must be nonnegative')
                fails(lambda: pw.pane(0, weight='x'), 'expected integer but got "x"')
                fails(lambda: pw.pane(tk.Frame(pw)), '.!panedwindow2.!frame5 is not managed by .!panedwindow2')
                pw.pack_forget()
                # The theme's sash: 6 pixels in clam.
                p2 = ttk.Panedwindow(root, orient='horizontal')
                for i in range(2):
                    p2.add(tk.Frame(p2, width=30, height=10))
                p2.update_idletasks()
                assert p2.winfo_reqwidth() == 65
                st = ttk.Style()
                st.theme_use('clam')
                p2.update_idletasks()
                assert p2.winfo_reqwidth() == 66
                st.theme_use('default')
                # Classic.
                cp = tk.PanedWindow(root)
                conf = cp.configure()
                assert conf['sashwidth'] == ('sashwidth', 'sashWidth', 'Width', 3, 3) and conf['orient'][4] == 'horizontal' and conf['bd'] == ('bd', '-borderwidth')
                assert (cp.cget('showhandle'), cp.cget('handlesize'), cp.cget('sashrelief'), cp.cget('width'), cp.winfo_class()) == (0, 8, 'flat', '', 'Panedwindow')
                c1 = tk.Frame(cp, width=26, height=21)
                c2 = tk.Frame(cp, width=65, height=21)
                cp.add(c1)
                cp.add(c2, minsize=40)
                assert cp.panes() == (str(c1), str(c2))
                pc = cp.paneconfigure(c2)
                assert sorted(pc) == ['after', 'before', 'height', 'hide', 'minsize', 'padx', 'pady', 'sticky', 'stretch', 'width']
                assert pc['minsize'] == ('minsize', '', '', 0, 40) and pc['stretch'] == ('stretch', 'stretch', 'Stretch', 'last', 'last') and pc['sticky'][4] == 'nesw'
                assert (cp.panecget(c2, 'minsize'), cp.panecget(c1, 'sticky'), cp.panecget(c1, 'stretch')) == (40, 'nesw', 'last')
                cp.update_idletasks()
                assert (cp.winfo_reqwidth(), cp.winfo_reqheight()) == (96, 23)
                cp.pack(fill='both', expand=True)
                root.update()
                assert geom(cp, c1, c2) == [(0, 0, 300, 200), (1, 1, 26, 198), (30, 1, 269, 198)], geom(cp, c1, c2)
                assert cp.sash_coord(0) == (27, 1) and cp.identify(27, 1) == (0, 'sash') and cp.identify(5, 5) == ''
                assert cp.sash_place(0, 150, 1) == ()
                root.update()
                assert cp.sash_coord(0) == (150, 1) and [(w.winfo_x(), w.winfo_width()) for w in (c1, c2)] == [(1, 149), (153, 146)]
                cp.sash_place(0, 290, 1)
                root.update()
                assert cp.sash_coord(0) == (256, 1) and c2.winfo_width() == 40
                root.geometry('400x200')
                root.update()
                assert [(w.winfo_x(), w.winfo_width()) for w in (c1, c2)] == [(1, 255), (259, 140)]
                cp.paneconfigure(c1, stretch='always')
                root.geometry('500x200')
                root.update()
                assert [(w.winfo_x(), w.winfo_width()) for w in (c1, c2)] == [(1, 427), (431, 68)]
                fails(lambda: cp.paneconfigure(c1, zzz=1), 'unknown option "-zzz"')
                fails(lambda: cp.paneconfigure(c1, sticky='q'), 'bad stickyness value "q": must be a string containing zero or more of n, e, s, and w')
                fails(lambda: cp.paneconfigure(c1, stretch='q'), 'bad stretch "q": must be always, first, last, middle, or never')
                cp.forget(c1)
                root.update()
                assert cp.panes() == (str(c2),) and c1.winfo_manager() == '' and c2.winfo_x() == 1
                fails(lambda: cp.sash_coord(0), 'invalid sash index')
                c3 = tk.Frame(cp, width=40, height=21)
                cp.add(c3, before=c2, width=50, padx=3, pady=2, sticky='n')
                root.update()
                assert cp.panes() == (str(c3), str(c2)) and geom(c3)[0] == (4, 3, 50, 21) and c2.winfo_x() == 60, (geom(c3), c2.winfo_x())
                cp.paneconfigure(c3, hide=True)
                root.update()
                assert not c3.winfo_ismapped() and c2.winfo_x() == 1 and cp.panes() == (str(c3), str(c2))
                assert tk.PanedWindow(root, orient='vertical').cget('orient') == 'vertical'
                cp.pack_forget()
                # LabeledScale.
                ls = ttk.LabeledScale(root, from_=0, to=10)
                ls.pack(fill='x')
                root.update()
                assert (ls.value, ls.label['text'], ls.scale.cget('from'), ls.scale.cget('to'), ls.winfo_class()) == (0, 0, 0, 10, 'TFrame'), (ls.value, ls.label['text'], ls.scale.cget('from'), ls.scale.cget('to'), ls.winfo_class())
                ls.value = 5
                root.update()
                assert (ls.value, ls.label['text']) == (5, 5)
                ls.value = 50
                root.update()
                assert (ls.value, ls.label['text']) == (5, 5), 'out of range: back to the last valid value'
                v = tk.DoubleVar()
                ls2 = ttk.LabeledScale(root, variable=v, from_=-1, to=1, compound='bottom')
                ls2.pack(fill='x')
                root.update()
                assert (ls2.value, ls2.label['text']) == (-1.0, -1.0)
                assert ls2.label.winfo_y() > ls2.scale.winfo_y(), 'compound bottom: the label below the scale'
                ls2.destroy()
                passed = True
                """);
        host.runUntil("passed");
    }

    /** ttk stage 6 through Python: Menu, Menubutton, OptionMenu (classic and ttk), a window's menubar; values measured in Tk 8.6 (menu1/2.py). */
    @Test void menusMatchTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                root.geometry('300x200+20+30')
                def fails(f, message):
                    try:
                        f()
                    except tk.TclError as error:
                        assert str(error) == message, str(error)
                        return
                    raise AssertionError('no error: ' + message)
                m = tk.Menu(root)
                assert str(m) == '.!menu' and m.index('end') == 0 and m.type(0) == 'tearoff' and m.cget('tearoff') == 1
                calls = []
                m.add_command(label='Open', accelerator='Ctrl+O', command=lambda: calls.append('open') or 'opened', underline=0)
                m.add_separator()
                v = tk.IntVar()
                m.add_checkbutton(label='Bold', variable=v)
                s = tk.StringVar(value='b')
                m.add_radiobutton(label='A', variable=s, value='a')
                m.add_radiobutton(label='B', variable=s, value='b')
                sub = tk.Menu(m, tearoff=0)
                sub.add_command(label='Inner')
                m.add_cascade(label='More', menu=sub)
                m.add_command(label='Gone', state='disabled')
                assert (m.index('end'), m.index('last'), m.index('active'), m.index('none')) == (7, 7, None, None)
                assert (m.index('Bold'), m.index('M*'), m.index(3), m.index(99), m.index('3')) == (3, 6, 3, 7, 3)
                fails(lambda: m.index(-1), 'bad menu entry index "-1"')
                fails(lambda: m.index('zzz'), 'bad menu entry index "zzz"')
                assert [m.type(i) for i in range(8)] == ['tearoff', 'command', 'separator', 'checkbutton', 'radiobutton', 'radiobutton', 'cascade', 'command']
                assert m.type(20) == 'command'
                assert (m.entrycget(1, 'label'), m.entrycget(1, 'accelerator'), m.entrycget(1, 'state'), m.entrycget(1, 'underline')) == ('Open', 'Ctrl+O', 'normal', 0)
                fails(lambda: m.entrycget(2, 'label'), 'unknown option "-label"')
                fails(lambda: m.entrycget(1, 'zzz'), 'unknown option "-zzz"')
                assert (m.entrycget(3, 'variable'), m.entrycget(3, 'onvalue'), m.entrycget(3, 'offvalue'), m.entrycget(3, 'indicatoron')) == (str(v), '1', '0', 1)
                assert m.entrycget(4, 'value') == 'a' and m.entrycget(6, 'menu') == str(sub)
                assert sorted(m.entryconfigure(1)) == ['accelerator', 'activebackground', 'activeforeground', 'background', 'bitmap', 'columnbreak', 'command', 'compound', 'font', 'foreground', 'hidemargin', 'image', 'label', 'state', 'underline']
                assert sorted(m.entryconfigure(2)) == ['background'] and sorted(m.entryconfigure(0)) == ['background', 'state']
                assert sorted(m.entryconfigure(4)) == ['accelerator', 'activebackground', 'activeforeground', 'background', 'bitmap', 'columnbreak', 'command', 'compound', 'font', 'foreground', 'hidemargin', 'image', 'indicatoron', 'label', 'selectcolor', 'selectimage', 'state', 'underline', 'value', 'variable']
                assert m.entryconfigure(1, 'label') == ('label', '', '', '', 'Open')
                assert m.entryconfigure(1)['compound'] == ('compound', 'compound', 'Compound', 'none', 'none')
                assert m.entryconfigure(4)['variable'] == ('variable', '', '', 'selectedButton', str(s))
                assert m.invoke(1) == 'opened' and calls == ['open']
                assert (m.invoke(3), v.get()) == ('', 1) and (m.invoke(3), v.get()) == ('', 0)
                assert (m.invoke(4), s.get()) == ('', 'a')
                assert m.invoke(7) == m.invoke(2) == m.invoke(6) == m.invoke(0) == ''
                # Default variables: a checkbutton's label, 'selectedButton' with the label as value.
                m2 = tk.Menu(root, tearoff=0)
                m2.add_checkbutton(label='Wrap')
                m2.add_radiobutton(label='X')
                m2.add_radiobutton(label='Y')
                assert (m2.entrycget(0, 'variable'), m2.entrycget(1, 'variable'), m2.entrycget(1, 'value')) == ('Wrap', 'selectedButton', 'X')
                fails(lambda: root.getvar('Wrap'), 'can\\'t read "Wrap": no such variable')
                assert (m2.invoke(0), root.getvar('Wrap')) == ('', '1')
                assert (m2.invoke(2), root.getvar('selectedButton')) == ('', 'Y')
                assert m2.index('end') == 2
                m2.insert_command(1, label='Ins')
                assert [m2.entrycget(i, 'label') for i in range(4)] == ['Wrap', 'Ins', 'X', 'Y']
                m2.insert_separator(0)
                assert [m2.type(i) for i in range(5)] == ['separator', 'checkbutton', 'command', 'radiobutton', 'radiobutton']
                m2.delete(0)
                m2.delete(1, 2)
                assert [m2.entrycget(i, 'label') for i in range(m2.index('end') + 1)] == ['Wrap', 'Y']
                m2.delete(0, 'end')
                assert m2.index('end') is None and m2.delete(5) is None
                fails(lambda: m2.add('zzz', label='x'), 'bad menu entry type "zzz": must be cascade, checkbutton, command, radiobutton, or separator')
                fails(lambda: m2.add_command(zzz=1), 'unknown option "-zzz"')
                fails(lambda: m2.add_separator(label='x'), 'unknown option "-label"')
                m.activate(1)
                assert m.index('active') == 1
                m.activate(2)
                assert m.index('active') is None, 'a separator is never active'
                assert m.cget('type') == 'normal' and m.cget('background') == '#d9d9d9' and m.cget('activebackground') == '#ececec'
                assert sorted(m.keys()) == ['activebackground', 'activeborderwidth', 'activeforeground', 'background', 'bd', 'bg', 'borderwidth', 'cursor', 'disabledforeground', 'fg', 'font', 'foreground', 'postcommand', 'relief', 'selectcolor', 'takefocus', 'tearoff', 'tearoffcommand', 'title', 'type']
                assert m.configure('activebackground') == ('activebackground', 'activeBackground', 'Foreground', '#ececec', '#ececec')
                fails(lambda: m.pack(), 'can\\'t pack ".!menu": it\\'s a top-level window')
                assert (m.yposition(1), m.xposition(1)) == (9, 1)
                assert m.index('@40') == 3 and m.index('@500') is None
                # Programs post menus; postcommand runs first.
                posted = []
                p = tk.Menu(root, tearoff=0, postcommand=lambda: posted.append('post'))
                p.add_command(label='one')
                p.add_command(label='two')
                assert p.post(100, 100) is None and posted == ['post']
                p.unpost()
                p.tk_popup(10, 10, 1)
                assert p.index('active') == 1
                p.unpost()
                # The menubar: between title bar and client, outside winfo_height and the geometry.
                bar = tk.Menu(root)
                filem = tk.Menu(bar, tearoff=0)
                bold = tk.BooleanVar()
                chosen = []
                filem.add_command(label='Open', command=lambda: chosen.append('open'))
                filem.add_checkbutton(label='Bold', variable=bold, command=lambda: chosen.append(bold.get()))
                bar.add_cascade(label='File', menu=filem)
                bar.add_command(label='Help', command=lambda: chosen.append('help'))
                before = (root.winfo_height(), root.winfo_rooty(), root.geometry())
                root.config(menu=bar)
                root.update()
                assert root.cget('menu') == str(bar)
                after = (root.winfo_height(), root.winfo_rooty(), root.geometry())
                assert before[0] == after[0] == 200 and before[2] == after[2] == '300x200+20+30', (before, after)
                assert after[1] == before[1] + 20, (before, after)
                # Menubutton and OptionMenu (classic and ttk).
                mb = tk.Menubutton(root, text='Pick')
                assert (mb.cget('direction'), mb.cget('indicatoron'), mb.cget('relief'), mb.cget('menu'), mb.winfo_class()) == ('below', 0, 'flat', '', 'Menubutton')
                mm = tk.Menu(mb, tearoff=0)
                mb['menu'] = mm
                assert mb.cget('menu') == str(mm)
                fails(lambda: mb.configure(direction='up'), 'bad direction "up": must be above, below, flush, left, or right')
                var = tk.StringVar(root)
                om = tk.OptionMenu(root, var, 'red', 'green', 'blue', command=lambda value: chosen.append(value))
                assert (om.winfo_class(), var.get(), str(om), str(om['menu']), om['menu'].index('end')) == ('Menubutton', '', '.!optionmenu', '.!optionmenu.menu', 2)
                assert [om['menu'].type(i) for i in range(3)] == ['command'] * 3
                assert (om.cget('indicatoron'), om.cget('relief'), om.cget('borderwidth'), om.cget('highlightthickness')) == (1, 'raised', 2, 2)
                assert (om['menu'].invoke(1), var.get(), chosen) == (None, 'green', ['green'])
                del chosen[:]
                tv = tk.StringVar(root)
                tom = ttk.OptionMenu(root, tv, 'b', 'a', 'b', 'c')
                assert (tv.get(), tom.winfo_class(), tom['menu'].index('end'), tom.cget('text')) == ('b', 'TMenubutton', 2, 'b')
                assert tom['menu'].type(0) == 'radiobutton' and tom['menu'].entrycget(0, 'variable') == str(tv)
                tom.set_menu('z', 'x', 'y', 'z')
                assert (tv.get(), tom['menu'].index('end'), [tom['menu'].entrycget(i, 'label') for i in range(3)]) == ('z', 2, ['x', 'y', 'z'])
                tmb = ttk.Menubutton(root, text='T')
                assert (tmb.cget('direction'), tmb.cget('takefocus'), tmb.winfo_class()) == ('below', 'ttk::takefocus', 'TMenubutton')
                ids = (bar._id, filem._id, root._id)
                armed = True
                while computer.uptime() < 1:
                    root.update()
                root.update()
                assert chosen == ['open'], chosen
                armed2 = True
                while computer.uptime() < 2:
                    root.update()
                root.update()
                assert chosen == ['open', True] and bold.get() is True, chosen
                armed3 = True
                while computer.uptime() < 3:
                    root.update()
                root.update()
                assert chosen == ['open', True, 'help'], chosen
                bold.set(False)
                root.update()
                unticked = True
                while computer.uptime() < 4:
                    root.update()
                passed = True
                """);
        host.runUntil("armed");
        TkTree tree = tile.widgets();
        Object[] ids = ((net.mpy.runtime.PyObj.Tuple) host.vm.globals.get("ids")).items;
        int file = ((Number) ids[1]).intValue(), root = ((Number) ids[2]).intValue();
        java.util.function.BiFunction<Integer, Integer, TkDisplayList.Item> part = (id, p) ->
                tree.render().items.stream().filter(i -> i.id == id && i.part == p).findFirst().orElse(null);
        // The menubar's entries are parts 18, 19 of the window (TkWindow.PART_MENUBAR + 1 + index; 0 is the tearoff, not shown).
        TkRect fileEntry = part.apply(root, 18).bounds, helpEntry = part.apply(root, 19).bounds;
        // The player opens File and chooses Open (the box's first row: y 1..21).
        tree.pointerDown(1, fileEntry.x + 3, fileEntry.y + 5);
        tree.pointerUp(1, fileEntry.x + 3, fileEntry.y + 5);
        assertTrue(tree.listOpen());
        TkRect box = part.apply(file, TkDisplayList.POPUP_PART).bounds;
        assertEquals(fileEntry.x, box.x); assertEquals(fileEntry.y + fileEntry.height, box.y, "below the menubar entry");
        tree.pointerDown(1, box.x + 10, box.y + 10);
        tree.pointerUp(1, box.x + 10, box.y + 10);
        assertFalse(tree.listOpen());
        host.time = 1;
        host.runUntil("armed2");
        // Then Bold, by keys: F10, Down, Return.
        tree.key(68, 0); tree.key(208, 0); tree.key(28, 0);
        host.time = 2;
        host.runUntil("armed3");
        tree.key(68, 0);
        assertTrue(tree.render().items.stream().anyMatch(i -> i.id == file && "✓".equals(i.text)), "the variable's indicator");
        tree.key(1, 0);
        tree.pointerDown(1, helpEntry.x + 3, helpEntry.y + 5);
        tree.pointerUp(1, helpEntry.x + 3, helpEntry.y + 5);
        host.time = 3;
        host.runUntil("unticked");
        tree.key(68, 0);
        assertFalse(tree.render().items.stream().anyMatch(i -> i.id == file && "✓".equals(i.text)), "set by the program: the indicator follows");
        tree.key(1, 0);
        host.time = 4;
        host.runUntil("passed");
    }

    @Test void treeviewMatchesTk() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                root.geometry('320x240')
                def fails(f, message):
                    try:
                        f()
                    except tk.TclError as error:
                        assert str(error) == message, str(error)
                        return
                    raise AssertionError('no error: ' + message)
                t = ttk.Treeview(root)
                assert (t.cget('columns'), t.cget('displaycolumns'), t.cget('height'), t.cget('selectmode'), t.cget('show'), t.cget('padding'),
                        t.cget('takefocus'), t.winfo_class()) == ('', ('#all',), 10, 'extended', ('tree', 'headings'), '', 'ttk::takefocus', 'Treeview')
                ids = [t.insert('', 'end', text='n%d' % i) for i in range(20)]
                assert ids[:3] == ['I001', 'I002', 'I003'] and ids[9:12] == ['I00A', 'I00B', 'I00C'] and ids[15:17] == ['I010', 'I011'], ids
                assert t.get_children()[:3] == ('I001', 'I002', 'I003') and len(t.get_children()) == 20
                a = t.insert('', 0, 'a', text='A', values=(1, 'two', 3.5), open=True, tags=('t1', 't2'))
                assert t.item('a') == {'text': 'A', 'image': '', 'values': [1, 'two', '3.5'], 'open': 1, 'tags': ['t1', 't2']}, t.item('a')
                assert (t.item('a', 'text'), t.item('a', 'values'), t.item('a', 'open'), t.item('a', 'tags'), t.item('a', 'image')) == ('A', ('1', 'two', '3.5'), 1, ('t1', 't2'), '')
                b = t.insert('a', 'end', 'b', text='B')
                assert t.item('b') == {'text': 'B', 'image': '', 'values': '', 'open': 0, 'tags': ''}
                fails(lambda: t.insert('', 'end', 'a'), 'Item a already exists')
                fails(lambda: t.insert('zz', 'end'), 'Item zz not found')
                fails(lambda: t.item('zz'), 'Item zz not found')
                assert (t.parent('b'), t.parent('a'), t.index('b'), t.index('a'), t.index(ids[0])) == ('a', '', 0, 0, 1)
                assert (t.next('a'), t.prev(ids[0]), t.next(ids[19]), t.prev('a')) == ('I001', 'a', '', '')
                assert t.exists('a') and not t.exists('zz')
                t.move('b', '', 1)
                assert (t.parent('b'), t.index('b'), t.get_children()[:3]) == ('', 1, ('a', 'b', 'I001'))
                fails(lambda: t.move('a', 'a', 0), 'Cannot insert a as descendant of a')
                c = t.insert('b', 'end', 'c')
                fails(lambda: t.move('b', 'c', 0), 'Cannot insert b as descendant of c')
                t.detach('b')
                assert t.exists('b') and t.parent('b') == '' and 'b' not in t.get_children() and t.index('b') == 0
                t.reattach('b', '', 'end')
                assert t.get_children()[-1] == 'b'
                t.delete('b')
                assert not t.exists('b') and not t.exists('c')
                fails(lambda: t.delete('zz'), 'Item zz not found')
                fails(lambda: t.delete(''), 'Cannot delete root item')
                assert t.index(t.insert('', 999, 'big')) == 21 and t.index(t.insert('', -5, 'neg')) == 0
                # Columns and headings.
                t2 = ttk.Treeview(root, columns=('x', 'y', 'z'))
                assert t2.cget('columns') == ('x', 'y', 'z') and t2.cget('displaycolumns') == ('#all',)
                assert t2.column('x') == {'width': 200, 'minwidth': 20, 'stretch': 1, 'anchor': 'w', 'id': 'x'}
                assert t2.column('#0') == {'width': 200, 'minwidth': 20, 'stretch': 1, 'anchor': 'w', 'id': ''}
                assert t2.column('#1')['id'] == 'x'
                assert t2.heading('x') == {'text': '', 'image': '', 'anchor': 'center', 'command': '', 'state': ''}
                assert t2.heading('x', text='Ex', anchor='e') == {}
                assert t2.heading('x') == {'text': 'Ex', 'image': '', 'anchor': 'e', 'command': '', 'state': ''} and t2.heading('x', 'text') == 'Ex'
                t2.column('y', width=50, minwidth=10, stretch=False, anchor='center')
                assert t2.column('y') == {'width': 50, 'minwidth': 10, 'stretch': 0, 'anchor': 'center', 'id': 'y'}
                fails(lambda: t2.column('nope'), 'Invalid column index nope')
                fails(lambda: t2.column('#9'), 'Column #9 out of range')
                i = t2.insert('', 'end', text='r', values=('1', '2'))
                assert t2.set(i) == {'x': '1', 'y': '2'} and t2.set(i, 'y') == '2' and t2.set(i, 'z') == ''
                t2.set(i, 'z', 'zz')
                assert t2.set(i) == {'x': '1', 'y': '2', 'z': 'zz'} and t2.item(i, 'values') == ('1', '2', 'zz')
                fails(lambda: t2.set(i, 'q'), 'Invalid column index q')
                t2.configure(displaycolumns=('z', 'x'))
                assert t2.cget('displaycolumns') == ('z', 'x') and t2.column('#1')['id'] == 'z'
                t2.configure(displaycolumns='#all')
                assert t2.cget('displaycolumns') == ('#all',)
                fails(lambda: t2.configure(displaycolumns=('q',)), 'Invalid column index q')
                fails(lambda: t2.configure(selectmode='many'), 'bad selectmode "many": must be none, browse, or extended')
                fails(lambda: t2.configure(show='nope'), 'bad value "nope": must be tree or headings')
                v = ttk.Treeview(root, columns=('a',))
                k = v.insert('', 'end', values=('x', 'y', 'z'))
                assert v.item(k, 'values') == ('x', 'y', 'z') and v.set(k) == {'a': 'x'}
                assert v.item(k, text='q') == {} and v.item(k, 'text') == 'q'
                v.insert(k, 'end', 'kid')
                assert v.get_children(k) == ('kid',)
                v.set_children(k)
                assert v.get_children(k) == () and v.exists('kid')
                w = ttk.Treeview(root)
                for n in 'abc':
                    w.insert('', 'end', n)
                w.set_children('', 'c', 'a')
                assert w.get_children() == ('c', 'a') and w.exists('b') and w.parent('b') == ''
                # Selection: <<TreeviewSelect>> after each selection command, queued.
                t3 = ttk.Treeview(root)
                items = [t3.insert('', 'end', text=str(n)) for n in range(5)]
                events = []
                t3.bind('<<TreeviewSelect>>', lambda e: events.append(t3.selection()))
                t3.selection_set(items[1])
                assert t3.selection() == ('I002',) and events == []
                root.update()
                assert events == [('I002',)]
                del events[:]
                t3.selection_add(items[3], items[2])
                t3.selection_remove(items[1])
                t3.selection_toggle(items[0])
                root.update()
                assert t3.selection() == ('I001', 'I003', 'I004') and len(events) == 3, events
                t3.configure(selectmode='browse')
                t3.selection_set(items[0], items[1])
                assert t3.selection() == ('I001', 'I002'), 'browse limits the player, not the program'
                fails(lambda: t3.selection_set('zz'), 'Item zz not found')
                assert t3.focus() == ''
                t3.focus(items[2])
                assert t3.focus() == 'I003'
                t3.delete(items[2])
                assert t3.focus() == '' and t3.selection() == ('I001', 'I002')
                # Tags.
                t4 = ttk.Treeview(root)
                x = t4.insert('', 'end', text='x', tags=('red',))
                assert t4.tag_configure('red', foreground='red') == {}
                assert t4.tag_configure('red') == {'text': '', 'image': '', 'anchor': '', 'background': '', 'foreground': 'red', 'font': ''}
                assert t4.tag_has('red') == ('I001',) and t4.tag_has('red', x) and not t4.tag_has('blue', x)
                assert t4.item(t4.insert('', 'end', tags='p q'), 'tags') == ('p', 'q')
                # Geometry, bbox, see, identify (a 5-row view of 30 items, one column).
                t5 = ttk.Treeview(root, columns=('c1',), height=5)
                t5.place(x=0, y=0)
                rows = [t5.insert('', 'end', text='row%d' % n, values=('v%d' % n,)) for n in range(30)]
                root.update_idletasks()
                assert (t5.winfo_width(), t5.winfo_height(), t5.winfo_reqwidth(), t5.winfo_reqheight()) == (402, 121, 402, 121)
                assert (t5.bbox(rows[0]), t5.bbox(rows[0], 'c1'), t5.bbox(rows[0], '#0')) == ((1, 20, 400, 20), (201, 20, 200, 20), (1, 20, 200, 20))
                assert t5.bbox(rows[10]) == ''
                assert t5.yview() == (0.0, 5 / 30)
                t5.see(rows[20])
                assert t5.yview() == (16 / 30, 21 / 30) and t5.bbox(rows[20]) == (1, 100, 400, 20)
                assert (t5.identify_region(5, 5), t5.identify_region(5, 30), t5.identify_row(30), t5.identify_column(5), t5.identify_column(250),
                        t5.identify_element(5, 30)) == ('heading', 'tree', 'I011', '#0', '#1', 'Treeitem.indicator')
                assert (t5.identify_row(500), t5.identify_region(500, 500)) == ('', 'nothing')
                assert [t5.identify_region(n, 5) for n in (195, 197, 205, 206)] == ['heading', 'separator', 'separator', 'heading']
                t5.yview_scroll(2, 'units')
                assert t5.yview() == (18 / 30, 23 / 30)
                assert ttk.Treeview(root, columns=('a', 'b'), show='headings', height=3).winfo_reqheight() == 81
                assert ttk.Treeview(root, columns=('a',), show=('tree',), height=3).winfo_reqheight() == 62
                assert ttk.Treeview(root, show='', height=3).cget('show') == ''
                # Columns follow the width as Tk's slack algorithm moves them.
                u = ttk.Treeview(root, columns=('c1', 'c2'), height=2)
                u.place(x=0, y=0, width=300)
                root.update_idletasks()
                assert (u.column('#0', 'width'), u.column('c1', 'width'), u.column('c2', 'width')) == (99, 99, 100)
                u.place(width=701)
                root.update_idletasks()
                assert (u.column('#0', 'width'), u.column('c1', 'width'), u.column('c2', 'width')) == (232, 233, 234)
                u.column('c1', stretch=False)
                u.place(width=900)
                root.update_idletasks()
                assert (u.column('#0', 'width'), u.column('c1', 'width'), u.column('c2', 'width')) == (332, 233, 333)
                u.column('#0', minwidth=250)
                u.place(width=200)
                root.update_idletasks()
                assert (u.column('#0', 'width'), u.column('c1', 'width'), u.column('c2', 'width')) == (250, 233, 20)
                u.column('c2', width=5)
                assert u.column('c2', 'width') == 20
                # Heading commands, tag bindings.
                clicked = []
                h = ttk.Treeview(root, columns=('a',), height=3)
                h.heading('a', text='A', command=lambda: clicked.append('a'))
                assert h.heading('a', 'command') != ''
                tagged = []
                hot = h.insert('', 'end', 'hot', text='hot', tags=('hot',))
                h.tag_bind('hot', '<Button-1>', lambda e: tagged.append(e.y))
                widget_clicks = []
                h.bind('<Button-1>', lambda e: widget_clicks.append(1))
                h.place(x=0, y=0)
                root.update_idletasks()
                h.event_generate('<Button-1>', x=10, y=25)
                h.event_generate('<Button-1>', x=10, y=50)
                assert tagged == [25] and widget_clicks == [1, 1], (tagged, widget_clicks)
                opened = []
                h.bind('<<TreeviewOpen>>', lambda e: opened.append(h.focus()))
                h_id, t5_id = h._id, t5._id
                armed = True
                while not clicked or not opened:
                    root.update()
                assert clicked == ['a'] and opened == ['hot']
                passed = True
                """);
        host.runUntil("armed");
        TkTree tree = tile.widgets();
        Treeview h = (Treeview) tree.get(((Number) host.vm.globals.get("h_id")).intValue());
        TkRect b = h.bounds();
        // A player clicks the heading of column a, then double-clicks the row (it has no children: Tk toggles it all the same).
        tree.pointerDown(1, b.x + 300, b.y + 5);
        tree.pointerUp(1, b.x + 300, b.y + 5);
        tree.pointerDown(1, b.x + 60, b.y + 25);
        tree.pointerUp(1, b.x + 60, b.y + 25);
        tree.pointerDown(1, b.x + 60, b.y + 25);
        tree.pointerUp(1, b.x + 60, b.y + 25);
        host.runUntil("passed");
    }

    /** ttk stage 5: the built-in themes answer as Tk 8.6 does, and widgets draw with their style in the theme in use. */
    @Test void stylesChangeHowWidgetsLook() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start("""
                import tkinter as tk
                from tkinter import ttk
                root = tk.Tk()
                root.geometry('320x240')
                st = ttk.Style()
                # Tk's own answers for the built-in themes (themes.py, themes2.py).
                assert st.configure('TButton') == {'anchor': 'center', 'padding': '3 3', 'relief': 'raised', 'shiftrelief': 1, 'width': '-9'}
                assert st.map('TButton') == {'relief': [('!disabled', 'pressed', 'sunken')]}
                assert (st.lookup('TButton', 'padding'), st.lookup('TButton', 'background'), st.lookup('TButton', 'background', ('active',)),
                        st.lookup('Accent.TButton', 'relief', ('pressed',))) == ('3 3', '#d9d9d9', '#ececec', 'sunken')
                assert st.element_names()[:5] == ('label', '', 'focus', 'treearea', 'separator')
                assert st.element_options('border') == ('background', 'borderwidth', 'relief') == st.element_options('Button.border')
                assert st.element_options('nope') == ()
                assert st.layout('TButton')[0][0] == 'Button.border' and st.layout('TButton')[0][1]['border'] == '1'
                assert st.map('.') == {'foreground': [('disabled', '#a3a3a3')], 'background': [('disabled', '#d9d9d9'), ('active', '#ececec')]}
                assert st.lookup('TCheckbutton', 'indicatorcolor', ('selected',)) == '#4a6984'
                assert st.lookup('TCheckbutton', 'indicatorcolor', ('selected', 'disabled')) == '#a3a3a3'
                st.theme_use('alt')
                assert st.element_options('Button.border') == ('background', 'bordercolor', 'default', 'borderwidth', 'relief')
                st.theme_create('mine', parent='alt')
                st.theme_use('mine')
                assert st.element_names() == () and st.lookup('TButton', 'background') == '' and st.configure('.') is None
                assert st.element_options('Checkbutton.indicator') == ('background', 'foreground', 'indicatorcolor', 'lightcolor', 'shadecolor', 'bordercolor', 'indicatormargin')
                st.element_create('My.border', 'from', 'clam')
                assert st.element_names() == ('My.border',) and st.element_options('My.border') == ('bordercolor', 'lightcolor', 'darkcolor', 'relief', 'borderwidth')
                st.element_create('Z.indicator', 'from', 'clam', 'Checkbutton.indicator')
                assert st.element_options('Z.indicator') == ('indicatorsize', 'indicatormargin', 'indicatorbackground', 'indicatorforeground', 'upperbordercolor', 'lowerbordercolor')
                st.theme_use('clam')
                assert st.lookup('TButton', 'background') == '#dcdad5' and st.configure('.')['bordercolor'] == '#9e9a91'
                st.theme_use('default')
                # Widgets draw with it.
                b = ttk.Button(root, text='go')
                b.pack()
                c = ttk.Checkbutton(root, text='c')
                c.pack()
                own = ttk.Button(root, text='own', background='#123456')
                own.pack()
                bar = ttk.Progressbar(root, value=50, length=100)
                bar.pack()
                book = ttk.Notebook(root)
                book.add(ttk.Frame(book), text='one')
                book.add(ttk.Frame(book), text='two')
                book.pack()
                ids = (b._id, c._id, own._id, bar._id, book._id)
                root.update()
                armed = True
                while computer.uptime() < 1:
                    root.update()
                st.theme_use('clam')
                root.update()
                switched = True
                while computer.uptime() < 2:
                    root.update()
                st.configure('TButton', background='red')
                st.map('TCheckbutton', indicatorbackground=[('selected', 'green')])
                root.update()
                styled = True
                while computer.uptime() < 3:
                    root.update()
                assert tk._color('SteelBlue') == 0x4682b4 and tk._color('grey50') == 0x7f7f7f
                st.theme_use('classic')
                root.update()
                classic = True
                while computer.uptime() < 4:
                    root.update()
                passed = True
                """);
        host.runUntil("armed");
        TkTree tree = tile.widgets();
        Object[] ids = ((net.mpy.runtime.PyObj.Tuple) host.vm.globals.get("ids")).items;
        int b = ((Number) ids[0]).intValue(), c = ((Number) ids[1]).intValue(), own = ((Number) ids[2]).intValue(),
                bar = ((Number) ids[3]).intValue(), book = ((Number) ids[4]).intValue();
        java.util.function.BiFunction<Integer, Integer, TkDisplayList.Item> part = (id, p) ->
                tree.render().items.stream().filter(i -> i.id == id && i.part == p).findFirst().get();
        // Default theme: TButton has '.''s background, lighter under the pointer (active), sunken while pressed.
        assertEquals(0xD9D9D9, part.apply(b, 0).background);
        assertEquals(TkRelief.RAISED, part.apply(b, 0).relief);
        TkRect r = tree.get(b).bounds();
        tree.pointerHover(r.x + 2, r.y + 2);
        assertEquals(0xECECEC, part.apply(b, 0).background, "active: the pointer is over it");
        tree.pointerDown(9, r.x + 2, r.y + 2);
        assertEquals(TkRelief.SUNKEN, part.apply(b, 0).relief, "pressed: the TButton map's sunken");
        tree.pointerUp(9, r.x + 2, r.y + 2);
        tree.pointerHover(300, 230);
        assertEquals(0xD9D9D9, part.apply(b, 0).background);
        assertTrue(tree.get(b).bounds().width >= 9 * TkFontMetrics.CELL_WIDTH, "TButton's -width -9: at least nine characters");
        assertEquals(0x123456, part.apply(own, 0).background, "a background the program set wins over the style");
        assertEquals(0x9FBDD8, part.apply(c, 1).background, "a Checkbutton without a variable is 'alternate'");
        assertEquals(0x4A6984, part.apply(bar, 1).background, "TProgressbar's bar");
        assertEquals(0xC3C3C3, part.apply(bar, 0).background, "and '.''s troughcolor");
        assertEquals(0xD9D9D9, part.apply(book, 2).background, "the selected tab");
        assertEquals(0xC3C3C3, part.apply(book, 3).background, "TNotebook.Tab's background");
        host.time = 1;
        host.runUntil("switched");
        assertEquals(0xDCDAD5, part.apply(b, 0).background, "clam");
        assertEquals(0xBAB5AB, part.apply(book, 3).background, "clam's tab map: the unselected one");
        assertEquals(0x5895BC, part.apply(c, 1).background, "clam has no indicatorcolor: its indicatorbackground, alternate in its map");
        host.time = 2;
        host.runUntil("styled");
        assertEquals(0xFF0000, part.apply(b, 0).background, "a style setting shows");
        tree.get(c);
        host.time = 3;
        host.runUntil("classic");
        assertEquals(0x4682B4, part.apply(bar, 1).background, "classic's TProgressbar is SteelBlue, a Tk colour name");
        host.time = 4;
        host.runUntil("passed");
    }

    @Test void starImportOfTtkKeepsTkintersWidget() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "from tkinter import *", "from tkinter.ttk import *",
                "assert Widget is tk.Widget and Button is tk.ttk.Button and Style is tk.ttk.Style",
                "try:", "    tkinter", "    raise AssertionError('tkinter is not in ttk.__all__')", "except NameError:", "    pass",
                "passed = True", ""));
        host.runUntil("passed");
        assertEquals(Boolean.TRUE, host.vm.globals.get("passed"));
    }

    @Test void textMatchesTk() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start(Files.readString(Path.of("src/test/resources/tk-text-cases.py"), java.nio.charset.StandardCharsets.UTF_8));
        host.runUntil("passed");
        com.google.gson.JsonArray expected = new com.google.gson.JsonParser().parse(Files.readString(
                Path.of("src/test/resources/tk-text-api-golden.json"), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("cases");
        com.google.gson.JsonArray actual = new com.google.gson.JsonParser().parse((String) host.vm.globals.get("result_json")).getAsJsonArray();
        assertEquals(expected.size(), actual.size());
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            final int index = i;
            checks.add(() -> assertEquals(expected.get(index), actual.get(index), expected.get(index).getAsJsonArray().get(0).getAsString()));
        }
        assertAll(checks);
    }

    @Test void textApiAndCallbacksSurviveJointSnapshotRestore() throws Exception {
        Host host=new Host();TileTkDisplay tile=new TileTkDisplay();host.tiles.put("screen-a",tile);
        host.start("""
                import tkinter as tk
                root = tk.Tk()
                text = tk.Text(root, width=20, height=5, wrap=tk.WORD, undo=True)
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
                text.tag_add('link', '1.0', '1.end')
                text.edit_separator()
                text.tag_configure('link', foreground='red', underline=True)
                tags_seen = []
                stale = text.tag_bind('link', '<Button-1>', lambda e: tags_seen.append('stale'))
                text.tag_bind('link', '<Button-1>', lambda e: tags_seen.append('link'))
                extra = text.tag_bind('link', '<Button-1>', lambda e: tags_seen.append('extra'), '+')
                text.tag_unbind('link', '<Button-1>', extra)
                assert text.tag_bind('link') == ('<Button-1>',)
                text.tag_bind('link', '<Enter>', lambda e: tags_seen.append(e.type))
                text.tag_bind('link', '<Leave>', lambda e: tags_seen.append(e.type))
                text.tag_bind('link', '<Key>', lambda e: tags_seen.append('key'))
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
                    text.edit_undo()
                    assert text.get('2.0', '2.end') == 'second' and not text.edit_modified()
                    text.edit_redo()
                    assert text.get('2.0', '2.end') == 'se恢复cond'
                    x, y, w, h = text.bbox('1.0')
                    text.event_generate('<Motion>', x=x+1, y=y+1)
                    text.event_generate('<Button-1>', x=x+1, y=y+1)
                    text.mark_set('current', '1.0')
                    text.event_generate('<Key>', keysym='a')
                    text._notified('leave')
                    assert tags_seen == ['<Enter>', 'link', 'key', '<Leave>'], tags_seen
                    seen.append(True)
                def checked_click():
                    try:
                        clicked()
                    except Exception as error:
                        seen.append(str(error))
                button = tk.Button(root, text='restore', command=checked_click)
                button.pack()
                armed = True
                while not seen:
                    root.update()
                assert seen == [True], seen
                text.destroy()
                button.destroy()
                bar.destroy()
                assert not root._callbacks
                assert stale not in root._commands and extra not in root._commands
                passed = True
                """);
        host.runUntil("armed");byte[] saved=host.vm.saveSnapshot(host.registry);NBTTagCompound nbt=new NBTTagCompound();tile.writeToNBT(nbt);
        Host restored=new Host();TileTkDisplay copy=new TileTkDisplay();copy.readFromNBT(nbt);restored.tiles.put("screen-a",copy);
        restored.vm=Vm.fromSnapshot(saved,restored.globals,restored.registry);assertEquals(tile.scene(),copy.scene());
        restored.click(copy,4);restored.runUntil("passed");   // the button (1 is the root window)
    }

    @Test void textEditorDemoFeedsReadOnlyLogAndSnapshotsEditor() throws Exception {
        Host host=new Host();TileTkDisplay tile=reobf.mpy4oc.tk.TkTestGroups.joined(2,2);host.tiles.put("screen-a",tile);
        host.reg("uptime",args->{host.time+=.01;return host.time;});
        String demo=Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tktext.py"));
        host.start(demo.replace("root.mainloop()","while log_count < 1000:\n    root.update()\nassert log['state'] == tk.DISABLED\nassert log.index('end-1c') == '1001.0'\nsnapshot()\nassert status['text'].startswith('Snapshot taken')\nclear_log()\nassert log.get('1.0', tk.END) == '\\n'\npassed = True\n"));
        host.runUntil("passed");
    }

    private static void set(Object object, String name, Object value) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }

    /**
     * Programs of one computer share displays: Tk() opens where this computer already has
     * windows, else on the first display by address that no other running computer holds;
     * naming a display opens there. With every display held by others there is none.
     */
    @Test void tkOpensBesideThisComputersWindowsElseOnTheFirstFreeDisplay() throws Exception {
        TileTkDisplay a = new TileTkDisplay(), b = new TileTkDisplay(), c = new TileTkDisplay();
        a.ownerAlive = address -> true;
        a.application().call("other-computer", "claim", "");   // screen-a: another running computer's
        Host host = new Host(); host.tiles.put("screen-c", c); host.tiles.put("screen-a", a); host.tiles.put("screen-b", b);
        host.start(String.join("\n", "import tkinter as tk, _program",
                "first = tk.Tk()", "assert first.winfo_screen() == 'screen-b', first.winfo_screen()",
                "_program.set_current('job:1')",
                "second = tk.Tk()", "assert second.winfo_screen() == 'screen-b' and second._token != first._token",
                "third = tk.Tk('screen-c')", "assert third.winfo_screen() == 'screen-c'",
                "assert tk._release_owned('job:1') == 2 and first.winfo_exists() and not second.winfo_exists()",
                "_program.set_current(None)",
                "assert list(tk._roots) == [first._token]",
                "passed = True", ""));
        host.runUntil("passed");
        assertEquals(1, b.application().tokens().size(), "first is left on screen-b");
        assertTrue(c.application().tokens().isEmpty());
        assertEquals("other-computer", a.application().owner());

        TileTkDisplay held = new TileTkDisplay();
        held.ownerAlive = address -> true;
        held.application().call("other-computer", "claim", "");
        Host none = new Host(); none.tiles.put("screen-x", held);
        none.start(String.join("\n", "import tkinter as tk",
                "try:", "    tk.Tk()", "    raise AssertionError('opened on a display another computer holds')",
                "except OSError as error:", "    assert str(error) == 'no free tk display: every one is in use', str(error)",
                "assert not tk._roots", "passed = True", ""));
        none.runUntil("passed");
    }

    @Test void releaseAllFreesDisplaysLeftBehindByEndedPrograms() throws Exception {
        // MPYOS calls _release_all() when a command ends; before, a program that stopped
        // without destroy() kept its display until reboot.
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "r = tk.Tk()", "other = tk.Tk()",
                "assert r.winfo_screen() == other.winfo_screen() == 'screen-a', 'the two share the display'",
                "tk._release_all()", "assert not r.winfo_exists() and not other.winfo_exists()",
                "r2 = tk.Tk()", "assert r2.winfo_screen() == 'screen-a'", "passed = True", ""));
        host.runUntil("passed");
        assertEquals(1, host.tiles.get("screen-a").application().tokens().size(), "only r2 is left on the display");
    }

    @Test void destroyAfterTheDisplayVanishedStillForgetsTheWindow() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay()); host.tiles.put("screen-b", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "go = False", "r = tk.Tk(screenName='screen-a')", "armed = True",
                "while not go:", "    r.update()", "r.destroy()", "assert not r.winfo_exists()",
                "r2 = tk.Tk(screenName='screen-b')", "passed = True", ""));
        host.runUntil("armed");
        host.tiles.remove("screen-a"); host.vm.globals.put("go", Boolean.TRUE);
        host.runUntil("passed");
    }

    @Test void closeButtonSendsWmDeleteWindowWhichAProtocolHandlerCanIntercept() throws Exception {
        Host host = new Host(); TileTkDisplay tile = new TileTkDisplay(); host.tiles.put("screen-a", tile);
        host.start(String.join("\n", "import tkinter as tk", "r = tk.Tk(screenName='screen-a')", "asked = []",
                "r.protocol('WM_DELETE_WINDOW', lambda: asked.append(1))", "assert 'WM_DELETE_WINDOW' in r.protocol()",
                "armed = True", "while not asked:", "    r.update()", "assert r.winfo_exists()",
                "r._protocols.clear()", "second = True",
                "r.mainloop()", "assert not r.winfo_exists()", "passed = True", ""));
        host.runUntil("armed");
        // The close box of the root's window (the only window on the desktop).
        int window = tile.widgets().root().children().get(0).id;
        host.signal("tk_close", "screen-a", token(tile), window, "Player");
        host.runUntil("second");
        // Without a handler, tkinter's default destroys the window and mainloop returns.
        host.signal("tk_close", "screen-a", token(tile), window, "Player");
        host.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty(), "the display was released");
    }

    @Test void scaleKeepsFractionsTimersAreDistinctAcrossWindowsAndListboxGetBatches() throws Exception {
        Host host = new Host(); host.tiles.put("screen-a", new TileTkDisplay()); host.tiles.put("screen-b", new TileTkDisplay());
        host.start(String.join("\n", "import tkinter as tk", "r = tk.Tk(screenName='screen-a')",
                "s = tk.Scale(r, from_=0, to=1, resolution=0.1)", "s.pack()", "s.set(0.7)", "s.configure(length=150)",
                "assert abs(s.get() - 0.7) < 1e-9, s.get()",
                "top = tk.Toplevel(r, screen='screen-b')",
                "a = r.after(10000, print)", "b = top.after(10000, print)", "assert a != b",
                "top.after_cancel(a)", "assert a not in r._timers and b in top._timers",
                "lb = tk.Listbox(r)", "lb.insert(tk.END, *[str(i) for i in range(600)])",
                "got = lb.get(0, tk.END)", "assert len(got) == 600 and got[599] == '599', len(got)",
                "assert lb.get(598, tk.END) == ('598', '599')",
                "passed = True", ""));
        host.runUntil("passed");
    }
}
