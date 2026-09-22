# MPY4OC — Write Python inside OpenComputers

*[中文](README.md) · English*

> Run real MicroPython on your OpenComputers computers. Minecraft 1.7.10 / GTNH.

A **MicroPython processor** for OpenComputers: drop an MPY CPU into the case, flash the BIOS, and that OC computer speaks Python. Ships with **mpyos** (shell, line editing, filesystem, editor) and an **SFTP server card** — point MobaXterm or any SFTP client at the computer's disk to edit `init.py` directly, and watch the in-game screen from an SSH terminal while typing remotely.

```python
gpu = component.gpu
w, h = gpu.getResolution()
gpu.set(1, 1, "Hello from Python!")

import threading, jasyncio
threading.Thread(target=lambda: print("threads work")).start()
jasyncio.run(main())          # asyncio is here too
```

## Features

- **Real MicroPython bytecode.** A pure-Java compiler is built in, and its output is **byte-for-byte identical** to the official `mpy-cross` (749/749 on the official compile test suite). You can also switch to the real mpy-cross binary as the backend in the mod config — same behaviour either way.
- **Full-state snapshots.** Saving the world freezes the entire VM into the save file: variables, call stacks, generators, coroutines, threads and component proxies all survive. Loading resumes **exactly where the save was taken**, without restarting your program. Every timer (`time.sleep`, coroutine sleeps) ignores the time you were offline.
- **Two processor families, three tiers each** (CPU, and APU with an integrated GPU):
  - Synchronous: runs on the server main thread, zero-latency component calls, guaranteed execution every tick — pick this one by default;
  - Asynchronous: runs on OC's compute thread, friendlier to TPS, non-direct component calls automatically bounce back to the main thread, semantics matching OC's own Lua architecture.
- **Threads and asyncio, both.** `threading` is stackful — an ordinary function can call `time.sleep` at any depth and the whole stack is preserved; `jasyncio` is an asyncio-style event loop where each `run()` is independent, so you can nest them or start one inside a thread.
- **Per-tick ops budget.** Scripts are rate-limited per tick by CPU tier (500 / 2000 / 16000 instructions). Yielding while idle banks budget (up to 10×), so a script that cooperates gets a burst when it actually has work.
- **24 standard library modules**: `json`, `re` (a superset), `struct`, `hashlib`, `collections`, `functools`, `pickle`, `traceback`, `machine`, `micropython`, and more — most of them byte-for-byte compatible with upstream.
- **Networking is just `requests` / `urllib` / `socket`.** Plug in OC's internet card and `requests.get(url).json()` means exactly what you think it means; `urlopen` returns a file-like object, and `socket` gives you raw TCP. These libraries are **always present** — they import fine with no card installed — and only raise `OSError` when you actually make a request. All waiting yields on tick boundaries, so it never hogs the server.

## Crafting recipes

Every recipe is **shapeless**, and a vanilla piece of **String** is the "MPY flavour" marker:

| Result | Recipe |
|---|---|
| MPY CPU (tier 1/2/3) | OC's **same-tier** CPU + String |
| MPY APU (tier 1/2/3) | OC's APU (tier 1/2) or the **Creative APU** (as tier 3) + String |
| MPYOS BIOS | Any EEPROM + String (the old contents are overwritten — it *is* a flash) |
| MPYOS LiveCD floppy | Any floppy + String (the old contents are overwritten — a flash, like the BIOS) |
| SFTP server card | Card board + String |
| Sync ↔ async (CPU/APU, same tier) | Drop a single processor into the crafting grid to convert, 1:1 both ways |

Two pre-existing paths still work: any EEPROM + an MPYOS floppy also crafts the BIOS, and a wrench + any loot floppy cycles through to the MPYOS disk.

## Getting started

1. Craft an **MPY CPU** and an **MPYOS BIOS**, then assemble a machine exactly as you would a Lua computer (case, RAM, GPU, screen, keyboard, hard drive), with the MPY CPU and EEPROM in place of the stock ones;
2. Craft an **MPYOS LiveCD floppy**, insert it into a floppy drive, and boot — you land in the shell from the LiveCD;
3. Run `install` to put the system on the hard drive; after that it boots without the floppy;
4. Edit `/init.py` to write your program (in-game `edit`, or plug in an SFTP card and edit remotely).

SFTP card: `component.sftp.start("user", "password")` starts the service, `getPort()` tells you the port, then connect any SFTP/SSH client to the server's IP. Shutting the computer down disconnects and deregisters automatically; unloading and reloading the chunk restores the service.

## Documentation

- **[Wiki](../../wiki)** — player manual and MicroPython programming guide (differences from standard Python, working with components, multiple return values, the ops budget, save-file caveats)

## Building

```bash
./gradlew build        # artifacts land in build/libs/
```

Built on GTNH's [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) buildscript. Depends on OpenComputers (the GTNH fork); other forks are not guaranteed to work.

## License

MIT
