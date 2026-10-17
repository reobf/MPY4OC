#!/usr/bin/env pythonw
"""tkpaint [image]: a small raster editor for MPYOS and a Tk display.

PNG/JPEG/GIF/BMP input; PNG/JPEG/BMP output. Images stay in the VM, including
undo history and unfinished strokes, so normal computer snapshots preserve them.
"""
import os
import sys
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog
from PIL import Image, ImageDraw, ImageColor

COLORS = ('#000000', '#ffffff', '#808080', '#c00000', '#ff8000', '#ffff00', '#008000', '#00c0c0', '#0040ff', '#8000c0')
TOOLS = ('Pen', 'Erase', 'Line', 'Rect', 'Oval', 'Fill', 'Pick', 'Text')
FORMATS = {'.png': 'PNG', '.jpg': 'JPEG', '.jpeg': 'JPEG', '.bmp': 'BMP'}
FILE_TYPES = [('Images', '*.png *.jpg *.jpeg *.bmp *.gif'), ('All files', '*')]


class Document:
    """One image and bounded, whole-gesture history; no Tk or host handles."""
    def __init__(self, size=(192, 128)):
        self.image = Image.new('RGBA', size, 'white')
        self.path = None
        self.undo_stack = []
        self.redo_stack = []
        self.before = None
        self.revision = 0
        self.saved = 0
        self.serial = 0
        self.history_limit = 4 * 1024 * 1024

    def dirty(self):
        return self.revision != self.saved or self.before is not None

    def begin(self):
        if self.before is None:
            self.before = (self.image.copy(), self.revision)

    def commit(self):
        if self.before is None:
            return
        self.undo_stack.append(self.before)
        self.before = None
        self.redo_stack = []
        self.serial += 1
        self.revision = self.serial
        # Old entries are evicted, never the image currently being edited.
        total = sum(im.width * im.height * 4 for im, rev in self.undo_stack)
        while len(self.undo_stack) > 20 or total > self.history_limit:
            old, rev = self.undo_stack.pop(0)
            total -= old.width * old.height * 4

    def rollback(self):
        if self.before is not None:
            self.image, self.revision = self.before
            self.before = None

    def undo(self):
        self.commit()
        if self.undo_stack:
            self.redo_stack.append((self.image, self.revision))
            self.image, self.revision = self.undo_stack.pop()

    def redo(self):
        if self.redo_stack:
            self.undo_stack.append((self.image, self.revision))
            self.image, self.revision = self.redo_stack.pop()

    def replace(self, image, path=None):
        # Convert/validate before releasing the current document.
        converted = image.convert('RGBA')
        self.image = converted
        self.path = path
        self.undo_stack = []
        self.redo_stack = []
        self.before = None
        self.serial += 1
        self.revision = self.serial
        self.saved = self.revision

    def stroke(self, a, b, color, width):
        self.begin()
        draw = ImageDraw.Draw(self.image)
        draw.line((a, b), fill=color, width=width)
        if width > 1:
            radius = (width - 1) // 2
            for x, y in (a, b):
                draw.ellipse((x - radius, y - radius, x - radius + width - 1, y - radius + width - 1), fill=color)

    def shape(self, tool, a, b, color, width, filled):
        self.begin()
        draw = ImageDraw.Draw(self.image)
        if tool == 'Line':
            draw.line((a, b), fill=color, width=width)
        else:
            box = (min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1]))
            method = draw.rectangle if tool == 'Rect' else draw.ellipse
            method(box, fill=color if filled else None, outline=color, width=width)

    def fill(self, point, color):
        ink = ImageColor.getcolor(color, 'RGBA')
        if self.image.getpixel(point) == ink:
            return
        self.begin()
        # One host call: a fill written in Python reads every pixel through the VM.
        ImageDraw.floodfill(self.image, point, ink)

    def save(self, path):
        path = os.path.abspath(path)
        if os.path.isdir(path):
            raise IsADirectoryError(path)
        fmt = FORMATS.get(os.path.splitext(path)[1].lower())
        if fmt is None:
            raise ValueError('Use .png, .jpg, .jpeg or .bmp')
        image = self.image
        if fmt != 'PNG':
            image = Image.new('RGB', self.image.size, 'white')
            image.paste(self.image, (0, 0), self.image)
        # Reserve a unique sibling without touching another editor's files.
        base = path + '.tkpaint-' + str(os.getpid())
        index = 0
        while os.path.exists(base + '-' + str(index) + '.tmp') or os.path.exists(base + '-' + str(index) + '.bak'):
            index += 1
        temp = base + '-' + str(index) + '.tmp'
        backup = base + '-' + str(index) + '.bak'
        try:
            image.save(temp, format=fmt)
        except BaseException:
            if os.path.exists(temp):
                try:
                    os.remove(temp)
                except OSError:
                    pass
            raise
        # OC filesystems need not support replacing a destination by rename.
        # Keep the old file as a backup until the second rename succeeds.
        existed = os.path.exists(path)
        if existed:
            try:
                os.rename(path, backup)
            except BaseException:
                os.remove(temp)
                raise
        try:
            os.rename(temp, path)
        except BaseException:
            if existed:
                try:
                    os.rename(backup, path)
                except OSError:
                    raise OSError('Save failed. Original: ' + backup + '; new image: ' + temp)
            if os.path.exists(temp):
                os.remove(temp)
            raise
        warning = ''
        if existed:
            try:
                os.remove(backup)
            except OSError:
                warning = 'Saved; backup kept: ' + backup
        self.path = path
        self.saved = self.revision
        return warning


