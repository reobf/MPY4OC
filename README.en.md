# MPY4OC — Write Python inside OpenComputers

*[中文](README.md) · English*

> Run real MicroPython on your OpenComputers computers. Minecraft 1.7.10 / GTNH.

A **MicroPython processor** for OpenComputers: drop an MPY CPU into the case, flash the BIOS, and that OC computer speaks Python. Ships with **mpyos** (shell, line editing, filesystem, editor) and an **SFTP server card** — point MobaXterm or any SFTP client at the computer's disk to edit `init.py` directly, and watch the in-game screen from an SSH terminal while typing remotely.

```python
gpu = component.gpu
w, h = gpu.getResolution()
gpu.set(1, 1, "Hello from Python!")

import threading, asyncio
threading.Thread(target=lambda: print("threads work")).start()
asyncio.run(main())           # asyncio is here too
```

## Features

- **Real MicroPython bytecode.** A pure-Java compiler is built in, and its output is **byte-for-byte identical** to the official `mpy-cross` (749/749 on the official compile test suite). No external program needed.
- **Full-state snapshots.** Saving the world freezes the entire VM into the save file: variables, call stacks, generators, coroutines, threads and component proxies all survive. Loading resumes **exactly where the save was taken**, without restarting your program. Every timer (`time.sleep`, coroutine sleeps) ignores the time you were offline.
- **Two processor families, three tiers each** (CPU, and APU with an integrated GPU):
  - Synchronous: runs on the server main thread, zero-latency component calls, guaranteed execution every tick — pick this one by default;
  - Asynchronous: runs on OC's compute thread, friendlier to TPS, non-direct component calls automatically bounce back to the main thread, semantics matching OC's own Lua architecture.
- **Threads and asyncio, both.** `threading` is stackful — an ordinary function can call `time.sleep` at any depth and the whole stack is preserved; `asyncio` follows CPython 3.12 (tasks, futures, `gather` / `wait_for` / `timeout`, locks and queues, `to_thread`), with an event loop per thread, so a thread can `asyncio.run()` its own; the old name `jasyncio` still imports, as the same module.
- **Per-tick ops budget.** Scripts are rate-limited per tick by CPU tier (500 / 2000 / 16000 instructions). Yielding while idle banks budget (up to 10×), so a script that cooperates gets a burst when it actually has work.
- **26 frozen standard library modules**: `json`, `re` (a superset), `struct`, `hashlib`, `collections`, `functools`, `pickle`, `traceback`, `machine`, `micropython`, and more — most of them byte-for-byte compatible with upstream.
- **Networking is just `requests` / `urllib` / `socket`.** Plug in OC's internet card and `requests.get(url).json()` means exactly what you think it means; `urlopen` returns a file-like object, and `socket` gives you raw TCP. These libraries are **always present** — they import fine with no card installed — and only raise `OSError` when you actually make a request. All waiting yields on tick boundaries, so it never hogs the server.

- **Tk interfaces.** A frozen `tkinter` subset provides buttons, entries, lists, Canvas and plain-text Text widgets. Each program's `Tk()` is a window on the display's desktop (drag, resize, minimize, maximize), and one computer's programs share a display. A Tk display can preview its live contents in the world; right-click opens its shared interactive GUI. MPYOS is optional. See the [Tk guide](docs/tkinter.md) (Chinese, with Python examples).

## Crafting recipes

Every recipe is **shapeless**, and a vanilla piece of **String** is the "MPY flavour" marker:

| Result | Recipe |
|---|---|
| MPY CPU (tier 1/2/3) | OC's **same-tier** CPU + String |
| MPY APU (tier 1/2/3) | OC's APU (tier 1/2) or the **Creative APU** (as tier 3) + String |
| MPYOS BIOS | Any EEPROM + String (the old contents are overwritten — it *is* a flash) |
| MPYOS LiveCD floppy | Any floppy + String (the old contents are overwritten — a flash, like the BIOS) |
| Tk display | OC tier-1 screen + String |
| SFTP server card | Card board + String |
| Sync ↔ async (CPU/APU, same tier) | Drop a single processor into the crafting grid to convert, 1:1 both ways |

Two pre-existing paths still work: any EEPROM + an MPYOS floppy also crafts the BIOS, and a wrench + any loot floppy cycles through to the MPYOS disk.

## Getting started

