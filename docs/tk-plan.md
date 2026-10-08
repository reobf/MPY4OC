# tkinter for MPY4OC — 实施清单

> 新会话先读这个文件。每步大约一个会话的量;做完把 `[ ]` 改成 `[x]`,并在步骤下追加一行"结果/遗留"。
> 架构决策写在最上面,免得每次重新推导。

## 已定决策

- **架构**:服务端 Java 维护控件树(布局、命中测试、焦点),把**显示列表**同步给客户端;客户端 GuiScreen 用 GL + OC 的 unifont 渲染器绘制;输入事件打包回服务端。不做"无头渲染像素再流到客户端"。
- **字体**:OC unifont(等宽 8×16,CJK 16×16)→ 文字宽度服务端可算,布局完全在服务端。
- **Python 半边**:最终**冻结进引擎**(与 `machine`/`jasyncio` 并列),开发期先放 `mpyos/lib/tkinter.py`(SFTP 改即生效,不用重编 jar)。回调表在 Python 侧(`id → callable`),Java 只存 `(事件 → 回调 id)`,这样 VM 快照自然包含回调。
- **事件入口**:复用 `MpyArchitecture.drainSignals()` 那条队列,`mainloop()` 从队列取事件分发;同步/异步 CPU 自动兼容。
- **屏幕选择**:照 Tk 的 X11 语义——`Tk(screenName=<显示方块地址或前缀>)`、`Toplevel(master, screen=<地址>)`、`winfo_screen()` 返回地址、`winfo_screenwidth/height()` 返回方块分辨率;默认绑网络里第一块显示方块。
- **缺硬件**:`import tkinter` 永远成功,`Tk()` 没有显示方块时抛 `OSError("no tk display")`(沿用 `requests` 的约定)。
- **布局必须在 Java**:每 tick ops 预算 500~16000,Python 只做薄包装 + 回调分发。
- **多人**:同一方块的所有观看者共享一份界面,谁操作都算(和 OC 屏幕一致)。
- **粘贴**:中键或绑定键 → 客户端读剪贴板 → `clipboard` 事件 → 光标处插入(照 OC `InputBuffer`)。
- **不做**:ttk 外观、与 Tk 像素级一致、完整 Text 的 tag/mark/undo(放最后可选)。

## 导入时基线(2026-10-06，历史记录)

- mod 里**没有**任何 Block / TileEntity / GuiScreen / 网络包(只有 `ModGuiFactory` 配置界面)。
- 冻结模块:`StdLibData.java`(预编译 .mpy 的 base64),由 `gen-stdlib.sh` 从 `stdlib/*.py` 生成——**脚本与源码不在仓库里**。
- 宿主原语模式:`StdLib.installPrimitives(globals)` 放 `__xxx` HostFunction;组件 API 由 `MpyArchitecture.installHostApi` 注入(`component`/`computer`)。
- 磁盘侧库:`assets/mpy4oc/mpyos/lib/`(fs/shell/term/event/requests/socket/urllib)。
- 快照按"模块 + 代码路径索引"引用代码(`Snapshot.codeAt`)，同时保存已加载模块的原始 `.mpy` 字节码。更新冻结模块不必然导致旧快照冷启动；宿主 API 和快照格式的兼容性仍须分别验证。

## 步骤

### 接续记录（2026-10-07，Codex）
- [x] 从导入会话留下的文件恢复生成器接入：`build.gradle.kts` 加载 `gen-stdlib.gradle.kts`，提供 `gradlew genStdlib`。
- [x] 25 个 `stdlib/*.py` 已恢复；生成器使用内置 Java 编译器，无需原生 mpy-cross。常量名转换固定使用 Locale.ROOT。
- [x] 连续两次运行 `gradlew genStdlib --offline --console=plain` 成功，第二次复用了 configuration cache；生成的 `StdLibData.java` 与 HEAD 无差异。
- [x] 接入 JUnit 5，8 个测试通过：25 个冻结模块逐字节复现、生成器重复运行、Python 回调表及导入模块的快照恢复、显示器 NBT/地址持久化、入网时请求保存与参数校验。
- [x] 验证首次编译测试到新目录时，`test` 确实执行，不会因 GTNH 降级目录为空而误报 `NO-SOURCE`。测试使用编译器对应 JDK 的原始类；发布构建继续进行 Java 8 降级。
- [x] `gradlew build --offline --console=plain` 完整打包通过。
- [x] 步骤 1 代码已实现：方块、`tk_display` 组件、默认 320×240 分辨率、表面显示开关、OC 地址与设置的 NBT 持久化、配方及中英文名称。
- [x] 独立目录 `build/tk-server-smoke-20261007`、本机端口 25566 的 Java 8 服务端启动、放置两块显示器、保存实际区块 NBT 均通过；两个 OC 节点生成不同 UUID。
- [x] 修复实际验证发现的存档遗漏：自身节点入网时 `markDirty()`，保证先前已保存但尚未 tick 的显示器在入网生成地址后重写区块，避免每次重启换地址。
- [x] 再次重启、正常 tick 后保存，两个地址与重启前完全一致；测试服已正常 `stop`。Python `component.list` 仍待带电脑的游戏内实测。GUI 从步骤 2 开始。
- 服务端日志中的 ANTLR 4.13.2 / 旧 ASM 类扫描警告未阻止 28 个 mod 加载及服务器启动；本轮没有调整这条依赖链。
- [ ] 本轮源码、生成器、测试及显示器代码尚未提交。
- 原始 `gen-stdlib.sh` 留在用户提供的 all-0.0.77 压缩包中；现用 Java 生成器替代，不需要恢复原生编译后端。生成文件保留历史头注释以便逐字节对比。
- 快照测试已验证导入的 `json` 模块和 Python 回调表恢复后能通过重新安装的宿主函数继续执行。完整 Tk 控件树与回调的联合存档恢复、信号队列分发和字体度量仍须在对应步骤验证。
- 开发命令：修改 `stdlib/*.py` 后执行 `gradlew genStdlib`，再执行 `gradlew build` 将新生成的源码编入产物。

### 0. 准备（功能与验证完成，待提交）
- [x] 从 **`mpy4oc-all-0.0.77.tar.gz` 的 `2-mpy-java/`** 恢复 25 个 `stdlib/*.py`，用内置 Java 编译器生成器替代旧 `gen-stdlib.sh`；重新生成后 `git diff StdLibData.java` 为空。
- [x] 评估 `mpy4oc-workspace-0.0.77.tar.gz` 的测试资料。本轮采用新的 JUnit 测试和真实 OC API/MC NBT，避免把旧桩类复制进当前 MC 类路径。以下资料留作后续编译器与架构测试参考，尚未整体移植：
  - `mpy-compiler-ws/oracle/rebuild-oracle.sh`:mpy-cross 对拍锚点 commit `a5bac1b75ad4ba69d30f73ee3ce7bc358665069c`;`tests/compile_corpus/`(107 个 .py,94 个逐字节对拍)+ `tests/BatchCompile.java`;`docs/COVERAGE_official_suite.md`(官方 1505 文件行为一致的方法)。
  - `archtest/`(158 文件,含 `stubs/` 30 个 OC/MC 桩类)= 不开游戏跑 `MpyArchitecture` 的集成测试台，可参考其测试方法；`harness/`(纯 VM 运行器 + aiotest/thrtest 脚本)。
  - 陷阱见 HOW-TO-RESUME:测试台不推进机器时钟(`FM.upTime()` 恒 0),涉及 sleep 的测试要手动推 `CLOCK`。
- [x] 建立 `src/test`(JUnit)，覆盖生成器、冻结模块、回调快照和显示器持久化。
- [ ] 将以上源码及构建接入提交进仓库。
- 完成标准:生成脚本可重复运行且结果逐字节一致；`gradlew test` 实际运行并通过有意义的回归测试。

