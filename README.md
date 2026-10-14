# MPY4OC — 在 OpenComputers 里写 Python

*中文 · [English](README.en.md)*

> Run real MicroPython on your OpenComputers computers. Minecraft 1.7.10 / GTNH.

一套给 OpenComputers 的 **MicroPython 处理器**:把 MPY CPU 插进机箱、刷好 BIOS,这台 OC 电脑就说 Python 了。附带 **mpyos**(shell、行编辑、文件系统、编辑器)和一张 **SFTP 服务器卡**——用 MobaXterm 之类的客户端直接 SFTP 进电脑磁盘改 `init.py`,还能在 SSH 终端里看到游戏内屏幕、远程打字。

```python
gpu = component.gpu
w, h = gpu.getResolution()
gpu.set(1, 1, "Hello from Python!")

import threading, asyncio
threading.Thread(target=lambda: print("threads work")).start()
asyncio.run(main())           # asyncio 也有
```

## 特性

- **真的 MicroPython 字节码**。内置纯 Java 编译器,输出与官方 `mpy-cross` **逐字节一致**(官方编译测试套件 749/749),不需要任何外部程序。
- **全状态快照**。存档时整个 VM 冻结进存档:变量、调用栈、生成器、协程、线程、组件代理全部保留,读档后**从存档那一刻原地继续**,不重启程序。所有计时(`time.sleep`、协程睡眠)不数你离线的时间。
- **同步 / 异步两族处理器,各三档**(CPU 与带内置显卡的 APU):
  - 同步:跑在服务器主线程,组件调用零延迟,每 tick 保证执行——默认选它;
  - 异步:跑在 OC 计算线程,对 TPS 友好,非 direct 组件调用自动回主线程执行,语义与 OC 的 Lua 架构一致。
- **线程 + asyncio 双并发模型**。`threading` 是有栈的(普通函数任意深度直接 `time.sleep`,整条栈保留);`asyncio` 按 CPython 3.12 的语义实现(任务、Future、`gather`/`wait_for`/`timeout`、锁和队列、`to_thread`),每个线程跑自己的事件循环,线程里也能 `asyncio.run()`;旧名 `jasyncio` 还能 import,就是同一个模块。
- **按 tick 的 ops 预算**。脚本每 tick 按 CPU 档次限速(500/2000/16000 条指令),空闲时主动让出能攒预算(至 10 倍)。
- **26 个冻结标准库模块**:`json` `re`(超集)`struct` `hashlib` `collections` `functools` `pickle` `traceback` `machine` `micropython` …,多数行为与官方逐字节一致。
- **联网就用 `requests` / `urllib` / `socket`**。插上 OC 的因特网卡,`requests.get(url).json()` 就是你想的那个意思;`urlopen` 返回类文件对象,`socket` 走裸 TCP。这些库**永远都在**(没插卡也 import 得进来),只在真正发请求时抛 `OSError`——等待全程按 tick 让出,不占服务器。

- **Tk 图形界面**。内置 `tkinter` 子集：按钮、输入框、列表、Canvas 和纯文本 Text 等。每个程序的 `Tk()` 是显示器桌面上的一个窗口（可拖动、改大小、最小化/最大化），一台电脑的多个程序共用一块屏。Tk 显示器支持世界表面预览，右键打开共享鼠标界面；无需安装 MPYOS。见 [Tk 使用说明](docs/tkinter.md)。

