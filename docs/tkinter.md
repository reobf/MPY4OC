# MPY4OC Tk 使用说明

`tkinter` 已冻结在 MPY 引擎里。装入 MPY CPU 或 APU 的 OC 电脑可直接导入，无需 MPYOS、软盘安装或本机 Tcl/Tk。它实现常用 Tk API 的子集；Java 服务端维护控件、布局和输入，客户端用 OpenGL 与 OC unifont 绘制。

## 从裸 init.py 启动

1. 用 **OC 一级屏幕 + 原版线** 无序合成 Tk 显示器。
2. 为 OC 机箱安装 MPY CPU、内存、MPYOS BIOS 和硬盘，供电；将 Tk 显示器接到同一个 OC 网络。Tk 本身不需要 OC 显卡或键盘。
3. 在硬盘根目录放下面的 `/init.py`，启动电脑，右键 Tk 显示器。

```python
import tkinter as tk

root = tk.Tk()
root.title('我的控制台')
root.geometry('320x240')
count = tk.StringVar(value='0')
tk.Label(root, text='你好，MPY4OC').pack(pady=8)
tk.Label(root, textvariable=count).pack()

def increment():
    count.set(str(int(count.get()) + 1))

tk.Button(root, text='计数 +1', command=increment).pack(pady=8)
root.mainloop()
```

没有显示器时 `import tkinter` 仍成功；`Tk()` 抛 `OSError`。`mainloop()` 按游戏 tick 处理信号并让出 VM。长任务应拆成小批次，用 `after(ms, callback)` 续跑；回调里的长循环会延迟同一应用的事件处理。

MPYOS 的 `/bin/tkinter/tkedit.py` 是一个可以实际使用的文本编辑器（见下文“tkedit 编辑器”）。`/bin/tkinter/` 还保留示例 `tkdemo.py`（按钮与输入框）、`tkdesktop.py`（桌面、窗口与任务栏）、`tkttk.py`（ttk 控件）、`tkimage.py`（`tkimage 文件路径` 显示图片，不带参数显示一张生成的渐变）、`tklog.py`（列表日志）、`tklayout.py`、`tkcanvas.py` 和 `tktext.py`（编辑器与只读日志）。`tkdemo.py` 用单块显示器即可；`tklog.py`、`tklayout.py`、`tkcanvas.py` 和 `tktext.py` 的窗口加上标题栏和任务栏要 2×2 拼接的显示器组（见下文“多块拼接与分辨率”），显示器太小时它们直接报错说明。已有 MPYOS 安装可只复制需要的示例；应用程序用普通 `import tkinter`。

## 指定显示器

```python
screens = component.list('tk_display')  # 地址 -> 组件类型
root = tk.Tk(screenName='完整地址或唯一前缀')
other = tk.Toplevel(root, screen='另一个显示器地址')  # 另一块屏上的新根窗口
print(root.winfo_screen())
```

一台电脑的程序共用显示器：每个程序的 `Tk()` 是桌面上的一个窗口（见下文“桌面与窗口”）。不传地址时，先用这台电脑已经开着窗口的那块；没有就按地址顺序，用第一块没被另一台**正在运行**的电脑占着的；全都被别的电脑占着时报 `OSError('no free tk display: every one is in use')`。占着显示器的电脑关机、重启或断开时，显示器会自动释放（清空画面、关闭玩家的界面），和 OC 的屏幕一样；显示器错过了这个消息（例如当时它所在的区块没加载）也不要紧，它记得是那台电脑的哪一次运行占的屏，电脑重启过就不再算占着。检查和占用是一次完成的，两台电脑同时启动的程序不会都占到同一块。指定地址时在那块屏上开窗口：这台电脑在用的就再加一个窗口，另一台电脑的就接管它（那台电脑的程序的窗口全部关掉，它们的会话 token 随之失效）。前缀匹配多台或目标不在网络中报 `OSError`。`Toplevel(root)` 在同一块屏上再开一个窗口；`screen=` 指向另一块屏时，在那块屏上开一个新的根窗口。

同一显示器由所有观看者共享控件内容、焦点、光标和选区，最后操作的玩家接管拖选。玩家界面只显示整块桌面，没有标题栏，也没有自己的关闭按钮：按 Esc 关掉自己的界面，程序照常运行。各窗口标题栏上的 × 才向程序发送 `WM_DELETE_WINDOW`，默认销毁那个窗口（程序可用 `root.protocol('WM_DELETE_WINDOW', 函数)` 接管，例如先确认）。程序释放显示器（`destroy()`、命令结束）时只关它自己的窗口；最后一个程序也走了，屏幕空出来，所有观看者的界面一并关闭。`quit()` 退出事件循环，`destroy()` 关掉这个程序的窗口、删掉它的控件和图片；屏幕已被拆除或被其他电脑接管时 `destroy()` 也不会报错，照样清理本地状态。

在 MPYOS 中，每条从 shell 直接运行的命令结束时（正常返回、出错，或 `quit()` 后返回），会销毁它打开的 Tk 窗口（同一块屏上别的程序的窗口不受影响），与 CPython 进程退出时关闭窗口一致。

**程序与线程**：在 shell 里运行的每条命令都是一个“程序”，它开的线程（`threading`、`_thread`）、协程（`asyncio`、`micropython.schedule`）、Tk 窗口、`event.listen`/`event.timer` 回调和 `os` 的当前目录都属于它，子线程开的也算。命令结束时，shell 像进程退出那样收尾：先等它的非守护线程跑完（和 CPython 一样；屏幕上会提示，按 Ctrl+C 不再等——这时它的线程收不到 Ctrl+C，留给 shell），再停掉它剩下的线程和协程，关掉它的窗口，删掉它的事件回调。所以 `python 程序.py` 里开的线程不会在程序结束后留在 MPYOS 里继续跑。守护与否和 CPython 一样：新线程默认跟创建它的线程相同（后台命令里直接开的线程算非守护），`_thread.start_new_thread` 开的线程都是守护线程，不会让 shell 等。停止是协作式的：被停止的线程或协程在下一个“等待点”收到 `SystemExit`（`finally` 照常执行），等待点包括 `time.sleep`、`computer.pullSignal`（以及基于它的 `event.pull`、终端读入）、Tk 的 `mainloop`/`update`、`asyncio` 事件循环的每一轮。`asyncio` 和 CPython 3.12 一样：事件循环跑在调用 `asyncio.run()` 的线程里（每个线程一个，旧名 `jasyncio` 是同一个模块）；程序被停止时 `asyncio.run()` 收到 `SystemExit`，关闭事件循环时像 CPython 那样取消剩下的任务，协程在它停着的 `await` 处收到 `CancelledError`（正在 `await asyncio.sleep(...)` 的也马上收到），收尾完 `SystemExit` 再接着往外抛。`SystemExit` 只给一次，`finally` 里还可以再等待、再 `await` 做收尾；收尾超过 2 秒（或把 `SystemExit` 吞掉接着跑）才会在下一个等待点再收到一次。一直在做纯计算、从不经过等待点的线程停不下来，要等它碰到下一个等待点。

**后台运行**：自带的 14 个 Tk 命令（`tkaemon`、`tkcanvas`、`tkdemo`、`tkdesktop`、`tkedit`、`tkexplorer`、`tkimage`、`tklayout`、`tklog`、`tkprogress`、`tkscroll`、`tktaskmgr`、`tktext`、`tkttk`）默认在后台运行，直接输入 `tkdesktop` 或 `tkedit notes.txt` 就会马上返回提示符，窗口一直开着，直到程序自己结束（关窗口、`destroy()`）。显式加 `&` 也只启动一个后台任务。其他命令可在末尾加 `&` 进入后台；用 `python /bin/tkinter/tkdesktop.py` 直接执行脚本则仍在前台运行。每个程序只管自己的窗口和事件：前台命令结束时不会关掉后台程序的窗口；不指定父窗口创建的控件、`Toplevel()` 落在各自程序的根窗口上；一个程序窗口上的点击、定时器只在这个程序自己的事件循环里执行。后台程序开着窗口时，下一个 `Tk()` 在同一块屏上再开一个窗口。

- `jobs` 列出还在运行的后台命令；`kill 编号`（或 `kill %编号`）停止那个命令：关掉它的窗口，它的线程和协程在各自的下一个等待点结束（`mainloop()` 之后的代码不会再执行）。
- 后台命令不能读键盘（键盘输入留给 shell，读终端会报错，`event.listen` 的按键回调也收不到），也不能在终端上画全屏界面；它 `print` 的内容和出错信息在下一次出现提示符时一起显示（最多保留最近 256 段，更早的会提示丢了多少），结束时显示 `[编号] done ...`（出错时显示错误）。后台命令再启动后台命令时，`[编号]` 也记在它自己的输出里。
- 后台命令用启动时的工作目录（`os.getcwd()` 和相对路径也是），之后在 shell 里 `cd` 不影响它；它自己 `cd` 或 `os.chdir` 也只改它自己的。前台命令的 `os.chdir` 同样只在这条命令里有效。
- 还有后台命令在运行时，`exit`/`reboot` 第一次只提示，再输一次才停掉它们（给它们一秒执行 `finally`）并关机。
- shell 等待输入时不会取走 Tk 事件（`computer.pullSignal` 在本机有 Tk 窗口时跳过它们，留给 tkinter）。

`sh.run_command(name, args)` 和 `sh.spawn(name, args)` 都接受命令名或脚本路径。裸命令名按 `/bin`、`/bin/tkinter`、`/usr/bin`、`/home/bin`、当前工作目录的顺序搜索，每个目录先试原文件名，不存在且未写 `.py` / `.mpy` 时依次补这两个后缀。带 `/` 的路径直接按调用程序的 cwd 解析，例如 `sh.spawn('../apps/viewer.py', ['notes.txt'])`；绝对路径也可用。`sh.cwd` 与本程序的 `os.getcwd()` 保持一致，`os.chdir()` 或 `sh.run_command('cd', ['目录'])` 只改变本程序。`spawn` 的 `cwd=` 可指定子任务起始目录，相对值按调用者 cwd 解析，脚本路径再按子任务 cwd 查找。

`os.environ['PATH']` 是冒号分隔的搜索路径（默认 `/bin:/bin/tkinter:/usr/bin:/home/bin:.`）；`sh.path` 是同一变量的兼容入口，任一入口修改后，命令查找、`which` 和 Tab 都立即使用新值。删除或清空 PATH 会禁用裸文件名搜索，内置命令、`./` 和绝对路径仍可用。`sh.command_path(name)` 返回命中的绝对路径或 `None`，`sh.find_command(name)` 返回源码文本或 `.mpy` 的原始字节。shell 执行脚本时注入 `args`、`tty`、`sh`，以及绝对路径 `__file__` 和 `__name__='__main__'`。嵌套 `run_command` 属于调用者程序，`spawn` 创建独立后台程序。

环境变量接口冻结在 `os` 模块中：`os.getenv(key, default=None)`，以及 `os.environ` 的读写、删除、遍历、`keys/values/items/get/update/setdefault/pop/popitem/clear/copy` 和 `dict(os.environ)`。键和值均为字符串，键区分大小写，空键、含 `=` 的键和 NUL 字符不能写入。环境属于游戏内的 OC 电脑，不读取运行 Minecraft 的 Java/Windows 主机环境；未启动 MPYOS 的裸程序初始环境为空。这里未实现 `putenv/unsetenv/environb`，通过映射修改变量。环境随 VM 存档恢复。

shell 启动前台命令或 `spawn` 时复制调用者环境，程序内部的线程共享该副本；子程序修改不会影响父程序。嵌套 `sh.run_command` 共用调用者环境。后台任务保留启动时的环境，父程序随后修改变量不会影响它；程序退出时待其线程完成清理再释放环境。要设置交互式 shell 的初始 PATH，在 `/init.py` 中调用 `shell.run` 前设置它；Python REPL 中的修改只影响该 REPL 程序及其后续子程序。

```python
import os

os.environ['PATH'] = '/home/apps:/bin:/bin/tkinter:/usr/bin:/home/bin:.'
os.environ['APP_MODE'] = 'demo'
print(os.getenv('APP_MODE', 'normal'))
job = sh.spawn('viewer', ['notes.txt'])
```

以上示例放在由 shell 直接执行的脚本里，`viewer` 及其线程可读取 `APP_MODE`，独立运行的其他程序不受影响。

后台生命周期由冻结标准库 `_process` 统一管理，shell 和 `subprocess` 使用同一运行层。Tk 命令通过第一行 `#!/usr/bin/env pythonw` 声明默认后台，不再自己调用 `sh.spawn`。已有系统需更新模组 JAR，从新 LiveCD 更新 `/lib/shell.py`、`/lib/osfs.py`、`/bin/which.py`、`/bin/python.py`、`/bin/python3.py`、`/bin/py.py`、新增 `/bin/pythonw.py` 及 `/bin/tkinter/tk*.py`，然后重启 OC 电脑。

