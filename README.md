# MPY4OC — 在 OpenComputers 里写 Python

*中文 · [English](README.en.md)*

> Run real MicroPython on your OpenComputers computers. Minecraft 1.7.10 / GTNH.

一套给 OpenComputers 的 **MicroPython 处理器**:把 MPY CPU 插进机箱、刷好 BIOS,这台 OC 电脑就说 Python 了。附带 **mpyos**(shell、行编辑、文件系统、编辑器)和一张 **SFTP 服务器卡**——用 MobaXterm 之类的客户端直接 SFTP 进电脑磁盘改 `init.py`,还能在 SSH 终端里看到游戏内屏幕、远程打字。

```python
gpu = component.gpu
w, h = gpu.getResolution()
gpu.set(1, 1, "Hello from Python!")

import threading, jasyncio
threading.Thread(target=lambda: print("threads work")).start()
jasyncio.run(main())          # asyncio 也有
```

## 特性

- **真的 MicroPython 字节码**。内置纯 Java 编译器,输出与官方 `mpy-cross` **逐字节一致**(官方编译测试套件 749/749),不需要任何外部程序。
- **全状态快照**。存档时整个 VM 冻结进存档:变量、调用栈、生成器、协程、线程、组件代理全部保留,读档后**从存档那一刻原地继续**,不重启程序。所有计时(`time.sleep`、协程睡眠)不数你离线的时间。
- **同步 / 异步两族处理器,各三档**(CPU 与带内置显卡的 APU):
  - 同步:跑在服务器主线程,组件调用零延迟,每 tick 保证执行——默认选它;
  - 异步:跑在 OC 计算线程,对 TPS 友好,非 direct 组件调用自动回主线程执行,语义与 OC 的 Lua 架构一致。
- **线程 + asyncio 双并发模型**。`threading` 是有栈的(普通函数任意深度直接 `time.sleep`,整条栈保留);`jasyncio` 是 asyncio 风格的事件循环,每次 `run()` 独立、可以多开、线程里也能开。
- **按 tick 的 ops 预算**。脚本每 tick 按 CPU 档次限速(500/2000/16000 条指令),空闲时主动让出能攒预算(至 10 倍)。
- **26 个冻结标准库模块**:`json` `re`(超集)`struct` `hashlib` `collections` `functools` `pickle` `traceback` `machine` `micropython` …,多数行为与官方逐字节一致。
- **联网就用 `requests` / `urllib` / `socket`**。插上 OC 的因特网卡,`requests.get(url).json()` 就是你想的那个意思;`urlopen` 返回类文件对象,`socket` 走裸 TCP。这些库**永远都在**(没插卡也 import 得进来),只在真正发请求时抛 `OSError`——等待全程按 tick 让出,不占服务器。

- **Tk 图形界面**。内置 `tkinter` 子集：按钮、输入框、列表、Canvas 和纯文本 Text 等。Tk 显示器支持世界表面预览，右键打开共享鼠标界面；无需安装 MPYOS。见 [Tk 使用说明](docs/tkinter.md)。

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
4. 编辑 `/init.py` 写你的程序(游戏内 `edit`,或插一张 SFTP 卡远程改)。

SFTP 卡:`component.sftp.start("user", "password")` 开服务,`getPort()` 看端口,然后用任意 SFTP/SSH 客户端连服务器 IP。电脑关机自动断线注销;区块卸载重载会自动恢复服务。

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

基于 GTNH 的 [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) 构建体系。依赖 OpenComputers(GTNH 版),不保证在其他分支稳定运行。

## 许可

MIT