- **前台中断**。OC 终端 Ctrl+C 在 sleep/yield 等协作点抛 `KeyboardInterrupt`；Ctrl+Alt+C 还检查循环回跳、调用和恢复，可中断纯 Python 死循环。可捕获，`finally` 正常清理；后台作业不受影响。详见 [终端中断](docs/tkinter.md#终端中断)。

## 合成配方

全部为**无序合成**,一根原版**线**(String)是"MPY 风味"的记号:

| 产物 | 配方 |
|---|---|
| MPY CPU(一/二/三级) | OC 原版**同级** CPU + 线 |
| MPY APU(一/二/三级) | OC 原版 APU(一/二级)或**创造 APU**(三级)+ 线 |
| MPYOS BIOS | 任意 EEPROM + 线(原内容被改写,就是刷写) |
| MPYOS LiveCD 软盘 | 任意软盘 + 线(原内容被改写,同样是刷写) |
| Tk 显示器 | OC 一级屏幕 + 线 |
| SFTP 服务器卡 | 卡基板 + 线 |
| 同步 ↔ 异步(CPU/APU,同级) | 单独放入工作台即可互相转换,双向 1:1 |

另外两条既有途径:任意 EEPROM + MPYOS 软盘也能合成 BIOS;扳手 + 任意战利品软盘可循环切换到 MPYOS 盘。

## 快速上手

1. 合成 **MPY CPU** 和 **MPYOS BIOS**,像装 Lua 电脑一样组一台机器(机箱、内存、显卡、屏幕、键盘、硬盘),CPU 和 EEPROM 换成 MPY 的;
2. 合成 **MPYOS LiveCD 软盘**插入软驱,开机——从 LiveCD 启动进 shell;
3. 运行 `install` 把系统装进硬盘,之后可脱离软盘启动;
4. 编辑 `/init.py` 写你的程序(游戏内 `edit`;接了 Tk 显示器时也可以用鼠标操作的 `tkedit`。所有自带 Tk 命令都默认在后台运行、不占住 shell,其他命令末尾加 `&` 也能在后台运行;或插一张 SFTP 卡远程改)。

shell 默认按 `/bin → /bin/tkinter → /usr/bin → /home/bin → 当前工作目录` 搜索命令，内置命令优先。每个目录先找原文件名，文件不存在且未写 `.py` / `.mpy` 后缀时依次尝试补 `.py`、`.mpy`；同目录同名源码优先于字节码。找到文件后执行失败不会改试其他候选。带 `/` 的路径不走搜索列表：`./app.py`、`../app`、`scripts/app` 按 cwd 解析，绝对路径直接使用。可用 `which app` 查看命中的文件。

shell 支持 **Tab 命令名和路径补全**：例如 `tkdes` + Tab 补成 `tkdesktop `；命令名来自内置命令和上述搜索目录，`.py` / `.mpy` 命令省略扩展名并合并同名候选。命令位置输入 `./` 等路径也能逐层补全。参数位置补全文件和目录，`cd` 只补目录，`python`、`cp` 等命令的参数都可使用。支持当前目录下的相对路径、`./`、`../`、绝对路径和 `/mnt/` 下的挂载盘；目录补 `/` 后可继续补下一层，文件补完加空格。带空格或引号的名字自动引用，隐藏项在输入 `.` 后才列出。

有多个匹配时补齐公共前缀、按名称列出候选，并保留后面的参数和光标；没有匹配时不改输入。用 `jobs` 查看后台程序，`kill 编号` 停止它。

用 `python app.py 参数` 前台运行，`pythonw app.py 参数` 独立后台运行、立即返回提示符。自带 Tk 命令用第一行 `#!/usr/bin/env pythonw` 声明默认后台，应用直接调用 `root.mainloop()`，无需 `sh.spawn()`；显式 `python /bin/tkinter/tkedit.py` 仍在前台。脚本使用 `sys.argv`（第 0 项为脚本绝对路径）、`os.getcwd/chdir`、`os.path.abspath` 和 `os.environ`，每个程序独立保存 argv、cwd、环境，同程序的线程共享它们。

`python app.mpy 参数` / `pythonw app.mpy 参数` 直接加载兼容的 MicroPython `.mpy` 字节码（v6，非原生机器码）。执行方式只看最终文件名的大小写敏感后缀：仅 `.mpy` 按字节码加载；`.py`、`.pyw`、其他后缀及无后缀文件永远按 Python 源码解释，不探测内容，也不在源码失败后尝试字节码。显式解释器参数必须是实际文件名，不补后缀。

脚本启动其他程序使用 `subprocess.Popen(['pythonw', 'app.py', '参数'])`，返回对象提供 `pid/poll/wait/returncode/terminate/kill`；`subprocess.run([...], check=True)` 等待结束并检查退出码，还支持 `call/check_call`、`cwd=`、`env=`、`timeout=`。程序在同一 OC VM 内协作调度，共享已导入模块；不运行主机可执行文件。当前不支持管道、重定向、`shell=True` 或 Python 的 `-c/-m`。终止请求在等待/轮询点生效，纯计算且不轮询的循环无法强制结束。详见 [程序启动接口](docs/tkinter.md#标准程序启动接口)。

也支持 `os.spawnl/spawnle/spawnlp/spawnlpe` 和 `os.spawnv/spawnve/spawnvp/spawnvpe`。`P_WAIT` 等待并返回退出码，`P_NOWAIT` / `P_NOWAITO` 返回虚拟 PID；用 `os.waitpid` 领取退出状态、`os.waitstatus_to_exitcode` 转成退出码，`os.kill(pid, 9或15)` 协作停止，`os.getpid/getppid` 查询身份。不带 `p` 时按 cwd 精确打开路径，带 `p` 时按子环境 PATH 搜索并支持 `.py` / `.mpy` 简写；`e` 版本整体替换环境。直接执行脚本时保留调用者提供的 `argv[0]`。本次新增接口在模组 JAR 内，更新后重启 OC 电脑即可使用；完整语义和限制见[程序启动接口](docs/tkinter.md#标准程序启动接口)。

旧 `sh.run_command(name, args)` / `sh.spawn(name, args)` 保留兼容，使用相同的路径查找规则；嵌套 `run_command` 属于调用者程序，`spawn` 创建独立后台程序。`subprocess` 始终创建独立子程序，启动时捕获 cwd 和环境，之后父程序的修改不影响它；`cwd=` 可指定子程序起始目录。

搜索路径由 **`os.environ['PATH']`** 控制，默认 `/bin:/bin/tkinter:/usr/bin:/home/bin:.`；`sh.path` 保留为同一变量的兼容入口。`os.getenv(name, default=None)` 读取环境变量，`os.environ` 支持赋值、删除、`get/update/setdefault/pop/clear/copy`、遍历和 `dict(os.environ)`。键和值必须是字符串，键区分大小写。每个独立程序继承环境副本，子线程共享本程序的环境；程序里的修改不影响交互式 shell 或其他程序，嵌套 `run_command` 则共用调用者的环境。删除或清空 PATH 后只运行内置命令及显式路径；`./app.py`、绝对路径和参数路径仍可补全。要更改交互式 shell 的默认搜索路径，可在 `/init.py` 调用 `shell.run(...)` 前设置 `os.environ['PATH']`。

已有 MPYOS 硬盘需更新模组 JAR，并从新 LiveCD 更新 `/lib/shell.py`、`/lib/osfs.py`、`/lib/term.py`、`/bin/which.py`、`/bin/python.py`、`/bin/python3.py`、`/bin/py.py`、新增的 `/bin/pythonw.py` 和 `/bin/tkinter/tk*.py`，然后重启 OC 电脑；只换模组 jar 不会覆盖硬盘上的系统文件。Tk 命令已集中到 `/bin/tkinter/`；旧硬盘应迁移原 `/bin/tk*.py`，避免旧副本优先于新目录被执行。自定义 PATH 需自行加入 `/bin/tkinter`。

SFTP 卡:`component.sftp.start("user", "password")` 开服务,`getPort()` 看端口,然后用任意 SFTP/SSH 客户端连服务器 IP。电脑关机自动断线注销;区块卸载重载会自动恢复服务。

### 文件浏览器

运行 `tkexplorer [目录或文件]`，后台浏览文件、挂载磁盘和隐藏文件，支持路径跳转、名称筛选、分页、新建和重命名。文件夹、Python、Lua 和普通文件显示各自图标。双击文件用独立 tkedit 窗口打开；删除需要确认且文件夹必须为空。已有硬盘需复制新 LiveCD 的 `/bin/tkinter/tkexplorer.py` 并更新服务端与客户端 JAR、重启 OC 电脑。详见[文件浏览器说明](docs/tkinter.md#tkexplorer文件浏览器)。

### 任务管理器

运行 `tktaskmgr`，后台显示各程序的 PID、命令、ops/s、指令执行份额、线程数、状态和累计 ops，支持表头排序及结束选中的程序。程序所属线程合并统计，计数随存档恢复；结束仍在等待/轮询点协作执行。ops 表示 VM 字节码执行量，不是服务器 CPU 耗时。已有硬盘更新 JAR 后，从新 LiveCD 复制 `/bin/tkinter/tktaskmgr.py` 并重启 OC 电脑。详见[任务管理器说明](docs/tkinter.md#tktaskmgr程序与-ops-监视器)。

### AE 合成监视器

把 OC Adapter 接在 AE 的 ME Controller 或 ME Interface 旁，并接入 OC 电脑网络和 Tk 显示器，运行 `tkaemon`（可用 `tkaemon 地址前缀` 指定 ME 组件）。命令默认后台运行。`CPUs` 页选择合成 CPU，`Tasks` 页用原生物品／流体图标卡片和灰／橙／绿状态条显示等待、制作中、观测到的完成约数；支持筛选、分页和点击预览，Stored 单独显示 CPU 库存。每秒刷新所选 CPU，其余 CPU 分批更新；添加或拆除 CPU 后点击 `Rescan`。

进度按 AE 合成状态界面的算法计算：剩余工作量 = Active（进行中）+ Pending（待制作）清单的数量之和，以首次观测到的值为起点，后续合并进来的任务把新增工作量加到起点上；中途打开时从打开那一刻算起，切换 CPU、Rescan 或观测到空闲/出错会重新计数。流体按 mB 计入，与 AE 相同。卡片的 D~ 是观测期间 Active + Pending 减少量的估计，库存不算已完成，空闲不等于成功；观测历史最多保留 512 种。显示最终产物需要 CPU 多方块里有 **Crafting Monitor（合成监视器）**；没有监视器时进度照常显示，只是不显示产物图标。读不到清单时显示不定量忙碌条和原因。监视器只读，不发起或取消合成。旧硬盘需从新 LiveCD 复制 `/bin/tkinter/tkaemon.py` 和 `/lib/aemon.py`；原生图标需要新版模组 JAR。

### 交互式 Python

在 MPYOS shell 输入 `python`（也可用 `python3` 或 `py`）。复合语句会显示
`... ` 提示；函数体保留缩进，最后输入空行提交：

```text
>>> def greet():
...     print("Hello")
...
>>> greet()
Hello
```

未闭合的括号、三引号字符串和行末反斜杠也支持续行。Ctrl+C 取消当前代码块。
这里是逐行输入；终端粘贴多行文本仍只接收第一行。较长程序请用 `edit` 或 SFTP
保存，再执行 `python filename.py`。

已有 MPYOS 硬盘需要从新 LiveCD 更新 `/bin/python.py`、`/bin/python3.py` 和
`/bin/py.py`，再退出并重新进入 REPL；只更换模组 jar 不会覆盖硬盘上已安装的文件。

## 文档

- **[Tk 使用说明](docs/tkinter.md)** — 裸 `init.py` 示例、选屏、控件子集、输入与存档兼容

- **[Wiki](../../wiki)** — 玩家手册与 MicroPython 编程指南(和标准 Python 的差异、组件操作、多值返回、ops 预算、存档注意点)

## 构建

```bash
./gradlew build        # 产物在 build/libs/
```

修改 `stdlib/*.py` 后，先运行 `./gradlew genStdlib`，再运行 `./gradlew build`。Windows 对应 `./gradlew.bat`；生成器使用内置 Java 编译器，不需要 WSL 或外部 `mpy-cross`。

基于 GTNH 的 [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) 构建体系。依赖 OpenComputers(GTNH 版)和 ModularUI2,不保证在其他分支稳定运行。

## 许可

MIT
