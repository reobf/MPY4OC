package reobf.mpy4oc.main.arch;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NBTTagCompound;
import net.mpy.vm.Vm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reobf.mpy4oc.tk.TileTkDisplay;
import reobf.mpy4oc.tk.TkTestRegistration;

/** Actual packaged scripts, compiler, VM, virtual process runtime and Tk server. */
class MpyosExplorerTest {
    @BeforeAll static void register() { TkTestRegistration.ensure(); }

    private TkPythonTest.Host host(String checks, TileTkDisplay tile) throws Exception {
        var host = TkPythonTest.shellHost(Files.readString(Path.of("src/test/resources/tkexplorer-fixture.py")) + "\n" + checks, tile);
        Path bin = Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkinter");
        host.vm.globals.put("EXPLORER", Files.readString(bin.resolve("tkexplorer.py"))
                .replace("explorer.root.mainloop()", "SHARED['explorer'] = explorer\nexplorer.root.mainloop()"));
        host.vm.globals.put("EDITOR", Files.readString(bin.resolve("tkedit.py"))
                .replace("root.mainloop()", "SHARED['editor'] = editor\nroot.mainloop()"));
        return host;
    }

    @Test void launchesInBackgroundBrowsesHistoryMountsAndHiddenFilesWithoutChangingShellCwd() throws Exception {
        var tile = new TileTkDisplay();
        var host = host("""
                app = launch('notes.txt')
                for button in (app.open_button, app.file_button, app.folder_button, app.rename_button, app.delete_button):
                    assert button.winfo_width() == button.winfo_reqwidth(), (button.cget('text'), button.winfo_width(), button.winfo_reqwidth())
                    assert button.winfo_x() + button.winfo_width() <= button.master.winfo_width(), button.cget('text')
                assert app.path == '/home' and app.selection()[0] == '/home/notes.txt'
                assert app.tree.item('0', 'text') == 'folder'
                assert not any(row[0].endswith('.hidden') for row in app.rows.values())
                app.hidden.set(True)
                app.apply_filter()
                assert any(row[0].endswith('.hidden') for row in app.rows.values())
                select_path(app, '/home/folder')
                app.open_selected()
                assert app.path == '/home/folder' and os.getcwd() == '/home'
                app.up()
                assert app.path == '/home' and app.selection()[0] == '/home/folder'
                app.history_go(-1)
                assert app.path == '/home/folder'
                app.history_go(1)
                assert app.path == '/home'
                app.address.delete(0, tk.END)
                app.address.insert(0, '../')
                app.go_address()
                assert app.path == '/' and any(row[0] == '/mnt' for row in app.rows.values())
                select_path(app, '/mnt')
                assert app.delete_button.cget('state') == 'disabled'
                app.open_selected()
                assert app.path == '/mnt' and app.file_button.cget('state') == 'disabled'
                select_path(app, '/mnt/data')
                assert app.rename_button.cget('state') == 'disabled'
                app.open_selected()
                assert app.path == '/mnt/data' and app.file_button.cget('state') == 'normal'
                assert not app.navigate('/missing') and app.path == '/mnt/data'
                assert 'Not a directory' in errors[-1]
                finish(app)
                passed = True
                """, tile);
        host.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty());
    }

    @Test void paginationFilteringSortingAndRefreshKeepWidgetAndCallbackCountsBounded() throws Exception {
        var host = host("""
                for i in range(237):
                    FAKE_VFS.files['/home/item%03d.txt' % i] = 'x' * i
                app = launch()
                assert len(app.rows) == 100 and app.next.cget('state') == 'normal'
                widgets, callbacks = len(app.root._widgets), len(app.root._callbacks)
                images = set(app.root._images)
                select_path(app, '/home/item180.txt')
                assert app.page == 1
                app.refresh()
                assert app.selection()[0] == '/home/item180.txt'
                for i in range(6):
                    app.sort()
                    app.refresh()
                assert app.selection()[0] == '/home/item180.txt'
                assert len(app.root._widgets) == widgets and len(app.root._callbacks) == callbacks
                assert len(images) == 4 and set(app.root._images) == images
                app.filter.insert(0, 'item23')
                app.apply_filter()
                assert len(app.rows) == 7 and app.page == 0
                assert app.next.cget('state') == 'disabled'
                app.filter.delete(0, tk.END)
                app.apply_filter()
                app.turn_page(2)
                assert app.page == 2 and len(app.rows) == 39
                assert app.tree.get_children()[0] == '..'
                assert app.page_label.cget('text') == '3/3 | 239 items'
                app.turn_page(1)
                assert app.page == 2
                app.filter.insert(0, 'not present')
                app.apply_filter()
                assert not app.rows and app.open_button.cget('state') == 'disabled'
                assert app.tree.get_children() == ('..',)
                finish(app)
                passed = True
                """, new TileTkDisplay());
        host.runUntil("passed");
    }

    @Test void parentEntryNavigatesWithoutAllowingRenameOrDelete() throws Exception {
        var host = host("""
                app = launch('/home/folder')
                assert app.tree.get_children()[0] == '..'
                assert app.tree.item('..', 'text') == '..'
                assert '..' not in app.rows
                app.tree.selection_set('..')
                app.select()
                assert app.open_button.cget('state') == 'normal'
                assert app.rename_button.cget('state') == app.delete_button.cget('state') == 'disabled'
                app.rename()
                app.delete()
                assert not prompts and not FAKE_VFS.renames and not FAKE_VFS.removes
                app.refresh()
                app.sort()
                assert app.tree.selection() == ('..',)
                event = tk.Event()
                event.y = app.tree.bbox('..')[1] + 5
                app.double_click(event)
                assert app.path == '/home' and app.selection()[0] == '/home/folder'
                app.history_go(-1)
                assert app.path == '/home/folder'
                app.filter.insert(0, 'no matches')
                app.apply_filter()
                assert app.tree.get_children() == ('..',)
                app.tree.selection_set('..')
                app.open_selected()
                assert app.path == '/home'
                app.navigate('/')
                assert not app.tree.exists('..')
                app.filter.delete(0, tk.END)
                app.navigate('/mnt/ro')
                assert app.readonly and app.tree.get_children()[0] == '..'
                app.tree.selection_set('..')
                event.keysym = 'Return'
                app.key(event)
                assert app.path == '/mnt' and app.selection()[0] == '/mnt/ro'
                assert os.getcwd() == '/home'
                finish(app)
                passed = True
                """, new TileTkDisplay());
        host.runUntil("passed");
    }

    @Test void createRenameAndConfirmedDeleteNeverOverwriteOrRecursivelyDeleteFolders() throws Exception {
        var host = host("""
                app = launch()
                answers.append(None)
                app.create(False)
                answers.append('draft name.txt')
                app.create(False)
                assert FAKE_VFS.files['/home/draft name.txt'] == b''
                assert app.selection()[0] == '/home/draft name.txt'
                answers.append('renamed.txt')
                app.rename()
                assert '/home/draft name.txt' not in FAKE_VFS.files
                assert app.selection()[0] == '/home/renamed.txt'
                answers.append('folder two')
                app.create(True)
                assert '/home/folder two' in FAKE_VFS.dirs
                confirmations.append(False)
                app.delete()
                assert '/home/folder two' in FAKE_VFS.dirs
                confirmations.append(True)
                app.delete()
                assert '/home/folder two' not in FAKE_VFS.dirs
                select_path(app, '/home/folder')
                confirmations.append(True)
                app.delete()
                assert '/home/folder/keep.txt' in FAKE_VFS.files and '/home/folder' not in FAKE_VFS.removes
                assert 'not empty' in errors[-1]
                select_path(app, '/home/renamed.txt')
                answers.append('notes.txt')
                app.rename()
                assert FAKE_VFS.files['/home/notes.txt'] == 'Hello explorer\\n'
                assert app.selection()[0] == '/home/renamed.txt'
                assert 'Already exists' in errors[-1]
                for invalid in ('', '.', '..', '../escape', '/absolute', 'bad\\\\name', 'bad\\nname'):
                    answers.append(invalid)
                    app.create(False)
                    assert 'single file or folder name' in errors[-1], invalid
                confirmations.append(True)
                app.delete()
                assert '/home/renamed.txt' not in FAKE_VFS.files
                finish(app)
                passed = True
                """, new TileTkDisplay());
        host.runUntil("passed");
    }

    @Test void readonlyFullDisconnectedAndRemovedFilesStayRecoverable() throws Exception {
        var host = host("""
                app = launch('/mnt/ro')
                select_path(app, '/mnt/ro/readme.txt')
                assert app.readonly and app.delete_button.cget('state') == 'disabled'
                assert app.open_button.cget('state') == 'normal'
                app.create(False)
                app.rename()
                app.delete()
                assert not prompts
                app.navigate('/home')
                FAKE_VFS.full = True
                answers.append('full.txt')
                app.create(False)
                assert 'not enough space' in errors[-1] and '/home/full.txt' not in FAKE_VFS.files
                FAKE_VFS.full = False
                FAKE_VFS.offline = True
                app.refresh()
                assert not app.ready and app.open_button.cget('state') == 'disabled'
                FAKE_VFS.offline = False
                app.refresh()
                assert app.ready
                select_path(app, '/home/notes.txt')
                FAKE_VFS.files.pop('/home/notes.txt')
                app.open_selected()
                assert 'no longer exists' in errors[-1]
                app.refresh()
                assert app.selection() is None
                finish(app)
                passed = True
                """, new TileTkDisplay());
        host.runUntil("passed");
    }

    @Test void openButtonLaunchesRealEditorWithExactFilenameAndIndependentLifetime() throws Exception {
        var tile = new TileTkDisplay();
        var host = host("""
                FAKE_VFS.files["/home/a 'quoted' name.py"] = 'value = 42\\n'
                app = launch("/home/a 'quoted' name.py")
                open_button = app.open_button._id
                armed = True
                wait(lambda: 'editor' in SHARED and SHARED['editor'].root._token)
                editor = SHARED['editor']
                assert editor.path == "/home/a 'quoted' name.py"
                assert editor.text.get('1.0', 'end-1c') == 'value = 42\\n'
                finish(app)
                assert editor.root._alive
                editor.root.after_idle(editor.root.destroy)
                wait(lambda: not _process.runtime().active)
                assert not editor.root._callbacks
                passed = True
                """, tile);
        host.runUntil("armed");
        host.click(tile, ((Number) host.vm.globals.get("open_button")).intValue());
        host.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty());
    }

    @Test void explorerAndFilesystemResumeTogetherAfterWorldSnapshot() throws Exception {
        var tile = new TileTkDisplay();
        var host = host("""
                app = launch()
                app.navigate('/home/folder')
                select_path(app, '/home/folder/keep.txt')
                armed = True
                wait(lambda: globals().get('RESTORED'))
                assert app.path == '/home/folder' and app.selection()[0] == '/home/folder/keep.txt'
                assert app.tree.item(app.tree.selection()[0], 'image') == str(app.icons['file'])
                assert len(app.root._images) == 4
                assert not app.tree.cget('showindicator')
                app.history_go(-1)
                assert app.path == '/home'
                answers.append('after restore')
                app.create(True)
                assert '/home/after restore' in FAKE_VFS.dirs
                finish(app)
                passed = True
                """, tile);
        host.runUntil("armed");
        byte[] snapshot = host.vm.saveSnapshot(host.registry);
        var nbt = new NBTTagCompound();
        tile.writeToNBT(nbt);
        var copy = new TileTkDisplay();
        copy.readFromNBT(nbt);
        var restored = new TkPythonTest.Host();
        restored.stepBudget = 300;
        restored.bootstrap = true;
        restored.start("pass");
        restored.tiles.put("screen-a", copy);
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.vm.globals.put("RESTORED", true);
        assertEquals(4, copy.scene().images.size());
        assertTrue(copy.scene().display.items.stream().anyMatch(i -> i.image != 0));
        restored.runUntil("passed");
        assertTrue(copy.application().tokens().isEmpty());
    }

    @Test void fileTypeIconsUseSharedPhotosAndClearWhenDeleted() throws Exception {
        var tile = new TileTkDisplay();
        var host = host("""
                for name in ('snake.PY', 'script.pyw', 'moon.LuA', 'unknown.bin'):
                    FAKE_VFS.files['/home/' + name] = ''
                app = launch()
                expected = {'folder': 'folder', 'snake.PY': 'python', 'script.pyw': 'python', 'moon.LuA': 'lua', 'unknown.bin': 'file', 'notes.txt': 'file'}
                for iid, row in app.rows.items():
                    kind = expected[os.path.basename(row[0])]
                    assert app.tree.item(iid, 'image') == str(app.icons[kind])
                assert all(icon.width() == 16 and icon.height() == 16 for icon in app.icons.values())
                assert app.icons['folder'].transparency_get(0, 0)
                armed = True
                wait(lambda: globals().get('CHECKED'))
                select_path(app, '/home/snake.PY')
                iid = app.tree.selection()[0]
                app.tree.item(iid, image=str(app.icons['lua']))
                assert app.tree.item(iid, 'image') == str(app.icons['lua'])
                try:
                    app.tree.item(iid, image='missing')
                    raise AssertionError('accepted a missing image')
                except ValueError:
                    pass
                app.icons['lua'].delete()
                assert app.tree.item(iid, 'image') == ''
                finish(app)
                passed = True
                """, tile);
        host.runUntil("armed");
        assertEquals(4, tile.scene().images.size());
        assertEquals(4, tile.scene().display.items.stream().filter(i -> i.image != 0).map(i -> i.image).distinct().count());
        for (var icon : tile.scene().display.items) if (icon.image != 0) {
            var cell = tile.scene().display.items.stream().filter(i -> i.id == icon.id && !i.text.isEmpty()
                    && i.bounds.y <= icon.textY && icon.textY < i.bounds.y + i.bounds.height).findFirst().orElseThrow();
            assertEquals(cell.bounds.x + 2, icon.textX);
        }
        host.vm.globals.put("CHECKED", true);
        host.runUntil("passed");
        assertTrue(tile.scene().images.isEmpty());
    }

    @Test void idleTimersPublishCompleteEntriesWithUniqueIdsAcrossTasks() throws Exception {
        var host = TkPythonTest.shellHost("""
                import time
                root = tk.Tk()
                scheduled, ran = [], []
                def schedule(worker):
                    for i in range(30):
                        scheduled.append(root.after_idle(ran.append, (worker, i)))
                        scheduled.append(root.after(0, ran.append, (worker, i + 30)))
                        time.sleep(0)
                workers = [threading.Thread(target=schedule, args=(n,)) for n in range(2)]
                for t in workers:
                    t.start()
                while any(t.is_alive() for t in workers) or root._timers:
                    root.update()
                    computer._yield_tick()
                assert len(scheduled) == len(set(scheduled)) == 120
                assert len(ran) == len(set(ran)) == 120
                root.after_idle(ran.append, 'idle')
                root.after(0, ran.append, 'normal')
                root.update_idletasks()
                assert 'idle' in ran and 'normal' not in ran
                root.update()
                assert 'normal' in ran
                root.destroy()
                passed = True
                """, new TileTkDisplay());
        host.stepBudget = 37;
        host.runUntil("passed");
    }
}
