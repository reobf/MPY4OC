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

/** Packaged app, actual compiler/VM/Tk/PIL/VFS; only the OC disks are substitutes. */
class TkPaintAppTest {
    @BeforeAll static void register() { TkTestRegistration.ensure(); }

    static String source() throws Exception {
        return Files.readString(Path.of("src/main/resources/assets/mpy4oc/mpyos/bin/tkinter/tkpaint.py"));
    }

    private TkPythonTest.Host host(String checks, TileTkDisplay tile) throws Exception {
        String script = source();
        script = script.substring(0, script.lastIndexOf("if __name__ == '__main__':"));
        String setup = Files.readString(Path.of("src/test/resources/stdio-fixture.py")) + "\n"
                + Files.readString(Path.of("src/test/resources/files-disk-fixture.py")) + "\n" + script + "\n" + """
                os.chdir('/home')
                errors, answers, paths = [], [], []
                messagebox.showerror = lambda title, message, **kw: errors.append(message)
                messagebox.askyesnocancel = lambda *a, **kw: answers.pop(0)
                messagebox.askyesno = lambda *a, **kw: answers.pop(0)
                simpledialog.askstring = lambda *a, **kw: answers.pop(0)
                simpledialog.askinteger = lambda *a, **kw: answers.pop(0)
                filedialog.asksaveasfilename = lambda **kw: paths.pop(0)
                filedialog.askopenfilename = lambda **kw: paths.pop(0)
                app = Paint(tk.Tk())
                def pointer(x, y):
                    event = tk.Event()
                    event.x, event.y = x, y
                    return event
                def draw(tool, a, b):
                    app.choose_tool(tool)
                    app.press(pointer(*a))
                    app.drag(pointer(*b))
                    app.release(pointer(*b))
                def finish_app():
                    app.close()
                    assert not app.root._callbacks and not app.root._images and not app.root._timers
                """;
        var host = TkPythonTest.shellHost(setup + checks, tile);
        host.stepBudget = 1000;
        return host;
    }

