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

MPYOS 的 `/bin/` 保留 `tkdemo.py`（按钮与输入框）、`tklog.py`（列表日志）、`tklayout.py`、`tkcanvas.py` 和 `tktext.py`（编辑器与只读日志）。已有 MPYOS 安装可只复制需要的示例；应用程序用普通 `import tkinter`。

## 指定显示器

```python
screens = component.list('tk_display')  # 地址 -> 组件类型
root = tk.Tk(screenName='完整地址或唯一前缀')
other = tk.Toplevel(root, screen='另一个显示器地址')
print(root.winfo_screen())
```

不传地址时选本应用尚未占用的第一个可达显示器。前缀匹配多台、目标不在网络中，或同一应用重复使用同一屏幕，均报 `OSError`。`Toplevel` 对应另一块物理屏幕，不提供桌面式浮动窗口。网络中另一台电脑创建 `Tk` 可以重新接管屏幕；原应用的会话 token 随之失效。

同一显示器由所有观看者共享控件内容、焦点、光标和选区，最后操作的玩家接管拖选。关闭玩家 GUI 不等于 `root.destroy()`：程序继续运行。`quit()` 退出事件循环，`destroy()` 释放该屏幕并清除应用控件。

## 控件与布局

| API | 支持范围 |
| --- | --- |
| `Frame` | 容器、背景、边距 |
| `Label` | 单行/多行文字、`textvariable`、锚点、折行；适合静态文字 |
| `Message` | 自动折行的说明文字 |
| `Button` | 鼠标/键盘激活、`command`、`invoke()`、禁用态 |
| `Entry` | 单行 Unicode 编辑、选区、剪贴板、`textvariable`、readonly/disabled |
| `Checkbutton` / `Radiobutton` | 变量联动、命令回调、选中/取消 |
| `Scale` | 横/纵滑块、范围、步长、变量与命令回调 |
| `Listbox` | 批量插入/删除、选择模式、可见行渲染、双向滚动 |
| `Scrollbar` | 横/纵、`command` 与控件的 `xview/yview` 连接 |
| `Canvas` | 线、多边形、矩形、椭圆、文字；坐标、tag、层级和鼠标绑定 |
| `Text` | 纯文本多行编辑、折行、选区、双向滚动和只读日志，详见下文 |

支持 `StringVar`、`IntVar`、`DoubleVar`、`BooleanVar`。变量与关联控件在 Python/Java 两侧同步；没有完整 Tcl 变量系统。

`pack` 支持 side/fill/expand/anchor/padding，`grid` 支持行列权重、跨度、sticky、minsize/pad/uniform，`place` 支持绝对和相对位置。支持相应的 forget/remove、传播开关和布局查询。同一个容器中不要混用 `pack` 与 `grid`，用子 Frame 分隔布局。`place` 可与它们共存。

画布分辨率每个维度为 64～1024 像素，默认 320×240；`geometry()` 只接受 `宽x高`。Entry、Label、Listbox、Text 的宽高按其字符/行语义处理，Frame/Canvas 的尺寸用像素。字体固定为 OC unifont：ASCII 通常 8×16，汉字通常 16×16；不支持 Tk 字体选择、图片控件或主题系统。

`bind` 仅支持实现中列出的事件，如 `<Button-1>`、`<ButtonRelease-1>`、`<Motion>`、`<B1-Motion>`、`<MouseWheel>`、`<Key>`、`<Return>`、`<Tab>` 和 `<<ListboxSelect>>`；不是完整的 Tk 事件匹配器。未知选项或不支持的操作可能报错，不能直接运行任意桌面 Tk 应用。

## Text：编辑器与只读日志

```python
text = tk.Text(root, width=36, height=8, wrap='word')
bar = tk.Scrollbar(root, orient=tk.VERTICAL, command=text.yview)
text.configure(yscrollcommand=bar.set)
bar.pack(side=tk.RIGHT, fill=tk.Y)
text.pack(fill=tk.BOTH, expand=True)
text.insert('end', '第一行\n第二行')
body = text.get('1.0', 'end-1c')

# 追加只读日志时暂时解除 disabled，再恢复。
text.configure(state=tk.NORMAL)
text.insert('end', '\n日志内容')
text.see('end')
text.configure(state=tk.DISABLED)
```

