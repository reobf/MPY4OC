"""tkinter.commondialog for MPY4OC: the base of the standard dialogs (messagebox, filedialog).

As CPython's: Dialog(master=None, **options), show(**options) runs the dialog and returns its
result (_fixresult), command names the dialog. Here the dialogs are tkinter windows on the Tk
display: modal (transient, grab), centred over their parent.
"""
import tkinter

__all__ = ['Dialog']


class Dialog:
    command = None

    def __init__(self, master=None, **options):
        if master is None:
            master = options.get('parent')
        self.master = master
        self.options = options

    def _fixoptions(self):
        pass  # hook

    def _fixresult(self, widget, result):
        return result  # hook

    def _test_callback(self, master):
        pass  # hook

    def show(self, **options):
        for k, v in options.items():
            self.options[k] = v
        self._fixoptions()
        master = self.master
        if master is None:
            master = tkinter._get_temp_root()
        try:
            self._test_callback(master)
            s = self._run(master)
            s = self._fixresult(master, s)
        finally:
            tkinter._destroy_temp_root(master)
        return s

    def _run(self, master):
        raise NotImplementedError(type(self).__name__ + ' has no dialog')


def _parent(options, master):
    # The window a dialog belongs to: -parent, else the master given, else the default root.
    parent = options.get('parent') or master
    if parent is None:
        parent = tkinter._get_temp_root()
    return parent


def _place(window, parent):
    # Tk's ::tk::PlaceWindow widget parent: centred over the parent, kept on the display.
    window.update_idletasks()
    width, height = window.winfo_reqwidth(), window.winfo_reqheight()
    screen_w, screen_h = window.winfo_screenwidth(), window.winfo_screenheight()
    # A display can be smaller than a desktop dialog's requested size. Give its
    # expanding rows/columns the available space; keep the title and taskbar clear.
    fitted_w = min(width, max(1, screen_w - 4))
    fitted_h = min(height, max(1, screen_h - 40))
    constrained = fitted_w != width or fitted_h != height
    width, height = fitted_w, fitted_h
    if parent is not None and parent.winfo_ismapped():
        top = parent.winfo_toplevel()
        x = top.winfo_rootx() + (top.winfo_width() - width) // 2
        y = top.winfo_rooty() + (top.winfo_height() - height) // 2 - 18
    else:
        x = (screen_w - width) // 2
        y = (screen_h - height) // 2
    x = max(0, min(x, screen_w - width))
    y = max(0, min(y, screen_h - height - 36))
    geometry = '+%d+%d' % (x, y)
    if constrained:
        geometry = '%dx%d' % (width, height) + geometry
    window.geometry(geometry)


def _modal(window, parent, focus):
    # Show window as a modal dialog of parent and run events until it is gone; then the grab and
    # the keyboard go back where they were (Tk's ::tk::SetFocusGrab / RestoreFocusGrab).
    old_grab = window.grab_current()
    old_focus = window.focus_get()
    if parent is not None and parent.winfo_viewable():
        window.transient(parent)
    _place(window, parent)
    window.deiconify()
    if focus is not None:
        focus.focus_set()
    window.grab_set()
    window.wait_window(window)
    if old_focus is not None and old_focus._alive:
        try:
            old_focus.focus_set()
        except Exception:
            pass
    if old_grab is not None and old_grab._alive:
        old_grab.grab_set()
