# MPY4OC

*中文 · [English](README.en.md)*

[![Release](https://img.shields.io/github/v/release/reobf/MPY4OC?include_prereleases&label=release)](https://github.com/reobf/MPY4OC/releases/latest)
[![Build and test](https://github.com/reobf/MPY4OC/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/reobf/MPY4OC/actions/workflows/build-and-test.yml)

给 OpenComputers 加一种 **MicroPython 处理器**。机箱里换上 MPY CPU、EEPROM 刷成 MPYOS BIOS,这台 OC 电脑就用 Python 编程;存档时程序原地冻结,读档后接着跑。Minecraft 1.7.10,面向 GTNH。

```python
# /init.py —— 开机自动运行。不装 MPYOS 时,硬盘上有这一个文件就够了
import time

gpu = component.gpu                              # component、computer 是内置全局对象
gpu.bind(next(iter(component.list("screen"))))   # 和 OC 的 Lua 一样,先把显卡绑到屏幕
w, h = gpu.getResolution()                       # 组件的多值返回就是元组
n = 0
while True:
    gpu.set(1, 1, "Hello from Python!  up %d s" % n)
    n += 1
    time.sleep(1)                                # 让出 CPU;存档再读档,从这里接着跑
```

## 它能做什么

- **纯 Java 实现的 MicroPython。** 不依赖任何动态链接库:编译器输出与官方 `mpy-cross` 逐字节一致,解释器直接执行 `.mpy` 字节码,不经过单片机模拟器;`.py` 源码和 `.mpy` 字节码都能直接运行。
- **存档即快照。** 变量、调用栈、线程、协程全部写进存档,读档后程序从那一刻原地继续,不重启。
- **线程和 asyncio。** `threading` 和 `asyncio` 按 CPython 3.12 的 API 和语义实现(`Lock`、`RLock`、`Condition`、`Event`、`Semaphore`、`Barrier`、`Timer`;`Task`、`gather`、`Queue` 等),不是 MicroPython 原生的 `uasyncio`。调度按 ops 算:每 tick 的 ops 预算在主程序和各个可运行的线程之间均分,一个线程用完自己的份额就轮到下一个,整个 tick 没用完的攒进银行(见下面"处理器");asyncio 的事件循环跑在调用它的线程里,由 park/unpark 调度,休眠的协程和等待中的线程不占份额、不花 ops,到期或被唤醒才继续。睡眠和超时按游戏 tick 计时(每秒 20 tick),不是真实时间:存档、区块卸载期间不走。
- **tkinter。** 按 CPython `tkinter`(含 `ttk` 和标准对话框)的 API 和语义实现,画在 Tk 显示器上:多窗口桌面、最多 8×8 块拼屏、方块表面实时显示,右键打开共享操作界面。
- **联网。** `requests`、`urllib`、`socket` 是面向客户端的部分实现(发 HTTP 请求、建 TCP 连接,不能监听端口),最终都经 OC 因特网卡收发。
- **SSH + SFTP 服务器卡。** 一张组件卡同时提供 SSH 和 SFTP:SFTP 直接读写电脑硬盘,SSH 终端显示游戏内屏幕并可远程打字。
- **MPYOS。** 用 Python 写、跑在这台 VM 上的操作系统:shell(Tab 补全、后台作业、Ctrl+C)、编辑器、Python REPL;随附的文件浏览器、任务管理器和 AE 合成监视器是 tkinter 应用示例。

## 安装

- Minecraft 1.7.10 + Forge,依赖 OpenComputers(GTNH 分支)和 ModularUI2;服务端、客户端都要装。
- 从 [Releases](https://github.com/reobf/MPY4OC/releases) 下载 `mpy4oc-<版本>.jar`(文件名不带 `-dev`、`-sources` 等后缀的那个),放进 `mods`。

## 快速上手

配方全部是**无序合成**,一根原版**线**(String)表示"MPY 版":

| 产物 | 配方 |
|---|---|
| MPY CPU / APU(一至三级) | OC 同级 CPU / APU + 线(三级 APU 用创造 APU) |
| MPYOS BIOS | 任意 EEPROM + 线,或任意 EEPROM + MPYOS 软盘(覆盖原数据) |
| MPYOS LiveCD 软盘 | 任意软盘 + 线(覆盖原数据);扳手 + 战利品软盘可循环切到 MPYOS 盘 |
| Tk 显示器 | OC 一级屏幕 + 线 |
| SFTP 服务器卡 | 卡基板 + 线 |
| 同步 ↔ 异步处理器 | 单独放入工作台,双向 1:1 |

1. 像装 Lua 电脑一样组机器(机箱、内存、显卡、屏幕、键盘、硬盘),CPU 和 EEPROM 换成 MPY 的,软驱里放 LiveCD。
2. 开机进入 shell,输入 `install`,系统装进硬盘,之后不再需要软盘。
3. `edit app.py` 写程序,`python app.py` 运行;接了 Tk 显示器可用 `tkedit app.py`。
4. 不想要 MPYOS,两种办法:硬盘根目录只放自己的 `/init.py`(如上例),MPYOS BIOS 开机会直接运行它;或者把程序本身写进 EEPROM(`component.invoke(地址, 'set', 源码)`,或在 Lua 电脑上用 OC 的 `flash`),开机就从 EEPROM 运行,不需要硬盘,只有 EEPROM 的微控制器也是这么跑(EEPROM 默认容量 4 KB)。

## 处理器

| 档位 | ops(每 tick 指令数) |
|---|---|
| 一级 | 500 |
| 二级 | 2000 |
| 三级 | 16000 |

空闲时没用掉的 ops 会攒起来(最多 10 个 tick 的量),有活时一次用掉。APU 自带显卡,省一个显卡槽。

- **同步**(默认选它):跑在服务器主线程,组件调用零延迟,每 tick 保证执行。
- **异步**:跑在 OC 计算线程,对 TPS 友好;非 direct 组件调用自动回主线程,语义同 OC 的 Lua 架构。

## MPYOS 常用命令

| 命令 | 作用 |
|---|---|
| `edit 文件` | 全屏文本编辑器 |
| `python` / `python 文件 参数` | 交互式 REPL,或前台运行脚本;`pythonw` 在后台运行 |
| `jobs` / `kill 编号` | 查看、结束后台程序;其他命令末尾加 `&` 进后台 |
| `components` / `which 命令` / `ops` | 列出组件 / 查命令位置 / 看本 tick 剩余指令预算 |
| `tkedit 文件` | 鼠标操作的编辑器(以下都需要 Tk 显示器) |
| `tkexplorer` | 文件浏览器 |
| `tktaskmgr` | 任务管理器:各程序 ops/s、线程数,可结束程序 |
| `tkaemon` | AE 合成 CPU 监视器:OC Adapter 贴着 ME 控制器或 ME 接口即可,只读 |
| `tkdesktop` / `tkdemo` 等 | Tk 示例,源码在 `/bin/tkinter/` |

tkinter 程序默认在后台运行,输入后立即返回提示符。脚本里启动别的程序用 `subprocess.Popen(['pythonw', 'app.py'])` 或 `os.spawn*`,语义见 [程序启动接口](https://github.com/reobf/MPY4OC/wiki/tkinter-使用说明#标准程序启动接口)。

## Python 里有什么

MPYOS 中可直接用 `open('notes.txt', 'w')` 和 `input('Name: ')`。文件按当前程序的 cwd 和 `/mnt` 挂载访问 OC 磁盘，支持 UTF-8、二进制、`with`、读写和追加；后台程序不能读取终端。OC 暂不支持 `+`、`x` 打开模式。已有硬盘更新 JAR、`/lib/osfs.py`、`/lib/term.py` 后重启。标准入口在冻结库里，其他宿主可用 `io.install(opener=..., reader=...)` 提供自己的文件和输入后端。

- `component`、`computer` 是内置全局对象,不用 import;MPYOS 另带 `event`、`fs`、`term`、`requests`、`socket`、`urllib` 等库。
- 冻结标准库:`asyncio` `binascii` `cmath` `collections` `deflate` `functools` `gc` `hashlib` `heapq` `io` `json` `machine` `math` `micropython` `os` `pickle` `random` `re` `struct` `subprocess` `sys` `threading` `time` `tkinter` `traceback`。
- 和标准 Python 的差异、组件调用、ops 预算、存档注意点,见 [MicroPython 编程指南](https://github.com/reobf/MPY4OC/wiki/MicroPython-编程指南)。

## 升级已有硬盘

模组更新了 MPYOS 的软盘代码后,已经装进硬盘的系统不会自动同步新代码。要用新版系统的特性,把新 LiveCD 上的 `/bin`、`/bin/tkinter`、`/lib` 复制到硬盘(软盘挂在 `/mnt/` 下,`cp` 一次复制一个文件;用 SFTP 卡可以整目录传),删掉 `/bin` 下旧的 `tk*.py`,再重启电脑。换一块空硬盘重新 `install` 最省事。

## 文档

- [tkinter 使用说明](https://github.com/reobf/MPY4OC/wiki/tkinter-使用说明)(wiki):从裸 `init.py` 启动、选屏、控件、桌面与窗口、各个 tkinter 命令、程序启动接口、终端中断。
- [MPYOS 用户手册](https://github.com/reobf/MPY4OC/wiki/MPYOS-用户手册)(wiki):装机、开机、shell 与命令、SFTP 卡、故障排查。
- [MicroPython 编程指南](https://github.com/reobf/MPY4OC/wiki/MicroPython-编程指南)(wiki):和标准 Python 的差异、组件操作、联网、存档注意点。

## 构建

```bash
./gradlew genStdlib    # 如果你修改了 stdlib/*.py,请重新运行一次,以重新生成冻结标准库
./gradlew build        # 含全部测试,产物在 build/libs/
```

基于 GTNH 的 [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10) 构建体系。

## 许可

MIT