Text 始终保留末尾换行；空控件 `get('1.0', 'end')` 返回 `\n`。获取用户正文通常用 `end-1c`。索引支持 `行.列`、`行.end`、`end`、`insert`、`sel.first/last`、`@x,y`、`±N c/char/chars`、`linestart/lineend`。字符位置按 Unicode 码点计，非 BMP 字符不会拆开，与旧 Tcl/Tk 的 UTF-16 索引可能不同。

支持 `get/insert/delete/index/compare/see`、`mark_set('insert', ...)`、`tag_add/remove/ranges('sel', ...)`、`selection_get()`、`edit_modified()`、`xview/yview` 及 moveto/scroll。`wrap` 可为 char/word/none；Tab 显示为 8 个 ASCII 字符宽的制表位。

`state=DISABLED` 时可选择、复制和滚动，但用户和 Python 的 insert/delete 都不能修改内容。只提供一个连续选区和 insert 光标，没有富文本 tag、任意 mark、undo/redo/search、嵌入图片/窗口、`<<Modified>>` 或完整 Text API。

## 输入、世界表面与限制

- GUI 支持鼠标点击/拖选/滚轮、方向键、Home/End、Ctrl+A/C/X/V 等；中键粘贴系统剪贴板。没有 IME，中文可通过剪贴板输入。Text 中 Tab 插入制表符，Ctrl+Tab / Shift+Tab 切换焦点。
- 每次粘贴最多 4 KiB UTF-8，截断处不会拆 Unicode 码点；复制 Text 选区最多 512 KiB。
- 每屏最多 256 个控件、深度 32；Canvas 每个最多 512 个图元，整树最多 1024 个。
- 每个 Text 最多 512 KiB UTF-8、262,144 个码点、20,000 个逻辑行（含末尾换行）；同屏 Text 合计最多 2 MiB。超限修改会整体拒绝。
- 世界表面是实时预览，鼠标交互在右键打开的 GUI 中进行。`component.invoke(address, 'setSurfaceVisible', False)` 只隐藏世界表面；GUI 和程序仍可用。
- 配置 `general.Tk_Surface_Enabled` 可关闭表面显示，`general.Tk_Surface_Distance` 控制最远距离，默认 32 格（8～64）。两端设置分别生效。
- 表面纹理按距离使用 512/256/128 像素，最多每 2/4/10 tick 更新；静态画面不重绘。每客户端最多 32 个表面订阅，保留最近 16 张纹理，每帧最多更新两张。FBO 不可用时跳过世界预览，GUI 仍可用。
- 表面同步每客户端有 1 MiB 突发额度、每 tick 补充 16 KiB；超额变化合并后再发。GUI 同步独立计费，不包含在这个带宽上限内。

## 存档与升级

Python 回调、变量和定时器随 VM 快照保存；Java 控件树、布局、输入状态和回调 ID 随显示器 NBT 保存。要恢复交互，两份状态必须来自同一个一致的世界存档。仅复制显示器 NBT 不会复制运行它的 Python 程序。新的程序接管屏幕会清除旧树。

本次冻结不强制冷启动：VM 快照嵌入已加载模块的字节码，恢复后继续使用其中的 tkinter；旧开发版磁盘模块的文件名不同也不影响恢复。已验证旧路径模块、按钮回调和变量联合恢复，以及保存时尚未导入 tkinter、恢复后首次从冻结库导入的情况。这只覆盖当前 `_tk` 宿主接口兼容的版本，不能保证未来任意版本都兼容。

真实 OC 机箱也已验证：只含 `init.py` 的硬盘通过 BIOS 启动 Tk；整个专用服务器保存重启后保留计数与输入内容，按钮、事件绑定和变量联动继续工作，没有重新执行 init。验证记录见 [实施清单步骤 11](tk-plan.md#11-冻结--收尾)。

冷启动后的普通 `import tkinter` 优先加载内置库；遗留 `/lib/tkinter.py` 不会覆盖内置版本，无需为本次升级删除它。若要让已经运行的应用使用新 Python 库，需关闭并重新启动该 OC 电脑；仅重开玩家 GUI 或保存重载不会替换快照里的旧库。

树 NBT 当前为版本 4，读取版本 1～4。网络通道为 `mpy4oc_tk9`，服务器与客户端需要一致的 Tk 协议；不匹配时不能使用 Tk GUI。不要用旧构建继续写入新控件存档。

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