Tk 命令位于 `/bin/tkinter/`，已加入默认 PATH，裸命令名与 Tab 补全保持可用。旧硬盘需更新 `/lib/shell.py` 并迁移原 `/bin/tk*.py`；不要在 `/bin` 留旧副本，否则会先执行旧版本。自定义 PATH 需自行加入新目录。

### 标准程序启动接口

应用代码可以完全不依赖 `sh`：路径使用 `os.getcwd()`、`os.chdir()`、`os.path.abspath()`，环境使用 `os.environ` / `os.getenv()`，参数读取 `sys.argv`。`sys.argv` 是真正的列表，第 0 项是脚本的绝对路径，后面是用户参数；修改、重新赋值、`from sys import argv` 和 `getattr/setattr` 均按当前程序访问。子线程共用本程序的 argv；另一程序有独立列表。argv、cwd、环境和后台程序随 VM 一同存档。

```python
import os
import sys
import subprocess

print(sys.argv[1:])
child = subprocess.Popen(['pythonw', 'viewer.py', 'notes.txt'], cwd='/home')
print(child.pid)
# poll() returns None while running; wait() blocks until it exits.
result = subprocess.run([sys.executable, '/home/check.py'], check=True)
print(result.returncode)
```

- `Popen(args, cwd=None, env=None)` 立即返回，启动者退出后子程序继续运行；`run` / `call` / `check_call` 等待子程序退出。字符串 `args` 作为单个程序名，多个参数要传列表；不会按 shell 语法拆字符串。
- `cwd` 相对调用者 cwd，显式脚本路径相对新 cwd；裸命令按子程序 `env['PATH']` 搜索，逐目录尝试原名、`.py`、`.mpy`。已明确写 `.py` / `.mpy` 时不再补后缀；找到文件后编译或运行失败不会改试另一个候选。`env` 不指定时复制父环境，指定时整体替换。参数中的相对文件名保持原样，由程序解析。
- `python 文件` 前台运行；`pythonw 文件` 后台运行且不能读取终端。两者的文件参数按 cwd 精确解析，不搜索 PATH、不补扩展名。仅大小写敏感的 `.mpy` 后缀加载兼容的 v6 字节码（拒绝 native）；其他文件永远按源码编译，不探测文件头，也不在失败后改用另一种格式。字节码使用 `pythonw` 或 `&` 指定后台运行。Tk 源码命令默认后台，显式 `python /bin/tkinter/tkedit.py` 则忽略第一行后台声明。`pythonw ... &` 仍只创建一个程序。这里将 `pythonw` 定义为后台启动入口；CPython Windows 的同名程序本身只代表无控制台解释器，并不普遍承诺 shell 立即返回。
- `Popen` 支持 `pid`、`poll()`、`wait(timeout=None)`、`returncode`、`terminate()`、`kill()`、`send_signal(9或15)`、上下文管理器，以及无管道的 `communicate()`。正常退出码 0，`sys.exit(n)` 返回 n，未捕获异常返回 1；终止/kill 的返回码为 -15/-9。`pid` 是 VM 内程序编号，shell 的 `jobs/kill` 使用另一套作业编号，不是主机 PID。
- `run(..., check=True)` 和 `check_call` 在非零退出时抛 `CalledProcessError`；`wait(timeout=...)` 超时抛 `TimeoutExpired`，保留运行中的子程序；`run/call` 超时会请求停止它。退出清理仍按协作调度执行，可能在后续 tick 完成。文件缺失在创建时抛 `FileNotFoundError`，不会登记一个隐藏任务。
- 所有子程序共用该 OC 电脑的 VM 和已导入模块，只有程序身份、argv、cwd、环境及所属资源隔离。正常退出等非守护线程，再停止其余任务并清理窗口/回调；独立子程序不属于父程序的线程。`terminate/kill` 都是协作停止，不能强制中断没有等待/轮询点的纯计算循环。
- 当前没有 stdin/stdout/stderr 管道或重定向、`capture_output`、`shell=True`、原生执行文件、`-c/-m` 和平台专属创建选项；不支持的 subprocess 选项明确抛 `NotImplementedError`。普通子程序继承终端，`pythonw` 使用后台终端视图。`CompletedProcess.stdout/stderr` 为 `None`。旧 `args/argv/sh/tty` 注入仍兼容，但新应用可仅使用标准模块。

也支持完整的 `os.spawn*` 函数族，无需 `sh`：

```python
import os

code = os.spawnv(os.P_WAIT, '/home/check.py', ['check', 'notes.txt'])
pid = os.spawnlp(os.P_NOWAIT, 'python', 'python', '/home/app.py', 'notes.txt')
print(pid)
finished, status = os.waitpid(pid, 0)
print(os.waitstatus_to_exitcode(status))
```

| 接口 | 参数形式 | 搜索 PATH | 子程序环境 |
|---|---|---|---|
| `spawnv(mode, path, argv)` | list / tuple | 否 | 继承副本 |
| `spawnve(mode, path, argv, env)` | list / tuple | 否 | env 整体替换 |
| `spawnvp(mode, file, argv)` | list / tuple | 是 | 继承副本 |
| `spawnvpe(mode, file, argv, env)` | list / tuple | 是 | env 整体替换 |
| `spawnl(mode, path, arg0, ...)` | 逐个参数 | 否 | 继承副本 |
| `spawnle(mode, path, arg0, ..., env)` | 逐个参数，最后为 env | 否 | env 整体替换 |
| `spawnlp(mode, file, arg0, ...)` | 逐个参数 | 是 | 继承副本 |
| `spawnlpe(mode, file, arg0, ..., env)` | 逐个参数，最后为 env | 是 | env 整体替换 |

- `P_WAIT` 等待程序和退出清理完成，返回退出码；即使启动 `pythonw` 也等待。`P_NOWAIT` / `P_NOWAITO` 立即返回独立子程序的虚拟 PID，并使用 MPYOS 后台终端视图，不能读取键盘。两种模式都登记程序和独立 runner 线程，不会把子程序挂在父程序的线程生命周期里。
- `argv` 必须包含非空 `argv[0]`，它是调用者提供的程序名，不用于定位文件。直接运行脚本时保留该值，`__file__` 则为脚本绝对路径。通过 `python/pythonw` 启动时，解释器按通常规则将目标脚本路径放在脚本的 `sys.argv[0]`，且只创建一个程序。
- 不带 `p` 时按 cwd 精确解析文件名，不补扩展名、不搜索 PATH。带 `p` 时使用子环境的 PATH，沿用 MPYOS 逐目录尝试原名、`.py`、`.mpy` 的简写规则；含 `/` 的名字直接按 cwd 解析。仅 `os.spawn*p*` 遵循以下规则：空 PATH 项表示 cwd（包括 `PATH=''`），缺少 PATH 时使用 `os.defpath`（`/bin:/bin/tkinter:/usr/bin`）。shell / subprocess 的原有 PATH 规则不变。
- 启动时复制 cwd、argv 和环境；`e` 版本的 env 必须是字符串键值映射。参数类型、NUL、环境变量名及模式错误在创建前报错。找不到或读不到可执行脚本时，按 Unix spawn 语义创建一个退出码为 127 的子程序；普通未捕获异常或脚本编译错误为 1。正常退出码取低 8 位，终止为 -9 / -15。
- `os.getpid()` / `os.getppid()` 返回虚拟程序 PID / 父 PID；交互式 shell 为 0，同程序的线程共享 PID。它们也能查询 shell / subprocess 启动的程序。`os.kill(pid, 0)` 检查身份是否存在，`os.kill(pid, 9或15)` 协作停止该虚拟程序；PID 与 shell 的作业编号不同，不访问主机进程。
- `os.waitpid(pid, 0)` 等待并领取一次退出状态；`pid=-1` 等待任一直接子程序。`os.WNOHANG` 不等待，尚未结束时返回 `(0, 0)`；没有可等待的子程序或状态已领取时抛 `ChildProcessError`。返回的 status 是 Unix 编码状态，使用 `os.waitstatus_to_exitcode(status)` 转换；它与 `P_WAIT` 直接返回的退出码不同。
- `waitpid` 在此仅收集本程序用 `os.spawn*` 启动的子程序，`subprocess` 的等待仍使用 `Popen.wait()`。未领取的退出记录只保留父 PID 和状态，支持存档恢复；父程序结束时清除其记录，仍运行的子程序转为父 PID 0，之后自动回收。根 shell 仍存活时，调用者需领取退出状态，避免一直累积记录。
- 不支持进程组、`P_OVERLAY`、`P_DETACH` 或其他信号。`kill(9)` 与 `kill(15)` 都需要程序到达等待/轮询点；没有主机进程隔离，也不能强制打断纯计算循环。新增接口在模组 JAR 内，升级 JAR 后重启 OC 电脑生效（已有标准程序运行层的硬盘无需为本次新增接口复制脚本）。