    @Test void rasterToolsHistoryAndDirtyStateAreWholeGestures() throws Exception {
        var tile = new TileTkDisplay();
        var h = host("""
                app.doc.replace(Image.new('RGBA', (64, 48), 'white'))
                app.reset_view()
                draw('Pen', (2, 3), (20, 3))
                assert app.doc.image.getpixel((10, 3)) == (0, 0, 0, 255)
                assert len(app.doc.undo_stack) == 1 and app.doc.dirty()
                app.undo()
                assert not app.doc.dirty() and app.doc.image.getpixel((10, 3)) == (255, 255, 255, 255)
                app.redo()
                app.choose_color('#ff0000')
                draw('Rect', (5, 7), (25, 27))
                assert app.doc.image.getpixel((5, 7)) == (255, 0, 0, 255)
                assert app.doc.image.getpixel((10, 10)) == (255, 255, 255, 255)
                app.choose_color('#0040ff')
                app.choose_tool('Fill')
                app.press(pointer(10, 10))
                assert app.doc.image.getpixel((24, 26)) == (0, 64, 255, 255)
                assert app.doc.image.getpixel((26, 26)) == (255, 255, 255, 255)
                before = len(app.doc.undo_stack)
                app.press(pointer(10, 10))
                assert len(app.doc.undo_stack) == before
                app.choose_tool('Pick')
                app.press(pointer(5, 7))
                assert app.color == '#ff0000' and len(app.doc.undo_stack) == before
                app.filled.set(True)
                draw('Oval', (32, 4), (48, 20))
                assert app.doc.image.getpixel((40, 12)) == (255, 0, 0, 255)
                draw('Line', (30, 30), (50, 30))
                assert app.doc.image.getpixel((40, 30)) == (255, 0, 0, 255)
                draw('Erase', (40, 30), (41, 30))
                assert app.doc.image.getpixel((40, 30)) == (255, 255, 255, 255)
                app.choose_tool('Text')
                answers.append('Hi')
                data = app.doc.image.tobytes()
                app.press(pointer(2, 32))
                assert data != app.doc.image.tobytes()
                app.undo()
                assert data == app.doc.image.tobytes()
                app.doc.history_limit = 64 * 48 * 4 * 2
                for i in range(6):
                    draw('Pen', (i, 0), (i, 0))
                assert len(app.doc.undo_stack) == 2 and not app.doc.redo_stack
                assert not errors, errors
                finish_app()
                passed = True
                """, tile);
        h.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty());
    }

    @Test void filesRoundTripAlphaFormatsMountsAndSaveMarkers() throws Exception {
        var h = host("""
                app.doc.replace(Image.new('RGBA', (16, 12), (20, 40, 60, 0)))
                app.reset_view()
                draw('Pen', (1, 2), (8, 2))
                assert app.write('alpha.png')
                data = app.doc.image.tobytes()
                assert not app.doc.dirty() and app.doc.path == '/home/alpha.png'
                assert Image.open('alpha.png').tobytes() == data
                draw('Pen', (1, 5), (8, 5))
                assert app.doc.dirty()
                app.undo()
                assert not app.doc.dirty()
                app.redo()
                assert app.doc.dirty()
                assert app.write('alpha.png')
                assert list(boot.files) == ['/home/alpha.png']
                assert app.write('/mnt/oth/copy.bmp')
                assert Image.open('/mnt/oth/copy.bmp').getpixel((15, 11)) == (255, 255, 255)
                assert app.write('/mnt/oth/copy.jpg')
                assert Image.open('/mnt/oth/copy.jpg').format == 'JPEG'
                assert app.load('/home/alpha.png') and not app.doc.undo_stack
                assert not boot.handles and not other.handles
                assert not errors, errors
                finish_app()
                passed = True
                """, new TileTkDisplay());
        h.runUntil("passed");
    }

    @Test void failedSaveAndDecodePreserveOldFileImageAndUnsavedState() throws Exception {
        var h = host("""
                draw('Pen', (1, 1), (3, 1))
                assert app.write('old.png')
                old = boot.files['/home/old.png']
                draw('Pen', (1, 2), (3, 2))
                def full(handle, data):
                    Disk._write_chunk(boot, handle, data[:3])
                    raise OSError('disk full')
                boot._write_chunk = full
                assert not app.write('old.png')
                assert boot.files['/home/old.png'] == old and app.doc.dirty()
                assert not boot.handles and len(boot.files) == 1
                del boot._write_chunk
                def reject_new(source, target):
                    if source.endswith('.tmp'):
                        return False
                    return disk_rename(boot, source, target)
                boot.rename = reject_new
                assert not app.write('old.png')
                assert boot.files['/home/old.png'] == old and len(boot.files) == 1
                del boot.rename
                assert not app.write('/mnt/ro/denied.png') and not readonly.files
                assert not app.write('unsupported.gif')
                boot.dirs.append('/home/folder.png')
                assert not app.write('folder.png') and '/home/folder.png' in boot.dirs
                boot.files['/bad.png'] = b'bad image'
                data = app.doc.image.tobytes()
                history = len(app.doc.undo_stack)
                assert not app.load('/bad.png')
                assert app.doc.image.tobytes() == data and len(app.doc.undo_stack) == history
                assert app.doc.path == '/home/old.png' and app.doc.dirty()
                assert len(errors) == 6, errors
                finish_app()
                passed = True
                """, new TileTkDisplay());
        h.runUntil("passed");
    }

    @Test void viewportZoomScrollingLayoutAndResourceCountsStayBounded() throws Exception {
        var tile = new TileTkDisplay();
        tile.setResolution(320, 240);
        var h = host("""
                app.doc.replace(Image.new('RGBA', (512, 512), 'white'))
                app.reset_view()
                app.set_zoom(8)
                app.canvas.xview_moveto(0.5)
                app.canvas.yview_moveto(0.5)
                x = int(app.canvas.canvasx(8) // 8)
                y = int(app.canvas.canvasy(8) // 8)
                assert x > 200 and y > 200
                draw('Pen', (8, 8), (16, 8))
                assert app.doc.image.getpixel((x, y)) == (0, 0, 0, 255)
                app.render()
                assert app.photo is not None, errors
                image_id = app.photo.name
                callbacks = len(app.root._callbacks)
                for i in range(8):
                    app.canvas.xview_scroll(1, 'units')
                    app.render()
                assert app.photo.name == image_id and len(app.root._images) == 1
                assert len(app.root._callbacks) == callbacks
                assert len(app.canvas.find_all()) == 1
                for button in list(app.tool_buttons.values()) + [app.size_button, app.color_button]:
                    assert button.winfo_width() >= button.winfo_reqwidth(), button.cget('text')
                    assert button.winfo_y() + button.winfo_height() <= button.master.winfo_height(), button.cget('text')
                assert app.size_button.winfo_rooty() + app.size_button.winfo_height() <= app.canvas.winfo_rooty() + app.canvas.winfo_height()
                assert app.canvas.winfo_width() >= 100 and app.canvas.winfo_height() >= 64
                assert not errors, errors
                armed = True
                wait(lambda: globals().get('CHECKED'))
                finish_app()
                passed = True
                """, tile);
        h.runUntil("armed");
        assertEquals(1, tile.scene().images.size());
        assertTrue(tile.scene().display.items.stream().anyMatch(i -> i.image != 0));
        h.vm.globals.put("CHECKED", true);
        h.runUntil("passed");
        assertTrue(tile.scene().images.isEmpty());
    }

    @Test void dialogsCancelFailedSaveAndKeyboardUndoDoNotDiscardChanges() throws Exception {
        var h = host("""
                draw('Pen', (1, 1), (6, 1))
                answers.append(None)
                app.quit()
                assert app.root._alive
                answers.append(True)
                paths.append('/mnt/ro/no.png')
                app.quit()
                assert app.root._alive and app.doc.dirty()
                answers.extend([40, None])
                app.new()
                assert app.doc.image.size == (192, 128)
                key = tk.Event()
                key.state, key.keycode = 4, 44
                assert app.key(key) == 'break' and not app.doc.dirty()
                key.state = 5
                app.key(key)
                assert app.doc.dirty()
                answers.extend([40, 30, False])
                app.new()
                assert app.doc.image.size == (40, 30) and not app.doc.dirty()
                app.rotate()
                assert app.doc.image.size == (30, 40)
                app.undo()
                assert app.doc.image.size == (40, 30) and not app.doc.dirty()
                answers.append('not-a-color')
                app.custom_color()
                assert app.color == '#000000' and len(errors) == 2
                finish_app()
                passed = True
                """, new TileTkDisplay());
        h.runUntil("passed");
    }

    @Test void documentViewportCallbacksAndUnfinishedStrokeSurviveJointSnapshot() throws Exception {
        var tile = new TileTkDisplay();
        var h = host("""
                draw('Rect', (3, 3), (25, 25))
                app.choose_tool('Pen')
                app.press(pointer(5, 5))
                app.drag(pointer(15, 5))
                app.render()
                armed = True
                while not globals().get('RESTORED'):
                    computer._yield_tick()
                app.release(pointer(20, 5))
                assert len(app.doc.undo_stack) == 2
                assert app.doc.image.getpixel((20, 5)) == (0, 0, 0, 255)
                app.undo()
                assert app.doc.image.getpixel((10, 5)) == (255, 255, 255, 255)
                assert app.doc.image.getpixel((3, 3)) == (0, 0, 0, 255)
                app.redo()
                assert app.write('restored.png')
                assert Image.open('restored.png').tobytes() == app.doc.image.tobytes()
                app.render()
                assert len(app.root._images) == 1 and not errors, errors
                finish_app()
                passed = True
                """, tile);
        h.runUntil("armed");
        byte[] snapshot = h.vm.saveSnapshot(h.registry);
        var nbt = new NBTTagCompound(); tile.writeToNBT(nbt);
        var copy = new TileTkDisplay(); copy.readFromNBT(nbt);
        var restored = new TkPythonTest.Host();
        restored.bootstrap = true; restored.stepBudget = 1000;
        restored.tiles.put("screen-a", copy);
        restored.start("pass");
        restored.vm = Vm.fromSnapshot(snapshot, restored.globals, restored.registry);
        restored.vm.globals.put("RESTORED", true);
        restored.runUntil("passed");
        assertTrue(copy.application().tokens().isEmpty());
    }

    @Test void mouseSignalsReachBindingsAndReleaseOutsideEndsOneStroke() throws Exception {
        var tile = new TileTkDisplay();
        var h = host("""
                canvas_id = app.canvas._id
                press_id = app.canvas._bindings['<Button-1>']
                drag_id = app.canvas._bindings['<B1-Motion>']
                release_id = app.canvas._bindings['<ButtonRelease-1>']
                armed = True
                while not app.doc.undo_stack:
                    app.root.update()
                assert app.start is None and app.doc.before is None
                assert len(app.doc.undo_stack) == 1
                assert app.doc.image.getpixel((15, 5)) == (0, 0, 0, 255)
                assert app.doc.image.getpixel((0, 9)) == (255, 255, 255, 255)
                app.undo()
                assert not app.doc.dirty()
                assert not errors, errors
                finish_app()
                passed = True
                """, tile);
        h.runUntil("armed");
        int canvas = ((Number) h.vm.globals.get("canvas_id")).intValue();
        var bounds = tile.widgets().get(canvas).bounds();
        assertEquals(canvas, tile.widgets().hit(bounds.x + 4, bounds.y + 5).id);
        String token = TkPythonTest.token(tile);
        String[] sequence = {"<Button-1>", "<B1-Motion>", "<B1-Motion>", "<ButtonRelease-1>"};
        String[] callback = {"press_id", "drag_id", "drag_id", "release_id"};
        int[] xs = {4, 24, -10, -10}, ys = {5, 5, 9, 9};
        for (int i = 0; i < sequence.length; i++)
            h.signal("tk_event", "screen-a", token, canvas, h.vm.globals.get(callback[i]), sequence[i],
                    xs[i], ys[i], 0, 0, 0, "Player", 0, canvas, i == 1 || i == 2, 0);
        h.runUntil("passed");
    }

    @Test void nearestZoomWidePreviewAndResizeReleaseOldPhotos() throws Exception {
        var tile = reobf.mpy4oc.tk.TkTestGroups.joined(2, 2);
        var h = host("""
                app.doc.replace(Image.new('RGBA', (2, 2), 'white'))
                app.doc.image.putpixel((0, 0), (255, 0, 0, 255))
                app.reset_view()
                app.set_zoom(8)
                app.render()
                assert app.photo.get(0, 0) == (255, 0, 0)
                assert app.photo.get(7, 7) == (255, 0, 0)
                assert app.photo.get(8, 0) == (255, 255, 255)
                assert app.photo.get(16, 0) == (128, 128, 128)
                app.doc.replace(Image.new('RGBA', (64, 64), 'white'))
                app.region()
                app.width = 32
                app.choose_tool('Rect')
                app.press(pointer(80, 80))
                app.drag(pointer(16, 16))
                app.render()
                assert not app.status.cget('text').startswith('Preview:'), app.status.cget('text')
                assert app.doc.image.getpixel((5, 5)) == (255, 255, 255, 255)
                app.release(pointer(16, 16))
                assert app.doc.image.getpixel((5, 5)) == (0, 0, 0, 255)
                previous = app.photo.name
                app.root.geometry('300x240')
                app.render()
                assert app.photo.name != previous and len(app.root._images) == 1
                app.root.geometry('640x480')
                app.render()
                assert len(app.root._images) == 1
                assert not errors, errors
                finish_app()
                passed = True
                """, tile);
        h.runUntil("passed");
        assertTrue(tile.scene().images.isEmpty());
    }

    @Test void rollbackFailureKeepsBothRecoverableFiles() throws Exception {
        var h = host("""
                assert app.write('recover.png')
                old = boot.files['/home/recover.png']
                draw('Pen', (2, 2), (4, 2))
                def fail_replace_and_restore(source, target):
                    if source.endswith('.tmp') or source.endswith('.bak'):
                        return False
                    return disk_rename(boot, source, target)
                boot.rename = fail_replace_and_restore
                assert not app.write('recover.png') and app.doc.dirty()
                backups = [name for name in boot.files if name.endswith('.bak')]
                temporary = [name for name in boot.files if name.endswith('.tmp')]
                assert len(backups) == len(temporary) == 1
                assert boot.files[backups[0]] == old
                assert Image.open(temporary[0]).tobytes() == app.doc.image.tobytes()
                assert backups[0] in errors[-1] and temporary[0] in errors[-1]
                finish_app()
                passed = True
                """, new TileTkDisplay());
        h.runUntil("passed");
    }

    @Test void commandUsesDefaultPathStartsInBackgroundAndClosesItsProcess() throws Exception {
        var tile = new TileTkDisplay();
        var h = TkPythonTest.shellHost("""
                FAKE_VFS.files['/bin/tkinter/tkpaint.py'] = PAINT
                sh.cwd = '/home'
                sh.run_command('tkpaint', ['picture.png'])
                wait(lambda: 'paint' in SHARED)
                app = SHARED['paint']
                assert app.doc.path == '/home/picture.png'
                assert len(sh.jobs) == 1 and sh.jobs[0]['thread'].is_alive()
                app.root.after_idle(app.close)
                wait(lambda: not sh.jobs[0]['thread'].is_alive())
                assert not app.root._callbacks and not app.root._images
                assert not any('Traceback' in line for line in tty.out), tty.out
                passed = True
                """, tile);
        h.vm.globals.put("PAINT", source().replace("        root.mainloop()", "        SHARED['paint'] = app\n        root.mainloop()"));
        h.runUntil("passed");
        assertTrue(tile.application().tokens().isEmpty());
    }
}
