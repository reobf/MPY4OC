# MPY4OC

*[中文](README.md) · English*

[![Release](https://img.shields.io/github/v/release/reobf/MPY4OC?include_prereleases&label=release)](https://github.com/reobf/MPY4OC/releases/latest)
[![Build and test](https://github.com/reobf/MPY4OC/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/reobf/MPY4OC/actions/workflows/build-and-test.yml)

A **MicroPython processor** for OpenComputers. Put an MPY CPU in the case and flash the EEPROM to the MPYOS BIOS, and that OC computer is programmed in Python. Saving the world freezes the program in place; loading resumes it. Minecraft 1.7.10, built for GTNH.

```python
# /init.py -- runs at boot. Without MPYOS, this one file on the hard disk is all you need
import time

gpu = component.gpu                              # component and computer are built-in globals
gpu.bind(next(iter(component.list("screen"))))   # bind the GPU to a screen first, as in OC's Lua
w, h = gpu.getResolution()                       # a component's multiple return values are a tuple
n = 0
while True:
    gpu.set(1, 1, "Hello from Python!  up %d s" % n)
    n += 1
    time.sleep(1)                                # yields the CPU; after save and load it resumes here
```

## What it does

- **MicroPython in pure Java.** No native libraries: the compiler's output is byte-for-byte identical to the official `mpy-cross`, and the interpreter executes `.mpy` bytecode directly, with no microcontroller emulator in between; `.py` source and `.mpy` bytecode both run as they are.
- **Saves are snapshots.** Variables, call stacks, threads and coroutines all go into the save file; after loading, the program continues from that moment without restarting.
- **Threads and asyncio.** `threading` and `asyncio` follow CPython 3.12's API and semantics (`Lock`, `RLock`, `Condition`, `Event`, `Semaphore`, `Barrier`, `Timer`; `Task`, `gather`, `Queue` and so on); this is not MicroPython's native `uasyncio`. Scheduling is in ops: each tick's ops budget is split evenly between the main program and the runnable threads, a thread that uses up its share hands over to the next, and whatever the tick leaves unused goes into the bank (see Processors below); an asyncio event loop runs in the thread that started it and is scheduled by park/unpark, so sleeping coroutines and waiting threads take no share and spend no ops until they are due or woken. Sleeps and timeouts count game ticks (20 per second), not wall-clock time: nothing passes while the world is saved or the chunk unloaded.
- **tkinter.** The API and semantics of CPython's `tkinter` (with `ttk` and the standard dialogs), drawn on Tk displays: a multi-window desktop, displays joined up to 8×8 blocks, live content on block faces, right-click for the shared interactive GUI.
- **Networking.** `requests`, `urllib` and `socket` are partial, client-side implementations (HTTP requests and TCP connections; no listening on ports), carried by OC's internet card underneath.
- **SSH + SFTP server card.** One component card serves both: SFTP reads and writes the computer's disks, and an SSH terminal mirrors the in-game screen and accepts remote typing.
- **MPYOS.** An operating system written in Python and running on this VM: a shell (Tab completion, background jobs, Ctrl+C), an editor and a Python REPL; the bundled file browser, task manager and AE crafting monitor are tkinter application examples.

## Installing

- Minecraft 1.7.10 + Forge, with OpenComputers (the GTNH fork) and ModularUI2; install on both server and client.
- Download `mpy4oc-<version>.jar` from [Releases](https://github.com/reobf/MPY4OC/releases) (the file without a `-dev`, `-sources` or similar suffix) and drop it into `mods`.

## Getting started

Every recipe is **shapeless**, and a vanilla piece of **String** marks the "MPY version":

| Result | Recipe |
|---|---|
| MPY CPU / APU (tiers 1–3) | OC's same-tier CPU / APU + String (the Creative APU stands in for tier 3) |
| MPYOS BIOS | Any EEPROM + String, or any EEPROM + an MPYOS floppy (overwrites the original data) |
| MPYOS LiveCD floppy | Any floppy + String (overwrites the original data); a wrench + a loot floppy cycles through to the MPYOS disk |
| Tk display | OC tier-1 screen + String |
| SFTP server card | Card base + String |
| Sync ↔ async processor | A single processor alone in the crafting grid, 1:1 both ways |

1. Assemble a machine as you would a Lua computer (case, RAM, GPU, screen, keyboard, hard disk) with the MPY CPU and EEPROM in place of the stock ones, and the LiveCD in a floppy drive.
2. Boot into the shell and type `install`; the system is copied to the hard disk and the floppy is no longer needed.
3. Write a program with `edit app.py` and run it with `python app.py`; with a Tk display attached, `tkedit app.py` works too.
4. No MPYOS wanted, two ways: put your own `/init.py` (like the example above) alone in the root of the hard disk, and the MPYOS BIOS runs it at boot; or write the program itself into the EEPROM (`component.invoke(address, 'set', source)`, or OC's `flash` on a Lua computer), and it runs straight from the EEPROM with no disk at all, which is how a microcontroller, EEPROM only, runs it too (an EEPROM holds 4 KB by default).

## Processors

| Tier | ops (instructions per tick) |
|---|---|
| 1 | 500 |
| 2 | 2000 |
| 3 | 16000 |

ops left unused while idle are banked (up to 10 ticks' worth) and spent in a burst when there is work. An APU has a built-in graphics card and saves a GPU slot.

- **Synchronous** (the default choice): runs on the server main thread, zero-latency component calls, guaranteed execution every tick.
- **Asynchronous**: runs on OC's compute thread and is friendlier to TPS; non-direct component calls bounce back to the main thread, matching OC's Lua architecture.

## Common MPYOS commands

| Command | Purpose |
|---|---|
| `edit file` | Full-screen text editor |
| `python` / `python file args` | Interactive REPL, or run a script in the foreground; `pythonw` runs it in the background |
| `jobs` / `kill number` | List or stop background programs; any other command goes to the background with a trailing `&` |
| `components` / `which command` / `ops` | List components / show where a command resolves / show this tick's remaining instruction budget |
| `tkedit file` | Mouse-driven editor (this and the rest need a Tk display) |
| `tkexplorer` | File browser |
| `tktaskmgr` | Task manager: ops/s and thread count per program, with End process |
| `tkaemon` | AE crafting CPU monitor: an OC Adapter next to an ME Controller or ME Interface is enough; read-only |
| `tkdesktop` / `tkdemo` and others | Tk examples, source in `/bin/tkinter/` |

tkinter programs run in the background by default and return the prompt immediately. To start another program from a script, use `subprocess.Popen(['pythonw', 'app.py'])` or `os.spawn*`; see the [process API](https://github.com/reobf/MPY4OC/wiki/tkinter-Guide#the-standard-process-launch-api) (wiki).

## What Python has here

MPYOS provides `open('notes.txt', 'w')` and `input('Name: ')`. Files use the calling program's cwd and `/mnt` mounts on OC disks, with UTF-8, binary mode, `with`, reading, writing and appending. Background programs cannot read the terminal. OC update (`+`) and exclusive creation (`x`) modes are not supported. Existing disks need the new JAR, `/lib/osfs.py`, `/lib/term.py` and an OC computer reboot. The entry points live in the frozen library; other hosts can supply their own file and input backends through `io.install(opener=..., reader=...)`.

- `component` and `computer` are built-in globals, no import needed; MPYOS adds `event`, `fs`, `term`, `requests`, `socket`, `urllib` and more.
- Frozen standard library: `asyncio` `binascii` `cmath` `collections` `deflate` `functools` `gc` `hashlib` `heapq` `io` `json` `machine` `math` `micropython` `os` `pickle` `random` `re` `struct` `subprocess` `sys` `threading` `time` `tkinter` `traceback`.
- Differences from standard Python, component calls, the ops budget and save-file caveats are in the [MicroPython Programming Guide](https://github.com/reobf/MPY4OC/wiki/MicroPython-Programming-Guide).

## Upgrading an existing disk

When a mod update changes the MPYOS code on the LiveCD, a system already installed on a hard disk does not pick up the new code by itself. To use the new system's features, copy `/bin`, `/bin/tkinter` and `/lib` from the new LiveCD onto the disk (the floppy is mounted under `/mnt/`; `cp` copies one file at a time, the SFTP card can move whole directories), delete the old `tk*.py` files under `/bin`, then reboot the computer. Running `install` onto a fresh empty disk is the least effort.

## Documentation

- [tkinter Guide](https://github.com/reobf/MPY4OC/wiki/tkinter-Guide) (wiki): bare `init.py`, display selection, widgets, desktop and windows, each tkinter command, the process API, terminal interrupts.
- [MPYOS User Manual](https://github.com/reobf/MPY4OC/wiki/MPYOS-User-Manual) (wiki): building the machine, booting, the shell and its commands, the SFTP card, troubleshooting.
- [MicroPython Programming Guide](https://github.com/reobf/MPY4OC/wiki/MicroPython-Programming-Guide) (wiki): differences from standard Python, working with components, networking, save-file caveats.

## Building

```bash
./gradlew genStdlib    # if you changed stdlib/*.py, run this again to regenerate the frozen standard library
./gradlew build        # runs all tests, artifacts land in build/libs/
```

Built on GTNH's [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) buildscript.

## License

MIT
