# mpy BIOS -- lives in the EEPROM code section; MpyArchitecture compiles and runs it
# at boot. Like OC's bios.lua: pick a bootable filesystem (the boot address if set,
# else scan), read its /init.py and exec() it in this namespace -- init.py takes over
# and control never returns here. Runs before mpyos exists, so only the injected host
# primitives (component, computer, exec) are available.
#
# SIZE LIMIT: OpenComputers clips EEPROM code to eepromSize (4096 bytes by default)
# when the loot item is registered and when the component is saved -- silently. Keep
# this file well under that (MyMod logs an error at startup if it no longer fits).


def _read_file(fs, path):
    try:
        h = component.invoke(fs, "open", path)
    except Exception:
        return None
    if not h:
        return None
    try:
        chunks = []
        while True:
            chunk = component.invoke(fs, "read", h, 4096)
            if chunk is None:
                break
            if isinstance(chunk, bytes):   # binary read -> text
                chunk = chunk.decode("utf-8")
            chunks.append(chunk)
        return "".join(chunks)
    finally:
        try:
            component.invoke(fs, "close", h)
        except Exception:
            pass


def _has_init(fs):
    try:
        return bool(component.invoke(fs, "exists", "/init.py"))
    except Exception:
        return False


def _exists(fs, path):
    try:
        return bool(component.invoke(fs, "exists", path))
    except Exception:
        return False


def _looks_like_lua(fs):
    # A Lua/OpenOS disk without init.py -- only used for a clearer error message.
    if _has_init(fs):
        return False
    return (_exists(fs, "/init.lua")
            or _exists(fs, "/boot")
            or _exists(fs, "/lib/core/boot.lua")
            or _exists(fs, "/OS.lua"))


def _find_boot_fs():
    # Boot address if set and still bootable, else the first filesystem with /init.py.
    # A scan result is written back with setBootAddress (as OC's stock BIOS does): the
    # EEPROM data area is the only place anything outside the VM (e.g. the SFTP card)
    # can learn which disk this machine booted from.
    addr = computer.getBootAddress()
    if addr is not None and _has_init(addr):
        return addr
    try:
        computer.setBootAddress()      # clear a stale address before scanning
    except Exception:
        pass
    for a in component.list("filesystem"):
        if _has_init(a):
            try:
                computer.setBootAddress(a)
            except Exception:
                pass          # read-only/unpowered EEPROM: boot anyway, just don't remember
            return a
    return None


def _fatal(msg):
    try:
        computer.beep(1000, 0.2)
    except Exception:
        pass
    print("mpy bios: " + msg)


def main():
    fs = _find_boot_fs()
    if fs is None:
        for a in component.list("filesystem"):
            if _looks_like_lua(a):
                _fatal("this looks like a Lua/OpenOS disk. MPYOS BIOS runs "
                       "Python (/init.py), not Lua. Use a Lua BIOS for OpenOS, "
                       "or insert a MPYOS floppy/disk.")
                return
        _fatal("no bootable medium (need /init.py on a floppy or disk)")
        return

    src = _read_file(fs, "/init.py")
    if src is None:
        _fatal("could not read /init.py from " + str(fs))
        return

    # Hand the machine to init.py: same global namespace, its definitions replace ours.
    g = globals()
    g["__boot_fs__"] = fs          # let init know which disk it booted from
    exec(src, g)


main()
