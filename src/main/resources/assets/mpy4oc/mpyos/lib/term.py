"""term.py -- the mpyos terminal.

Wraps the GPU component into a scrolling text terminal: write text, move the
cursor, clear, scroll, and read a line of input from the keyboard. Corresponds to
the term + tty layer of OpenOS, kept small.

The GPU addresses the screen 1-indexed: the top-left cell is (1, 1). We track the
cursor ourselves and scroll by copying the screen up a row and clearing the last.
Input comes in as key_down / clipboard signals via event.pull.
"""

import event


# LWJGL scancodes we care about for line editing (see OpenOS lib/keyboard.lua).
KEY_BACK = 0x0E
KEY_TAB = 0x0F
KEY_ENTER = 0x1C
KEY_LEFT = 0xCB
KEY_RIGHT = 0xCD
KEY_UP = 0xC8
KEY_DOWN = 0xD0
KEY_HOME = 0xC7
KEY_END = 0xCF
KEY_DELETE = 0xD3

# Named colours (RGB), roughly the OpenComputers tier-3 palette. Handy for
# tty.write_color(text, term.CYAN) and friends.
WHITE = 0xFFFFFF
SILVER = 0xC0C0C0
GRAY = 0x808080
BLACK = 0x000000
RED = 0xFF4040
GREEN = 0x40FF40
YELLOW = 0xFFFF40
BLUE = 0x4040FF
CYAN = 0x40FFFF
MAGENTA = 0xFF40FF
ORANGE = 0xFFA000
GREENDARK = 0x00A000


