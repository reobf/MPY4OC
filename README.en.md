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

- **Foreground interrupts.** In the OC terminal, Ctrl+C raises `KeyboardInterrupt` at cooperative points such as sleep/yield. Ctrl+Alt+C also checks loop back edges, calls and resumption, including busy Python loops. Handlers and `finally` run normally; background jobs are unaffected. See [terminal interrupts](docs/tkinter.md#终端中断) for upgrade instructions and limits.

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

The default shell command search order is `/bin → /bin/tkinter → /usr/bin → /home/bin → current working directory`, with built-ins taking precedence. In each directory it tries the exact filename, then `.py` followed by `.mpy` if the name is missing and does not already end in either suffix. Source wins over same-name bytecode in that directory; an execution error never retries another candidate. Names containing `/` bypass the search list: `./app.py`, `../app` and `scripts/app` resolve against cwd; absolute paths are used directly. `which app` shows the resolved file.

The shell supports **Tab command-name and path completion**: `tkdes` + Tab becomes `tkdesktop `; command names come from built-ins and all the search directories above, omitting `.py` / `.mpy` and merging identical names. Explicit command paths such as `./` also complete directory by directory. Arguments complete files and directories (`cd` only offers directories), including arguments to `python` and `cp`. Paths can be relative to the current directory, use `./` or `../`, or be absolute, including mounted disks under `/mnt/`. Directories get a trailing `/` so you can keep completing; files get a space. Names containing spaces or quotes are quoted automatically. Hidden entries appear when the basename starts with `.`.

Multiple matches extend the common prefix and list candidates in name order while preserving later arguments and the cursor; no match leaves the input unchanged. Use `jobs` to list background programs and `kill number` to stop one.

`python app.py args` runs in the foreground; `pythonw app.py args` starts an independent background program and returns the prompt immediately. Bundled Tk commands declare `#!/usr/bin/env pythonw` on their first line and simply call `root.mainloop()` without `sh.spawn()`. Explicit `python /bin/tkinter/tkedit.py` remains foreground. Scripts use `sys.argv` (the absolute script path at index 0), `os.getcwd/chdir`, `os.path.abspath` and `os.environ`; argv, cwd and environment belong to each program and are shared by its threads.

`python app.mpy args` / `pythonw app.mpy args` directly load compatible MicroPython bytecode (v6, no native machine code). Only the final filename's case-sensitive `.mpy` suffix selects bytecode loading. `.py`, `.pyw`, other extensions and extensionless files always use the source compiler, without content sniffing or bytecode fallback. Explicit interpreter arguments must name the actual file; no suffix is added.

Use `subprocess.Popen(['pythonw', 'app.py', 'arg'])` to launch another program. It provides `pid/poll/wait/returncode/terminate/kill`; `subprocess.run([...], check=True)` waits and checks the exit status. `call/check_call`, `cwd=`, `env=` and `timeout=` are supported. Programs share one OC VM and imported modules. They cannot run host executables. Pipes, redirection, `shell=True` and Python's `-c/-m` are not implemented. Termination is cooperative: a computation loop without poll points cannot be forcibly stopped. MPYOS defines `pythonw` as a background launcher; CPython's Windows `pythonw` only selects a console-free interpreter.

All eight `os.spawnl/spawnle/spawnlp/spawnlpe` and `os.spawnv/spawnve/spawnvp/spawnvpe` variants are available. `P_WAIT` returns the exit code after completion; `P_NOWAIT` / `P_NOWAITO` return a virtual PID. Collect spawn children with `os.waitpid` and convert its encoded status with `os.waitstatus_to_exitcode`. `os.kill(pid, 9 or 15)` requests cooperative termination; `os.getpid/getppid` query identity. Non-`p` variants open exact cwd-relative or absolute paths; `p` variants search the child's PATH and support MPYOS's `.py` / `.mpy` shorthand. `e` variants replace the environment. Direct script execution preserves the supplied `argv[0]`. These additions are frozen into the mod JAR; update it and reboot the OC computer. See the [process API guide](docs/tkinter.md#标准程序启动接口) for details and limits.

Legacy `sh.run_command(name, args)` / `sh.spawn(name, args)` remain compatible with the same lookup rules. Nested `run_command` calls share the caller's program; `spawn` and `subprocess` create independent programs. Children capture cwd and environment at launch, and `cwd=` can select their starting directory.

**`os.environ['PATH']`** controls command lookup, defaulting to `/bin:/bin/tkinter:/usr/bin:/home/bin:.`; `sh.path` remains a compatibility alias for that variable. `os.getenv(name, default=None)` reads variables. `os.environ` supports assignment, deletion, `get/update/setdefault/pop/clear/copy`, iteration and `dict(os.environ)`. Keys and values must be strings; keys are case-sensitive. Each independent program inherits an environment copy, shared by its own threads. Changes do not affect the interactive shell or other programs; nested `run_command` calls share the caller's environment. Removing or emptying PATH leaves built-ins and explicit command paths available; explicit paths and argument paths still complete. To configure the interactive shell's default search path, set `os.environ['PATH']` in `/init.py` before calling `shell.run(...)`.

On an existing MPYOS hard disk, update the mod JAR and copy `/lib/shell.py`, `/lib/osfs.py`, `/lib/term.py`, `/bin/which.py`, `/bin/python.py`, `/bin/python3.py`, `/bin/py.py`, the new `/bin/pythonw.py` and `/bin/tkinter/tk*.py` from the new LiveCD, then reboot the OC computer. Replacing the mod jar does not overwrite installed system files. Tk commands now live in `/bin/tkinter/`; move old `/bin/tk*.py` copies there so they do not shadow the new directory. Add `/bin/tkinter` to any custom PATH.

SFTP card: `component.sftp.start("user", "password")` starts the service, `getPort()` tells you the port, then connect any SFTP/SSH client to the server's IP. Shutting the computer down disconnects and deregisters automatically; unloading and reloading the chunk restores the service.

### File browser

Run `tkexplorer [directory or file]` to browse in the background. It supports mounted disks, hidden files, path navigation, name filtering, pages, creation and renaming, with icons for folders, Python, Lua and other files. Files open in independent tkedit windows; deletion requires confirmation and folders must be empty. Existing disks need `/bin/tkinter/tkexplorer.py` from the new LiveCD, updated server and client JARs and an OC computer reboot. See the [file browser guide](docs/tkinter.md#tkexplorer文件浏览器).

### Task manager

Run `tktaskmgr` to open a background process monitor with PID, command, ops/s, instruction share, thread count, state and cumulative ops. Click column headings to sort and End process to request termination of the selected program. Its threads share one counter; counters survive snapshots. Termination remains cooperative at wait/poll points. Ops measure VM bytecode dispatches, not server CPU time. Existing disks need `/bin/tkinter/tktaskmgr.py` from the new LiveCD after updating the JAR and rebooting the OC computer. See the [task manager guide](docs/tkinter.md#tktaskmgr程序与-ops-监视器).

### AE crafting monitor

Connect an OC Adapter beside an AE ME Controller or ME Interface to your OC computer network and Tk display, then run `tkaemon` (or `tkaemon address-prefix` to select an ME component). It runs in the background by default. Select a CPU on the `CPUs` tab. The default `Tasks` tab shows native item/fluid icon cards with gray waiting, amber crafting and green observed-done segments. Filter, page through and click cards for a preview; Stored displays CPU inventory separately. The selected CPU refreshes every second; other CPUs update in batches. Click `Rescan` after adding or removing CPUs.

Progress is computed the way AE's crafting status screen does it: the work left is the sum of the Active and Pending lists, the start is that sum when the job is first seen, and a job merged in later adds its new work to the start. Opening mid-job counts from that moment; switching CPUs, rescanning or seeing the CPU idle or failing starts a new count. Fluids count in mB, as in AE. D~ estimates observed reductions in Active + Pending; inventory is not completed work, and idle does not prove success. Observation history is capped at 512 types. Showing the final output needs a **Crafting Monitor** block in the CPU multiblock; without one the progress still works, only the output icon is missing. If the lists cannot be read, an indeterminate bar and the reason appear. Monitoring is read-only and never starts or cancels crafting. Existing disks need `/bin/tkinter/tkaemon.py` and `/lib/aemon.py` from the new LiveCD; native icons require the new mod JAR.

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