参考：[Python subprocess](https://docs.python.org/3/library/subprocess.html)、[Python os.spawn](https://docs.python.org/3/library/os.html#os.spawnl)、[Windows pythonw](https://docs.python.org/3/using/windows.html)。

任何玩家都能打开 GUI 观看；只有占用该显示器的电脑允许交互的玩家（`computer.addUser` 名单，与 OC 屏幕和键盘相同）才能点击、输入和复制。由玩家引起的事件按 OC 的 `computer.checked_signal` 投递，每台电脑各自按自己的名单过滤。

## tkexplorer：文件浏览器

运行 `tkexplorer [目录或文件]`，默认后台打开，省略参数时浏览当前工作目录。传入文件则打开其父目录并选中它；浏览不会改变 shell 的 cwd。

文件名前显示 16×16 像素图标：文件夹为经典黄色文件袋，`.py` / `.pyw` 为蓝黄蛇形图标，`.lua` 为带 Lua 字样的蓝色图标，其他文件为折角文档。扩展名不区分大小写；每个窗口复用四张 PhotoImage，刷新与翻页不重复分配图像。

浏览器隐藏树形展开箭头及其 16 像素占位，图标从单元格左边 2 像素处开始绘制。行图标使用最近邻采样，GUI 放大后保持像素边缘；普通 Label/Button 照片仍使用平滑采样。

- `<` / `>` 后退、前进，`Up` 返回上级；地址栏支持绝对路径或相对当前浏览目录的路径。`Refresh` 重新读取目录并扫描挂载磁盘，根目录显示 `/mnt`。
- 非根目录的列表首项为 `..`，双击、Enter 或 Open 返回上一级并选中刚离开的目录。它不受隐藏文件、筛选、排序和分页影响，不计入文件数，也不可重命名或删除；根目录不显示。
- 目录排在文件前面，点击 Name 切换名称排序。显示文件类型、大小；Hidden 显示隐藏文件，Filter / Apply 按名称筛选。每页最多 100 项，Prev / Next 翻页，刷新和排序保留所选文件。
- 双击或 Open 进入目录；文件交给独立后台 `tkedit` 打开。不会执行 Python 文件；文本格式和大小限制沿用 tkedit。关闭浏览器不会关闭已经打开的编辑器。
- File + / Folder + 新建空文件或文件夹，Rename 重命名，Delete 确认后永久删除文件或空文件夹。已有同名目标不会被覆盖；非空目录不会递归删除。挂载点不可重命名或删除，只读目录禁用修改按钮。
- 快捷键：文件列表中 Enter 打开、Backspace 上级、F2 重命名、Delete 删除；F5 刷新、Ctrl+L 选中地址栏。磁盘满、路径失效或磁盘断开时显示错误；重新接回磁盘后可以 Refresh。

首版没有复制/剪切/粘贴、递归删除、拖放或文件类型关联设置。界面文字为英文。升级服务端与客户端 JAR 后将新 LiveCD 的 `/bin/tkinter/tkexplorer.py` 复制到已有硬盘，并重启 OC 电脑以载入 Tk 的更新。

## 终端中断

在 OC 终端运行前台程序时，Ctrl+C 在 `time.sleep`（包括 `sleep(0)`）、事件等待、显式 yield、线程 join、Tk / asyncio 轮询等协作点抛出 `KeyboardInterrupt`。普通 Ctrl+C 不会打断完全没有协作点的计算循环；Ctrl+Alt+C 另外在 VM 循环回跳、函数/方法调用和执行恢复处检查，可打断 `while True: pass`。

中断交给当前前台程序的主执行线程；嵌套的前台子程序优先，后台程序及其子程序不会抢终端中断。同程序的工作线程不会代收。异常可以用 `except KeyboardInterrupt` 捕获，`finally` 正常运行；一次按键只投递一次，长按重复事件不会反复打断清理。未捕获时前台程序返回 130，并协作停止所属线程。REPL 显示 `KeyboardInterrupt` 后继续接受输入。

Ctrl+Alt+C 仍然是 Python 异常，不是强杀：被用户代码捕获后可以继续运行，长时间 Java/组件调用要等返回，`machine.disable_irq()` 期间延迟到恢复 IRQ 后检查。待处理中断随 VM 存档保存，程序结束时清除。Tk 编辑控件内的 Ctrl+C 仍用于复制；这两个中断快捷键来自 OC 终端键盘。

升级模组后需要关闭并重新启动 OC 电脑，让新冻结模块生效；只读档会继续执行存档内的旧代码。已安装到硬盘的系统还需更新 `/bin/python.py`、`/bin/python3.py`、`/bin/py.py`，才能获得 REPL 中断后继续输入的处理。

## tktaskmgr：程序与 ops 监视器

在 shell 输入 `tktaskmgr`，默认后台打开。每秒刷新 PID、命令行、ops/s、Share %、线程数、状态和累计 ops；点击表头排序，再次点击反转。刷新保留选择，窄屏使用底部横向滚动条查看其余列，选中项下方显示父 PID、前台/后台和精确累计数。

选择程序后点击 **End process** 请求停止该程序及所属线程，并释放它的窗口/回调。状态变为 Stopping，清理完成后从列表移除；独立子程序继续运行。PID 0（shell/系统）和任务管理器自身的结束按钮禁用，自身使用窗口关闭按钮退出。停止仍是协作式：sleep、Tk 更新、事件等待等轮询点响应，没有轮询的纯计算循环会一直显示 Stopping。

- ops 是 VM 实际分派的字节码指令数；程序的主执行流、子线程、同程序 asyncio 工作及内嵌 Python 回调合计。异常指令和挂起后重试的调用也计数。Java 组件内部工作和等待时间不折算成 ops。
- `ops/s` 是两次采样间指令增量除以 `computer.uptime()` 的增量。`Share %` 是同一时段本程序 ops / 整台 VM ops，表示指令执行份额，**不是服务器真实 CPU 使用率或 TPS 耗时**。任务管理器自身的刷新消耗也计入。
- PID 0 的 Shell / system 行包含根执行流和未归属独立程序的线程。Active 表示程序尚存活，包含等待中的程序；Stopping 表示已请求停止；Finishing 表示正在收尾。
- 首次采样显示 `--`，之后显示速率。采样期间退出程序的指令仍计入 VM 总量，因此当前行的份额之和可能小于 100%。累计数随存档恢复；退出程序的 VM 计数表项被清理，历史总量只保留整数，不保留线程或应用对象。
- 更新 JAR 并重启 OC 电脑；已有 MPYOS 硬盘从新 LiveCD 复制 `/bin/tkinter/tktaskmgr.py`。本次新增统计接口在冻结模块里，已有标准程序运行层的硬盘无需更新其他系统脚本。旧格式存档可以读取，但使用新增统计功能应重启电脑以载入新的冻结模块。

脚本也可读取诊断快照（MPYOS 扩展，并非 CPython 标准 API）：

```python
import _process

data = _process.snapshot()
print(data['total_ops'])
for process in data['processes']:
    print(process['pid'], process['command'], process['ops'], process['threads'])
```

快照为独立字典，不会清零计数或暴露可修改的作业对象。`_process.ProcessMonitor().sample(computer.uptime())` 可增量采样；保留同一个 monitor 实例用于后续采样，各窗口互不重置计数。

## tkaemon：AE 合成 CPU 监视器

`tkaemon [ME组件地址或唯一前缀]` 默认后台启动，可在单块 Tk 显示器上使用。OC Adapter 须贴着 AE 的 ME Controller 或 ME Interface，并与 OC 电脑连接。顶部选择组件，`CPUs` 页选择 CPU。默认打开 `Tasks` 页：每种物品／流体使用原生 ItemIcon / FluidIcon 卡片，显示名称、数量和分段状态条，绿色为 D~（观测期间完成约数）、橙色为 A（正在制作）、灰色为 W（等待制作）。流体数量单位是 mB；卡片数量缩写为 k/M/G，点击卡片可在底部查看当前筛选的数量（All 为剩余量，Done~ 为完成约数）。

筛选器有 All / Crafting / Waiting / Done~ / Stored。一个物品可以同时有等待、制作和已完成数量，因此会出现在多个筛选中。Stored 单独展示 CPU 当前库存，不计为已完成。卡片数随可用高度调整，每页最多 8 张，使用 `<` / `>` 或鼠标滚轮翻页；刷新保留当前页及所选物品，复用控件。观测历史最多保留 512 种，超出时提示；总体进度仍统计全部清单。

**D~ 是估计值，不是 AE 提供的完成记录**：同一种物品的 Active + Pending 数量下降时，累计减少量；Pending 转到 Active 不增加完成量，任务合并增加待制作量时不抹掉已经观测到的完成量。物品按名称、metadata 和可用的 NBT/tag 区分，流体与同名物品分开。CPU 空闲既可能是完成也可能是取消，因此显示 `Idle: finished or cancelled`，并清空上一任务的卡片，不把未完成部分自动补成成功。切换 CPU、Rescan、Active/Pending 读取失败、断线或观测到不同最终产物时重新计数。OC 不提供任意 CPU 的任务 ID 和原子快照，漏采期间取消后重开同种产物的任务无法可靠区分；中途打开也不会补出历史完成量。

所选 CPU 每秒读取一次，其他 CPU 每次最多检查 4 个，轮转更新。CPU userdata 保持复用；新建、拆除或重组 CPU 后点 `Rescan` 更新 CPU 清单。每 5 秒发现组件变化，断线后清空旧数据并重试，重新连接后重新计数；没有 AE 网络时窗口保留，显示连接说明。只读监视，不请求或取消合成。

数据来自当前依赖 [OpenComputers 1.12.64-GTNH 的 NetworkControl](https://github.com/GTNewHorizons/OpenComputers/blob/1.12.64-GTNH/src/main/scala/li/cil/oc/integration/appeng/NetworkControl.scala)：`getCpus`、CPU 的 `isBusy/activeItems/pendingItems/storedItems/finalOutput`。进度照 AE 自己的算法（`CraftingCPUCluster.prepareStepCount`：剩余量为 ACTIVE 与 PENDING 清单数量之和，起点取任务开始时的这个和，合并进来的任务把新增量加到起点上，产物送回网络时扣减）：OC 的 `activeItems`/`pendingItems` 用的是同一个 `getModernListOfItem`，所以剩余量 = 两张清单的数量之和，起点 = 首次观测到的和，剩余量上升时起点加上增量，界面用 **~百分比 seen** 标记从开始观测起的进度。OC 没有暴露 AE 的 `getStartItemCount`，中途打开时只能从打开那一刻算起。`finalOutput` 只用来显示合成目标，它要求 CPU 多方块中有 Crafting Monitor；它的数量是“最终产物尚未交付的数量”，只在成品送回网络时才减少（单件产物整个任务里一直是 1），不能当进度用，这是改用清单计算的原因。切换 CPU、重新扫描、观测到空闲/错误时重新计数；Idle 无法区分完成或取消。清单读不到时使用不定量忙碌条，显示 API 返回的原因。

已有系统从新 LiveCD 复制 `/bin/tkinter/tkaemon.py`、`/lib/aemon.py`，并更新模组 JAR。`python /bin/tkinter/tkaemon.py` 可前台运行。

## 原生物品与流体图标（MPY4OC 扩展）

`tk.ItemIcon` 与 `tk.FluidIcon` 是本模组扩展，供任意 Tk 程序使用；客户端绘制游戏本身的物品图标、Forge 流体材质及染色，GUI 和方块表面共用绘制路径。

```python
import tkinter as tk

root = tk.Tk()
item = tk.ItemIcon(root, item={'name': 'minecraft:iron_ingot', 'damage': 0}, width=32, height=32)
item.pack(side=tk.LEFT)
fluid = tk.FluidIcon(root, fluid={'name': 'water'}, width=32, height=32)
fluid.pack(side=tk.LEFT)
root.mainloop()
```

描述表可直接使用 OC 返回的物品/流体表。ItemIcon 使用 `name`、`damage`（默认 0，可取 0..65535）和可选的 `nbt`（SNBT compound 字符串）；FluidIcon 只使用 `name`。数量、标签及 OC 的压缩 `tag` 字段不参与图标绘制，数量可另用 Label 显示。`configure(item=None)` / `configure(fluid=None)` 清空图标；`width/height` 是内容像素，默认 24，图标在内容区内保持正方形，普通布局、背景、边框、anchor、鼠标绑定均可用。无效资源或客户端渲染失败显示缺失图标，不读取或修改实际库存。

名称最多 256 UTF-8 字节；物品 NBT 最多 4096 UTF-8 字节、嵌套深度 16；整棵控件树的图标描述预算 64 KiB。超限或格式错误会报错，不替换已有图标。控件描述和显示记录随存档保存。

## 桌面与窗口

显示器就是桌面：桌面的颜色归显示器（组件方法 `setBackground(颜色)`，默认深灰），桌面上不能放控件。这台电脑的每个程序的 `Tk()` 都是桌面上的一个窗口，`Toplevel()` 再开一个；标题栏右侧有最小化 –、最大化 ⧠ 和关闭 × 三个按钮，几个程序的窗口在同一块桌面上重叠、切换：

```python
root = tk.Tk()
root.title('主窗口')
root.geometry('240x160+10+10')   # 宽x高+X+Y,X/Y 是含标题栏的窗口左上角
top = tk.Toplevel(root)
top.title('设置')
top.geometry('200x120+20+10')
top.minsize(120, 60)             # 玩家拖边框改大小时的下限(maxsize 是上限)
tk.Button(top, text='确定', command=top.destroy).pack()
top.protocol('WM_DELETE_WINDOW', lambda: print('点了 ×'))   # 不设时 × 直接 destroy
```

- **玩家操作**：
  - 拖标题栏移动窗口；点窗口任意处把它提到最上层。
  - 拖窗口的边或角改大小。可抓的范围是 1 像素的边框，外加边框外 3 像素；离角 12 像素以内同时改两个方向。没有鼠标指针变化，改为高亮提示：鼠标停在能拖的地方时，那一边（靠近角时是角上两边各一段）亮成蓝色，拖动过程中一直亮着；最大化的窗口和不能改大小的方向不亮。
  - – 最小化，⧠ 最大化（填满任务栏以上的区域，按钮变成 ⧉，再点还原）；双击标题栏也能最大化或还原。最大化的窗口不能拖动，也不能改大小。
  - × 向窗口所属的程序发 `WM_DELETE_WINDOW`；根窗口默认 `destroy()` 整个程序的窗口，Toplevel 默认只关它自己。
  - 标题栏、边框、按钮和任务栏上的点击都不会触发程序的绑定事件。
- **任务栏**：屏上有窗口（显示中或最小化）时，屏幕底部出现 18 像素高的任务栏，每个窗口（所有程序的）一个按钮（窗口很多时按钮变窄，最窄 8 像素；仍放不下的窗口没有任务栏按钮，也就没有最小化按钮），当前窗口的按钮是蓝色，最小化的按钮颜色变暗。点按钮：最小化的窗口还原并置顶；当前窗口最小化；其他窗口置顶。任务栏以上是桌面的工作区，`geometry('-X-Y')` 的 Y 从任务栏上沿算。
- **API**（根窗口和 Toplevel 相同，与 Tk 一致）：
  - 位置和大小：`geometry('宽x高')`、`'+X+Y'`、`'-X-Y'`（标题栏右端到屏幕右边缘、窗口底边到任务栏上沿的距离；窗口隐藏时设置也按显示后的任务栏算），读出来是 `'宽x高+X+Y'`。
  - 状态：`state()` 返回或设置 `'normal'`、`'iconic'`（最小化）、`'withdrawn'`（隐藏，不在任务栏）、`'zoomed'`（最大化）；`iconify()`、`withdraw()`、`deiconify()`（显示、置顶并把键盘焦点给它）；`attributes('-zoomed', 1)` 与 `state('zoomed')` 相同。只有玩家能还原的窗口（两个方向都可改大小、有最大化按钮）才会被最大化，否则 `state('zoomed')` 只是显示它。
  - 大小限制：`resizable(宽, 高)` 决定玩家能否按各方向改大小，两个方向都能改才有最大化按钮；`minsize(宽, 高)`、`maxsize(宽, 高)` 限制内容区大小，对 `geometry` 和自然大小同样生效，`maxsize()` 默认返回显示器大小。
  - 其他：`title()`、`protocol()`、`lift()`/`tkraise()`、`lower()`、`focus_set()`（置顶并恢复该窗口上次的焦点）、`winfo_toplevel()`。Tk 在其他平台上的 `attributes`（`-topmost`、`-alpha` 等）可以照写，没有效果。
  - `winfo_x()/winfo_y()` 与 geometry 的 X/Y 相同（含标题栏），`winfo_rootx()/winfo_rooty()` 是内容区左上角；`winfo_screenwidth()/winfo_screenheight()` 是显示器的分辨率。普通控件也有 `lift()`/`lower()`，调整兄弟控件的叠放顺序（参照控件必须属于同一个根窗口）。
- **大小**：只放了子控件的窗口按内容自动定大小；空窗口默认 200×200，与 Tk 一致。`geometry('宽x高')` 或玩家拖边框后大小固定，`geometry('')` 恢复按内容定大小。窗口里用 `pack(fill=..., expand=True)` 的控件随窗口一起变大变小。窗口不能 `pack/grid/place`，位置只由 `geometry` 决定；新开的窗口错开排在左上角。
- **`<Configure>` 事件**：任何控件都可以 `bind('<Configure>', 函数)`，控件大小变了就调用，`event.width`/`event.height` 是新大小，`event.x`/`event.y` 是它在父控件里的位置。控件第一次绑定时会先收到一次当前大小（之后重新绑定、解绑再绑定都不会，除非大小变了）；窗口隐藏后再显示也会再收到一次。与 Tk 不同：只在大小变化时触发（移动不触发），也只发给绑定的那个控件本身（Tk 还会把子控件的 Configure 发给所在窗口的绑定）。常见用法是 Canvas 填满窗口、按新大小重画。
- **焦点**：键盘焦点跟着点击的窗口走，每个窗口记住自己上次的焦点控件；Tab 只在当前窗口内循环；没有焦点时按键发给最上层的窗口；最小化当前窗口后，下一个窗口变成当前窗口并恢复它的焦点。绑定事件先给控件，再给它所在的窗口，与 Tk 的 bindtags 一致。别的程序的窗口在最上层时，`focus_set()` 只记下自己那个窗口的焦点、不抢键盘，也不把窗口提上来（与 Tk 不带 `-force` 的 `focus` 一样）；`focus_get()` 只返回本程序的控件。
- **程序之间**：一个程序只能操作自己的控件、窗口和图片；拿别的程序的控件编号去调用会报错。新开的窗口在最上层，所以新启动的程序马上拿到键盘。
- **限制**：窗口总在桌面上，拖动、改大小或 `geometry` 都会把窗口夹在屏内，标题栏不会被拖到任务栏下面，至少留 24 像素标题栏可以抓。标题栏至少要放下按钮再加 24 像素，很窄的窗口也有地方拖。销毁一个控件时，以它（或它的子控件）为 master 的 Toplevel 一起销毁。一块屏最多 32 个窗口（所有程序合计），所有窗口共用这块屏的控件和绘制预算（见“输入、世界表面与限制”）：程序的调用超出时报错；玩家操作造成的超出先丢最下层窗口的内容（任务栏总是保留）。拖动和改大小按游戏 tick 更新（每秒 20 次），重内容窗口拖动时更费带宽。
- 同一块屏上的窗口都属于同一台电脑；另一台电脑指定这块屏的地址 `Tk()` 会接管整块屏。

MPYOS 的 `/bin/tkinter/tkdesktop.py` 是一个示例：它自己的窗口上有开新窗口的按钮，另外开一个带计数器的窗口、一个不能改大小的窗口、一个 Canvas 随窗口大小重画的图表窗口。

## 多块拼接与分辨率

同一平面、朝向相同的 Tk 显示器可以用扳手拼成一块大屏，最大 8×8 块。OC、GregTech（矿辞 `craftingToolWrench`：扳手和充着电的 LV/MV/HV 电动扳手；工具箱选中扳手格时也算）以及 BuildCraft、AE2、EnderIO、CoFH、IC2 等向 OC 登记过的扳手都可以用。GT 的锤子、锯等其他工具（包括 GT++ 与 Detrav 的工具）不算扳手。

- **拼接**：手持扳手右键显示器正面靠近某条边的位置（离边 30% 以内），把相邻那块显示器（或它所在的组）并进来。必须拼成矩形：先把 A、B 拼成 AB，C、D 拼成 CD，再在 AB 下边缘点一下，得到 2×2。被点的那一组保留自己的程序、组件地址和画面；被并入的组的程序被断开、画面清空，组件对电脑隐藏。
- **拆分**：潜行 + 扳手右键组内任意一块，整组拆回 1×1。敲掉组内任意一块也会拆开其余部分。拆开后主显示器保留程序，分辨率回到单块的最大值。
- **视觉提示**：手持扳手看向显示器时，白框为当前组；瞄准边缘时绿框为拼接后的范围，红线表示这条边不能拼（旁边不是显示器、朝向不同、形状不成矩形或超过 8×8）；潜行时红框和网格表示将被拆开的组。
- 右键组内任意一块都打开同一个 GUI，世界表面的画面铺满整个组（保持画布宽高比，留 1/16 格边距）。
- 修改拼接、拆分和分辨率需要权限：显示器上没有程序时任何人都可以，有程序时需要是那台电脑允许交互的玩家。

每块显示器在每个方向提供 320 像素：单块最大 320×320，2×1 最大 640×320，8×8 最大 2560×2560；最小 64×64。显示器分辨率默认是这个最大值，拼接、拆分或敲掉一块后回到新组的最大值。程序的 `root.geometry()` 只改它自己窗口的大小；`winfo_screenwidth()`、`winfo_screenheight()` 是显示器的分辨率。要改分辨率：

- **空手潜行右键**显示器，弹出分辨率对话框（ModularUI2）：输入宽、高后回车或点别处生效，或点 `320×240` / “最大”按钮。
- OC 组件：`getResolution()`、`setResolution(宽, 高)`、`getMaxResolution()`。

超过上限的设置会报错。

## 控件与布局

| API | 支持范围 |
| --- | --- |
| `Frame` | 容器、背景、边距 |
| `LabelFrame` | 边框上带标题的容器：`text`、12 种 `labelanchor`，或用 `labelwidget` 拿它自己的一个子控件当标题（Tk 也允许用兄弟控件，这里不行） |
| `Label` | 单行/多行文字、`textvariable`、锚点、折行；适合静态文字 |
| `Message` | 自动折行的说明文字 |
| `Button` | 鼠标/键盘激活、`command`、`invoke()`、禁用态 |
| `Entry` | 单行 Unicode 编辑、选区、剪贴板、`textvariable`、readonly/disabled、`show`（掩码）、`justify`、输入校验（见下文“Entry 的显示与校验”） |
| `Spinbox` | Entry 加上下两个箭头：`from_`/`to`/`increment` 或 `values` 步进，`wrap`、`format`、`command`，详见下文 |
| `Checkbutton` / `Radiobutton` | 变量联动、命令回调、选中/取消 |
| `Scale` | 横/纵滑块、范围、步长、变量与命令回调 |
| `Listbox` | 批量插入/删除、选择模式、可见行渲染、双向滚动 |
| `Scrollbar` | 横/纵、`command` 与 Listbox、Text、Entry、Canvas 的 `xview/yview` 连接 |
| `Canvas` | 线、多边形、矩形、椭圆、文字、图片；坐标、tag、层级和鼠标绑定；`scrollregion` 滚动，详见下文。填充多边形的自交最多 128 处、整屏合计 512 处（客户端三角化代价随自交增长） |
| `Text` | 多行编辑、标签样式、标记、撤销/重做、搜索和只读日志，详见下文 |
| `ttk.Progressbar` | 定量/不定量、横/纵、`value`/`maximum`/`length`/`variable`、`step()`、`start()`/`stop()`，详见下文 |
| `tkinter.ttk` | Button、Checkbutton、Radiobutton、Label、Frame、Labelframe、Notebook、Entry、Spinbox、Combobox、Treeview、Scale、Scrollbar、Progressbar、Separator、Sizegrip 和 `ttk.Style`，详见下文“ttk” |

**路径名**：控件有 Tk 的路径名，和 CPython 的编号方式一样：`str(控件)` 是 `.!frame.!label2` 这种，`name=` 可以自己取名，`nametowidget()` 按路径名找回控件，`children` 是名字到子控件的字典。

**边框**：经典控件都接受 `relief`（`flat`、`raised`、`sunken`、`groove`、`ridge`、`solid`，也可以用常量 `tk.SUNKEN` 等）和 `borderwidth`（`bd`），默认值与 Tk 8.6 相同：Button 凸起，Entry、Listbox、Text、Scrollbar 凹陷，其余是平的（`Label(bd=2)` 不加 `relief` 时看不出边框）。边框的亮色和暗色由背景色算出，与 Tk 在 X11 上画的一样；按住的按钮画成凹陷。`padx`/`pady` 是边框到内容的距离，Frame 里就是子控件到边框的距离。

支持 `StringVar`、`IntVar`、`DoubleVar`、`BooleanVar`。变量与关联控件在 Python/Java 两侧同步；没有完整 Tcl 变量系统。

`pack` 支持 side/fill/expand/anchor/padding，`grid` 支持行列权重、跨度、sticky、minsize/pad，`place` 支持绝对和相对位置。支持相应的 forget/remove、传播开关和布局查询。同一个容器中不要混用 `pack` 与 `grid`，用子 Frame 分隔布局。`place` 可与它们共存。

根窗口和 Toplevel 一样是桌面上的窗口，`geometry('宽x高+X+Y')` 设它的大小和位置（见“桌面与窗口”）；显示器的分辨率见“多块拼接与分辨率”。Entry、Label、Listbox、Text 的宽高按其字符/行语义处理，Frame/Canvas 的尺寸用像素。字体固定为 OC unifont：ASCII 通常 8×16，汉字通常 16×16；不支持 Tk 字体选择或主题系统。图片见下文。

`bind` 仅支持实现中列出的事件，如 `<Button-1>`、`<ButtonRelease-1>`、`<Double-Button-1>`（`<Double-1>`）、`<Motion>`、`<B1-Motion>`、`<MouseWheel>`、`<Key>`、`<Return>`、`<Tab>`、方向键 `<Up>`/`<Down>`/`<Left>`/`<Right>`、翻页 `<Prior>`/`<Next>`、`<<ListboxSelect>>` 和 `<Configure>`（见上文“桌面与窗口”）；不是完整的 Tk 事件匹配器。同一事件用 `add='+'` 可以绑多个函数，按绑定顺序调用，某个返回 `'break'` 时后面的不再调用，某个出错时报告错误、其余照常调用；`bind` 返回各自的 funcid，`unbind(事件, funcid)` 只去掉那一个（不存在的 funcid 报 TclError）。按键有自己的绑定（如 `<Return>`、`<Up>`）时只触发它，没有时才触发 `<Key>`，两者不会同时触发。未知选项或不支持的操作可能报错，不能直接运行任意桌面 Tk 应用。

- **双击**：同一个控件上 500 ms、4 像素以内的第二次按下，在绑定了 `<Double-Button-1>` 的地方代替 `<Button-1>` 发出（与 Tk 选更具体的模式相同）；第三次按下重新算作单击，两次之间按了右键或其他键（Shift、Ctrl、Alt 除外）也不算双击。按钮的 `command` 每次单击都会执行，双击时也执行两次（与 Tk 相同）。Canvas 图元的 `tag_bind` 也支持。
- **自定义虚拟事件**：`bind('<<名字>>', 函数)` 可以绑定任意 `<<...>>`，`event_generate('<<名字>>', x=..., ...)` 立即依次调用控件和它所在窗口（Toplevel 或根）上的绑定，回调返回 `'break'` 时停止；`when='tail'`（以及 `'head'`、`'mark'`）改为在已排队的事件之后调用。`event_generate` 也能触发 `<Button-1>` 等已绑定的普通事件；按键事件会补上 `keysym`、`char`（如 `<Return>` 的 `'\r'`），`event_generate('<Key>', keysym='Up')` 和真实按键一样优先调用 `<Up>` 的绑定。`rootx`、`time`、`serial` 这类 Tk 才有意义的字段接受后忽略。
- **键盘焦点**：`takefocus=0` 的控件不参加 Tab 切换，点击 Button、Scale 等也不会抢焦点（Entry、Text、Listbox 点击仍会得到焦点，与 Tk 的类绑定相同）；这样的点击仍会把键盘交给被点的窗口，按键不会留在另一个窗口里。从点击得到焦点的 `takefocus=0` 控件按 Tab，会接着走到它后面的控件。`takefocus=1` 让 Label、Frame、Canvas 等平时不接收键盘的控件参加 Tab 并可以 `focus_set()`，例如可用方向键操作的 Canvas；Toplevel 本身不是 Tab 的一站。`''` 恢复默认。不支持 Tk 的回调形式。
- **变量跟踪**：`var.trace_add('write'|'read'|..., 回调)`、`trace_remove`、`trace_info`，以及旧的 `trace_variable`/`trace`（模式字母 `r`、`w`、`u`、`a`）、`trace_vdelete`、`trace_vinfo`，回调参数与 Tk 相同 `(变量名, '', 'write')`。和 Tk 一样：新加的跟踪先调用；两种写法的跟踪在同一个列表里，`trace_info` 也列出旧写法加的跟踪；`trace_remove` 的模式要和添加时完全相同才会删除；回调运行期间同一个变量的跟踪不会再次触发；回调出错时报告错误，其余跟踪和控件的 `command` 照常执行。
- 其他：`register(函数)` 返回一个名字（`root._commands` 中可查），`after_idle`、`focus()`（`focus_set` 的别名）、`focus_get()`（这块显示器上有键盘焦点的控件，没有时为 None）、`winfo_class()`、Scrollbar 的 `identify`/`delta`/`fraction`、Scale 的 `coords`。

## Entry 的显示与校验

```python
pw = tk.Entry(root, show='*')                        # 显示（和复制出去）的都是 *，get() 仍是原文
num = ttk.Entry(root, justify=tk.RIGHT, validate='key',
                validatecommand=(root.register(lambda p: p.isdigit() or p == ''), '%P'),
                invalidcommand=lambda: print('digits only'))
```

- **show**：每个字符画成它的第一个字符；选中后复制、剪切出去的也是掩码（与 Tk 相同），设了 `show` 的 Entry 按 Ctrl+左/右直接到头尾（没有“词”）。**justify**：`left`/`center`/`right`（可以缩写，如 `'c'`），文字放得下时按它摆，放不下时和左对齐一样滚动。
- **validate**：`none`（默认）、`key`、`focus`、`focusin`、`focusout`、`all`；`validatecommand`（`vcmd`）可以是函数（不带参数调用），或 `(register 得到的名字, '%P', ...)`、`'名字 %P'`；替换符 `%d`（1 插入、0 删除、-1 其他）、`%i`、`%P`、`%s`、`%S`、`%v`、`%V`、`%W`，`%%` 是 `%`。返回 True/False（或 `'yes'`、`'0'` 这类 Tcl 布尔值）；不通过时修改不生效并调用 `invalidcommand`（`invcmd`）。返回值和报错规则照 Tk 8.6：
  - 经典 Entry/Spinbox：函数出错或返回的不是布尔值时，这次修改不生效且 `validate` 变成 `none`；`textvariable` 设新值、Spinbox 步进时做 `forced` 校验，值照样改，不通过时 `validate` 变成 `none`（不调用 `invalidcommand`）；校验函数里改了这个 Entry 的内容，也会停止校验。
  - ttk Entry/Spinbox/Combobox：变量和 `set()` 设的值不校验；出错时 `validate` 变成 `none` 并抛出 TclError（如 `expected boolean value but got "None"`）；焦点校验和 `validate()` 的结果记在 `invalid` 状态里；校验函数里改了内容只是让这次修改不生效。
- 玩家在显示器上的修改也先校验：键入的字先交给程序，程序回答后才改（之后的按键排在后面依次处理），所以程序要在跑事件循环；没有校验时照旧直接修改。选中文字后键入，和 Tk 一样分成“删除选区”“插入”两次校验。

## Spinbox 与 Combobox

```python
s = tk.Spinbox(root, from_=0, to=10, increment=0.5, wrap=True, command=lambda: print(s.get()))
t = ttk.Spinbox(root, values=('低', '中', '高'))
c = ttk.Combobox(root, values=('red', 'green', 'blue'), state='readonly')
c.bind('<<ComboboxSelected>>', lambda e: print(c.get()))
```

- **Spinbox**：右边两个箭头，点击或按上下键步进（ttk 的还响应滚轮）；按住箭头只走一步，没有 Tk 的连发。步进规则分经典和 ttk 两套，结果与 Tk 8.6 实测一致：经典的有 `from_`/`to` 时一开始就显示 `from_`（格式化后），松开鼠标才步进、不抢键盘焦点，`format` 只接受 Tk 认的 `%<宽>.<精度>f` 写法，默认格式按范围和步长算（如 0～1 步长 0.1 显示 `0.3`），`invoke('buttonup')`、`identify`（`buttonup`/`buttondown`/`entry`）；ttk 的一开始为空，按下就步进并取得焦点、步进后全选，`format` 是任意 Tcl 格式（只支持一个 f/e/g/d/s 转换），步进经过 `<<Increment>>`/`<<Decrement>>`，程序绑定它们并返回 `'break'` 可以拦下，`set()`。两者都在每次步进后调用 `command`（到头没变也调用），`textvariable` 跟着变。
- **Combobox**（ttk）：点右边的箭头或按 Down 打开下拉列表（`readonly` 时点哪里都打开），列表在所有窗口和任务栏之上，放在框的下方（下面放不下就放上方），最多 `height` 行（默认 10），多了有滚动条和滚轮。列表打开时高亮跟着鼠标，点一项或按回车选中，上下键移动，Tab 选中并移到下一个控件；点列表外面（这一下不会传给别的控件）或按 Esc 关闭（这时 Esc 只关列表，不关界面）。选中后值放进框里、全选，并发 `<<ComboboxSelected>>`；鼠标滚轮在没打开的框上可以直接换到前一个/后一个值。`postcommand` 在打开前调用（可以在里面改 `values`）。`current()` 返回当前值在 `values` 里的序号（不在其中为 -1），`current(i)`、`set(值)`；`cget('values')` 返回元组，`values` 也可以给 `'x y {a b}'` 这样的 Tcl 列表字符串。

## Treeview

```python
tree = ttk.Treeview(root, columns=('size',), height=6)
tree.heading('#0', text='Name')
tree.heading('size', text='Size', command=lambda: print('sort'))
tree.column('size', width=60, anchor='e', stretch=False)
bin_ = tree.insert('', 'end', text='bin', open=True)
tree.insert(bin_, 'end', text='tkedit.py', values=('31 KB',), tags=('big',))
tree.tag_configure('big', foreground='red')
tree.bind('<<TreeviewSelect>>', lambda e: print(tree.selection()))
```

- 方法与返回值同 CPython 3.12 + Tk 8.6：`insert`（自动 id 为 `I001`、`I002`……十六进制）、`item`、`delete`、`detach`、`move`/`reattach`、`get_children`、`set_children`、`parent`、`index`、`next`、`prev`、`exists`、`focus`、`selection` 与 `selection_set/add/remove/toggle`、`column`、`heading`、`set`、`see`、`bbox`、`identify`（`region`/`row`/`column`/`element`）、`tag_configure`、`tag_has`、`tag_bind`、`xview`/`yview`；选项 `columns`、`displaycolumns`、`height`（行数）、`selectmode`（`extended`/`browse`/`none`）、`show`（`tree`、`headings`）、`xscrollcommand`/`yscrollcommand`、`padding`。报错文字也一样（`Item x not found`、`Cannot insert a as descendant of b`、`Invalid column index q` 等）。
- 外观照 default 主题：标题行 19 像素、每行 20 像素、每层缩进 20 像素，默认列宽 200；控件变宽变窄时列宽按 Tk 的算法伸缩（`stretch` 为假的列不动，不能小于 `minwidth`）。标签的 `foreground`、`background` 会画出来。
- 行的 `insert(..., image=photo)` / `item(iid, image=photo)` 支持同一窗口的 PhotoImage 对象或名称，在树列的文字前绘制，随选择、滚动、存档恢复。推荐 16×16；大图按固定行高和列边界裁剪，隐藏树列时不画。`image=''` 清除图标；删除图片后引用它的行继续显示文字。标题/标签的 `image`、状态图片列表与字体尚不绘制。
- MPY 扩展 `showindicator=False` 隐藏展开箭头及其占位，适用于平面文件列表；默认 True 保持普通树形布局，子级缩进仍然保留。此选项随存档保存。
- 玩家：点一行选中它（`extended` 下 Ctrl 加减、Shift 从焦点项到这一项按树的顺序全选，收起的子项也算）；点行首的 ▸/▾ 或双击展开收起；上下左右键按 Tk 的规则移动和展开收起，Return、空格展开收起，PageUp/PageDown 翻页；滚轮滚一行；点标题执行它的 `command`；拖标题之间的分隔线改列宽。选择变化发 `<<TreeviewSelect>>`（排队，程序改选择也发），展开收起发 `<<TreeviewOpen>>`/`<<TreeviewClose>>`。
- `tag_bind(标签, 事件, 函数)`：事件发生在带这个标签的行上时调用（先于控件自己的绑定）。

## 菜单：Menu、Menubutton、OptionMenu

```python
menubar = tk.Menu(root)
filemenu = tk.Menu(menubar, tearoff=0)
filemenu.add_command(label='Open', accelerator='Ctrl+O', command=open_file, underline=0)
bold = tk.BooleanVar()
filemenu.add_checkbutton(label='Bold', variable=bold)
filemenu.add_separator()
filemenu.add_command(label='Quit', command=root.destroy)
menubar.add_cascade(label='File', menu=filemenu, underline=0)
root.config(menu=menubar)                     # 窗口的菜单栏

popup = tk.Menu(root, tearoff=0)
popup.add_command(label='Copy')
popup.tk_popup(100, 80)                       # 程序弹出菜单（显示器坐标）

colour = tk.StringVar(value='red')
tk.OptionMenu(root, colour, 'red', 'green', 'blue').pack()
ttk.OptionMenu(root, colour, 'red', 'red', 'green', 'blue').pack()
```

- `Menu` 的方法和返回值同 CPython 3.12 + Tk 8.6：`add`/`insert` 与各类型的 `add_command`/`add_checkbutton`/`add_radiobutton`/`add_cascade`/`add_separator`（及 `insert_*`）、`delete`、`entrycget`、`entryconfigure`（`entryconfig`）、`index`、`invoke`、`type`、`post`、`unpost`、`tk_popup`、`activate`、`yposition`、`xposition`；菜单选项 `tearoff`、`postcommand`、颜色（`background`、`foreground`、`activebackground`、`activeforeground`、`disabledforeground`、`selectcolor`）等。和 Tk 一样默认 `tearoff=1`：第 0 项是 tearoff 虚线，命令项从 1 开始编号。索引可以是数字（超过末项取末项）、`'end'`/`'last'`、`'active'`、`'none'`、`'@y'`，或按标签匹配的模式（`'Op*'`）。报错文字同 Tk（`bad menu entry index "x"`、`unknown option "-x"`、`bad menu entry type ...`）。
- `invoke` 照 Tk：复选项在 `onvalue`/`offvalue`（默认 `'1'`/`'0'`）间切换变量，单选项把变量设为 `value`，然后调用 `command` 并返回它的返回值；禁用项、分隔项、级联项返回 `''`。没给变量时复选项用标签名、单选项用 `selectedButton` 作全局变量名（值默认是标签），`root.getvar(名字)`/`setvar` 只认这些名字。勾选标记（✓ / ●）随变量变化。
- 菜单栏：`root.config(menu=菜单)`（Toplevel 也可以）在标题栏下面加一条 20 像素的菜单栏；和 Tk 一样 `geometry`、`winfo_height` 的高不含菜单栏，geometry 的 y 是整个窗口的顶，加上或去掉菜单栏时窗口顶不动，内容区上下移。菜单栏只排一行，放不下的项不显示（Tk 会折成几行）；菜单栏里的命令项点一下就执行。
- 玩家：点菜单栏的项打开它的菜单，再点一下关上；菜单打开时鼠标移到菜单栏的其他项就换成那一项的菜单；鼠标停在级联项上在右边打开下一级（右边放不下就开在左边）；在一项上松开就执行它（整串菜单先关上，再调用程序的命令），按下拖到项上松开也行；在菜单以外按下关上全部菜单，这一下不传给程序。键盘：F10 打开当前窗口菜单栏的第一个菜单，上下键移动（跳过分隔项和禁用项），右键打开级联或换到菜单栏下一个菜单，左键关一级或换到上一个，Return/空格执行，Esc 关一级，按带下划线（`underline`）的字母执行那一项。菜单打开时它拿着鼠标和键盘，程序收不到这些事件；高亮变化时绑定了 `<<MenuSelect>>` 的菜单收到这个事件。
- `Menubutton`（经典）/`ttk.Menubutton`：按下在下面（`direction`：`above`/`below`/`left`/`right`/`flush`；下面放不下就到上面）弹出它的 `menu`，按住拖到项上松开执行，再点一下关上；经典的 `indicatoron=1` 在右边画一个小凸条，ttk 的总有向下的箭头；菜单打开时经典的 raised 画成 sunken，ttk 处于 `pressed`。`OptionMenu`（经典：command 项，不设变量初值）与 `ttk.OptionMenu`（单选项，设默认值，`set_menu` 重建）照 CPython 写。
- 和 Tk 的差别：程序 `post` 时先运行 `postcommand` 再显示（同 Tk）；玩家打开带 `postcommand` 的菜单时先显示，`postcommand` 稍后运行，它改的项随后更新。tearoff 虚线点了不会撕下成新窗口；`font`、`image`、`bitmap`、`compound`、`columnbreak`、`hidemargin` 只记录；菜单项标签只能一行。

## Panedwindow 与 LabeledScale

```python
panes = ttk.Panedwindow(root, orient='horizontal')        # ttk 默认 vertical
panes.add(ttk.Frame(panes, width=80, height=60))
panes.add(ttk.Frame(panes), weight=1)                     # 多出的宽度给它
panes.pack(fill='both', expand=True)
panes.sashpos(0, 120)

classic = tk.PanedWindow(root, showhandle=True, sashrelief='raised')   # 经典的默认 horizontal
classic.add(tk.Label(classic, text='left'), minsize=40)
classic.add(tk.Label(classic, text='right'), stretch='always')

ttk.LabeledScale(root, from_=0, to=10).pack(fill='x')   # 数值标签跟着滑块
```

- 方法与返回值同 CPython 3.12 + Tk 8.6：ttk 的 `add`、`insert`（位置是索引、`'end'` 或某个窗格，已有的窗格会移过去）、`forget`、`panes`、`pane`（只有 `weight`）、`sashpos`、`identify`（分隔条序号或 `''`）；经典的 `add`（`after`、`before`、`width`、`height`、`minsize`、`padx`、`pady`、`sticky`、`stretch`、`hide`）、`forget`/`remove`、`panes`、`panecget`、`paneconfigure`、`sash_coord`、`sash_place`、`sash_mark`、`identify`（`(序号, 'sash' 或 'handle')` 或 `''`）、`proxy_*`。窗格可以是 panedwindow 的子控件，也可以是它的兄弟控件（Tk 允许）。报错文字同 Tk（`Slave index 5 out of bounds`、`sash index 3 out of range`、`... already added`、`-weight must be nonnegative`、`bad stretch ...` 等）。
- 尺寸照 Tk 的算法：ttk 每个窗格先按请求的大小排，多出或不够的部分按 `weight` 分（余数从前往后各给 1），最后一个窗格占到底；拖动或 `sashpos` 移分隔条时推开相邻的分隔条，之后各窗格按新大小请求。ttk 分隔条宽度取主题的 `Sash` 样式（default 5 像素，clam 6 像素）。经典的 `stretch` 窗格按各自当前大小的比例分多出（或少掉）的空间，不小于 `minsize`；`sash_place` 移分隔条时会设窗格的 `width`/`height`（同 Tk）。
- 玩家：拖分隔条（经典的也可拖把手）改窗格大小；鼠标停在分隔条上时它亮成蓝色（default 主题的分隔条本来是看不见的空隙）。经典的 `opaqueresize=0` 也直接移动，不画代理线。
- `ttk.LabeledScale(master, variable=None, from_=0, to=10, compound='top')` 照 CPython：标签显示当前值并跟着滑块；`value` 读写变量，超出范围时改回上一个有效值。
- 另外两处随之改成 Tk 的做法：`update_idletasks()` 会运行 `after_idle` 排的回调（主题切换后的重画也在其中），`after()` 的定时器仍要等 `update()`/`mainloop()`；ttk Scale 不会把变量改成夹在范围内的值（只是显示时夹住），`label['text'] = 5` 之后 `cget('text')` 返回 5 而不是 `'5'`。

## 对话框：messagebox、simpledialog、filedialog

```python
from tkinter import messagebox, simpledialog, filedialog
if messagebox.askyesno('Quit', 'Save first?', parent=root):
    name = simpledialog.askstring('Save', 'File name?', initialvalue='notes.txt', parent=root)
path = filedialog.askopenfilename(title='Open', filetypes=[('Text', '*.txt'), ('All', '*')])
```

- 函数、类和返回值同 CPython 3.12：`messagebox` 的 `showinfo`/`showwarning`/`showerror`（返回 `'ok'`）、`askquestion`（`'yes'`/`'no'`）、`askokcancel`、`askyesno`、`askretrycancel`（真/假）、`askyesnocancel`（取消为 `None`）和 `Message`（`type`、`icon`、`default`、`detail`，报错文字同 Tk）；`simpledialog` 的 `askstring`/`askinteger`/`askfloat`（`initialvalue`、`minvalue`、`maxvalue`、`show`，取消为 `None`，值不对时弹 Tk 的警告再让你改）、`Dialog`（`body`/`buttonbox`/`validate`/`apply`）、`SimpleDialog`；`filedialog` 的 `askopenfilename`、`askopenfilenames`、`asksaveasfilename`、`askopenfile`、`askopenfiles`、`asksaveasfile`、`askdirectory`（取消时 `askopenfilename`/`askdirectory` 返回 `()`、`asksaveasfilename` 返回 `''`，同 Tk 8.6）、`Open`/`SaveAs`/`Directory`，以及旧的 `FileDialog`/`LoadFileDialog`/`SaveFileDialog`；`commondialog.Dialog`。
- 外观照 Tk 8.6 在 X11 上的样子：消息框是图标、消息、详细说明和一行按钮，默认按钮拿着键盘（Return 按下它），关闭框等于取消项；文件对话框上面是“Directory:”（点开是上级目录的菜单）和 Up 按钮，中间是文件夹（带 `/`）和文件的列表，下面是“File name:”、“Files of type:”和 Open/Save、Cancel。双击文件夹进入，双击文件选中；名字里写文件夹会进去，写 `*.py` 这样的模式会过滤；打开不存在的文件、保存时覆盖已有文件会照 Tk 弹出提示；`defaultextension` 会补在没有扩展名的名字后面。文件来自 `os` 模块的文件系统（MPYOS 装好的）；没有文件系统时文件对话框报 `RuntimeError`。
- 对话框是模态的（照 Tk）：它是父窗口的 transient 窗口（没有任务栏按钮，总在父窗口上面，跟着父窗口最小化和还原），居中在父窗口上；它拿着本程序的 grab，期间本程序其他窗口里的点击和按键都没有作用（点一下会把对话框提到前面），窗口的边框（拖动、关闭）仍然可用；别的程序不受影响。对话框关掉后 grab 和键盘回到原处（同 Tk 的 RestoreFocusGrab），所以对话框里再弹的消息框关掉后，原对话框仍是模态的。
- 标准对话框按显示器可用尺寸约束窗口；文件对话框的目录和文件类型菜单随空间伸缩，单块显示器上也能完整看到 Open/Save、Cancel 和 Up。
- 新增的基础方法：`wait_window`、`wait_variable`、`wait_visibility`（窗口立刻显示，不用等）、`grab_set`/`grab_set_global`/`grab_release`/`grab_current`/`grab_status`、`transient(master)`（真正生效，见上）、`winfo_viewable`、`iconname`、`bell`（显示器没有铃声，什么也不做）、`winfo_vrootwidth` 等；`update_idletasks()` 运行 `after_idle` 的回调；经典 Button 的 `default`、Label 等的 `justify`、Message 的 `aspect`、Listbox/Entry 的 `exportselection` 只记录；`Toplevel(class_=...)` 设 `winfo_class`；补齐了 CPython `tkinter.constants` 里的常量（`YES`、`NO`、`SEL_FIRST` 等）。
- 和 Tk 的差别：Esc 关的是显示器界面，不会取消对话框（用 Cancel 或关闭框）；`bind('<Escape>', ...)` 可以写，只有 `event_generate('<Escape>')` 会触发它。消息框的图标是带颜色的字母方块（i、?、!、x），不是 Tk 的位图；字体只有一种；文件列表不显示以 `.` 开头的名字（同 Tk 的默认），没有“显示隐藏文件”的开关；`SaveFileDialog` 问覆盖时用的是 `messagebox.askyesno`。

## Canvas 滚动

```python
c = tk.Canvas(root, width=280, height=180, scrollregion=(0, 0, 1200, 600))
ys = tk.Scrollbar(root, orient=tk.VERTICAL, command=c.yview)
xs = tk.Scrollbar(root, orient=tk.HORIZONTAL, command=c.xview)
c.configure(xscrollcommand=xs.set, yscrollcommand=ys.set)

def clicked(event):                      # event.x/y 是窗口坐标
    x, y = c.canvasx(event.x), c.canvasy(event.y)   # 换算成画布坐标
c.bind('<Button-1>', clicked)
```

- 与 Tk 相同：`scrollregion=(x1, y1, x2, y2)` 是滚动条覆盖的范围（`''` 为无）；`xview()/yview()` 返回可见部分的比例，`xview_moveto(比例)`、`xview_scroll(n, 'units'|'pages')`；一个 unit 默认是窗口的 1/10（`xscrollincrement`/`yscrollincrement` 可改成固定像素，视图会对齐到它的整数倍），一页是 9/10；`confine`（默认开）让视图不离开 scrollregion。边框算在视图里：原点 0 时画布坐标 0 在边框下面，`moveto(0)` 后 scrollregion 的边缘正好在边框内侧。
- 图元坐标、`coords()`、`bbox()` 始终是画布坐标；事件的 `x/y` 是窗口坐标，用 `canvasx/canvasy` 换算。
- 有 scrollregion 的 Canvas 可以用滚轮上下滚动（每格一个 unit）；程序在 Canvas、它的图元或所在窗口（Toplevel 或根）上绑定了 `<MouseWheel>` 时不再自动滚动，以免滚两次。Canvas 尺寸变化后视图按 confine 重新约束（与 Tk 相同）。
- 常量 `tk.MOVETO`、`tk.SCROLL`、`tk.UNITS`、`tk.PAGES` 可用。
- 视图外的图元不发给客户端，大画布滚动时只传看得见的部分。画布坐标范围仍是 ±32768。

## 进度条

```python
from tkinter import ttk
value = tk.DoubleVar(value=30)
bar = ttk.Progressbar(root, length=280, maximum=100, variable=value)   # 定量
spin = ttk.Progressbar(root, length=280, mode='indeterminate')        # 不定量
spin.start(50)        # 立即 step() 一次，之后每 50 ms 一次；已在运行时再 start() 不起作用
spin.stop()           # 停止，value 回到 0（关联了 variable 的保留变量的值）
bar.step(10)          # 加 10；定量模式按 maximum 取模（到 100 回到 0，与 Tk 相同）
```

- `mode='determinate'` 按 `value/maximum` 填充（竖向从下往上）；`'indeterminate'` 是一个来回移动的色块，`value` 每增加一个 `maximum` 走一趟（不取模，一直累加）。
- 选项：`orient`、`length`、`mode`、`maximum`、`value`、`variable`；颜色用 `background`（槽）和 `foreground`（进度）。ttk 的其他内容见下一节。

## ttk

```python
import tkinter as tk
from tkinter import ttk            # import tkinter.ttk、from tkinter.ttk import * 也可以

style = ttk.Style()
style.configure('Accent.TButton', foreground='white', background='#4a6984')
style.map('Accent.TButton', background=[('active', '#5a7d9c')])   # 鼠标移上去时
style.theme_use('clam')
b = ttk.Button(root, text='确定', style='Accent.TButton')
b.state(['disabled'])              # 返回 ('!disabled',)，用来恢复：b.state(('!disabled',))
ttk.Separator(root).pack(fill=tk.X)
ttk.Frame(root, padding='3 3 12 12')
```

- **控件**：Button、Checkbutton、Radiobutton、Label、Frame、Entry、Scale、Scrollbar、Progressbar 是在经典控件上加了 ttk 的部分（Spinbox、Combobox 是 ttk.Entry 的子类，见上文“Spinbox 与 Combobox”；Treeview 见上文“Treeview”），`isinstance(ttk.Button(), tkinter.Button)` 成立，`bg=`、`fg=` 这类经典选项照样能用。新控件：Separator（2 像素的刻线，`orient` 横/竖，用 `fill` 拉长）、Sizegrip（15×15 的把手，放在 Toplevel 里，拖它等于拖窗口右下角改大小；在桌面上、最大化或不能改大小的窗口里不起作用。只有左键拖动归窗口管理器，右键、滚轮和鼠标移动照常发给程序）。`from tkinter.ttk import *` 按 `__all__` 导入，不会覆盖 tkinter 的 `Widget`。
- **ttk 公共部分**：`style=`、`class_=`（创建时给，`winfo_class()` 返回它，默认是 `TButton` 这类类名）、`cursor=`、`takefocus`（未设置时 Button、Checkbutton、Radiobutton、Entry、Scale 的 `cget` 返回 `'ttk::takefocus'`，其余返回 `''`，与 Tk 相同）、`identify(x, y)`（返回该点的元素名，如 `label`、`thumb`、`uparrow`、`pbar`、`textarea`）。
- **状态**：`state()` 返回当前状态元组，`state(['disabled', '!focus'])` 设置并返回撤销用的状态，`instate(状态, 回调, *参数)`，与 Tk 8.6 相同（元组按 Tk 的位顺序排列；状态名为 `active`、`disabled`、`focus`、`pressed`、`selected`、`background`、`alternate`、`invalid`、`readonly`、`hover`、`reserved1`～`reserved3`、`user1`～`user3`）。`disabled`、`readonly`（Entry）真正禁用/只读控件，`invoke()` 也按 disabled 状态决定是否执行；`focus`、`pressed`、`background`（所在窗口不是当前窗口）反映控件的实际情况；`selected` 由 Checkbutton/Radiobutton 的值决定，`state(['selected'])` 只改状态，不改变量，也不影响同组的其他单选按钮（与 Tk 相同，变量下次变化时重新以值为准）；`hover`（鼠标在控件上）和按钮的 `active` 由服务端按鼠标位置给出；`alternate`、`invalid`、`user1`～`user3` 等由程序设置，只影响按样式取的外观。和 Tk 一样，`state()` 不改变 `state` 选项；`configure(state=...)` 设置 disabled、readonly、active 三者之一并清掉另外两个（`normal` 或其他值全部清掉）。Frame、Separator、Progressbar、Scrollbar 和 Tk 一样没有 `state` 选项，它们的 disabled 状态只记录（禁用的 Frame 不会禁用里面的控件）。没有设 `variable` 的 ttk Checkbutton/Radiobutton 开始处于 `alternate`（三态）状态，设了变量后退出。
- **padding**：`padding=5`、`'3 3 12 12'` 或元组都接受，按左、上、右、下四边分别生效（少给的边按 Tk 的规则补齐：右同左、下同上、上同左），`cget('padding')` 返回原样的元组。经典控件没有这个选项（报 `unknown option "-padding"`），用 `padx`/`pady`。ttk.Label 和 Tk 一样自带 1 像素边框，`padding` 加在边框里面，设置 `relief` 时这圈边框就画出来。
- **Scale**：按 ttk 的默认值，范围 0～1.0、连续取值、横向、长度 100、不显示数值；`value=` 选项（同时给了 `variable=` 时以变量为准）、`set()` 立即调用 `command`（禁用时什么也不做；`value=` 不调用）、`get(x, y)`（该点对应的值）、改 `from`/`to` 时发 `<<RangeChanged>>`。
- **Labelframe**（也叫 LabelFrame）：标题的位置、大小和边框按 Tk 的 ttk::labelframe（default 主题）来，和经典 LabelFrame 不同：标题离外边 8 像素，没有文字时也留出一个字符的高度，`padding` 加在边框里面。
- **Notebook**：标签页在上方，选中页的内容在下面 1 像素凸起的边框里（default 主题的样子，选中的标签和页面连在一起）。`add`、`insert`、`forget`、`hide`、`select`、`tab`、`tabs`、`index`、`identify` 的返回值和报错文字与 CPython 3.12 + Tk 8.6 相同，标签可以用序号、页面控件或它的路径名、`'current'`、`'@x,y'`、`'end'`（只有 `index`、`insert` 接受）来指定；哪一页被选中的规则也照 Tk：第一个加进来的标签自动选中（被禁用的不选，隐藏的会显示出来），选中的页被隐藏、禁用、移走或销毁时选它后面第一个可用的，没有就选前面最近的。选中的页变了就发 `<<NotebookTabChanged>>`，和 Tk 一样排在事件队列里，在下一次 `update`/事件循环时送到。玩家点标签换页；点了新的一页时键盘给这一页里第一个能用 Tab 到达的控件，没有就给 Notebook，Notebook 有键盘时左右方向键换页（跳过禁用的，到头绕回）。页面要是 Notebook 自己的子控件（Tk 也允许 Notebook 父控件下的其他控件，这里不行）；页面被 pack/grid/place 到别处或被销毁时，它的标签随之去掉。标签的 `image`、`compound`、`underline` 只记录不绘制，`enable_traversal()` 可以调用但 Ctrl+Tab 还不支持。
- **relief**：ttk.Label、ttk.Frame、ttk.Labelframe 的 `relief` 会画出来，`cget('relief')` 在没设置时和 Tk 一样返回 `''`。
- **只记录、暂不绘制的选项**：Button 的 `compound`、`underline`、`default`；Label 的 `compound`、`underline`、`justify`、`font`；Entry 的 `font`、`exportselection`。这些会保存并由 `cget` 返回。
- **ttk.Style**：`configure`、`map`、`lookup`（先按状态查整条样式链 `A.B.TButton` → `B.TButton` → `TButton` → `.` 的 map，再查 configure）、`layout`、`element_create`（只支持 `'from'`）、`element_names`、`element_options`、`theme_create`、`theme_settings`、`theme_names`、`theme_use`，返回值与 CPython 3.12 + Tk 8.6 一致。内置主题 default、clam、alt、classic 带着 Tk 自己的全部设置、layout 和元素（从 Tk 8.6.14 导出），所以 `lookup('TButton', 'padding')` 是 `'3 3'`、`layout('TButton')` 是 Tk 的布局，查不到的 layout 报 `Layout X not found`。和 Tk 一样，样式设置属于设置它们时所用的主题，切到别的主题（包括子主题）就看不到；layout 和元素会沿父主题查找，`layout(名字, '')` 设为空布局。主题按根窗口分开：`root.destroy()` 后新建的 `Tk()` 从四个内置主题重新开始。`theme_use` 在已排队的事件之后向所有控件发送一次 `<<ThemeChanged>>`（连续切换只发一轮）。
- **样式决定外观**：ttk 控件按自己的样式（`style=`，没设时是类名，Scale、Scrollbar、Progressbar 带 `Horizontal.`/`Vertical.` 前缀）在当前主题里取颜色、relief、边框宽度、padding、anchor 和按钮最小宽度（TButton 的 `width -9`）来画，随状态变化：鼠标移到按钮上变 `active`（default 主题变亮），按下时 relief 变 sunken，禁用时文字变灰，选中的复选框填 `indicatorcolor`，Notebook 的标签、Treeview 的选中行和标题、Progressbar 的进度条和槽、Scale 和 Scrollbar 的槽、Entry/Combobox/Spinbox 的 `fieldbackground` 和箭头颜色都取自样式。`style.configure`/`map`/`theme_use` 之后在已排队的事件处理完时整体重画。程序在控件上直接设的 `background`、`foreground`、`relief`、`padding` 等优先于样式。颜色可以写 `'#rgb'`、`'#rrggbb'` 或 Tk 8.6 的颜色名（不分大小写，如 `'SteelBlue'`、`'grey50'`；带空格的写法如 `'alice blue'` 不认）。限制：字体只有一种，`font` 只记录；各主题元素自己的形状（clam 的平面边框与渐变、alt 的复选框形状等）没有另画，只换颜色和 relief；Entry 类控件的 padding 不跟样式变（保持经典尺寸），选中文字的颜色固定。
- **控件齐了**：CPython 3.12 `tkinter.ttk` 的全部控件都可用（Menubutton、OptionMenu 见上文“菜单”，Panedwindow、LabeledScale 见上文）。

MPYOS 的 `/bin/tkinter/tkttk.py` 是一个示例，用 Notebook 分成四页：`Widgets` 页有 ttk 按钮（单击计数，双击显示在顶部）、复选/单选、带变量跟踪的 Scale 和 Progressbar、分隔线和一个带 Sizegrip 的窗口；`Borders` 页有六种 relief、ttk 和经典两种 Labelframe，以及可以用方向键移动圆点的 Canvas（`takefocus=1`）；`Inputs` 页有只读 Combobox、Spinbox、只能输入数字的 Entry、显示成 `*` 的 Entry 和切换主题的下拉框；`Tree` 页是带滚动条的文件夹 Treeview，点 Size 标题按大小排序。窗口标题跟着 `<<NotebookTabChanged>>` 变；单块显示器放得下。

## 图片

```python
cat = tk.PhotoImage(file='/home/cat.jpg', fit=(200, 150))   # 读电脑硬盘上的文件，缩到 200x150 框内
tk.Label(root, image=cat).pack()                             # 有图片时显示图片、不显示文字
canvas.create_image(10, 10, image=cat, anchor='nw')
dot = tk.PhotoImage(width=16, height=16)                     # 空白（透明）图
dot.put('#ff0000', to=(4, 4, 12, 12))                        # 填一块红色
```

- **来源**：`file=` 读 OC 文件系统里的 PNG、JPEG、GIF（只取第一帧）、BMP（只支持不压缩的）。装了 MPYOS 时按 MPYOS 的路径（挂载点、`cd` 后的当前目录）；裸机时读启动盘，没有启动地址就找含这个文件的文件系统。文件是边读边分块发给显示器的，程序不用把整个文件装进内存。`data=` 给文件内容（bytes，或 Tk 的 base64 文本，可以有换行）。`width=`/`height=` 建空白图。文件最大 1 MiB，源图最多 1600 万像素、每边 ≤ 16384；不支持 WebP 和 CMYK JPEG。
- **`fit=(宽, 高)`**（MPY4OC 扩展，原版 Tk 没有）：按比例缩小到框内，不放大；框超过 1024 时按 1024 算。大照片解码时就隔行采样，不会完整解码。不带 fit 时图片每边最多 1024 像素，超过会报错并提示用 fit。结果还必须放得进这块屏剩余的像素额度（见下），放不下时在解码前就报错。
- **方法**：`width()`、`height()`、`put(颜色, to=(x, y))`（颜色可以是 `'#rrggbb'`、`'{#.. #..} {#.. #..}'` 多行，或行的列表；`to=(x1, y1, x2, y2)` 平铺填充；超出图片会报错）、`get(x, y)`、`blank()`、`copy()`、`zoom(x, y)`、`subsample(x, y)`、`transparency_get/set`。PNG 的透明通道会保留。
- **名字**：`str(img)` / `img.name` 是图片名，可以代替图片对象传给 `image=`；自动名字在所有屏之间不重复。`PhotoImage(name='logo', ...)` 遇到同名的旧图会替换它（旧图被删除）。
- **释放**：本 VM 没有 `__del__`，不用的图片调 `img.delete()`；正在显示它的 Label/Button/Canvas 图片项会变空白（Label/Button 改为显示文字）。程序释放显示器时这块屏的图片全部删除。每块屏最多 64 张、合计 524,288 像素（约 724×724），单张每边 ≤ 1024。
- **显示与存档**：图片随画面显示在 GUI 和方块表面上；像素按块单独发给客户端，大图片在带宽额度内分几次到达，到达前那一处先空着；改过的图片在新版本到达前继续显示旧版本。存档时像素不写进区块（照片会撑爆区块上限），而是写到世界目录 `mpy4oc/tk-images/` 下，只写有变化的图片；方块被破坏时一并删除（稍后删除，移动方块不会丢图），复制出来的屏会改用自己的目录。

## Text：标签、标记与撤销

```python
text = tk.Text(root, width=36, height=8, wrap='word', undo=True, maxundo=100)
bar = tk.Scrollbar(root, orient=tk.VERTICAL, command=text.yview)
text.configure(yscrollcommand=bar.set)
bar.pack(side=tk.RIGHT, fill=tk.Y)
text.pack(fill=tk.BOTH, expand=True)
text.insert('end', 'Click here\nEdit this text')
text.tag_add('link', '1.0', '1.end')
text.tag_configure('link', foreground='blue', underline=True)
text.tag_bind('link', '<Button-1>', lambda event: print('clicked'))
text.mark_set('bookmark', '2.0')
text.mark_gravity('bookmark', 'left')
text.edit_reset()
text.edit_modified(False)
body = text.get('1.0', 'end-1c')
```

Text 始终保留末尾换行；空控件 `get('1.0', 'end')` 返回 `\n`。获取正文通常用 `end-1c`。字符位置按 Unicode 码点计，非 BMP 字符不会拆开，与旧 Tcl/Tk 的 UTF-16 索引可能不同。

| 功能 | 支持的接口和行为 |
| --- | --- |
| 内容与索引 | `get/insert/delete/replace/index/compare/see`；`行.列`、`行.end`、`end`、标记名、`tag.first/last`、`@x,y`；加减 chars/indices/lines，linestart/lineend/wordstart/wordend；`display` 的行操作按实际折行计算 |
| 标签 | `tag_add/remove/delete/names/ranges/nextrange/prevrange/raise/lower/configure/cget`；区间随编辑移动，按优先级合成样式；前景色、背景色、下划线、删除线实际绘制。`sel` 也是标签，可以调优先级；默认选区背景 #c3c3c3 |
| 标签事件 | `tag_bind/tag_unbind`；标签回调按优先级从低到高先于控件回调运行，返回 `'break'` 停止分发；支持鼠标、键盘及进入/离开标签的事件 |
| 标记 | `mark_set/unset/names/gravity/next/previous`；left/right gravity 决定在标记处插入时它跟哪边，`current` 随鼠标移动 |
| 撤销 | `undo=True` 启用，`edit_undo/redo/separator/reset/modified`；`autoseparators` 自动分组，`maxundo` 限组数（0 表示不按组数限制，仍受内存上限约束）。Ctrl+Z 撤销，Ctrl+Y 或 Ctrl+Shift+Z 重做 |
| 查询 | `search`（正反向、大小写、regexp、stopindex、IntVar count），`count`（字符/逻辑行/显示行/像素），`dump`（text/mark/tagon/tagoff，支持回调），`bbox/dlineinfo` |
| 通知与状态 | `<<Modified>>` 在修改标志变化时触发，撤销到干净位置会复原标志；`<<Selection>>` 通知玩家的选区变化。内容、标签、标记、撤销/重做历史、视口和回调可随存档恢复 |

连续输入默认合为一组；程序希望某个状态能单独撤销回去时，调用 `edit_separator()`。打开另一份文档后可用 `edit_reset()` 清掉旧历史，再 `edit_modified(False)` 标为未修改；保存文件不必清历史。标签和标记操作本身不进入文本撤销历史。

`wrap` 可为 char/word/none。`state=DISABLED` 时可选择、复制和滚动，但用户和 Python 的内容修改都不生效。追加只读日志时，暂时设为 NORMAL，插入后 `see('end')`，再设为 DISABLED。

兼容范围与限制：

- 使用游戏的固定字体度量。字体、spacing1/2/3、tabs/tabstyle、justify、margins、offset、elide 等选项可记录和查询，但不改变显示；Tab 固定按 8 个 ASCII 字符宽的制表位展开，elide 不隐藏文字。像素坐标按本实现的字体计算，不等于操作系统 Tk 的字体坐标。
- 不支持嵌入图片、嵌入窗口或 peer；不提供 Tcl 解释器。`regexp=True` 使用 Java 正则，常见表达式可用，Tcl 专有语法不保证一致。搜索还受固定工作量上限保护，回溯过多的表达式会报 `Text search work limit exceeded`，以免阻塞服务器。
- 每个 Text 最多 256 个标签（含 sel）、8,192 个区间、1,024 个自定义标记；每条显示行最多合成 16 个样式片段，超出的样式变化会合并或省略。复杂高亮应避免在单行制造过多交替样式。
- 撤销历史最多累计 1,048,576 个码点、16,384 个编辑记录；超出时丢弃最旧组，单组本身超过上限则清空历史。撤销也检查当前内容和同屏 Text 的共享预算，失败时保留正文和历史。
- 绘制预算包含底层正文、样式叠加层、下划线/删除线和光标，并按展开后的 Tab 估计字节数；内容只发送可见部分。内容与屏幕总预算见下文。

## tkedit 编辑器

在 MPYOS shell 里运行 `tkedit [文件]`（文件可以是相对路径，不存在时保存后才创建），编辑器在这台电脑用的 Tk 显示器上开一个最大化的窗口（⧠ 还原成显示器四分之三大小），对话框开在编辑器窗口中间。tkedit 总是在后台运行（同 `tkedit [文件] &`）：提示符马上回来，编辑器一直开着，再开一个会在同一块屏上再开一个窗口（见上文“指定显示器”中的后台运行）；以前的 `-w` 开关仍接受，不起作用。

- **工具栏**：`New`、`Open`、`Save`、`Save as`、`Find`、`Wrap`（按词 → 按字 → 不折行轮换，不折行时出现横向滚动条）。界面文字都是英文。
- **快捷键**：Ctrl+S 保存、Ctrl+Shift+S 另存为、Ctrl+O 打开、Ctrl+N 新建、Ctrl+Q 退出、Ctrl+F 查找、F3 / Shift+F3 下一个 / 上一个、Ctrl+H 替换、Ctrl+G 跳到行；Ctrl+A 全选，Ctrl+C / X / V 复制、剪切、粘贴（系统剪贴板）。Esc 关闭的是显示器界面，编辑器本身仍在运行。
- **查找栏**：查找框里按回车找下一个（从光标往后），`Prev` 往回找（光标所在的那个也算），到末尾/开头时绕回整篇并在状态栏提示；`Replace` 替换当前选中的匹配并找下一个，`All` 一次替换全部；`Aa` 勾上时区分大小写。
- **打开/另存为**：列出当前目录（文件夹在前），双击文件夹进入、`../` 回上一级（`/` 下列出 `mnt/`，其他磁盘挂在那里，每次打开对话框都会重新查一次），双击文件或在下面的框里输入文件名（相对当前目录，或 `/` 开头的绝对路径）后按 `OK`；另存为到已有的其他文件要再按一次 `OK` 才覆盖。打不开或存不了时原因显示在对话框里，对话框不关。
- **未保存的修改**：新建、打开或退出（Ctrl+Q 或编辑器窗口标题栏的 ×）时会问 `Save`、`Don't save` 还是 `Cancel`。状态栏左边是文件名（修改过带 `*`），右边是行:列，点它可以跳到某一行。
- **文件**：按 UTF-8 纯文本读写。不是 UTF-8、含制表符和换行以外的控制字符、超过 Text 的上限（262143 字、512 KB 或 20000 行）的文件不能打开，正在编辑的内容、光标和选区都保持不变。保存已有的文件时先写到同目录的 `文件名.tkedit-tmp` 再改名覆盖，磁盘满之类的失败不会把原文件弄成空的。换行：大多数行是 CRLF 的文件保存时写回 CRLF，否则用 LF；文件里混用了两种换行（或有单独的 CR）时，打开时在状态栏提示保存后会统一。支持 Ctrl+Z 撤销、Ctrl+Y / Ctrl+Shift+Z 重做；打开另一份文档会清空旧历史。暂不提供语法高亮。

## 输入、世界表面与限制

- GUI 支持鼠标点击/拖选/滚轮、方向键、Home/End、Ctrl+A/C/X/V 等；中键粘贴系统剪贴板。没有 IME，中文可通过剪贴板输入；AltGr（Windows）与 Option（macOS）组合出的字符可直接键入。Text 中 Tab 插入制表符，Ctrl+Tab / Shift+Tab 切换焦点。
- 每次粘贴最多 4 KiB UTF-8，截断处不会拆 Unicode 码点；控制字符（Text 中的换行和制表符除外）会被滤掉，超出控件预算时只插入放得下的部分。复制 Text 选区最多 512 KiB。
- 每屏最多 4096 个控件（所有程序合计），再建就报错；深度 32；Canvas 每个最多 512 个图元，整树最多 1024 个。
- 每屏最多画 8192 条绘制记录、64 KB 文字。程序的一次调用若让屏上有东西画不下，这次调用报错（`tk.TclError`：“the display is full”）：要新建的控件或窗口不会建出来，其他改动保留（删掉些东西就能腾出地方）。玩家把窗口拉大、露出更多行而超出时，先丢最下层窗口的内容。
- 每个 Text 最多 512 KiB UTF-8、262,144 个码点、20,000 个逻辑行（含末尾换行）；同屏 Text 合计最多 2 MiB。Python 的超限修改会整体拒绝（抛异常）；玩家粘贴只插入放得下的部分。
- 世界表面是实时预览，鼠标交互在右键打开的 GUI 中进行。`component.invoke(address, 'setSurfaceVisible', False)` 只隐藏世界表面；GUI 和程序仍可用。
- 配置 `general.Tk_Surface_Enabled` 可关闭表面显示，`general.Tk_Surface_Distance` 控制最远距离，默认 32 格（8～64）。两端设置分别生效。
- 表面纹理按距离使用 512/256/128 像素（显示器组按长边块数相乘，最多 1024），最多每 2/4/10 tick 更新；静态画面不重绘。每客户端最多 32 个表面订阅，保留最近 16 张纹理，每帧最多更新两张。FBO 不可用时跳过世界预览，GUI 仍可用。
- 表面同步每客户端有 1 MiB 突发额度、每 tick 补充 16 KiB；超额变化合并后再发。GUI 流量（打开、增量、重同步、复制回包）另有一份同样大小的独立额度，客户端读得慢时也会暂停发送；超额时增量合并后再发，打开 GUI 会提示“显示器正忙”，复制/剪切的回包不发送且剪切不会删除内容。
- 每个连接每 10 tick 最多打开一次 GUI；客户端请求的全量重同步每 20 tick 最多一次。每位观看者每 tick 最多 32 个输入包、8 KiB；改动文本的输入（可打印字符、退格、删除、回车、Tab、粘贴、剪切）按令牌桶限速，平均每 tick 4 次、最多连续 8 次，方向键等导航不计。每个连接在服务端最多排队 64 个 Tk 请求（关闭请求另有 8 个预留），超出的丢弃，不影响其他玩家。

## 存档与升级

Python 回调、变量和定时器随 VM 快照保存；Java 控件树、布局、输入状态和回调 ID 随显示器 NBT 保存。要恢复交互，两份状态必须来自同一个一致的世界存档。仅复制显示器 NBT 不会复制运行它的 Python 程序。另一台电脑接管屏幕会清除旧树。

本次冻结不强制冷启动：VM 快照嵌入已加载模块的字节码，恢复后继续使用其中的 tkinter；旧开发版磁盘模块的文件名不同也不影响恢复。已验证旧路径模块、按钮回调和变量联合恢复，以及保存时尚未导入 tkinter、恢复后首次从冻结库导入的情况。这只覆盖当前 `_tk` 宿主接口兼容的版本，不能保证未来任意版本都兼容。

真实 OC 机箱也已验证：只含 `init.py` 的硬盘通过 BIOS 启动 Tk；整个专用服务器保存重启后保留计数与输入内容，按钮、事件绑定和变量联动继续工作，没有重新执行 init。验证记录见 [实施清单步骤 11](tk-plan.md#11-冻结--收尾)。

冷启动后的普通 `import tkinter` 优先加载内置库；遗留 `/lib/tkinter.py` 不会覆盖内置版本，无需为本次升级删除它。若要让已经运行的应用使用新 Python 库，需关闭并重新启动该 OC 电脑；仅重开玩家 GUI 或保存重载不会替换快照里的旧库。

树 NBT 当前为版本 6（另存窗口和图片属于哪个程序），读取版本 1～6。几个程序共用一块屏（第 23 步）以前的存档，读档后显示器上的旧窗口不再保留（桌面清空），当时正在运行的 Tk 程序的调用报 “Tk window is no longer attached to this display”，重新运行即可。网络通道为 `mpy4oc_tk15`，服务器与客户端需要一致的 Tk 协议；不匹配时不能使用 Tk GUI。另外，Tk 显示器是新注册的方块：没有它的旧客户端（v0.0.1p6-beta 及更早）**完全无法进入**装有本版本的服务器（FML 同步方块注册表时以 “Fatally missing blocks and items” 拒绝），与是否使用 Tk 无关。服务器和所有玩家需要一起更新。不要用旧构建继续写入新控件存档。

## 开发与宿主边界

唯一的 Python 源码是 [`stdlib/tkinter.py`](../stdlib/tkinter.py)，冻结数据由 Java 生成器写入 `StdLibData.java`。MPYOS 只分发示例，不再携带另一份 tkinter。

```powershell
.\gradlew.bat genStdlib --offline --console=plain
.\gradlew.bat build --offline --console=plain
```

离线构建需要已有 Gradle 依赖缓存。正常构建不自动重新生成冻结数据；修改 Python 源码后先生成再构建，`FrozenStdlibTest` 会检查逐字节一致性和生成可重复性。构建与测试不需要 WSL 或外部 mpy-cross；两个原生 Tk 对照数据生成脚本仅用于刷新布局/Text 测试夹具，运行这些可选脚本时才需要本机 CPython/Tk。

`MpyArchitecture` 注入私有模块 `_tk`：`screens()` 枚举可达显示器，`call(address, operation, token, ...)` 经 OC 组件调用发送操作，`poll()` 从信号队列取 Tk 事件。组件方法 `tk` 是非 direct 回调，异步 MPY CPU 的调用也会回到服务端主线程。宿主注册名 `_tk.call/_tk.screens/_tk.poll` 与快照中的函数引用绑定；修改名称或参数时必须同时处理旧快照。

OC 的 `Machine.onMessage` 会为 `computer.signal` 自动插入发送节点地址。因此显示器发送的消息数据以 `事件名, token, ...` 开头，Python 收到的信号才是 `事件名, address, token, ...`；组件不要重复发送 address。模拟机器的测试也必须保留这一层转换。

本实现不调用 AWT/Swing/Tcl 进行服务端渲染。服务端输出显示列表及增量变化，客户端 GL 绘制 GUI/表面，因而专用服务器无需桌面显示环境。Tk 参考资料的许可见 [`docs/licenses`](licenses/) 及 jar 中 `META-INF/LICENSE-Tk.txt`；字体由 OpenComputers 提供。