### 1. 方块 + TileEntity + OC 组件(无 GUI)
- [x] `BlockTkDisplay` / `TileTkDisplay`；OC `TileEntityEnvironment`/Node 接入网络；NBT 持久化骨架。分辨率限制为每轴 64..1024。
- [x] 红石创造栏物品；OC 一级显示器 + 线的无序配方；lang 中英文。暂用原版铁块外壳及黑色羊毛正面贴图。
- [x] 独立 Java 8 服务端实测放置方块；实际 NBT 含不同 OC 地址及默认显示器设置；缺地址的已存区块入网后可自动保存新地址。
- [x] Python `component.list("tk_display")` 带电脑实测（步骤 11：真实 OC 机箱 + BIOS + 裸 init.py，按地址选择连通的显示器）。
- 完成标准:放下方块后 Python `component.list("tk_display")` 能列出它。

### 2. 网络通道 + 空 GuiScreen
- [x] FML `SimpleNetworkWrapper`：`OpenGui`、`SceneSync`(全量/增量)、`InputEvent`、`Clipboard`，以及关闭、重同步和心跳包。版本化通道 `mpy4oc_tk1`；未注册该通道的旧客户端右键时显示提示。
- [x] 右键方块 → 服务端校验 → 客户端打开 `GuiTkDisplay`(先只画底色和标题)；TE 维护 viewers 列表，关 GUI 时移除。校验距离、维度、节点地址、连接和会话；掉线、离开、区块卸载及心跳超时均会清理观看者。
- [x] 两个真实客户端同时打开同一方块，服务端改底色，两端都收到增量更新；关闭后观看者归零。
- 完成标准:两人同时打开同一方块,服务端改一个颜色两边同时变。
- 结果/遗留（2026-10-06，Codex）：独立 Java 8 `runServer` + 两个 `runClient` 实测完成。临时测试模组调用真实方块右键入口；两端都从 revision 1 / `0x202020` 更新为 revision 2 / `0x224488`，渲染截图一致，随后关闭 GUI，服务端确认 `VIEWERS 0`。两个客户端及服务器正常退出，25566 端口已释放。
- 新增 `setBackground/getBackground`、`setTitle/getTitle` OC 回调，背景、标题、分辨率和表面开关均保存到 NBT。服务器 tick 合并变化；客户端遇到缺失的增量基准会请求全量同步。会话令牌避免旧关闭包影响重新打开的界面。
- 输入传输已有鼠标按下/释放、按键及修饰键、中键与 Ctrl+V/Shift+Insert 粘贴。探针发送的鼠标、中文按键和中文剪贴板包均经真实网络到达 OC 的 `computer.signal`，信号名为 `tk_input` / `tk_clipboard`。控件事件分发、Entry 编辑、拖动、滚轮和 Motion 留到步骤 3～5；此处没有验证系统剪贴板读取或 Entry 插入。
- 标题目前使用 Minecraft 原版字体；OC unifont、控件文字度量与绘制在步骤 3 接入。表面开关已同步，但世界内表面渲染仍在步骤 9。
- `gradlew build --offline --console=plain` 完整打包通过，14 个 JUnit 测试均通过（原 8 个 + 6 个协议测试）。新增用例覆盖双观看者增量、未发送的取消变化、重同步、过期会话、输入预算及畸形/超长数据包。
- 实测资料全部位于忽略的 `build/` 下：`tk-probe/` 是临时测试模组，`tk-client-one-20261006/` 和 `tk-client-two-20261006/` 中有 `tk-smoke-state.txt`、日志及 `screenshots/tk-TkOne-2.png` / `tk-TkTwo-2.png`；服务端目录沿用 `tk-server-smoke-20261007/`。测试模组未加入发布产物。
- 再跑双客户端时使用独立运行目录与用户名。RFG 的 `runClient.extraArgs` 需要逐项参数列表；把 `--server ... --port ...` 整段交给 `--mcArgs` 会被当成一个参数，停留在主菜单。临时 `build/tk-probe/client-args.gradle` 已演示正确用法。
- 步骤 0～2 的新增源码和测试仍未提交。下一步是 Frame/Label/Button、pack、显示列表和 unifont；`tkinter.py` 的 Python 包装在步骤 4。