class Paint:
    def __init__(self, root):
        self.root = root
        self.doc = Document()
        self.tool = 'Pen'
        self.color = '#000000'
        self.width = 1
        self.filled = tk.BooleanVar(root, False)
        self.zoom = 1
        self.start = None
        self.last = None
        self.preview = None
        self.photo = None
        self.photo_size = None
        self.pending = None
        self.closed = False
        self.tool_buttons = {}
        root.title('tkpaint')
        root.geometry('%dx%d' % (min(640, root.winfo_screenwidth()), min(480, root.winfo_screenheight() - 42)))
        root.minsize(280, 190)
        root.maxsize(640, 480)
        root.columnconfigure(1, weight=1)
        root.rowconfigure(1, weight=1)
        root.protocol('WM_DELETE_WINDOW', self.quit)
        self.make_menu()

        palette = tk.Frame(root)
        palette.grid(row=0, column=0, columnspan=3, sticky='ew')
        for color in COLORS:
            tk.Button(palette, text=' ', bg=color, width=1, padx=1, pady=1, takefocus=0, command=lambda c=color: self.choose_color(c)).pack(side=tk.LEFT)
        self.color_button = tk.Button(palette, text='Color', padx=2, pady=1, takefocus=0, command=self.custom_color)
        self.color_button.pack(side=tk.LEFT)

        tools = tk.Frame(root)
        tools.grid(row=1, column=0, sticky='nw')
        for index, tool in enumerate(TOOLS):
            button = tk.Button(tools, text=tool, width=4, padx=0, pady=1, takefocus=0, command=lambda t=tool: self.choose_tool(t))
            button.grid(row=index // 2, column=index % 2, sticky='ew')
            self.tool_buttons[tool] = button
        self.size_button = tk.Button(tools, text='1 px', padx=0, takefocus=0, command=self.choose_width)
        self.size_button.grid(row=4, column=0, columnspan=2, sticky='ew')

        self.canvas = tk.Canvas(root, width=64, height=32, bg='#808080', borderwidth=0, highlightthickness=0, takefocus=1)
        self.canvas.grid(row=1, column=1, sticky='nsew')
        self.ybar = tk.Scrollbar(root, takefocus=0, command=self.canvas.yview)
        self.ybar.grid(row=1, column=2, sticky='ns')
        self.xbar = tk.Scrollbar(root, orient=tk.HORIZONTAL, takefocus=0, command=self.canvas.xview)
        self.xbar.grid(row=2, column=1, sticky='ew')
        self.canvas.configure(xscrollcommand=self.xscroll, yscrollcommand=self.yscroll)
        self.image_item = self.canvas.create_image(0, 0, anchor=tk.NW)
        self.status = tk.Label(root, anchor='w', text='')
        self.status.grid(row=3, column=0, columnspan=3, sticky='ew')
        self.canvas.bind('<Button-1>', self.press)
        self.canvas.bind('<B1-Motion>', self.drag)
        self.canvas.bind('<ButtonRelease-1>', self.release)
        self.canvas.bind('<Configure>', lambda e: self.schedule())
        root.bind('<Key>', self.key)
        self.choose_tool('Pen')
        self.choose_color(self.color)
        self.region()
        self.canvas.focus_set()
        self.schedule()

    def make_menu(self):
        bar = tk.Menu(self.root, tearoff=0)
        self.root.configure(menu=bar)
        for title, entries in (
            ('File', (('New', self.new), ('Open...', self.open), ('Save', self.save), ('Save as...', self.save_as), ('Quit', self.quit))),
            ('Edit', (('Undo', self.undo), ('Redo', self.redo), ('Clear', self.clear), ('Rotate right', self.rotate), ('Flip horizontal', self.flip))),
            ('View', (('100%', lambda: self.set_zoom(1)), ('200%', lambda: self.set_zoom(2)), ('400%', lambda: self.set_zoom(4)), ('800%', lambda: self.set_zoom(8)))),
        ):
            menu = tk.Menu(bar, tearoff=0)
            for label, command in entries:
                menu.add_command(label=label, command=command)
            bar.add_cascade(label=title, menu=menu)
        options = tk.Menu(bar, tearoff=0)
        options.add_checkbutton(label='Filled shapes', variable=self.filled)
        options.add_command(label='Brush width...', command=self.choose_width)
        options.add_command(label='Custom color...', command=self.custom_color)
        bar.add_cascade(label='Options', menu=options)

    def refresh(self, message=None):
        name = os.path.basename(self.doc.path) if self.doc.path else 'Untitled'
        if len(name) > 48:
            name = name[:45] + '...'
        self.root.title(('*' if self.doc.dirty() else '') + name + ' - tkpaint')
        if message is None:
            message = '%s %s | %dx%d | %d%%' % (self.tool, self.color, self.doc.image.width, self.doc.image.height, self.zoom * 100)
        self.status.configure(text=message)

    def error(self, error):
        messagebox.showerror('tkpaint', str(error), parent=self.root)

    def choose_tool(self, tool):
        self.finish()
        self.tool = tool
        for name, button in self.tool_buttons.items():
            button.configure(relief=tk.SUNKEN if name == tool else tk.RAISED)
        self.refresh()

    def choose_color(self, color):
        self.finish()
        self.color = color
        r, g, b = ImageColor.getrgb(color)
        self.color_button.configure(bg=color, fg='#000000' if r + g + b > 380 else '#ffffff')
        self.refresh()

    def custom_color(self):
        self.finish()
        value = simpledialog.askstring('Color', 'Color name or #RRGGBB:', initialvalue=self.color, parent=self.root)
        if value:
            try:
                r, g, b = ImageColor.getrgb(value.strip())
                self.choose_color('#%02x%02x%02x' % (r, g, b))
            except Exception as error:
                self.error(error)

    def choose_width(self):
        self.finish()
        value = simpledialog.askinteger('Brush width', 'Width in pixels (1-32):', initialvalue=self.width, minvalue=1, maxvalue=32, parent=self.root)
        if value is not None:
            self.width = value
            self.size_button.configure(text=str(value) + ' px')

    def xscroll(self, first, last):
        self.xbar.set(first, last)
        self.schedule()

    def yscroll(self, first, last):
        self.ybar.set(first, last)
        self.schedule()

    def region(self):
        self.canvas.configure(scrollregion=(0, 0, self.doc.image.width * self.zoom, self.doc.image.height * self.zoom))

    def set_zoom(self, zoom):
        self.finish()
        self.zoom = zoom
        self.region()
        self.refresh()
        self.schedule()

    def schedule(self):
        if not self.closed and self.pending is None:
            self.pending = self.root.after(60, self.render)

    def render(self):
        from PIL import ImageTk
        if self.pending is not None:
            self.root.after_cancel(self.pending)
        self.pending = None
        if self.closed:
            return
        try:
            # The bounded window keeps one viewport image below Tk's pixel quota.
            w = max(1, min(640, self.canvas.winfo_width()))
            h = max(1, min(480, self.canvas.winfo_height()))
            sx, sy = int(self.canvas.canvasx(0)), int(self.canvas.canvasy(0))
            x, y = max(0, sx // self.zoom), max(0, sy // self.zoom)
            right = min(self.doc.image.width, (sx + w + self.zoom - 1) // self.zoom)
            bottom = min(self.doc.image.height, (sy + h + self.zoom - 1) // self.zoom)
            view = Image.new('RGB', (w, h), '#808080')
            if right > x and bottom > y:
                part = self.doc.image.crop((x, y, right, bottom))
                part = part.resize((part.width * self.zoom, part.height * self.zoom), Image.NEAREST)
                white = Image.new('RGB', part.size, 'white')
                white.paste(part, (0, 0), part)
                view.paste(white, (x * self.zoom - sx, y * self.zoom - sy))
            if self.preview is not None:
                a, b = self.preview
                ax, ay = a[0] * self.zoom - sx, a[1] * self.zoom - sy
                bx, by = b[0] * self.zoom - sx, b[1] * self.zoom - sy
                draw = ImageDraw.Draw(view)
                if self.tool == 'Line':
                    draw.line((ax, ay, bx, by), fill=self.color, width=self.width * self.zoom)
                else:
                    method = draw.rectangle if self.tool == 'Rect' else draw.ellipse
                    method((min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)), outline=self.color, fill=self.color if self.filled.get() else None, width=self.width * self.zoom)
            if self.photo is not None and self.photo_size != (w, h):
                self.canvas.itemconfigure(self.image_item, image='')
                self.photo.delete()
                self.photo = None
            if self.photo is None:
                self.photo = ImageTk.PhotoImage(view, master=self.root)
                self.photo_size = (w, h)
                self.canvas.itemconfigure(self.image_item, image=self.photo)
            else:
                self.photo.paste(view)
            self.canvas.coords(self.image_item, sx, sy)
        except Exception as error:
            self.refresh('Preview: ' + str(error))

    def point(self, event, clamp=False):
        x = int(self.canvas.canvasx(event.x) // self.zoom)
        y = int(self.canvas.canvasy(event.y) // self.zoom)
        w, h = self.doc.image.size
        if clamp:
            return (max(0, min(w - 1, x)), max(0, min(h - 1, y)))
        if 0 <= x < w and 0 <= y < h:
            return (x, y)
        return None

    def press(self, event):
        self.finish()
        self.canvas.focus_set()
        point = self.point(event)
        if point is None:
            return
        try:
            if self.tool == 'Pick':
                r, g, b, a = self.doc.image.getpixel(point)
                self.choose_color('#%02x%02x%02x' % (r, g, b))
            elif self.tool == 'Text':
                text = simpledialog.askstring('Text', 'Text to place:', parent=self.root)
                if text:
                    self.doc.begin()
                    ImageDraw.Draw(self.doc.image).text(point, text, fill=self.color)
                    self.doc.commit()
            elif self.tool == 'Fill':
                self.doc.fill(point, self.color)
                self.doc.commit()
            else:
                self.start = point
                self.last = point
                if self.tool in ('Pen', 'Erase'):
                    self.doc.stroke(point, point, 'white' if self.tool == 'Erase' else self.color, self.width)
            self.refresh()
            self.schedule()
        except Exception as error:
            self.doc.rollback()
            self.finish()
            self.schedule()
            self.error(error)

    def drag(self, event):
        if self.start is None:
            return
        point = self.point(event, self.tool in ('Line', 'Rect', 'Oval'))
        if self.tool in ('Pen', 'Erase'):
            if point is not None:
                self.doc.stroke(self.last or point, point, 'white' if self.tool == 'Erase' else self.color, self.width)
                self.schedule()
            self.last = point
        else:
            self.last = point
            self.preview = (self.start, point)
            self.schedule()

    def release(self, event):
        if self.start is not None:
            self.drag(event)
            if self.tool in ('Line', 'Rect', 'Oval'):
                self.doc.shape(self.tool, self.start, self.last, self.color, self.width, self.filled.get())
        self.finish()

    def finish(self):
        self.start = None
        self.last = None
        self.preview = None
        self.doc.commit()
        if hasattr(self, 'status'):
            self.refresh()
            self.schedule()

    def undo(self):
        self.finish()
        self.doc.undo()
        self.region()
        self.refresh()
        self.schedule()

    def redo(self):
        self.finish()
        self.doc.redo()
        self.region()
        self.refresh()
        self.schedule()

    def transform(self, operation):
        self.finish()
        self.doc.begin()
        try:
            self.doc.image = operation(self.doc.image)
            self.doc.commit()
        except Exception as error:
            self.doc.rollback()
            self.error(error)
        self.region()
        self.refresh()
        self.schedule()

    def rotate(self):
        self.transform(lambda im: im.transpose(Image.ROTATE_270))

    def flip(self):
        self.transform(lambda im: im.transpose(Image.FLIP_LEFT_RIGHT))

    def clear(self):
        if messagebox.askyesno('Clear image', 'Fill the entire image with white?', parent=self.root):
            self.transform(lambda im: Image.new('RGBA', im.size, 'white'))

    def may_discard(self):
        self.finish()
        if not self.doc.dirty():
            return True
        answer = messagebox.askyesnocancel('Unsaved image', 'Save changes before continuing?', parent=self.root)
        if answer is None:
            return False
        return self.save() if answer else True

    def new(self):
        width = simpledialog.askinteger('New image', 'Width (1-1024):', initialvalue=192, minvalue=1, maxvalue=1024, parent=self.root)
        if width is None:
            return
        height = simpledialog.askinteger('New image', 'Height (1-1024):', initialvalue=128, minvalue=1, maxvalue=min(1024, Image.MAX_IMAGE_PIXELS // width), parent=self.root)
        if height is not None and self.may_discard():
            self.doc.replace(Image.new('RGBA', (width, height), 'white'))
            self.reset_view()

    def reset_view(self):
        self.zoom = 1
        self.region()
        self.canvas.xview_moveto(0)
        self.canvas.yview_moveto(0)
        self.refresh()
        self.schedule()

    def load(self, path):
        try:
            with Image.open(os.path.abspath(path)) as image:
                self.doc.replace(image, os.path.abspath(path))
            self.reset_view()
            return True
        except Exception as error:
            self.error(error)
            return False

    def open(self):
        self.finish()
        path = filedialog.askopenfilename(parent=self.root, initialdir=self.directory(), filetypes=FILE_TYPES)
        if path and self.may_discard():
            self.load(path)

    def directory(self):
        return os.path.dirname(self.doc.path) if self.doc.path else os.getcwd()

    def write(self, path):
        self.finish()
        try:
            warning = self.doc.save(path)
            self.refresh(warning or 'Saved ' + os.path.basename(self.doc.path))
            return True
        except Exception as error:
            self.error(error)
            return False

    def save(self):
        if self.doc.path and os.path.splitext(self.doc.path)[1].lower() in FORMATS:
            return self.write(self.doc.path)
        return self.save_as()

    def save_as(self):
        self.finish()
        path = filedialog.asksaveasfilename(parent=self.root, initialdir=self.directory(), initialfile=os.path.splitext(os.path.basename(self.doc.path))[0] + '.png' if self.doc.path else 'image.png', defaultextension='.png', filetypes=[('PNG', '*.png'), ('JPEG', '*.jpg *.jpeg'), ('BMP', '*.bmp')])
        return self.write(path) if path else False

    def key(self, event):
        if event.state & 4 and not event.state & 8:
            actions = {49: self.new, 24: self.open, 31: self.save_as if event.state & 1 else self.save, 44: self.redo if event.state & 1 else self.undo, 21: self.redo, 16: self.quit}
            action = actions.get(event.keycode)
            if action is not None:
                action()
                return 'break'

    def quit(self):
        if self.may_discard():
            self.close()

    def close(self):
        self.closed = True
        if self.pending is not None:
            self.root.after_cancel(self.pending)
            self.pending = None
        if self.photo is not None:
            self.photo.delete()
            self.photo = None
        self.root.destroy()


def main():
    root = tk.Tk()
    try:
        app = Paint(root)
        if len(sys.argv) > 1:
            path = os.path.abspath(sys.argv[1])
            if os.path.exists(path):
                app.load(path)
            elif os.path.splitext(path)[1].lower() in FORMATS:
                app.doc.path = path
                app.refresh()
            else:
                app.error('Use .png, .jpg, .jpeg or .bmp for a new image')
        root.mainloop()
    finally:
        if root._alive:
            root.destroy()


if __name__ == '__main__':
    main()