class Terminal:
    def __init__(self, gpu):
        """gpu is a component proxy (component.proxy(address))."""
        self.gpu = gpu
        self.x = 1
        self.y = 1
        # The current foreground colour we set, tracked here rather than read back
        # from the GPU: gpu.getForeground() returns TWO values (colour, from_palette)
        # and the palette case returns an index, not an RGB -- feeding that back into
        # setForeground (which wants an integer) throws "integer expected, got table".
        # We default to white and update it whenever we set a colour.
        self.fg = 0xFFFFFF
        try:
            self.w, self.h = gpu.getResolution()
        except Exception:
            self.w, self.h = 80, 25

    # -- screen binding ------------------------------------------------------
    #
    # A screen can vanish while we are running (the monitor block is broken, or a
    # different one is placed). We rebind PASSIVELY -- only
    # when the current binding is actually gone -- so the common case costs nothing.
    # This is what lets a machine survive losing its monitor and pick up the card's
    # virtual screen (or a freshly placed monitor) on its own.

    def bound_screen(self):
        """Address of the screen this gpu currently draws to, or None."""
        try:
            r = self.gpu.getScreen()
            if isinstance(r, tuple):
                r = r[0] if r else None
            return r or None
        except Exception:
            return None

    def screen_alive(self):
        """True if the gpu still has a working screen bound."""
        addr = self.bound_screen()
        if addr is None:
            return False
        try:
            for a in component.list("screen"):
                if a == addr:
                    return True
        except Exception:
            # can't tell -- assume alive rather than thrash rebinding
            return True
        return False

    def rebind(self):
        """Bind to any available screen and redraw from a clean slate.

        Returns True if we ended up bound to a screen. The clear matters: the new
        screen (a virtual one, or a monitor that was used by something else) can
        hold stale content, and we would otherwise draw our prompt on top of
        someone else's leftovers.
        """
        try:
            candidates = list(component.list("screen"))
        except Exception:
            candidates = []
        for addr in candidates:
            try:
                self.gpu.bind(addr)
                # adopt the new screen's geometry, then wipe it
                try:
                    self.w, self.h = self.gpu.getResolution()
                except Exception:
                    pass
                self.x, self.y = 1, 1
                try:
                    self.gpu.setForeground(self.fg)
                except Exception:
                    pass
                self.gpu.fill(1, 1, self.w, self.h, " ")
                return True
            except Exception:
                continue
        return False

    def ensure_screen(self):
        """Rebind if the current screen is gone. Cheap when nothing changed."""
        if self.screen_alive():
            return True
        return self.rebind()

    # -- geometry --
    def size(self):
        return self.w, self.h

    def refresh_size(self):
        self.w, self.h = self.gpu.getResolution()

    def set_cursor(self, x, y):
        self.x = max(1, min(x, self.w))
        self.y = max(1, min(y, self.h))

    def get_cursor(self):
        return self.x, self.y

    # -- block cursor ----------------------------------------------------
    #
    # OC has no hardware cursor, so we fake one: read the cell, redraw its own
    # character with fg/bg swapped (inverse video), and swap back to undo. Shared
    # by the line editor (read) and by `edit`, so both look and behave the same.
    #
    # The invariant that actually matters for visibility: the player only sees the
    # screen at the moments the VM yields, so the cursor must be ON before every
    # wait. Draw it right before pulling, erase it right after a signal arrives.

    def draw_cursor_cell(self, x, y, on):
        """Paint (on) or restore (off) a block cursor at column x, row y."""
        try:
            cell = self.gpu.get(x, y)          # (char, fg, bg, ...)
            ch = cell[0] if cell else " "
            fg = cell[1] if cell and len(cell) > 1 else None
            bg = cell[2] if cell and len(cell) > 2 else None
        except Exception:
            ch, fg, bg = " ", None, None
        if not ch:
            ch = " "
        try:
            if on and fg is not None and bg is not None:
                self.gpu.setForeground(bg)
                self.gpu.setBackground(fg)
                self.gpu.set(x, y, ch)
                self.gpu.setForeground(fg)
                self.gpu.setBackground(bg)
            else:
                self.gpu.set(x, y, ch)
        except Exception:
            pass

    # -- screen ops --
    def clear(self):
        # Headless (no screen bound yet) is not an error: just reset the cursor.
        # The screen gets wiped when we bind one -- see rebind().
        try:
            self.gpu.fill(1, 1, self.w, self.h, " ")
        except Exception:
            pass
        self.x = 1
        self.y = 1

    def clear_line(self, y=None):
        if y is None:
            y = self.y
        self.gpu.fill(1, y, self.w, 1, " ")

    def scroll(self, lines=1):
        """Scroll the screen up `lines` rows, clearing the freed rows at the bottom.
        This is a GPU copy of the region up by `lines`, then a fill of the tail --
        the same primitive OpenOS uses. A no-op while headless."""
        if lines <= 0:
            return
        try:
            if lines >= self.h:
                self.gpu.fill(1, 1, self.w, self.h, " ")
                return
            # copy rows [lines+1 .. h] up to row 1
            self.gpu.copy(1, lines + 1, self.w, self.h - lines, 0, -lines)
            # clear the newly exposed rows at the bottom
            self.gpu.fill(1, self.h - lines + 1, self.w, lines, " ")
        except Exception:
            pass

    def _newline(self):
        self.x = 1
        if self.y >= self.h:
            self.scroll(1)
            self.y = self.h
        else:
            self.y += 1

    # -- writing --
    def write(self, text):
        """Write text at the cursor, wrapping at the right edge and scrolling at the
        bottom. Handles \\n and \\r; \\t expands to the next 4-column stop.

        Runs of printable characters are drawn in BATCHES: one gpu.set per
        screen-row segment instead of one per character. gpu.set takes a whole
        string anyway, and every call burns one unit of the GPU's per-tick
        direct-call budget -- per-character output made a 50-column line cost 50
        calls, blow the budget, and visibly trickle out over several ticks."""
        i = 0
        n = len(text)
        while i < n:
            ch = text[i]
            if ch == "\n":
                self._newline()
                i += 1
            elif ch == "\r":
                self.x = 1
                i += 1
            elif ch == "\t":
                # expand from the CURRENT column, like the old per-char path
                self._blit(" " * (4 - ((self.x - 1) % 4)))
                i += 1
            elif ch == "\b":
                if self.x > 1:
                    self.x -= 1
                i += 1
            else:
                # batch everything up to the next control character
                j = i + 1
                while j < n:
                    c = text[j]
                    if c == "\n" or c == "\r" or c == "\t" or c == "\b":
                        break
                    j += 1
                self._blit(text[i:j])
                i = j

    def _blit(self, s):
        """Draw a run of printable characters, wrapping at the right edge: one
        gpu.set per row segment. Same semantics as the old per-character loop
        (wrap happens BEFORE drawing, so a char may sit in the last column and
        only the next one triggers the newline)."""
        while s:
            if self.x > self.w:
                self._newline()
            room = self.w - self.x + 1
            seg = s[:room]
            try:
                self.gpu.set(self.x, self.y, seg)
            except Exception:
                pass
            self.x += len(seg)
            s = s[room:]

    def print(self, *parts, **kw):
        sep = kw.get("sep", " ")
        end = kw.get("end", "\n")
        self.write(sep.join([str(p) for p in parts]) + end)

    # -- colors --
    def set_foreground(self, rgb):
        self.fg = rgb
        self.gpu.setForeground(rgb)

    def set_background(self, rgb):
        self.gpu.setBackground(rgb)

    def write_color(self, text, rgb):
        """Write text in a colour, then restore the previous foreground so the
        colouring is local to this call. The previous colour is our tracked value,
        not gpu.getForeground() (which returns a (colour, from_palette) pair that
        can't be fed straight back into setForeground)."""
        prev = self.fg
        self.set_foreground(rgb)
        self.write(text)
        self.set_foreground(prev)

    def print_color(self, text, rgb, end="\n"):
        self.write_color(text + end, rgb)

    # -- input --
    def read(self, prompt=None, history=None, complete=None):
        """Read one line of input, echoing as the user types. Supports backspace,
        left/right, home/end, up/down history, and Tab completion. Returns the line
        without the trailing newline, or None on interrupt (Ctrl+D / Ctrl+C on
        empty).

        complete(text, pos) -- optional Tab callback: given the current line and
        cursor index, return a list of full-line candidates, each a (new_text,
        new_pos) tuple; empty list = no completion. One candidate: applied. Many:
        the candidates' common prefix is applied and names are listed below."""
        if prompt:
            self.write(prompt)

        buf = []          # list of characters
        pos = 0           # cursor index within buf
        start_x = self.x  # column where input begins on the current row
        start_y = self.y
        hist = list(history) if history else []
        hist_index = len(hist)   # points past the end

        def redraw():
            # Repaint the line from start to end, clearing leftovers.
            self.gpu.fill(start_x, start_y, self.w - start_x + 1, 1, " ")
            self.gpu.set(start_x, start_y, "".join(buf))
            self.set_cursor(start_x + pos, start_y)

        # --- blinking cursor ---------------------------------------------------
        # OC screens have no hardware cursor, so we fake one the way OpenOS does
        # (see openos core/cursor.lua): every BLINK seconds read the cell under the
        # cursor with gpu.get -> its character and its own fg/bg -> redraw it in
        # inverse video (fg/bg swapped), then plain again. event.pull waits with a
        # timeout so it wakes to toggle; a real keystroke still returns at once.
        BLINK = 0.5
        cursor_on = [False]

        def cursor_x():
            return start_x + pos

        def draw_cursor(on):
            self.draw_cursor_cell(cursor_x(), start_y, on)
            cursor_on[0] = on

        def erase_cursor():
            # Restore the plain cell before editing/echoing.
            if cursor_on[0]:
                draw_cursor(False)

        # Show the cursor solid as soon as the user does anything. Without this the
        # cursor is erased on each keystroke and only comes back on the next blink
        # tick, so after an arrow key you can be left up to BLINK seconds with no
        # idea where the cursor is. Idle blinking still happens; it just never
        # starts out invisible at a position you just moved to.
        show_now = [True]

        while True:
            if show_now[0]:
                draw_cursor(True)
                show_now[0] = False
            sig = event.pull(BLINK)
            if sig is None:
                # timeout: toggle the cursor and keep waiting
                draw_cursor(not cursor_on[0])
                continue
            erase_cursor()
            show_now[0] = True          # repaint it at the new spot on the way round
            name = sig[0]

            if name == "clipboard":
                # ("clipboard", keyboardAddress, text) on real OC.
                paste = ""
                if len(sig) >= 3:
                    paste = sig[2]
                elif len(sig) > 1:
                    paste = sig[1]
                # A paste stops at the first newline (that submits the line).
                nl = paste.find("\n")
                if nl >= 0:
                    text = paste[:nl]
                else:
                    text = paste
                for ch in text:
                    buf.insert(pos, ch)
                    pos += 1
                redraw()
                if nl >= 0:
                    break
                continue

            if name != "key_down":
                continue

            # Real OC key_down signals carry the keyboard address first:
            #   ("key_down", keyboardAddress, char, code)
            # (Keyboard.signal sends char/code, and sendToReachable prepends the
            # source node's address.) So char/code start at index 2. Be tolerant of
            # a 3-tuple form too, in case a signal was pushed without an address.
            if len(sig) >= 4:
                ch = sig[2]
                code = sig[3]
            else:
                ch = sig[1] if len(sig) > 1 else 0
                code = sig[2] if len(sig) > 2 else 0
            ch = int(ch) if ch else 0

            if code == KEY_ENTER:
                break
            elif code == KEY_BACK:
                if pos > 0:
                    # NB: del buf[i] corrupts the list in this VM (leaves a None and
                    # keeps the length); rebuild via slicing instead. Same below.
                    buf = buf[:pos - 1] + buf[pos:]
                    pos -= 1
                    redraw()
            elif code == KEY_DELETE:
                if pos < len(buf):
                    buf = buf[:pos] + buf[pos + 1:]
                    redraw()
            elif code == KEY_LEFT:
                if pos > 0:
                    pos -= 1
                    self.set_cursor(start_x + pos, start_y)
            elif code == KEY_RIGHT:
                if pos < len(buf):
                    pos += 1
                    self.set_cursor(start_x + pos, start_y)
            elif code == KEY_HOME:
                pos = 0
                self.set_cursor(start_x, start_y)
            elif code == KEY_END:
                pos = len(buf)
                self.set_cursor(start_x + pos, start_y)
            elif code == KEY_UP:
                if hist and hist_index > 0:
                    hist_index -= 1
                    buf = list(hist[hist_index])
                    pos = len(buf)
                    redraw()
            elif code == KEY_DOWN:
                if hist and hist_index < len(hist) - 1:
                    hist_index += 1
                    buf = list(hist[hist_index])
                    pos = len(buf)
                    redraw()
                elif hist_index >= len(hist) - 1:
                    hist_index = len(hist)
                    buf = []
                    pos = 0
                    redraw()
            elif ch == 9:          # Tab: completion via the callback
                if complete:
                    try:
                        cands = complete("".join(buf), pos)
                    except Exception:
                        cands = []
                    if cands:
                        if len(cands) == 1:
                            c0 = cands[0]
                            buf = list(c0[0])
                            pos = c0[1]
                            redraw()
                        else:
                            # apply the common prefix of all candidates, then list
                            # them below and re-draw the prompt + line
                            texts = [c[0] for c in cands]
                            common = texts[0]
                            for t in texts[1:]:
                                n = 0
                                lim = len(common) if len(common) < len(t) else len(t)
                                while n < lim and common[n] == t[n]:
                                    n += 1
                                common = common[:n]
                            shown = []
                            for c in cands:
                                w = c[2] if len(c) > 2 else c[0]
                                shown.append(w)
                            self._newline()
                            self.write("  ".join(shown))
                            self._newline()
                            if len(common) > len(buf):
                                buf = list(common)
                                pos = len(buf)
                            # re-print the prompt + current line on the new row
                            if prompt:
                                self.write(prompt)
                            start_x = self.x
                            start_y = self.y
                            redraw()
            elif ch == 3:          # Ctrl+C
                self.write("^C\n")
                return None
            elif ch == 4:          # Ctrl+D
                if not buf:
                    return None
            elif ch >= 32:         # printable
                buf.insert(pos, chr(ch))
                pos += 1
                redraw()

        # move to the next line after the input
        self.set_cursor(start_x + len(buf), start_y)
        self._newline()
        return "".join(buf)