### 3. Java 控件树核心(无 Python)
- [x] 包 `reobf.mpy4oc.tk`：`TkWidget` 基类、Frame/Label/Button、`TkTree`、`pack`、脏标记、显示列表序列化、命中测试、共享焦点、OC unifont 度量。
- [x] JUnit：构造树 → pack → 断言各控件矩形，覆盖四向布局、混合方向扩展、内外间距、anchor、尺寸传播、裁剪、隐藏与销毁。
- [x] Java 8 专用服务端和两个真实客户端渲染同一棵硬编码树，自动通过 GUI 的鼠标和键盘入口验证按钮操作与多人同步。
- 完成标准:客户端能渲染一棵硬编码的树;pack 用例全绿。
- 结果/遗留（2026-10-06，Codex）：9 个控件的测试界面在两端正常显示中文与 ASCII、嵌套 Frame、普通/禁用按钮和焦点边框。TkOne 点击计数按钮，TkTwo 用 Tab + Enter 重置；两端均收到 `revision 4 → 5 → 6 → 8`，计数由 0 到 1 再重置。OC 网络分别收到两个 `tk_command`。关闭后 `VIEWERS 0`，三个运行任务正常退出，25566 端口已释放。
- `pack` 支持 top/bottom/left/right、fill、expand、九向 anchor、非对称外间距、ipadx/ipady、嵌套尺寸传播与 `propagate(false)`；重配保留既有 packing order，forget 后重新 pack 移到末尾。`before/after/in` 尚未实现。Java 的 `size()` 使用像素，Label/Button 的 Python 字符单位宽高转换留给步骤 4。语义参考 [Tk pack 官方说明](https://www.tcl-lang.org/man/tcl8.6/TkCmd/pack.htm)。
- 字体直接复用当前 OC 的 `FontUtils.wcwidth` 缓存与 `DynamicFontRenderer` 字形纹理。画布按原生 8×16 像素绘制，宽字符按 OC 度量前进，避免普通 `drawString` 固定单格步进造成中文重叠；资源重载后重新准备字形。布局、裁剪区域、文本原点均由服务端提供。
- 协议升级为 `mpy4oc_tk2`，避免旧版空画布客户端误解新增字段。`TkScene` 增加不可变显示列表；变化字段包只在控件绘制结果变化时携带列表，目前列表整体替换，逐控件/逐行补丁留待大列表与 Text 阶段。限制为 256 个控件、32 层嵌套、单控件 1 KiB/整树 16 KiB UTF-8 文本。
- Java 入口：`tile.widgets()` 返回树，`new Frame(tree.root())` / `new Label(frame, text)` / `new Button(frame, text)` 创建控件，`pack(...)` 后显示。`Button.command(id)` 只保存回调 ID，激活时发 `tk_command(address, widgetId, callbackId, playerName)`。这些操作必须在服务端主线程执行。鼠标按下按观看会话记录，移出画布释放会取消；焦点在观看者间共享。
- 修正了根节点初始化误触发 `markDirty()` 的回归；也补上极长多行文字在很小控件内靠下对齐时的序列化边界。最终 `gradlew build --offline --console=plain` 通过，25 个测试全绿（原 14 个 + 11 个控件/显示列表测试）。
- 实测探针在忽略的 `build/tk-widget-probe/`；两客户端目录沿用步骤 2，新增 `tk-widget-state.txt` 和 `screenshots/widgets-TkOne-*.png` / `widgets-TkTwo-*.png`。最终截图分别为 `widgets-TkOne-8.png` 与 `widgets-TkTwo-8.png`。探针构造演示界面与执行 Java 回调，不随发布 jar 打包。
- 步骤 3 结束时，控件树仅保存在运行内存中；持久化、Python 回调与 VM 快照联合恢复、`tkinter.py` 和演示程序随后在步骤 4 实现。
- 步骤 0～3 的改动仍未提交；后续进展见步骤 4。

### 4. Python 绑定(磁盘版先行)
- [x] 宿主 `_tk.call/screens/poll`：创建、配置、布局、焦点、绑定与销毁经非 direct 的 `tk_display.tk` 回调执行；事件复用 `drainSignals` 队列。`after` 使用 Python 回调表和 `computer.uptime`，不另设 Java 定时队列。
- [x] `mpyos/lib/tkinter.py`:Tk/Toplevel/Frame/Label/Button/Entry、StringVar、bind、after、mainloop、`screenName`/`screen`/`winfo_screen*`。
- [x] `mpyos/bin/tkdemo.py`:按钮计数器 + Entry 回显。
- [x] 控件树及窗口所有者/令牌保存到 NBT；保留控件 ID、创建/pack 顺序、文本、Entry 光标、焦点和回调 ID，与 VM 快照联合恢复。
- [x] JUnit 验证真实 Java Python 编译器/VM：无硬件导入、地址与前缀选择、多屏窗口、回调与变量、事件/计时器恢复、销毁清理、旧窗口令牌拒绝，以及异步调用暂停/重试不重复执行。
- [x] 专用服务器 + 两客户端实测：Python demo、中文输入、保存/重建后的回调及同步。
- 完成标准:点击 → 回调 → Label 刷新;**存档读档后 GUI 与回调仍工作**。
- 接口范围（2026-10-07）：`pack` 外间距支持单值或双值；Label/Button/Entry 的 width/height 为字符单位，Frame 为像素。基础 Entry 支持 Unicode 字符索引、左右/Home/End、删除/退格、单行粘贴、自动保持光标可见和 readonly/disabled；选区、拖动、滚轮及完整输入语义留在步骤 5。
- `bind` 目前支持 `<Button-1>`、`<ButtonRelease-1>`、`<Key>`、`<Return>`、`<space>`、`<<Paste>>`；其他事件明确报错。支持替换/追加绑定和 unbind；回调异常打印后继续事件循环。`_tk.poll` 只取 Tk 高层事件，不吞掉键盘等其他 OC 信号。
- 一块显示器对应一个窗口；默认选择本 VM 尚未占用的第一块，显式地址支持唯一前缀。Toplevel 当前是另一块显示器上的独立窗口，不在同一画布叠放。窗口标识持久化，其他电脑重新 claim 后，旧窗口操作会失败，避免误改新界面。
- 协议升级为 `mpy4oc_tk3`，增加 Entry 类型和光标位置；需要客户端与服务端同步更新。库仍在磁盘版 MPYOS 中，未冻结进 StdLibData，已有安装需更新 tkinter.py 和 tkdemo.py。
- 结果（2026-10-07）：33 个 JUnit 测试全部通过，`gradlew build --offline --console=plain` 完整打包通过。专用 Java 8 服务器和两个真实客户端运行实际 `tkdemo.py`：TkOne 点击后计数 0→1，输入/粘贴“中文”，Entry 与 Label 在两端一致。将 VM 快照（19,518 字节）与方块 NBT 写入文件，创建新 VM/新方块恢复，再由 TkTwo 点击后计数 1→2，两端显示恢复后的中文内容；两个客户端均记录 `PASS / CLOSED` 并正常退出。服务器正常保存、stop，25566 端口已释放。
- 实测范围：临时探针以真实 `MpyArchitecture.installHostApi` / `invokeComponent` / `drainSignals`、真实 OC Component.invoke 和节点信号连接真实 VM，代为提供 Machine 生命周期及 tick。验证了组件参数转换、网络输入、Python 分发及文件快照恢复；**未把它等同于实际 OC 机箱从 BIOS 冷启动或整服重启恢复**，步骤 1 的实体电脑发现测试仍保留。系统剪贴板读取未自动测试，实测发送的是正式 Clipboard 数据包。
- 探针首次恢复后未收到点击，是临时节点连线在替换方块的生命周期中丢失；将探针重连安排在方块加入世界并 tick 后，回调恢复。此处只改了忽略目录中的测试探针。测试世界设为和平模式并禁止刷怪，避免角色死亡阻断 GUI 测试。
- 实测资料：`build/tk-python-probe/`（临时模组，不进入发布 jar），服务端 `tk-python.vm` / `tk-python.nbt` 与 `logs/fml-server-latest.log`，两个客户端的 `tk-python-state.txt` 及最终截图 `screenshots/python-TkOne-6.png` / `python-TkTwo-6.png`。客户端日志保留首次失败和复测轨迹，以最后的 PASS 为准。
- 步骤 0～4 改动仍未提交。下一步是步骤 5 的输入完整化；不要重复恢复生成器或重做前三阶段。

### 5. 输入完整化
- [x] 鼠标按下/释放/拖动/滚轮/移动(Motion 客户端限频);键盘 `<Key>` / `<KeyRelease>` + 修饰键;Tab/Shift+Tab 焦点切换。
- [x] 中键与 Ctrl+V/Shift+Insert 粘贴；Entry 光标、选区、横向滚动及编辑逻辑在 Java 侧。
- [x] 43 个 JUnit 测试通过，新增选区/拖动/复制回包用例与实际 Python 编译器、VM 的事件分发验证。
- [x] 双客户端、系统剪贴板及选区绘制实测。
- 完成标准:Entry 可编辑、能粘贴中文、Tab 切焦点、`<Motion>` 可绑。
- 支持左键拖动选区、越过画布边缘继续拖动/自动横向滚动，Shift+方向键/Home/End、Ctrl+A、Ctrl+左右与按单词删除；Ctrl+C/X/Insert 和 Shift+Delete 使用服务端选区复制/剪切。readonly 仍可选取/复制，disabled 不接受操作。Unicode 索引按码点计，中文与非 BMP 字符不会被截成半个字符。
- Python Entry 增加 `selection_clear/from/to/adjust/range/present/get`（含 `select_*` 别名）、`sel.first/sel.last/anchor/@x` 索引、`xview` / `xview_moveto` / `xview_scroll`。NBT 保存光标、锚点、选区和横向视口，兼容步骤 4 缺少这些字段的存档。
- 新增 `<Button-3>` / `<ButtonRelease-3>`、`<Motion>` / `<B1-Motion>`、`<MouseWheel>`、`<KeyRelease>`、Tab/BackSpace/Delete/方向键/Home/End 和 Copy/Cut 虚拟事件。Event.widget 为实际命中控件，x/y 为该控件局部坐标，delta 为滚轮增量；state 使用 Tk 位值 Shift=1、Control=4、Alt=8、Button1=256。
- 指针位置按客户端 tick 合并（正常每秒最多 20 次），释放时额外发送最后一个拖动坐标。按住画布边缘时继续发送 tick 以支持自动滚动，但静止拖动不重复触发 Python Motion；未绑定的 Motion 不生成原始 OC 信号。焦点与选区在所有观看者之间共享，同一 Entry 的新按下接管旧观看者的拖动。
- 复制回复仅发给操作玩家，客户端检查显示器、GUI 会话和最新请求编号，过期/重复/未经请求的回复被忽略。协议升级为 `mpy4oc_tk4`，客户端与服务器需要同步更新。
- 语义参考 [Tk Entry](https://www.tcl-lang.org/man/tcl8.6/TkCmd/entry.htm) 和 [Tk bind](https://www.tcl-lang.org/man/tcl8.6/TkCmd/bind.htm)。当前仍是子集：没有 IME、双/三击选词选行、完整 bind 模式匹配或通过返回 `break` 阻止默认编辑行为；中键采用 OC 风格的系统剪贴板粘贴。
- 结果（2026-10-07）：`gradlew build --offline --console=plain` 通过，43 个测试全绿。真实 Java 8 专用服务器和两个客户端完成：TkOne 拖选 AB → 系统复制 → 中键中文粘贴替换选区 → 全选剪切 → 中键填入“输入完成” → Tab 聚焦按钮、Enter 触发 Python；TkTwo 拖选前两个汉字并改为“双人”。两端最终同时显示并选中“双人完成”，Python Motion/MouseWheel/KeyRelease 回调均通过，截图核对蓝色选区与中文文字一致。两个客户端记录 `PASS / CLOSED`，`runClient` 正常退出；服务端确认 `VIEWERS 0` 后正常保存、stop。
- 实测边界：鼠标拖动、中键粘贴、Tab/Enter 调用真实 GUI 入口；Ctrl+A、复制/剪切请求、滚轮及 KeyRelease 用正式协议或 GUI 持有的指针合并器注入，未模拟物理按键状态。系统复制/剪切回复确实写入 Windows 剪贴板，中键确实读取系统剪贴板。第一次运行因临时探针恢复剪贴板时遇到 Windows 占用而退出，探针加重试后复测通过；发布源码没有这段恢复逻辑。
- 实测资料保存在忽略的 `build/tk-input-probe/`、两客户端的 `tk-input-state.txt` 和 `screenshots/input-TkOne-selected.png` / `input-TkTwo-selected.png`。日志保留失败及复测轨迹，以最后的 PASS 为准。探针仍沿用步骤 4 的真实 VM/组件加模拟 Machine 生命周期方式，未补做实体 OC 机箱冷启动测试。
- 发布 jar 已核对新类为 Java 8 字节码（major 52），含 tkinter.py/tkdemo.py，不含临时探针或 JUnit。步骤 0～5 的源码仍未提交；下一步为步骤 6 控件扩充。

### 6. 控件扩充 I
- [x] Checkbutton/Radiobutton(IntVar/BooleanVar)、多行 Label + `wraplength` + Message、Listbox(按行增量同步)、Scrollbar、Scale。
- [x] 控件模型与 Python 回调的联合存档恢复、53 个 JUnit 测试、完整构建。
- [x] 万行日志示例的专用服务器和双客户端实测。
- 完成标准:日志面板示例(Listbox + Scrollbar)灌 10k 行不卡 tick。
- `tklog.py` 演示 10,000 行中文日志、跟随选项、共享变量单选、批量大小 Scale 和 Listbox/Scrollbar 双向回调。批次通过 after 分散到 tick；既有 MPYOS 安装需更新磁盘上的 tkinter.py 并复制 tklog.py。
- Listbox 保留服务端完整行数据，仅产生可见行绘制记录。协议 `mpy4oc_tk5` 使用 `(widgetId, partId)` 键，增量包包含绘制顺序与变更记录；缺少基准记录会请求全量重同步。协议需要两端同步更新。
- 支持 insert/delete/get/size/index、curselection/selection_*、activate/nearest/bbox/see、xview/yview 的索引/moveto/scroll，以及 single/browse/multiple/extended 选中模式和 `<<ListboxSelect>>`。yscrollcommand/xscrollcommand 与 Scrollbar.command 使用 Python 回调；服务端每 tick 合并同一控件/回调的更新。Scrollbar 支持箭头、翻页、拖动滑块、方向键；Scale 支持方向、范围、步长、变量、command、点击/拖动/方向键。
- 限制：每个 Listbox 最多 20,000 行、2 MiB UTF-8 文本，整个控件树列表文本最多 8 MiB，每行最多 1 KiB；Java 每次插入最多 256 行，Python insert 自动分批；范围 get 每次最多 256 行。仍是 Tk 子集，没有 listvariable、每行单独样式、Scrollbar 自动连发、Scale 刻度及 Message aspect/justify。Message 使用像素 width（默认 160），Label 保留字符单位 width 与像素 wraplength。
- 自动测试测量（开发机一次运行，非性能承诺）：10,000 行分 100 批插入并渲染 100 次约 25 ms，视口末尾共 16 条绘制记录；只改变一行选择的同步包为 132 字节。测试也验证视口外追加不会更改 Listbox 绘制记录、删除后选区索引调整、非法批次不部分写入、禁用列表及回调绑定的 NBT/VM 恢复。
- 语义参考 [Tk Listbox](https://www.tcl-lang.org/man/tcl8.6/TkCmd/listbox.htm)、[Tk Scrollbar](https://www.tcl-lang.org/man/tcl8.6/TkCmd/scrollbar.htm)、[Tk Checkbutton](https://www.tcl-lang.org/man/tcl8.6/TkCmd/checkbutton.htm)。
- 双客户端结果（2026-10-07）：真实 Java 8 专用服务器运行实际 tklog.py，101 个 tick 加载 10,000 行（每批 100 行，约 5 秒），整幅界面只含 34 条绘制记录。TkOne 关闭跟随并把 Scale 改到 200；TkTwo 选择另一个 Radiobutton、将滚动条从末尾拖到首行并选中第 0 行，两端状态、蓝色选择和截图一致，均记录 `PASS / CLOSED`，两次 runClient 正常退出，服务端确认 `VIEWERS 0` 后正常 stop。
- 性能边界：双客户端首次测量 101 次 VM.step 合计 341.35 ms，单次最大 90.17 ms。随后仅服务端细分复测，初始化第 1 tick 为 75.98 ms；持续插入 99 次合计 261.88 ms（平均 2.65 ms、最大 22.96 ms）。这说明持续插入未超过测试服 50 ms tick 时长，但初始化仍有一次尖峰；测量的是探针 VM.step，不含整个 Minecraft tick、网络编码或客户端帧耗时，不能视为所有服务器的无卡顿保证。
- 探针依旧使用真实 MpyArchitecture 宿主 API、OC Component.invoke/节点、Java Python 编译器及 VM，由临时模拟 Machine 提供生命周期。实体 OC 机箱 BIOS 冷启动仍在步骤 1 的待办中。新控件与变量/回调联合 NBT/VM 恢复由自动测试覆盖，本轮双客户端没有再次重启 VM。
- 资料：忽略目录 `build/tk-controls-probe/`（含 `dual-client-server.log` 与性能探针），两客户端的 `tk-controls-state.txt`、`screenshots/controls-TkOne.png` / `controls-TkTwo.png`，服务端细分性能日志。发布 jar 的新类为 Java 8 major 52，包含 tkinter.py/tklog.py，不含探针/JUnit。
- 最后补上 Python 分批 insert 对负索引的钳制，避免超过 256 项的插入在批次边界错序，回归用例覆盖第 255～257 项。步骤 0～6 改动仍未提交；下一步是步骤 7 的 grid/place。

### 7. grid + place
- [x] `grid`:row/columnconfigure weight、sticky、span、padx/pady/ipadx/ipady;`place` 基本参数。
- [x] golden 用例:本机 CPython tkinter 跑出各控件矩形存 JSON,Java 对照。
- [x] Python API、NBT/VM 联合恢复及全量/增量协议回归；310 个测试与完整构建通过。
- [x] 专用服务器和双客户端的缩放、隐藏恢复及负坐标裁剪实测。
- 完成标准:golden 全部通过。
- `grid` 支持 row/column、rowspan/columnspan、sticky、非对称外间距、ipadx/ipady；行列 weight/minsize/pad、anchor、独立 grid_propagate、grid_remove/forget、配置查询、grid_size/bbox/slaves。隐藏保留布局、重配只更新指定字段；恢复时保存行列配置、管理顺序及隐藏控件的旧配置。pack 与 grid 在同一父容器中互斥，place 可叠加。
- `place` 支持 x/y/width/height、relx/rely/relwidth/relheight、九向 anchor、inside/outside/ignore；绝对与相对尺寸相加，空字符串清除指定尺寸。采用 Tk 的边界舍入及非正尺寸压至 1 像素规则；不会向父容器传播请求尺寸。支持 place_info/configure/forget/slaves。
- 对照数据：`tools/tk-layout-golden.py` 用本机 CPython 3.12 / Tk 8.6.12 生成 `src/test/resources/tk-layout-golden.json`，固定种子、253 组布局、1,803 个控件矩形和请求尺寸，另核对 grid_bbox；180 组 grid、72 组 place、1 组嵌套 pack/grid/place。使用像素尺寸 Frame，排除系统字体影响。覆盖加权伸缩、最小尺寸、相互重叠的跨格约束、空槽、所有 anchor、负位置、相对尺寸及非对称内外间距；所有用例还对照 NBT 恢复后的绘制列表。
- 新协议 `mpy4oc_tk6` 保留有符号原始绘制矩形和独立的画布裁剪区，控件伸出画布时不移动文字或伪造边框；服务端命中测试使用可见裁剪区。客户端与服务端需同步更新。控件树 NBT 升为版本 2，继续读取版本 1。
- 新增 `tklayout.py`：共享表单、1:2 列权重、按钮切换窗口大小、详情 grid_remove/恢复、右上角相对定位标记、左下角负坐标裁剪。既有 MPYOS 安装需要更新磁盘上的 tkinter.py，并复制 tklayout.py。
- 实测修复：发现两层未被旧测试桩覆盖的转换：宿主数组在生产中变成 list，OC Registry.convert 还会把 Java Map 变成 Scala Map。布局查询改用字典、grid_size/bbox 显式转 tuple，MpyArchitecture.toPy 补上递归转换 Scala Map。TkPythonTest 现在同时使用真实 OC Registry.convert 和生产 toPy，310 个回归测试再次通过。两次排查日志保留在 `build/tk-layout-probe/first-run-server.log` 与 `second-run-server.log`。
- 范围限制：128 行/列，weight 0..1000，minsize 0..8192，间距 0..1024；place 像素参数 -8192..8192，相对值 -16..16，计算后的单轴尺寸最多 32768。暂不支持 uniform、非直接父容器的 in_、物理长度单位，以及用窗口名或 all 指定行列。没有更改既有字体/控件外观，所以布局算法对齐不代表外观像素级复制原生 Tk。
- 网格约束与 place 舍入参考 [Tk grid](https://www.tcl-lang.org/man/tcl8.6/TkCmd/grid.htm)、[Tk place](https://www.tcl-lang.org/man/tcl8.6/TkCmd/place.htm) 及 Tcl/Tk 官方 C 实现；上游许可保存在 `docs/licenses/tk.txt` 并随 jar 打包为 `META-INF/LICENSE-Tk.txt`。
- 双客户端结果（2026-10-07）：实际 Java 8 runServer + 两个 runClient 运行 tklayout.py；TkOne 通过真实 GUI 鼠标入口将 400×300 放大为 500×340 并隐藏详情，TkTwo 恢复详情，两端最终 revision 28 画面一致。place 标记右缘保持在 x=492，负位置控件 bounds=(-24,320,82,18)、clip=(0,320,58,18)；两端均记录 `GRID RESIZE / REMOVE RESTORE / PLACE CLIP PASS` 和 `PASS / CLOSED`。服务端确认 VIEWERS 0 后正常保存、stop。
- 验证资料：`build/tk-layout-probe/dual-client-server.log`、两客户端目录下的 `tk-layout-state.txt` 和 `screenshots/layout-TkOne.png` / `layout-TkTwo.png`，已查看两端截图。探针调用实际 MpyArchitecture/OC Component.invoke/VM，Machine 生命周期仍由探针模拟；实体机箱 BIOS 冷启动的待办没有因此完成。启动首个 VM.step 约 544 ms，其中 OC 字体宽度缓存与覆盖表初始化约 194 ms；本步验证布局正确性，没有测量持续刷新性能。
- 发布产物包含 tklayout.py、tkinter.py 和 Tk 许可，Tk 包的 80 个类均为 Java 8 字节码，不含临时探针或 JUnit。步骤 0～7 的源码仍未提交；下一步为步骤 8 Canvas。

### 8. Canvas(基础)
- [x] create_line/rectangle/oval/polygon/text、item id + tags、coords/itemconfig/delete/move、tag_bind 命中测试。
- [x] NBT/VM 联合恢复、形状命中与非法更新回归；318 个测试及完整构建通过。
- [x] 动态仪表盘、专用服务器和双客户端交互实测，记录每秒同步字节数。
- 完成标准:仪表盘示例(表盘 + 曲线)连续刷新时每秒同步字节数可接受(记录数值)。
- Python `Canvas` 支持五种图形的创建、整数 ID、多个 tags、coords 查询/修改、itemconfigure/itemconfig、itemcget、move、delete；补上 find_all/find_withtag、gettags、type、bbox、addtag_withtag/dtag、tag_raise/tag_lower 及别名。ID 单调增长，删除后不复用；有多个匹配对象时，coords 查询/修改取绘制顺序中的第一个，配置/移动/删除作用于全部匹配对象。非法批量更新先校验后写入。
- 图形选项包括 fill、outline、width、tags、normal/disabled/hidden；文本支持 text、多行换行和九向 anchor。空颜色为透明，矩形/椭圆坐标会归一化。服务端维护图形与命中，客户端绘制向量记录；凹多边形及自交多边形使用 LWJGL GLU 的 odd winding 镶嵌，折线采用圆接头/端点，椭圆用 96 段近似。字体仍为 OC unifont，外观不是原生 Tk 像素级复制。
- tag_bind/tag_unbind 支持 Button-1/3、ButtonRelease-1/3、Motion、B1-Motion、MouseWheel；回调按 all → tags 顺序 → current → item ID 分发，然后是 Canvas/root 控件绑定。按下后保存每位观看者的图形捕获，拖动越过其他图形仍交给原图形。Event.item 扩展字段给出命中 ID；Python 回调内 current 对应该事件，避免队列延迟或另一个玩家移动鼠标改变目标。解绑、删除 ID 对象和销毁 Canvas 会释放相应 Python 回调引用，普通 tag 的绑定保留以用于以后添加的对象。
- 协议升级为 `mpy4oc_tk7`：每个图形一条独立绘制记录，增量仅传更改的记录及绘制顺序；part ID 扩为 32 位，避免反复创建/删除 32,767 次后耗尽。客户端与服务器需同步更新。NBT 控件树版本为 3，继续读取版本 1/2；保存图形顺序、坐标、样式、tags、下一个 ID 和回调编号，鼠标捕获/current 不存档。
- 资源限制：每 Canvas 512 个对象，整树 1,024 个；每对象最多 256 个点，整树最多 32,768 个坐标数；坐标为有限 double，范围 ±32,768，笔宽 0..64（零按 1 像素画）；文本每对象 1 KiB、整树 Canvas 文本 16 KiB。每对象最多 16 个、每 tag 最长 64 个 Java 字符，每 Canvas 最多 256 个绑定。hidden 对象也计入预算；线宽、坐标、数量和网络载荷会校验。
- 当前子集没有 arc/image/window、滚动视口、平滑曲线/箭头/虚线、active/disabled 独立样式、文本编辑/自定义 font/旋转/自动折行、Enter/Leave 或 tag 键盘事件；highlightthickness 只接受 0。bbox 是近似像素包围框，itemconfigure 查询所有选项的模式未实现，可用 itemcget 查询单项。语义参考 [Tk Canvas](https://www.tcl-lang.org/man/tcl8.6/TkCmd/canvas.htm)，包括透明多边形仍命中内部、空心矩形/椭圆只命中轮廓等规则。
- `tkcanvas.py` 示例包含表盘、指针、80 点历史曲线、凹多边形和中文文字，每 100 ms 请求刷新；点击暂停区域暂停/恢复，点击曲线换色，暂停后可拖动指针。既有 MPYOS 安装需要更新磁盘上的 tkinter.py，并复制 tkcanvas.py。
- 双客户端结果（2026-10-07）：实际 Java 8 `runServer` + 两个 `runClient` 运行示例；TkOne 点击暂停，TkTwo 点击曲线变为粉色，TkOne 从表盘内拖动指针到 (220,240)。两个客户端最终同为 revision 459、28 条绘制记录，均记录 `SHAPES / TAG CLICK / CAPTURE DRAG / TWO VIEWERS PASS` 和 `PASS / CLOSED`；已查看运行中和两端最终截图。鼠标经真实 GUI 的按下/释放入口，释放会发送最后一次拖动；没有模拟物理鼠标轨迹。两次 runClient 正常退出，服务端确认 VIEWERS 0 后正常保存、stop。
- 带宽：预热 40 tick 后连续测量 200 tick，实际 9.996 秒，共 97 个增量包、182,056 字节，即每位观看者约 **18,206 B/s（17.78 KiB/s）**；两位合计约 36.4 KB/s。全量初始包 4,489 字节，更新最多改变 3 条记录（指针、数值、曲线）。使用同一 TkViewers.update/SceneSync.toBytes 编码测量服务端逐 tick 场景变化，包含正式协议字段，不含 Minecraft/FML/TCP 包装、压缩和入站鼠标/心跳；不是抓网卡得到的流量。该示例的请求目标为 10 Hz，本次实际 9.7 包/秒。
- 同一测量窗口 200 次 VM.step 合计 336.41 ms（平均 1.68 ms），最大 5.20 ms；初始化首个 VM.step 为 139.48 ms。只计 VM 执行及同步宿主调用，不含整个 Minecraft tick、包编码或客户端帧耗时；不能视为所有服/所有硬件上的性能保证。测量方式仍沿用真实 MpyArchitecture/OC Component.invoke/节点/VM、模拟 Machine 生命周期；实体 OC 机箱 BIOS 冷启动仍留在步骤 1 待办。
- 验证资料在忽略的 `build/tk-canvas-probe/`（探针、dual-client-server.log、两端日志），客户端 `tk-canvas-state.txt` 和 `screenshots/canvas-live-TkOne.png`、`canvas-TkOne.png` / `canvas-TkTwo.png`。实测后补上离开 Canvas 时清除 current、删除 item ID 绑定时释放引用、空 current 不会与用户 tag 冲突，相关自动回归及完整构建再通过；这三个收尾修改没有再次启动双客户端。
- 发布产物已核对 Tk 包的 89 个类均为 Java 8 字节码（major 52），包含 tkinter.py、tkcanvas.py 和 Tk 许可，不含临时探针/JUnit；318 个测试零失败。步骤 0～8 的源码仍未提交；下一步为步骤 9 方块表面的实时渲染。

### 9. 方块表面实时显示(TESR)
- [x] 同一显示列表在世界里按距离/频率降级渲染；支持单块隐藏与全局关闭。
- [x] 独立只读订阅、增量同步、带宽与纹理预算、离开范围/关闭/掉线清理。
- [x] 324 个测试、完整构建，以及十块动态屏幕的真实专用服务器 + 客户端性能与生命周期实测。
- 完成标准:10 块同屏帧率下降可接受(记录数值)。
- 实现（2026-10-07）：客户端复用 GUI 的显示列表、Canvas 图形和 OC unifont，在 FBO 中绘制并缓存纹理；TESR 在方块正面画一个保留宽高比的矩形。支持北/南/西/东四个朝向、正常深度遮挡和自发光显示。世界表面只读，右键后的 GUI 继续负责交互；服务端不生成像素图片。
- 距离以玩家至方块中心计算：8 格内纹理最长边 512 像素、最多每 2 tick 更新；8～16 格为 256 像素/4 tick；更远为 128 像素/10 tick。默认最远 32 格。静态场景不重复绘制；每客户端最多 32 个订阅，在已订阅屏幕中选择最近的 16 个保留纹理，每帧最多更新两张，按上次绘制时间轮换以避免低帧率时饿死后面的屏幕。
- 配置 `general.Tk_Surface_Enabled`（默认 true）和 `general.Tk_Surface_Distance`（默认 32，范围 8..64）同时提供中英文配置项。客户端关闭会释放纹理并取消订阅，服务端关闭会终止所有表面订阅；双方距离限制各自生效。单块 `setSurfaceVisible(false)` 同步空显示列表并释放纹理，不删除 GUI 控件。资源重载后重新准备字体和画面；FBO 不可用时跳过世界表面，GUI 仍可使用。
- 协议升级为 `mpy4oc_tk8`，两端需要同步更新。表面使用独立 WATCH/RESYNC/CLOSE 与客户端 nonce，不创建 GUI viewer；输入仍需要服务端签发的 GUI 会话。服务端校验连接、协议、维度、已加载方块实例/地址、距离，100 tick 无心跳到期，客户端每 40 tick 续订。增量复用 `TkDisplayPatch`，缺失基准会重同步；方块替换、卸载、切世界、离开范围或掉线会释放相应状态。NBT 格式仍为版本 3。
- 每客户端的表面包预算为 1 MiB 突发、每 tick 补充 16 KiB（20 TPS 时约 320 KiB/s），按实际 `SurfaceSync.toBytes` 长度计费；不含 FML/TCP 包装，也不限制独立 GUI 流量。超过预算保留原基准，随后发送最新变化。CLOSE 不受每 tick 8 次新请求/重同步限制，保证批量关闭不会遗留订阅。新增 6 个回归覆盖距离档位、四向几何、带宽预算、非法请求、过期会话/地址与全量恢复、隐藏时保留 GUI 控件。
- 十屏性能：同一真实 Java 8 `runServer` 上运行 10 个独立 Python `tkcanvas.py` VM，单个 `runClient` 在 854×480 窗口中观看；RTX 4090 D、NVIDIA 610.47、Angelica 2.1.16 默认渲染，无垂直同步和帧率限制。按“关闭 → 开启 → 关闭”测量，每段预热 3 秒后记录 8 秒；开启时近处十屏使用 512/256 像素档位，示例每 100 ms 请求刷新。

| 状态 | 帧数 | 平均 FPS | 平均帧间隔 | 渲染回调平均耗时 |
| --- | ---: | ---: | ---: | ---: |
| 关闭（前） | 5,812 | 726.4 | 1.377 ms | 0.250 ms |
| 开启十屏 | 5,897 | 737.0 | 1.357 ms | 0.378 ms |
| 关闭（后） | 6,178 | 772.2 | 1.295 ms | 0.223 ms |

- 开启时 FPS 落在两次关闭基线的波动范围内，不能据此声称提高帧率或给出可靠下降百分比；渲染回调均值增加约 0.13～0.16 ms/帧（合并基线约 +0.14 ms）。测量是客户端 RenderTick 回调间的 CPU 墙钟时间，不是 GPU 计时器；只证明该本机/场景下开销较小，未验证低配显卡、光影包或高延迟网络。
- 生命周期实测全部通过：十屏隐藏/恢复、约 20 格切为 128 像素、32 格外订阅清空、返回重建、四个朝向、服务端全局关/开、资源重载后恢复、GUI 鼠标点击触发实际 Python 暂停回调、客户端关闭后缓存清空。已查看四向、资源重载及 GUI 截图，未发现镜像/倒置或穿透前方方块。最终客户端记录 `PASS / CLOSED`，runClient 正常退出；服务器确认 `WATCHES=0 GUI_VIEWERS=0` 后正常保存、stop。
- 第一轮性能数据完整，但后续隐藏测试因临时探针没有覆盖专用服务器的命令权限入口而超时；修正探针后第二轮生命周期全部通过，发布源码无需为此修改。测试依旧使用真实 MpyArchitecture/OC Component.invoke/节点/编译器/VM，Machine 生命周期由探针模拟；实体 OC 机箱 BIOS 冷启动仍是步骤 1 的待办。本轮没有实测区块卸载/跨维度切换或物理右键，GUI 由正式 `openFor` 入口打开。
- 资料保存在忽略目录 `build/tk-surface-probe/`：`benchmark-first.txt`、`client-first.log`、`server-first.log`、`lifecycle-state.txt`、`client-lifecycle.log`、`server-lifecycle.log`；截图在 `build/tk-client-one-20261006/screenshots/surface-*.png`。首次失败和最终通过轨迹均保留。临时探针不进入发布 jar。
- 发布 jar 已核对 Tk 包 104 个类均为 Java 8 字节码（major 52），包含 tkinter.py、tkcanvas.py 和 Tk 许可，无临时探针/JUnit；324 个测试零失败/零跳过。步骤 0～9 的源码仍未提交；下一步为步骤 10 Text-lite。

### 10. Text-lite
- [x] 纯文本、字符/单词折行、`insert/delete/get` 支持 `行.列`/`end`/`insert`/`±Nc`、选区、滚动条、`state` 切换、按行增量同步。
- [x] 原生 Tk 编辑/索引对照、Python/VM/NBT 恢复、334 个自动测试与完整构建。
- [x] 真实专用服务器与双客户端验证日志控制台、编辑器、剪贴板和滚动条。
- 完成标准:`state=DISABLED` 日志控制台示例 + 简单编辑器可用。
- `Text` 的内容、逻辑行、折行后的显示行、插入光标、单一连续选区和滚动位置均在 Java 服务端维护；客户端只收到可见行。支持 `wrap='char'/'word'/'none'`，默认 char；Tab 保留在文本内，绘制为 8 个 ASCII 字符宽度的制表位。宽高使用字符/行单位，字体仍为 OC unifont。
- Python API：`insert/delete/get/index`、`mark_set('insert', ...)`、`tag_add/remove/ranges('sel', ...)`、`selection_get`、`compare`、`see`、`edit_modified`、`xview/yview` 及 moveto/scroll。索引支持 `行.列`、`行.end`、`end`、`insert`、`sel.first/last`、`@x,y`、`±N c/char/chars` 与 linestart/lineend；所有字符偏移按 Unicode 码点计，非 BMP 字符不会拆开。与旧 Tcl/Tk 8.6 的 UTF-16 索引在非 BMP 字符上可能不同。
- 保留 Tk 的永久末尾换行：空 Text 的 `get('1.0', 'end')` 返回 `\n`，读取用户正文通常使用 `end-1c`；在 end 插入会放在最后换行之前。删除完整末尾行时按 Tk 规则处理前一行分隔符。已有字数/行数/字节限额的更新先完整校验再修改，错误不会先删除选区或移动光标。
- 鼠标跨行拖选、越界自动滚动、Shift 扩选、Ctrl+A、方向键、Home/End、Ctrl+Home/End、PageUp/PageDown、按词移动/删除、Enter 换行和 Tab 插入均在 Java 侧执行；Ctrl+Tab/Shift+Tab 切焦点。禁用 Text 仍能选择、复制和滚动，输入、粘贴、剪切删除及 Python insert/delete 均不会修改内容。共享选区由最后按下的玩家接管。
- 协议升级为 `mpy4oc_tk9`，需要两端同步更新。每个可见显示行一条绘制记录，复用已有增量补丁；万行内容修改一个可见行的回归用例只发送一条变化记录。CopyResult 改为 32 位字节长度，可复制最多 512 KiB 的完整选区，并仍校验请求序号/观看会话；复制请求计入输入预算。粘贴仍沿用现有每次 4 KiB 的客户端截断规则，不拆 Unicode 码点。
- 每个 Text 限制 512 KiB UTF-8、262,144 个码点、20,000 个逻辑行（均包含末尾换行），整树 Text 总计最多 2 MiB。横向只同步可见文本；零宽字符密集时单显示行最多携带 512 个字形，正文保留完整。存档使用 UTF-8 字节数组，避免 NBT 单字符串长度限制；树 NBT 升为版本 4，继续读取版本 1～3，保存正文、光标、选区、视口、wrap、modified 标志及滚动回调 ID。
- 对照脚本 `tools/tk-text-golden.py` 使用本机 CPython / Tk 8.6.12 生成 `src/test/resources/tk-text-golden.json`：固定种子、100 组、1,200 次插入/删除，逐组比较正文及 13 种索引查询。包含中文、Tab、换行和越界偏移。另有非 BMP、边界原子性、跨行选择、只读保护、大文本压缩 NBT、长剪贴板和真实 Python 回调联合恢复测试。语义参考 [Tk Text 官方说明](https://www.tcl-lang.org/man/tcl8.6/TkCmd/text.htm)。
- 示例 `tktext.py` 包含共享编辑器、记录快照按钮、折行切换，以及分批写入 1,000 行的只读日志和双向滚动条。自动测试已验证日志输入、只读切换、读取编辑器和清空日志。既有 MPYOS 安装需更新磁盘上的 tkinter.py 并复制 tktext.py。
- 子集边界：没有富文本样式、任意 tag/mark、多个不连续选区、undo/redo/search、嵌入图片/窗口、自定义字体、IME 或完整 Tk 事件匹配；`edit_modified` 提供标志查询/设置，尚无 `<<Modified>>` 事件。sel 的 tag_add 设置一个连续选区，移除选区中段时只保留前段；不等价于完整 Tk 多范围 tag 系统。
- 双客户端结果（2026-10-07）：真实 Java 8 runServer + 两个 runClient 运行 tktext.py。TkOne 用 GUI 鼠标入口跨行拖选“第一行\n第二”，复制回包实际写入 Windows 剪贴板；中键从系统剪贴板读取“替换\n共享”并替换选区。点击记录快照触发 Python；TkTwo 继续在同一 Text 中换行、写入“第二位玩家”并再次记录。只读日志拒绝 Backspace 与粘贴，滚动条箭头经 Python 回调滚动日志，折行按钮切为 char。两端最终 revision 125、35 条绘制记录，均记录 `TWO CLIENTS / MULTILINE / WRAP PASS` 和 `PASS / CLOSED`。已查看初始、跨行选区及最终截图。
- 性能记录：从示例就绪起观察 200 tick（实际约 9.576 秒，含初始化当次计数共 201 个 VM.step），向同一虚拟观看者编码全量/增量共 45 包、52,171 字节；最多 36 条显示记录，一次更新最多改变 8 条。窗口包括灌入 1,000 行和随后静止阶段，不能当作持续灌日志的吞吐上限；不含 FML/TCP 包装、压缩或世界表面订阅。VM.step 总计 303.27 ms，最大 141.29 ms 即初始化首步；剩余 200 步合计约 161.98 ms。测量不含整个服务端 tick，初始化仍有尖峰。
- 实测边界：鼠标按下/释放（含释放前最后一次拖动）、中键粘贴、Enter 和按钮点击调用真实 GUI 入口；Ctrl+A/Ctrl+End、第二位玩家的粘贴和复制请求经正式网络包注入，未模拟物理键盘状态。沿用真实 MpyArchitecture/OC Component.invoke/网络/VM 加模拟 Machine 生命周期；实体 OC 机箱 BIOS 冷启动仍是步骤 1 待办。Text 的 VM/NBT 联合恢复和超过 65,535 字节的复制包由自动测试覆盖，本轮游戏内未再次恢复 VM 或执行大选区复制。
- 启动时 TkOne 在服务器就绪前连接、未能入服；保留 `client-one-early-connect.log` 后只重启了本轮 TkOne 测试进程，随后成功入服。功能测试一次通过，两个客户端正常退出，服务端确认 `VIEWERS 0` 后正常保存、stop；25566 已释放。
- 资料：忽略目录 `build/tk-text-probe/` 保存临时探针、`dual-client-server.log`、`client-one.log` / `client-two.log` 和双方状态文件；截图在各自运行目录的 `screenshots/text-*.png`。发布 jar 包含 tkinter.py/tktext.py/Tk 许可，Tk 包 107 个类均为 Java 8 major 52，不含探针/JUnit。334 个测试零失败/零跳过，包含 1,200 次原生 Tk 编辑对照。步骤 0～10 改动仍未提交；下一步是步骤 11 冻结模块与收尾。

### 11. 冻结 + 收尾
- [x] `tkinter.py` 移入 `stdlib/`,gen-stdlib 生成;`tkdemo` 留在 mpyos/bin。
- [x] 中英文 README 与 `docs/tkinter.md`；说明宿主 API、控件状态与旧 VM 快照的兼容策略。
- [x] 实体 OC 机箱裸 init.py 冷启动、GUI 交互及整个服务器保存重启验证。
- 完成标准:裸 `init.py`(不装 mpyos)也能 `import tkinter` 跑 demo;发布。
- 冻结结果（2026-10-07）：内置模块由 25 增至 26，旧 25 份字节码与 HEAD 逐字节一致；tkinter 为 18,879 字节 .mpy。删除 MPYOS 资源中的开发副本，唯一源文件为 `stdlib/tkinter.py`。生成器头注释更新为实际 `gradlew genStdlib` 命令。生成重复性与源码一致性由测试验证。
- 快照策略：VM 默认冻结库优先于磁盘，所以既有 `/lib/tkinter.py` 不会覆盖新启动的内置模块。旧运行快照保存原模块字节码，继续沿用旧代码；测试覆盖旧文件路径/不同字节码标记、按钮回调和变量的联合恢复，以及快照恢复后首次导入冻结库。冻结本身无需强制冷启动，想换用新库的既有应用才需重新开机。
- 实体冷启动补测发现并修正两个集成遗漏：同步 CPU ticker 在 OC 机箱关闭/暂停时仍会推进 VM（现在尊重 `isRunning/isPaused`）；Tk 信号重复带地址，而 OC `Machine.onMessage` 会自行插入源地址，导致真实机器静默丢弃回调。已修正 command/event/changed/input/clipboard 消息，Python 预期信号格式保持不变。过去模拟 Machine 的探针直接读取原始网络消息，漏测了这一层；不能把它们的结果当作完整机箱链路验证。
- 自动测试增加开关机/暂停保护与真实发送端的信号封装；按钮和排队控件回调经 `TileTkDisplay.emitCommand` 再模拟 OC 源地址注入，不再手工拼出预期元组。339 个测试零失败/零跳过，完整构建通过。
- 实体机箱结果：真实 Java 8 runServer + runClient，OC 1.12.50-GTNH 创造机箱、同步 MPY CPU 三级、RAM、MPYOS BIOS、只含 `init.py` 的 HDD，没有 MPYOS/lib/tkinter.py、显卡或 OC 键盘。直接邻接显示器，`component.list('tk_display')` 找到目标地址；BIOS 执行裸脚本，`import tkinter` 加载冻结库并显示 tkdemo。探针只装配硬件、开机、观察 VM、调用正式方块交互入口，不代替 Machine 调度或信号转发。
- 修正后的实测：GUI 鼠标按下/释放触发 Python 按钮，计数 0→1；根控件 `<Button-1>` 绑定改变窗口标题；输入框通过正式输入/剪贴板包写入 `frozen input 1`，Python StringVar 与关联 Label 同步。正常保存并完整停止服务器，再启动后计数仍为 1、正文仍在，没有再次打印 BIOS/裸 init 的启动标记；第二轮点击变为 2，绑定与输入联动再次通过。客户端两轮均 `PASS / CLOSED`、进程退出 0；两次关闭后 GUI viewers 均为 0，服务器正常保存退出。已查看两轮截图，中文及世界预览正常。
- 实测边界：该轮真实机箱使用同步 CPU、单客户端；异步调用回主线程、旧库快照及完整 Text/Canvas 状态继续由自动测试覆盖，之前的双客户端 GUI 测试记录保留。此轮没有重跑完整 Text 双人流程、跨维度或多屏 Toplevel。首次探针在玩家加载区块前超时、错误假设只有一块屏幕；随后一次识别按钮因临时 Java 探针文件被 Windows 默认编码改写而超时。已修正探针并保留失败轨迹，这些辅助文件不进入 jar。
- 证据位于忽略目录 `build/tk-freeze-probe/`：最终 `server-round-1.log` / `server-round-2.log`、`client-round-1.log` / `client-round-2.log`、`client-state-history.txt`、`artifact-check.json`，以及首轮失败日志和探针源码。截图为 `build/tk-client-one-20261006/screenshots/frozen-tk-input-round-1.png` / `frozen-tk-input-round-2.png`。日志沿用旧 ASM/ANTLR 扫描与 OC 文件线程池警告，未出现最终 VM 快照恢复错误。
- 本地发布包：`build/libs/mpy4oc-v0.0.1p6-beta-master.2+8d9d2762df-dirty.jar`，3,318,055 字节，SHA-256 `847faed9fe0e181ff054626032f7e5b44f6d24384110f0bb990aa965b6c0fc16`。107 个 Tk 类全部为 Java 8 major 52，包含冻结数据、五个示例和 Tk 许可，不含磁盘 tkinter 副本、测试类或探针。步骤 0～11 功能主线已完成；改动仍在工作区，尚未提交、推送或创建线上 release。

### 12. 审计加固（2026-10-08 Claude 复核后追加）
> 完整报告：`build/tk-audit-20261008.json`（8 个视角 + 查漏，每条发现经 1~2 个反驳者核验；53 条确认，去重约 43 条，11 条被驳回）。
> 复核属实：genStdlib 幂等、原 25 模块与 HEAD 逐字节一致；`gradlew build` 通过、JUnit 350/0/0/0；发布 jar 的 Tk 类全为 major 52；实测截图与日志真实存在。arch tick 守卫正确（还修了不尊重 OC 读档后 pause 的旧 bug）；toPy 的 Scala Map 分支只影响原先会退化成字符串的值；CI 能跑测试，release 流程不受影响；Tk 许可证合规。

阻断级：
- [ ] 绘制预算溢出只在 `render()` 检查，异常逃出 TE tick → **服务器崩溃**（或 TE 被删）；`writeToNBT` 里同样抛出 → 该 TE 连同 OC 地址从区块存档丢失。改为变更时校验/回滚，`render()` 降级而非抛出，tick/保存调用点加 try/catch。（`TileTkDisplay.java:41`、`TkTree.java:77`）
- [ ] GUI 出站流量无字节预算：右键、Resync（每 tick 可 32 次全量场景，可达 267 KB）、CopyResult（可达 512 KiB）→ 单客户端可让服务器每秒发上百 MB，积压至 OOM。复用 `TkSurfaceBudget` 计费；Resync 每 20 tick 至多一次；`openFor` 重用新近会话。（`TileTkDisplay.java:157`、`TkNetwork.java:123`）

严重级：
- [ ] 全局 2048 槽请求队列入队前不按连接限流 → 一个客户端挤掉所有人的心跳/表面续租；丢失的 Resync 不重试致 GUI 冻结。（`TkNetwork.java:135`，三个视角独立发现）
- [ ] 输入预算不计工作量：Entry 粘贴 O(n²)、Text 编辑 O(n)，单观看者每 tick 80~130 ms。单遍计算可接受前缀、TkTree 缓存文本字节总数、Text 增量更新。（`Entry.java:173`）
- [ ] NBT 加载拒绝运行时会产生的状态（Listbox top/scrollX 未钳制、焦点在被裁掉的控件上），一个字段失败即丢弃整棵树和令牌 → 读档后程序失去显示器。设置时钳制、焦点/视口尽力恢复、保存前 `refreshWidgets()`。（`TkControlPersistence.java:40`、`TkTreePersistence.java:141`）
- [ ] 不检查 OC 电脑用户列表：OC 屏幕/键盘用 `computer.checked_signal` + `canInteract`，Tk 用普通 signal 且改控件前不校验 → 非授权玩家可操作别人电脑的界面。（`TileTkDisplay.java:232`）
- [ ] Canvas 多边形客户端细分无上限：合法场景可让渲染线程卡约 7 s，表面渲染波及路人。服务端限制自交数/总顶点，客户端编译成显示列表。（`TkVectorRenderer.java:162`）
- [ ] 残留根窗口：MPYOS 命令异常退出未 destroy 时 `_roots` 留在 VM 模块缓存，之后 `Tk()` 一律 "no tk display" 直到重启；示例无退出路径。shell/REPL 结束时释放，或允许接管不在 mainloop 中的根。（`stdlib/tkinter.py:521`）
- [ ] AltGr / Option 输入被丢（@ { } [ ] \ | ~ € 等）：Entry/Text 把修饰位 4 当 Alt 直接丢弃。（`Entry.java:142`、`Text.java:195`）
- [ ] 无 variable 的 Scale 在任何 `configure()` 后丢失小数（私有变量是 IntVar）。（`stdlib/tkinter.py:800,818`）
- [ ] `tk_input`/`tk_clipboard` 原始信号无人消费，堆积进 VM 快照（约 890 KB）且不计内存限额。（`TkHostApi.java:41`）

兼容性（轻微级，但影响发布说明）：
- [ ] 新注册方块 → p6 及更早客户端**完全无法进入**本版服务器（FML 注册表同步 "Fatally missing blocks and items"），与是否使用 Tk 无关；`docs/tkinter.md:114` 与 MyMod 注释写成"只是不能用 Tk GUI"，需更正，发布说明写明所有人须更新。

轻微级（约 25 条，详见报告）：bind 的 add/unbind 语义、keysym、禁用控件不触发绑定、点击抢焦点、Toplevel 的 after id 冲突、`Listbox.get` 超 256 行报错、颜色名只认 8 个、数字 `text=`/元组 `sticky`、无 variable 的 Radiobutton 不互斥、Tab 顺序、文本禁 Tab、计时器精度与顺序；坏包每次打 WARN 堆栈、字形图集无限增长、41 格外 z-fighting、FBO 创建失败泄漏、区块 NBT 超 1 MiB 会被原版静默放弃保存；test 任务不感知 `stdlib/` 改动、golden JSON 为 CRLF、GenStdlib 单字符串常量 64 KB 上限（tkinter 已用 38%）；`docs/tkinter.md:63` 写了 grid `uniform` 但未实现。

- 完成标准：阻断与严重级全部修复并各有回归测试；双客户端复测一轮；然后提交（作者按仓库历史）。

## 后续可选
- Toplevel 多屏实测、messagebox/simpledialog、Menu、完整 Text(tag/mark/undo/search)、ttk 别名。