1. Craft an **MPY CPU** and an **MPYOS BIOS**, then assemble a machine exactly as you would a Lua computer (case, RAM, GPU, screen, keyboard, hard drive), with the MPY CPU and EEPROM in place of the stock ones;
2. Craft an **MPYOS LiveCD floppy**, insert it into a floppy drive, and boot — you land in the shell from the LiveCD;
3. Run `install` to put the system on the hard drive; after that it boots without the floppy;
4. Edit `/init.py` to write your program (in-game `edit`; with a Tk display attached, also the mouse-driven `tkedit`. All bundled Tk commands run in the background by default and give the shell back; other commands can use `&`. Or plug in an SFTP card and edit remotely).

The shell uses OpenOS's default command search order: `/bin → /usr/bin → /home/bin → current working directory`, with built-ins taking precedence. In each directory it tries the exact filename, then adds `.py` if that file does not exist, so both `tkedit` and `tkedit.py` work. Names containing `/` bypass the search list: `./app.py`, `../app` and `scripts/app` resolve against cwd; absolute paths are used directly. `which app` shows the resolved file.

The shell supports **Tab command-name and path completion**: `tkdes` + Tab becomes `tkdesktop `; command names come from built-ins and all the search directories above, omitting `.py` from Python command names. Explicit command paths such as `./` also complete directory by directory. Arguments complete files and directories (`cd` only offers directories), including arguments to `python` and `cp`. Paths can be relative to the current directory, use `./` or `../`, or be absolute, including mounted disks under `/mnt/`. Directories get a trailing `/` so you can keep completing; files get a space. Names containing spaces or quotes are quoted automatically. Hidden entries appear when the basename starts with `.`.

Multiple matches extend the common prefix and list candidates in name order while preserving later arguments and the cursor; no match leaves the input unchanged. Use `jobs` to list background programs and `kill number` to stop one.

Inside scripts, `sh.run_command(name, args)` and `sh.spawn(name, args)` use the same lookup rules, and `sh.cwd` follows the program's `os.chdir()`. Background jobs inherit cwd and environment variables at launch; later shell directory changes do not affect them. `sh.spawn(name, args, cwd='directory')` selects the child's starting directory and resolves the script from there.

**`os.environ['PATH']`** controls command lookup, defaulting to `/bin:/usr/bin:/home/bin:.`; `sh.path` remains a compatibility alias for that variable. `os.getenv(name, default=None)` reads variables. `os.environ` supports assignment, deletion, `get/update/setdefault/pop/clear/copy`, iteration and `dict(os.environ)`. Keys and values must be strings; keys are case-sensitive. Each independent program inherits an environment copy, shared by its own threads. Changes do not affect the interactive shell or other programs; nested `run_command` calls share the caller's environment. Removing or emptying PATH leaves built-ins and explicit command paths available; explicit paths and argument paths still complete. To configure the interactive shell's default search path, set `os.environ['PATH']` in `/init.py` before calling `shell.run(...)`.

On an existing MPYOS hard disk, update `/lib/shell.py`, `/lib/term.py`, `/bin/which.py` and `/bin/tk*.py` from the new LiveCD, then reboot the OC computer. Replacing the mod jar does not overwrite installed system files.

SFTP card: `component.sftp.start("user", "password")` starts the service, `getPort()` tells you the port, then connect any SFTP/SSH client to the server's IP. Shutting the computer down disconnects and deregisters automatically; unloading and reloading the chunk restores the service.

### Interactive Python

Run `python` (also `python3` or `py`) in the MPYOS shell. Compound statements show
`... `; type the body with indentation, then submit a blank line:

```text
>>> def greet():
...     print("Hello")
...
>>> greet()
Hello
```

Open brackets, triple-quoted strings and trailing backslashes also allow continued
input. Ctrl+C cancels the pending block. This is line-by-line input; the terminal
still only accepts the first line of a multiline clipboard paste. Save longer
programs with `edit` or SFTP and run `python filename.py`.

On an existing MPYOS hard disk, update `/bin/python.py`, `/bin/python3.py` and
`/bin/py.py` from the new LiveCD and leave/re-enter the REPL. Replacing the mod jar
does not overwrite files already installed on the hard disk.

## Documentation

- **[Tk guide](docs/tkinter.md)** — bare `init.py`, display selection, supported widgets, input and snapshot compatibility

- **[Wiki](../../wiki)** — player manual and MicroPython programming guide (differences from standard Python, working with components, multiple return values, the ops budget, save-file caveats)

## Building

```bash
./gradlew build        # artifacts land in build/libs/
```

After editing `stdlib/*.py`, run `./gradlew genStdlib` followed by `./gradlew build` (`./gradlew.bat` on Windows). Generation uses the built-in Java compiler; WSL and external `mpy-cross` executables are unnecessary.

Built on GTNH's [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) buildscript. Depends on OpenComputers (the GTNH fork) and ModularUI2; other forks are not guaranteed to work.

## License

MIT
