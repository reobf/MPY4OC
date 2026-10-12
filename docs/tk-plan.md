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
- [x] 绘制预算溢出只在 `render()` 检查，异常逃出 TE tick → **服务器崩溃**（或 TE 被删）；`writeToNBT` 里同样抛出 → 该 TE 连同 OC 地址从区块存档丢失。改为变更时校验/回滚，`render()` 降级而非抛出，tick/保存调用点加 try/catch。（`TileTkDisplay.java:41`、`TkTree.java:77`）
- [x] GUI 出站流量无字节预算：右键、Resync（每 tick 可 32 次全量场景，可达 267 KB）、CopyResult（可达 512 KiB）→ 单客户端可让服务器每秒发上百 MB，积压至 OOM。复用 `TkSurfaceBudget` 计费；Resync 每 20 tick 至多一次；`openFor` 重用新近会话。（`TileTkDisplay.java:157`、`TkNetwork.java:123`）

严重级：
- [x] 全局 2048 槽请求队列入队前不按连接限流 → 一个客户端挤掉所有人的心跳/表面续租；丢失的 Resync 不重试致 GUI 冻结。（`TkNetwork.java:135`，三个视角独立发现）
- [x] 输入预算不计工作量：Entry 粘贴 O(n²)、Text 编辑 O(n)，单观看者每 tick 80~130 ms。单遍计算可接受前缀、TkTree 缓存文本字节总数、Text 增量更新。（`Entry.java:173`）
- [x] NBT 加载拒绝运行时会产生的状态（Listbox top/scrollX 未钳制、焦点在被裁掉的控件上），一个字段失败即丢弃整棵树和令牌 → 读档后程序失去显示器。设置时钳制、焦点/视口尽力恢复、保存前 `refreshWidgets()`。（`TkControlPersistence.java:40`、`TkTreePersistence.java:141`）
- [x] 不检查 OC 电脑用户列表：OC 屏幕/键盘用 `computer.checked_signal` + `canInteract`，Tk 用普通 signal 且改控件前不校验 → 非授权玩家可操作别人电脑的界面。（`TileTkDisplay.java:232`）
- [x] Canvas 多边形客户端细分无上限：合法场景可让渲染线程卡约 7 s，表面渲染波及路人。服务端限制自交数/总顶点，客户端编译成显示列表。（`TkVectorRenderer.java:162`）
- [x] 残留根窗口：MPYOS 命令异常退出未 destroy 时 `_roots` 留在 VM 模块缓存，之后 `Tk()` 一律 "no tk display" 直到重启；示例无退出路径。shell/REPL 结束时释放，或允许接管不在 mainloop 中的根。（`stdlib/tkinter.py:521`）
- [x] AltGr / Option 输入被丢（@ { } [ ] \ | ~ € 等）：Entry/Text 把修饰位 4 当 Alt 直接丢弃。（`Entry.java:142`、`Text.java:195`）
- [x] 无 variable 的 Scale 在任何 `configure()` 后丢失小数（私有变量是 IntVar）。（`stdlib/tkinter.py:800,818`）
- [x] `tk_input`/`tk_clipboard` 原始信号无人消费，堆积进 VM 快照（约 890 KB）且不计内存限额。（`TkHostApi.java:41`）

兼容性（轻微级，但影响发布说明）：
- [x] 新注册方块 → p6 及更早客户端**完全无法进入**本版服务器（FML 注册表同步 "Fatally missing blocks and items"），与是否使用 Tk 无关；`docs/tkinter.md:114` 与 MyMod 注释写成"只是不能用 Tk GUI"，需更正，发布说明写明所有人须更新。

轻微级（约 25 条，详见报告）：bind 的 add/unbind 语义、keysym、禁用控件不触发绑定、点击抢焦点、Toplevel 的 after id 冲突、`Listbox.get` 超 256 行报错、颜色名只认 8 个、数字 `text=`/元组 `sticky`、无 variable 的 Radiobutton 不互斥、Tab 顺序、文本禁 Tab、计时器精度与顺序；坏包每次打 WARN 堆栈、字形图集无限增长、41 格外 z-fighting、FBO 创建失败泄漏、区块 NBT 超 1 MiB 会被原版静默放弃保存；test 任务不感知 `stdlib/` 改动、golden JSON 为 CRLF、GenStdlib 单字符串常量 64 KB 上限（tkinter 已用 38%）；`docs/tkinter.md:63` 写了 grid `uniform` 但未实现。

- 完成标准：阻断与严重级全部修复并各有回归测试；双客户端复测一轮；然后提交（作者按仓库历史）。
- 结果（2026-10-08，Claude）：快照提交 `2a61af3` 之后修复，360 个 JUnit 全过（新增 `TkHardeningTest` 7 个、`TkPythonTest` 3 个）。
  - 渲染：`TkTree.render()` 永不抛出；超预算按绘制顺序截断（`overflowed()`），其他异常保留上一帧。TE tick、`writeToNBT`、服务端 tick 循环各自兜底；读档捕获 `RuntimeException`（含 ReportedException）只丢控件树、不丢 TE。
  - 出站：GUI 流量（OpenGui/SceneSync/CopyResult）按编码字节走每连接预算（1 MiB 突发、16 KiB/tick）；增量只有真正发出才确认（`Viewer.prepare/sent`），超额下 tick 合并重发；每连接每 10 tick 至多开一次 GUI；Resync 改为标记全量、每 20 tick 至多一次；客户端每 2 秒重试未回应的 Resync。
  - 入站：每连接最多排队 64 个请求（关闭请求另有 8 个预留）；文本编辑类输入（可打印字符/退格/删除/回车/Tab/粘贴/剪切）令牌桶限速，平均 4 次/tick、突发 8 次；Entry 粘贴改单遍；Text 粘贴过滤控制字符并只插入放得下的部分。
  - 权限：输入、粘贴、复制要求占用该屏的电脑 `canInteract(player)`；玩家引起的 `tk_command/tk_event/tk_changed` 改用 `computer.checked_signal`；去掉无人消费的 `tk_input/tk_clipboard`。
  - 存档：Listbox 视图在设置时钳制（`clampScroll`），加载时钳制而非拒绝；焦点恢复失败只丢焦点；保存前按保存尺寸重新布局。
  - Canvas：填充多边形自交上限 128、整树 512（`TkVector.crossings()` 缓存于不可变向量）。
  - Python：`destroy()` 在显示器失效时也清理本地状态；新增 `tkinter._release_all()`，MPYOS 命令结束时调用（网络里没有 Tk 显示器则不导入）；Scale 私有变量改 DoubleVar；after id 全局唯一、`after_cancel` 跨窗口查找；`bind/tag_bind` 的 add 任意真值即追加；`Listbox.get(a, b)` 分批读取。AltGr/Option 字符可键入。
  - 文档：删去 grid `uniform`；写明旧客户端（p6 及更早）完全无法进入新服务器、权限规则、粘贴过滤、多边形与流量限制、MPYOS 命令结束自动关窗。
  - 仍未做：Text 每次编辑仍是 O(n)（靠每 tick 4 次上限约束）；其余轻微级（keysym、禁用控件绑定、点击抢焦点、颜色名、uniform 实现、`unbind(seq, funcid)` 对追加绑定无效、z-fighting、字形图集增长、FBO 失败泄漏、区块 NBT 超 1 MiB、golden CRLF、GenStdlib 64 KB 常量）。
- 复核（2026-10-08，第二轮对抗审查 4 视角 + 查漏）：确认 3 条已修——关闭请求无限绕过队列上限（改为 8 个预留）、自交计数把接触算作交叉（改为只计真正交叉，基线面积图不再被拒）、客户端仍把 AltGr+V 当粘贴；另修轻微：剪切改为回包发出后再删除、编辑上限改令牌桶且只计改文本的键、超预算时不重复编码、连接不可写时暂停发送（背压）、服务端兜底改为关闭出错显示器的 GUI 而非每 tick 刷日志、读档时关闭在线 GUI 且场景版本单调递增、保存时只布局不发布场景、粘贴/剪切后立即发出其排队的视图回调、Windows 左 Alt+字母不再打字、tkinter 通过 `computer._tk_release` 挂钩让 shell 无需导入即可关窗；新增权限判断单测。完整报告：`build/tk-review-hardening-20261008.json`。
- 新功能（2026-10-08，用户要求）：GUI 标题栏右上角 × 关闭按钮——发 `WindowClose` 包（协议升为 `mpy4oc_tk10`），有权操作且屏上有程序时向程序发 `tk_close`（tkinter `_delete_window`：有 `protocol('WM_DELETE_WINDOW', f)` 调 f，否则 `destroy()`），否则只关自己的 GUI；程序 `release` 显示器时关闭所有观看者的 GUI。新增 `Tk.protocol/wm_protocol`；363 个测试通过。
- [ ] 双客户端游戏内复测（权限、限流、粘贴、AltGr、读档、× 按钮）后提交。

### 13. 多方块拼接显示器（扳手）+ 分辨率对话框（2026-10-08 用户需求）
需求（用户原话要点）：
- 扳手**点一下**拼接：点正面靠近哪条边，就和那一侧相邻的屏/屏组拼；AB 拼、CD 拼，再 AB+CD（同宽且对齐）拼成 2×2。**Shift+扳手**：整组拆回 1×1。
- 手持扳手时有**视觉指示**（看向显示器正面时画出将要拼接的边：能拼绿、不能拼红；Shift 时提示拆分）。兼容 GT 及其他 mod 扳手。
- **分辨率默认永远 320×240**；**上限随尺寸**：每块提供 320×320（1×1 上限 320×320，w×h 组上限 320w×320h）。
- **空手 Shift+右键**弹出 **MUI2**（ModularUI2）对话框改分辨率；API 也提供改/查分辨率（含上限）。
设计决定：
- 被点的那块所在屏组保留程序与地址（它的主屏仍是主屏）；被并进来的屏组上的程序被释放、GUI 关闭。主屏不必在左上角，成员记录主屏绝对坐标 + 组矩形（以主屏为原点的 u 右 / v 下）。
- 规则：同朝向、同平面、拼后为矩形（左右拼行数相同且对齐；上下拼列数相同且对齐）；组最大 8×8（分辨率上限 2560）。
- 从属屏组件 `setVisibility(None)`（不出现在 component.list、仍可连通网络）；拆分时恢复。敲掉组内任一块：整组拆开（主屏保留状态）。
- 右键组内任意一块打开主屏 GUI；距离按组内最近的方块算。世界表面由主屏 TESR 画一张跨整组的图（保持宽高比），从属屏不画；渲染包围盒覆盖整组。组信息通过 TE 描述包同步到客户端。
- 扳手识别：先查矿物词典 `craftingToolWrench`（GT 扳手按 damage 精确登记），再查 OC 扳手接口 `li.cil.oc.api.internal.Wrench` 与 OC 的 `integration.util.Wrench.isWrench`（BC/CoFH/AE2/EnderIO 等）；GT 的其他工具（锤子等同一物品类，也实现扳手接口）若不在扳手词典里则不算扳手。
- 画布硬上限 1024 → 2560：TkScene.validSize、MAX_SIZE、geometry 上限、TkDisplayList 裁剪框/选区/光标、TkPointerInput 截断、InputEvent 坐标校验。单块上限变 320×320，旧存档超限读档时收紧。
- MUI2 依赖 `com.github.GTNewHorizons:ModularUI2:2.3.92-1.7.10:dev`（缓存已有，传递依赖齐），`required-after:modularui2`；TileTkDisplay 实现 `IGuiHolder<PosGuiData>`。改分辨率权限：屏上有程序时需 canControl，否则范围内任何人。
- API：OC 回调 `getMaxResolution()`；tkinter `root.maxsize()` 返回上限，`geometry()` 受上限约束。
- [x] A 组模型 + 持久化 + 描述包同步 + 几何（朝向→u/v）纯逻辑与单测
- [x] B 扳手识别工具类
- [x] C 方块交互：扳手拼接/Shift 拆分、右键路由到主屏、空手 Shift 开 MUI2；破坏方块拆组
- [x] D 组件可见性、被吞并屏组释放程序
- [x] E 分辨率上限（每块 320×320）+ 2560 硬上限放开
- [x] F 世界表面跨组渲染
- [x] G 扳手视觉指示
- [x] H MUI2 对话框 + 依赖
- [x] I API（getMaxResolution / maxsize）+ 文档
- [ ] J 测试、构建、游戏内验证、提交（368 个测试通过；自动化游戏内验证通过，见下；待提交）

实现记录（2026-10-08）：
- 扳手点击不走原版交互：客户端 `TkWrenchClient` 在 `PlayerInteractEvent` 里取消对显示器的扳手右键（以及随后的“对空气使用物品”），从 `objectMouseOver.hitVec` 算出边，发 `TkPackets.Wrench`（x, y, z, 边序号或 SPLIT）。这样任何扳手的 sneak 绕过、`onItemUseFirst`、旋转逻辑都不会介入。服务端 `TkWrenchEvents` 也取消漏过来的原版扳手点击。服务端处理：每人每 4 tick 一次、8 格内、手持扳手、权限（`mayReconfigure`：屏上无活程序则任何人，否则 canControl），结果用聊天提示（lang 键 `mpy4oc.tk.merged/split/merge.*/notPermitted`）。扳手点正面中心或侧面不做事。
- 协议升为 `mpy4oc_tk11`（新增 Wrench 包，id 14）。
- `TkGroups.plan` 两端共用：客户端画绿框/红线，服务端执行前同样校验。主屏每 40 tick 检查已加载成员是否仍跟随自己，不一致则整组拆开（处理主屏区块未加载时成员被敲掉）；从属屏同样每 40 tick 检查主屏。
- 演示程序：`tkcanvas.py` 改为 520×320、`tklayout.py` 宽档改为 500×320，二者与 `tklog.py` 需要 2×1；`tktext.py` 仍为 560×420，需要 2×2。不够大时 `RuntimeError` 说明用扳手拼接。测试用 `TkTestGroups.joined(w, h)` 造组。
- 自动化游戏内验证（2026-10-09）：临时探针 `build/tk-group-probe/`（不进发布 jar）+ 独立运行目录 `build/tk-group-server/`（端口 25567、超平坦、和平）与 `build/tk-group-client/`（1280×720、guiScale 2）。服务端在玩家登录时摆好 A B / C D（朝北）、B 右侧单块 E、A 左侧朝东的 F，并发放 OC 扳手、AE2 赛特斯石英扳手、AE2 网络工具、木棍；玩家离开即停服。客户端按步骤设视角后调用 Minecraft 自己的右键方法 `func_147121_ag`（Forge 事件、TkWrenchClient、原版方块交互、MUI2 工厂都按真人路径走），潜行用替换的 MovementInput，MUI2 按钮/输入框用 MUI2 自身的命中测试与同步包。`tkcanvas.py` 由探针内的模拟电脑 Tap（真实 MpyArchitecture/VM/冻结 tkinter）运行。
- 结果：两轮全新世界均 33/33 通过（`tk-group-client/tk-group-state.txt`），runClient/runServer 正常退出。覆盖：三种扳手识别、木棍不是扳手；OC 扳手拼 AB、AE2 扳手拼 CD、点 AB 下边缘得 2×2 且 A 保持主屏、C 被吞并；四种拒绝（形状/朝向/下方无屏/同组）的聊天提示；2×2 上运行 tkcanvas.py，表面只由主屏画、纹理 1024；木棍右键从属屏 D 打开主屏 A 的 GUI；空手潜行右键 B 打开分辨率对话框，显示 520×320，“最大”得 640×640，输入宽 600 同步到服务端（600×640）；潜行+扳手整组拆开、分辨率收紧到 320×320；敲掉 B 拆开 A+B；AE2 网络工具拼 CD。
- 已查看截图（`tk-group-client/screenshots/group-*.png`）：瞄边缘时绿框为拼后范围、拒绝时红线在对应边、同组无颜色、潜行时红框加网格；画布跨整组且保持宽高比；拆分后 A 只显示 320×320 裁剪画面。块内细黑线是原版选择框，不是渲染问题。
- 探针发现并已修正两处：对话框的 `IntSyncValue` 缺 `.allowC2S()`，输入框改的值根本发不到服务端（MUI2 默认禁止客户端到服务端同步）；对话框是 GuiContainer，NEI 物品栏会盖在旁边，现用 `settings.getRecipeViewerSettings().disable()` 关掉。
- 未覆盖：GregTech 扳手（开发环境没有 GT，矿辞分支只由代码审查保证）；无权限玩家被拒绝（Tap 的 canInteract 恒真）；真实 OC 机箱（用 Tap 代替）；多人同时观看；区块卸载时主屏/成员的自愈。

## 14. 桌面与可拖动窗口（第一档：单程序、服务端窗口管理）

需求（2026-10-09，用户）：实现桌面 + 可拖动 Tk 窗口。按此前评估的第一档做：`Tk()` 的整块屏是桌面，`Toplevel()` 在同一块屏上开窗口；客户端与协议不改。

实现：
- `TkWindow`：窗口状态（标题、上次焦点、withdraw 前的位置）与几何（标题栏 18 px、× 18 px、1 px 边、装饰至少 42 px 宽、夹位置保留 24 px）。窗口是根下的 Frame，用规范的 place（x/y = 内容区原点、NW、bordermode ignore）定位；装饰作为 part 1/2/3 记录画在内容区之外，世界表面自然显示。
- `TkTree`：根的子控件中窗口总在最后（`Frame.adopt`/`restack`）；命中测试先判窗口装饰；按下任意处置顶；标题栏/边拖动（移动捕获，不产生程序事件）；× 按下/松开在同一处才产生关闭请求；每窗口记上次焦点、点击窗口恢复焦点、`focus_set()` 置顶；Tab 只在当前窗口内循环；渲染时把越界窗口夹回；超预算时按层裁剪，先丢最下层窗口。
- `TileTkDisplay.input`：装饰上的点击/移动/滚轮/右键不给程序；没有焦点时按键给最上层窗口；绑定冒泡到所在窗口（Tk bindtags）；× 发 `tk_close`，widget 为窗口 id。
- API：`create('window')`、`wm(geometry/title/lift/lower/withdraw/deiconify/state)`、布局命令 `lift/lower`；`place_slaves` 不含窗口；窗口不能 pack/grid/place/forget。
- 存档 v5：每个 Frame 存叠放顺序 `stack`，窗口存 `window` 复合标签；只接受规范的窗口位置；读 v1～5。
- tkinter：同屏 `Toplevel`（`screen=` 指向别的屏或旧的位置参数时保持旧行为，且不再先占一个默认屏）；`_parse_geometry`（`WxH`、`±X±Y`、`''` 恢复自然大小、严格的分隔符）；`lift/lower` 检查同屏；销毁控件时连同其下任意深度的 Toplevel；`tk_close` 按窗口分发。演示 `/bin/tkdesktop.py`。

审查与修正（2026-10-09）：四个视角各一审查 + 逐条反驳验证（`build/` 外的工作流记录），16 条全部属实（含 2 对重复）。已修：无焦点按键到不了活动窗口（焦点 0 被当成根）；嵌套 Toplevel 销毁后服务端残留；`Toplevel(screen=)` 先占默认屏；右键松开在装饰上漏给程序；`geometry` 校验前就改了位置；存档可带非规范窗口位置（还可能让拖动抛异常）；`focus_set()` 对窗口报错；过窄窗口标题栏全是 ×；位置参数 `screen`；宽松/错误的 geometry 字符串；`winfo_y`；跨屏 `lift`。

VM 缺陷（游戏内发现）：`callable()` 对闭包返回 False，函数里写的 `command=lambda: ...` 全被拒绝（影响所有 tkinter 程序）；`Builtins.isCallable` 已加 `Closure`，新增 `CallableTest`。

GT 扳手（静态核对 GT5-Unofficial 5.09.54.205，GT 不在开发运行时）：扳手 meta 16 与充电的 LV/MV/HV 电动扳手（120/122/124）以精确 meta 登记在 `craftingToolWrench`，矿辞查找忽略 NBT，能识别；放电（奇数 meta）不识别，与 GT 自己一致。修正：GT++（角磨机、剪线钳）与 Detrav 探测器是 `MetaGeneratedTool` 的子类、在 `gregtech.` 包外，原先经 OC 的 IAEWrench 检查被误判为扳手，现在沿父类链排除；GT 工具箱按选中格里的工具判断（与 GT 机器一致）。

验证：
- JUnit 382 个通过（新增 `TkWindowTest` 11 个、Python 层 2 个、`CallableTest`）。
- 游戏内自动探针 `build/tk-desktop-probe/`（独立目录 `build/tk-desktop-server/`、`build/tk-desktop-client/`，端口 25568）：真实右键打开 GUI、GUI 自身的点击入口、标题栏拖动用 GUI 同样的 InputEvent 包（GUI 每帧采样真实光标，无人值守时无法驱动）。12/12 通过：两个窗口与装饰、新窗口在上且激活、geometry 位置、点击置顶、拖动 (+80,+60) 与服务端一致、甩出屏外保留 24 px、窗口内按钮回调、× 经 protocol 关闭、桌面按钮新建窗口、无焦点按键到达活动窗口绑定。截图 `build/tk-desktop-client/screenshots/desktop-*.png` 已查看。多块拼接探针回归 33/33。
- 未覆盖：真实鼠标拖动手感（每秒 20 次）、多人同时拖、玩家改窗口大小（不支持）、第二/三档。

## 15. 图片（PhotoImage）

需求（2026-10-09，用户）：图片控件，能直接读 OC 文件系统里的 jpg/png，并支持 `fit=` 缩放。

设计：
- 模型：每块屏（TkTree）一个图片表，`TkImage` = id、宽高、ARGB 像素、版本号。上限：单张每边 ≤ 1024，每块屏最多 64 张、合计 ≤ 524,288 像素。id 不复用；程序释放屏幕时清空。
- 解码（服务端，Java 自带 ImageIO）：PNG/JPEG/GIF（首帧）/BMP；输入 ≤ 1 MiB；先只读文件头的尺寸。`fit=(w, h)` 按比例缩小到框内、不放大：解码时先整数倍隔行采样，再用盒式滤波缩到目标尺寸，大照片不会完整解码。不带 fit 且超过 1024 时报错并提示 fit。
- Python：`PhotoImage(file=, data=, width=, height=, fit=)`、`width/height/put/get/blank/copy/zoom/subsample/transparency_get/set`、`delete()`（本 VM 没有 `__del__`，需显式释放）；`Label/Button(image=)`（有图时显示图片、不显示文字，与 Tk 默认一致）；`Canvas.create_image(x, y, image=, anchor=)`。`file=` 先走 MPYOS 的 `osfs`（挂载点、当前目录），裸机时走启动盘，没有启动地址就找含该文件的文件系统。
- 绘制记录：Item 增加 image 字段；Label/Button 记录用 textX/textY 作图片左上角；Canvas 图片项是新形状 IMAGE（两个坐标）。场景增加图片表（id → 版本、宽高），掩码位 IMAGES。
- 传输：像素用自定义紧凑格式（RGBA 按行差分 + Deflate）分块（≤ 32 KiB）发送，GUI 和方块表面各自按带宽预算发送，客户端按 (id, 版本) 拼装、上传纹理；拼装出错时请求全量重同步。协议升到 `mpy4oc_tk12`。
- 存档：图片像素不进区块 NBT（照片很容易超过区块 1 MiB 上限导致整块数据丢失），存到世界目录 `mpy4oc/tk-images/<每块屏的随机 id>/<图片 id>.tki`，NBT 只存元数据；只写有变化的；保存时删除已不用的文件；方块被破坏时删除整个目录。文件丢失时载入为同尺寸透明图并记日志。

审查（2026-10-09，4 个方面各一轮查找 + 一轮核实；34 条里 30 条成立，4 条不成立：put 越界报错是有意的、`python /bin/tkimage.py` 用 `args` 是所有命令的惯例、读取器白名单与 MIME base64 只算加固）。已全部修复：
- 存档会丢整个界面：Label/Button 指向已 delete 的图片、Canvas 图片项没有图片（`create_image(x, y)`）或图片已删，重新载入时 `TkTreePersistence`/`TkCanvasPersistence` 报错，`readFromNBT` 于是换成空树。现在 `deleteImage` 会清掉所有引用（与 Tk 一样控件变空白）；载入时找不到的图片当作无图；`configure` 只在本次设置 image 时才检查图片是否存在。
- MPYOS 下 `file=` 超过 128 KiB 就 MemoryError（`fs.read_bytes` 用 `+=`，沙箱单次分配上限 = 1 MiB/8 个元素），裸机路径也要把整个文件放进 1 MiB 的 VM。现在 `file=` 边读边发：每 32 KiB 一次 `image upload`（偏移必须连续），最后 `create(upload=总长)`；Python 端只持有一块。MPYOS 用 `vfs._route` 找到磁盘地址后走同一条读循环。
- 解码：每个轴各自采样（极扁的图以前另一个轴几乎不采样）；源图上限降到 1600 万像素、每边 16384；按文件头魔数只认 4 种格式，并固定用 JDK 自带读取器；PNG 只保留 IHDR/PLTE/tRNS/IDAT/IEND（zTXt、iCCP 等可在读取器里膨胀）；JPEG 扫描段 > 32 拒绝；BMP 只收不压缩的（RLE、内嵌 JPEG/PNG 会按文件头分配内存）；内存缓存输入流替代临时文件；OOM/栈溢出转成 Python 可捕获的错误；8/16 位灰度直接取样本值（getRGB 会按线性灰度提亮，128 变 188）；解码前先按文件头算出结果尺寸，查本屏剩余像素额度（满了不再白白解码）；`fit` 超过 1024 的框按 1024 处理。
- 传输：打包改成后台线程 + Deflate 最快档（724×724 的图在服务端线程上 Deflate 6 要 0.2~0.5 s，每次改图都要重打包）；≤ 16384 像素的小图仍当场打包；同一张大图每 200 ms 至多重打包一次。正在发送的版本发完再发新版本（以前每次改图都从 0 重来，一直在改的图永远到不了）；多张图轮流发；只发客户端已有场景里的图片。客户端按 id 保留最新一份完整纹理直到更新的到达，只收当前图片表里、尺寸相符的块，纹理数不超过表的大小。
- 换程序（claim）时新树接着用旧的图片 id，不再从 1 开始；传输记录的是图片对象本身，同 id 的另一张图会重发（以前方块表面会一直显示上个程序的图）。
- 方块表面：先发场景同步再用剩余额度发图片；多块屏轮流排第一；通道不可写时不发（与 GUI 的 `trySend` 一样）；某个租约出异常只结束这一个。图片的 alpha 改为"叠加"混合，表面渲染关闭 alpha 测试（透明像素以前会在表面上打洞）。
- 存图目录：复制出来的屏（WorldEdit、原理图）NBT 里是同一个目录名。现在 `TkImageStores` 记录每个目录由哪块已加载的屏使用；别处的屏再认领时换一个新目录并把所有图片重写过去（同一位置重新载入的屏不算）。方块被破坏后目录延迟 100 tick 删除，期间被认领（移动）就不删；服务器停止时未到期的不删。
- 读文件失败（IOException）时这张图当作已保存，不会用空白图覆盖原文件；目录里的 `9999999999.tki` 之类不再让保存抛异常；读文件只读普通文件且有长度上限。
- Python：自动名字用全局计数（各屏不重名）；同名新建会替换旧图；`tkimage` 演示把框限制在 1024 与本屏像素额度内。

验证（2026-10-09）：
- 单元测试 402 个全部通过（TkImageTest 18 个，含灰度、扁图、BMP/JPEG/PNG 加固、额度、分块上传、删除后存档、换树重发、存图目录分叉；TkPythonTest 新增 MPYOS 路径下按游戏内内存上限读一个超过 160 KB 的文件）。
- 游戏内图片探针：新世界创建 5/5、重新载入 5/5（照片 1600×1200 JPEG `fit=(200,150)`、48×48 透明 PNG、一个图片已删除的 Label、一个无图的 Canvas 图片项；载入后界面完整，`tk-images` 里只剩 2 个文件）。回归：桌面探针 12/12、多方块探针 33/33。

## 16. 进度条与 Canvas 滚动

需求（2026-10-09，用户）：加进度条和 Canvas 滚动。

设计：
- `ttk.Progressbar`：新控件种类 PROGRESSBAR（枚举末尾追加）。服务端存 orient/length/mode/maximum/value；绘制为槽（part 0）+ 进度块（part 1，颜色取 foreground）；定量按 value/maximum 填充，竖向从下往上；不定量是宽为槽 1/5（至少 10 px）的色块，value 每增加一个 maximum 走一趟、两趟一个来回。`step()` 在服务端执行，到 maximum 取模回到开头（Tk 行为）。`start(interval)`/`stop()` 用 Python 计时器（与 Tk 用 Tcl 计时器相同），控件销毁时计时器随之清除。`variable=` 双向同步。`from tkinter import ttk` 是 tkinter 模块里的一个命名空间对象（tkinter 是单文件模块，`import tkinter.ttk` 不支持）。
- Canvas 滚动：按 Tk 8.6 tkCanvas.c 实现 xOrigin/yOrigin（含边框）、scrollregion、confine、x/yscrollincrement（对齐到增量整数倍）、xview/yview 的 moveto/scroll units（窗口 1/10）/pages（9/10）、ScrollFractions；视图变化时在绘制中排队 x/yscrollcommand（去重）。命中测试用画布坐标；事件仍是窗口坐标，`canvasx/canvasy` 向服务端取原点换算。有 scrollregion 且程序没绑定 `<MouseWheel>` 时滚轮滚一个 unit。
- 绘制：图元坐标不变，平移放在记录里——文字和图片记录带自己的左上角，其余矢量记录在 textX/textY 里带画布原点，客户端从这里画（原来从 bounds 左上角画）；完全在视图外的图元不发送。协议升为 `mpy4oc_tk13`。
- 存档：Canvas 节点新增 `canvasView`（原点、区域、confine、增量、回调），旧存档没有它时用默认值。

审查（2026-10-09，3 个方面各一轮查找 + 一轮核实；13 条全部成立，去重后 11 处，已全部修复）：
- 不定量模式下 `step()` 也按 maximum 取模，`start()` 的色块只单向走再跳回起点。Tk 只在定量模式取模（`fmod`，负数保号）；不定量模式一直累加，只在超出数值范围时按整周期折回（位置不变）。
- 视图只在 xview/yview/configure 时约束，Canvas 变大（显示器改分辨率、`configure(height=)`、布局变化）后会露出区域外的空白。现在每次绘制前按新尺寸重新约束，与 Tk 在 ConfigureNotify 时一样。
- 约束平移按增量整数倍（Tk 的 `delta -= delta % increment`）；增量取整照抄 Tk 的正负两个分支。
- 滚轮：程序在窗口/根上绑定 `<MouseWheel>`（这里没有 bind_all，常用 root.bind）时会滚两次；现在也看所在窗口的绑定。
- `canvasx/canvasy` 每次一个同步服务端调用（拖动画线时 3 倍调用）；现在只有视图可能动过（设过 scrollregion 或调过 xview/yview）才去问原点。取整按 Tk：先取整到像素（四舍五入远离零），网格对齐按 GridAlign 对称取整。
- `stop()` 按 Tk 把 value 置 0（有 variable 时不动），`start()` 运行中再调不起作用、第一步立即执行。
- 补 `MOVETO/SCROLL/UNITS/PAGES` 常量；tkscroll 演示 `'%d' % 浮点` 在本 VM 报 TypeError，改成 int()（VM 与 CPython 的这处差异另开任务）。

验证（2026-10-09）：
- 单元测试 412 个通过（修复后补了：不定量 step 累加后回头、接近上限的折回、定量负数取模；增量整数倍约束、变大后重新约束与比例；窗口上绑定滚轮；Python 侧 stop 置 0、重复 start、立即 step、常量、canvasx 取整）：TkProgressScrollTest 8 个（定量/竖向/不定量/step 取模/选项校验/存档与协议往返；视图与 Tk 对照：无区域、moveto、confine、units/pages、字符串参数、增量取整、不 confine、边框内侧；平移绘制、视图外不发送、命中与 bbox 用画布坐标；回调去重、滚轮与绑定滚轮的程序；视图存档与坏 NBT）；TkPythonTest 新增 2 个（ttk.Progressbar 与变量、step、start/stop 计时器、销毁时清计时器；Canvas + Scrollbar 联动、canvasx/canvasy；两个演示程序 tkscroll/tkprogress 在 VM 里跑通）。
- 回归（客户端改了 Canvas 矢量的绘制原点）：图片探针 5/5、多方块探针 33/33（含 tkcanvas 仪表盘截图）。
- 游戏内探针 9/9（修复前后各跑一次，都是 9/9）：40% 进度块宽 119 px（298 px 槽）、视图外图元不发送（46 个里发 11 个）、不定量进度条随计时器移动、拖竖滚动条 60 px → 视图 y = 376、滚轮一格 +16 px、拖横滚动条、点击事件为窗口坐标且 canvasx/canvasy 加上视图原点、点击处画出标记；GUI 与方块表面截图正常。

## 17. 窗口改大小、最小化/最大化与任务栏

需求（2026-10-09，用户）：在第 14 步的可拖动窗口上加改大小、最小化、最大化和任务栏（之前评估的第二档“拖动跟手”不做）。客户端与协议不改，全部是服务端绘制记录与命中测试。

设计：
- 改大小：可抓范围是 1 px 边框加外侧 3 px，离角 12 px 以内两个方向一起改；左/上边拖动时对边不动。`wm resizable/minsize/maxsize`（内容区大小）；min/max 在布局（`TkPlace.layout`）里对窗口生效，所以 `geometry` 和自然大小也受限制。拖动的上限默认是显示器（窗口本来更大时取当时大小），标题栏不出屏幕上沿。改过大小后尺寸固定（与 geometry('WxH') 相同）。
- 标题栏按钮：最小化 –（U+2013）、最大化 ⧠（U+29E0）/还原 ⧉（U+29C9）、关闭 ×；按下高亮，在同一个按钮上松开才生效（与 × 一样）。只有两个方向都可改大小才有最大化按钮；任务栏关掉时没有最小化按钮。标题栏最小宽度 = 按钮数 × 18 + 24。双击标题栏（500 ms、4 px 内）最大化/还原。
- 状态：最小化 = Tk 的 iconic（隐藏、保留 restore、`iconic` 标记，出现在任务栏）；withdrawn 不在任务栏。最大化 = zoomed：`normal` 存还原用的位置，窗口放到工作区（标题栏在顶端、内容区填到任务栏上沿），每次布局按显示器/任务栏重算；最大化的窗口不能拖动、不能改大小；设 geometry 或 `resizable` 取消最大化。隐藏状态下的最大化窗口还原后仍是最大化。最小化当前窗口后，下一个窗口变成当前窗口并恢复它的焦点。
- 任务栏：有显示中或最小化的窗口、且程序没关（`root.attributes('-taskbar', False)`，MPY4OC 扩展）时，底部 18 px。桌面（根）的布局高度缩到任务栏上方（工作区），窗口也裁剪在工作区内，`-X-Y` 的 Y 从工作区底边算。按钮按创建顺序，最宽 120 px、最窄 24 px，放不下的不画；当前窗口蓝色、最小化的变暗。点按钮：最小化的还原并置顶、当前窗口最小化、其他窗口置顶。绘制记录：底条挂在第一个任务窗口上（part 7，绘制记录的 id 不能是 0），按钮是各窗口的 part 4，最小化/最大化按钮 part 5/6；任务栏是最上一层，超预算时最先保留。
- 窗口管理的按下（拖动、改大小、标题栏/任务栏按钮、双击、最大化窗口的标题栏、任务栏空白处）松开时不发给程序（`TkTree.managing`），修正了第 14 步拖标题栏后根窗口会收到 `<ButtonRelease-1>` 的问题。
- `<Configure>`：任何控件可绑定；布局后大小变化（含刚绑定、重新映射）时触发，`event.width/height` 和相对父控件的 x/y。与 Tk 的差异：只看大小、不看位置，只发给绑定的控件本身（不按 bindtags 发给所在窗口）。服务端排队在 `TkTree.takeConfigures`，`TileTkDisplay.emitConfigures` 按 tk_event 格式发送（末尾加宽高）。
- Python：Toplevel 的 `iconify`、`state('normal'|'iconic'|'withdrawn'|'zoomed')`、`resizable`、`minsize`、`maxsize`（默认返回显示器大小，与 Tk 默认是屏幕一致）、`attributes('-zoomed')` 及 `wm_*` 别名；根的 `attributes('-taskbar')`；Tk 其他平台的 attributes（`-topmost`、`-alpha` 等）接受但无效果，未知名字报 ValueError。
- 存档：窗口复合标签加 `iconic`、`normal`、`resizableX/Y`、`limits`（min/max 四个数），树加 `taskbar`；都是可选键，版本仍为 5，旧存档按默认值读入；显示中的窗口不能是 iconic，限制值越界即拒绝。
- 演示 `tkdesktop.py`：计数器窗口（minsize）、不能改大小的窗口、Canvas 随 `<Configure>` 重画的图表窗口。

自查（写游戏内探针时发现）：双击标题栏、按住最大化窗口的标题栏、点任务栏空白处这三种按下没有登记为窗口管理操作，松开会把 `<ButtonRelease-1>` 漏给根窗口；已加 `swallowed` 记录。窗口甩到屏幕左边时留下的 24 px 正好是按钮，可拖的标题部分全在屏外；`clampX` 改为保留 24 px 可拖动的标题部分。

评审（2026-10-09，4 个方面各一轮查找 + 一轮核实；23 条全部成立，去重后 17 处，已全部修复）：
- 只拖左右边时把高度也写成固定值（反之亦然），自然高度的窗口加内容后被裁掉；现在只写被拖动的那个方向。
- 内容比标题栏窄的窗口拖边时边框不跟手（按内容宽度算）；现在按可见边框宽度算，内容在边框超过标题栏最小宽度前保持原宽，左边拖动时边框右端不动。
- 内容区 1～3 px 高（或很窄）时，一次按下同时改上下（左右）两边；现在只取离指针近的那条。
- 任务栏放不下全部按钮时，没有按钮的窗口照样能最小化，最小化后玩家找不回来；按钮最窄降到 8 px，仍放不下的窗口没有最小化按钮。
- 程序对不能改大小的窗口 `state('zoomed')` 后玩家无法还原；现在只有有最大化按钮的窗口才会被最大化。
- 不能最大化的窗口 500 ms 内第二次按标题栏被当成双击吞掉、拖不动；现在只有能最大化的窗口才判双击。
- `-X` 按内容宽度算，窄窗口的按钮出屏；现在按标题栏右端算。窗口隐藏时设的 `-Y` 按当时（无任务栏）的工作区算，显示后底部被任务栏盖住；现在按显示后的工作区算。
- `deiconify` 后窗口在最上层但键盘焦点留在别的窗口；现在 `deiconify` 以及从隐藏状态 `state('normal'|'zoomed')` 会把焦点给它（Tk 在 Windows 上的行为）。
- 每次重新绑定 `<Configure>`（含解绑再绑、`add='+'`）都会再触发一次，回调里重新绑定会每 tick 循环；现在只有控件第一次绑定时报告当前大小。
- 右键按下和松开不在同一处时，松开会漏给程序或丢失；现在右键松开跟着按下走（Tk 的隐式抓取），按在窗口装饰上的右键松开不发给程序。
- 根窗口 `attributes('-zoomed')` 报错（加入忽略列表）；`root.minsize()` 返回 None（改为 `(1, 1)`）；`minsize(0, 0)` 被拒绝（≤0 视为没有下限）。
- 任务栏按钮和标题栏带完整标题（最长 512 字节），挤占绘制预算；现在只发能显示的部分。
- 文档里“`geometry()` 只接受 `宽x高`”已过时，已改；演示的状态文字超出单块屏宽度，已缩短。

验证（2026-10-09）：
- 单元测试 435 个全部通过（`TkWindowTest` 新增 16 个：改大小的边/角/限制/锁定轴、单轴保持自然大小、窄窗口按边框、极小窗口、最小化与任务栏、任务栏放不下、最大化/还原/双击/跟随显示器、只最大化可还原的窗口、关掉任务栏、`<Configure>`、存档往返与坏 NBT、窗口管理按下不给程序、deiconify 给焦点、`-X-Y`、minsize 与标题裁剪、右键配对；`TkPythonTest` 新增 3 个：Python 接口、`<Configure>` 只在首次绑定和大小变化时触发、`tkdesktop.py` 在 VM 里跑通）；`gradlew build` 通过。
- 游戏内探针（`build/tk-desktop-probe`，端口 25568）最终一轮 22/22：三个窗口与按钮（固定大小的没有最大化）、任务栏三个按钮且当前窗口高亮、图表的 `<Configure>` 画出 150×100、点击置顶、拖标题栏 +80/+60 且服务端一致、拖右下角改为 120×130 且 Canvas 重画、最小化后任务栏按钮变暗、点任务栏按钮原位还原并置顶、最大化填满任务栏以上且按钮变 ⧉、双击标题栏还原、窗口内按钮回调、甩出屏外保留 24 px 可拖标题、× 经 protocol 关闭且任务栏按钮消失、桌面按钮新建窗口、无焦点按键到活动窗口、窗口管理的松开不发给程序、标题栏上按下的右键松开不发给窗口、窗口内右键能收到 `<ButtonRelease-3>`。前几轮的失败都是探针脚本自身的问题（靠 × 识别窗口而 × 被拖出屏外、窗口被盖住时点错位置），以及一次在探针启动期间改了资源文件，客户端的 gradle 重新打包了运行中服务器正在用的 mod jar，导致登录时类加载失败（"Internal server error"）。截图已查看。
- 未覆盖：真实鼠标拖边框的手感（每秒 20 次）、多人同时操作同一窗口、世界表面上的任务栏截图之外的交互。

后续变更（2026-10-11，用户：“鼠标可以拖动边框的位置，对边框进行高亮提示”）：服务端跟踪鼠标所在窗口能拖的边（`TkTree.hintWindow/hintEdges`，取自 `TkWindow.resizeEdges`，所以最大化或不能改大小的不亮），窗口装饰多画 `PART_HINT`～`PART_HINT+3`（左右上下，#5DA9FF，宽 3 像素：边框和外面 2 像素；角上只亮两边各 14 像素）；拖动改大小时按抓住的边一直亮，松手后按鼠标所在重算。客户端在鼠标离开显示画面时补发一次画面外的 MOTION（`InputEvent` 允许 MOTION 在画面外），服务端据此清掉悬停与高亮；关界面也清。测试 `TkWindowTest.theEdgesADragWouldResizeLightUpUnderThePointer`、`TkInputTest` 的离开事件；游戏内探针 `build/tk-hint-probe` 6/6，截图已看。

## 18. ttk 阶段 0、1：子模块、事件与 ttk 基础

需求（2026-10-09，用户）：先评估完整 ttk 的工作量（多代理估算：完整约 7,000 行代码 + 4,800 行测试，推荐分 8 个阶段），然后做阶段 0、1。

阶段 0（前置）：
- 冻结标准库支持子目录模块：`stdlib/tkinter/ttk.py` 冻结为 `tkinter/ttk`，VM 现有的点号导入按路径查到它；`from tkinter import ttk` 找不到属性时回退到子模块。`import tkinter.ttk`、`from tkinter.ttk import *` 都可用。旧的 `ttk = _Ttk()` 命名空间对象删除。超过 60,000 个 base64 字符的模块拆成几个常量，类加载时拼接（`dec(String...)`），避免超出 class 文件 65,535 字节的常量上限。
- 新事件：`<Up>`/`<Down>`/`<Prior>`/`<Next>`（及 `<Key-Up>`、`<Page_Up>` 等别名）、`<Double-Button-1>`（`<Double-1>`）。双击在服务端检测：同一会话、同一控件、500 ms、4 px 内的第二次按下；第三次重新算单击；在绑定了双击的标签上代替 `<Button-1>` 发出（Canvas 图元的 tag_bind 也是）。`<Escape>` 仍由 GUI 用来关闭界面，不可绑定。
- Python：程序自定义的 `<<虚拟事件>>` 在 Python 里绑定（支持 add 和 `'break'`），`event_generate` 立即依次调用控件和所在窗口的绑定；变量名 `PY_VARn` 和 `trace_add/remove/info`、旧的 `trace_variable/trace/trace_vdelete/trace_vinfo`（跟踪回调里不再触发同一变量的跟踪）；`register`/`deletecommand`、`after_idle`、`focus` 别名、`winfo_class`；经典 Scrollbar 的 `identify/delta/fraction`、Scale 的 `coords`。

阶段 1（ttk 基础，不改外观）：
- `-takefocus`（所有控件）：0 不参加 Tab、点击 Button/Scale 等不抢焦点（Entry/Text 点击仍得到焦点）；1 让 Label/Frame/Canvas 参加 Tab 并可 `focus_set()`；`''` 恢复默认。存档可选键。
- 服务端状态标志查询 `flags`：disabled、readonly、selected、focus、pressed、background（所在窗口不是当前窗口）。
- 新控件 Separator（请求 2×2，画成带 PRESSED 的 FRAME 记录 = 刻线）、Sizegrip（15×15，背景 + 6 个 2×2 点；在可改大小、未最大化的 Toplevel 里按住它等于拖窗口右下角，`zoneAt` 视为窗口边，按下和松开都不给程序）。新的 Kind 只用于服务端和存档，绘制用旧记录类型，协议不变。
- Scrollbar/Scale 新增查询：`identify`、`delta`、`fraction`、某点的值、`coords`。
- `tkinter/ttk.py`：ttk 公共部分是一个不继承任何类的混入类（VM 的方法解析是深度优先），`class Button(Widget, tkinter.Button)`，所以 ttk 控件仍是经典控件的实例，经典选项照样可用。截获 `style`、`class_`（只读）、`cursor`、`padding`（存元组，暂按平均值画成对称边距）、`state` 选项（与 Tk 一样和 `state()` 分开）和若干“只记录不绘制”的选项。`state()/instate()` 与 Tk 8.6 一致（disabled/readonly 真正生效，selected 勾选，focus/pressed/background 取服务端实际值，其余在 Python 记录）；没有 variable 的 Checkbutton/Radiobutton 初始 `alternate`；`identify` 返回元素名；ttk.Scale 用 ttk 默认值（0～1.0、连续、横向、不显示数值）并支持 `value=`、`get(x, y)`、`<<RangeChanged>>`。`ttk.Style` 只记录和查询（configure/map/lookup 按 Tk 的顺序、layout、element_create 'from'、theme_create/settings/names/use 与 `<<ThemeChanged>>`）。尚未实现的控件使用时抛 `NotImplementedError`。
- 返回值以 WSL 中 CPython 3.12 + Tk 8.6（WSLg 有显示）的实测为准。
- 演示 `/bin/tkttk.py`。

评审（2026-10-09，4 个方面各一轮查找 + 一轮核实；35 条全部成立，去重后 33 处，已全部修复，返回值都在 WSL 的 Tk 8.6 上重新核对过）：
- 变量跟踪：改为新加的先调用；两种写法共用一个列表（`trace_info` 也列出 `trace_variable` 加的，`trace_remove` 也能删它，反之亦然），模式按 Tcl 的顺序；`trace_remove` 要求模式完全相同；同一轮里被前面的跟踪删掉的跟踪不再调用；`trace_variable` 接受 `a`；回调出错时报告并继续，不再中断 `set()` 和控件的 `command`；删除不存在的名字报 "can't delete Tcl command"。
- 绑定：每个事件改为一个处理函数列表（`_Handlers`，服务端仍只登记一个回调号），`bind` 返回各自的 funcid，`unbind(事件, funcid)` 只删那一个；`add='+'` 时前一个返回 `'break'` 不再继续调用后面的（以前组合函数丢掉了 `'break'`）；出错的处理函数报告后其余照常调用。Canvas 的 `tag_bind/tag_unbind` 同样处理。`register`/`deletecommand` 和 funcid 共用命令表，删除不存在的报 TclError。
- `event_generate`：接受 `when=`（非 now 时排在已排队事件之后）和 Tk 才有的字段；按键序列补上 keysym/char/keycode；`<Key>` 带 keysym 时与真实按键一样优先找该键自己的绑定；`<Key-space>` 别名。
- 真实按键：同一控件同时绑了 `<Key>` 和 `<Up>` 时会收到两次（这一轮新增的 `<Up>` 等让问题更常见）；改为每个标签只发该键自己的绑定，没有才发 `<Key>`。
- 焦点：`takefocus=1` 的 Toplevel 让 Tab 卡住不动（窗口本身成了候选）；从点击得到焦点的 `takefocus=0` 控件按 Tab 会跳回第一个；点另一个窗口里 `takefocus=0` 的 Button/Scale 时键盘留在原窗口；`takefocus=0` 的 Listbox 点击不再得到焦点（Tk 的类绑定总会给）。均已按 Tk 修正。
- 双击：两次单击之间的右键按下或按键（修饰键除外）也被算成双击；加了 `interruptClick`。
- Sizegrip 被当作窗口装饰后，它上面的鼠标移动、滚轮、右键也不发给程序；现在只有左键拖动归窗口管理器。
- Scrollbar 的 `fraction`/`delta` 用了以滑块中心、扣掉滑块长度的公式；改为 Tk 的 `(位置 - 箭头) / (长度 - 1 - 2×箭头)`，与视图无关。
- ttk 状态：`invoke()` 看 `state` 选项而不是 disabled 标志（`state(['disabled'])` 后照样执行，反过来恢复后又不执行）；`state(['selected'])` 改了变量并取消同组单选（Tk 只改标志）；`configure(variable=...)` 不退出 alternate；`state()` 的返回按传入顺序而不是 Tk 的位顺序，状态名应为 `user1～user3`、`reserved1～3`；`state` 选项的 active 不设标志、normal 不清 active、其他值报错（Tk 接受并当作 normal）；禁用的 ttk.Frame 把里面的控件也禁用了（Tk 的 Frame 连 `state` 选项都没有：Frame、Separator、Progressbar、Scrollbar 改为没有该选项，disabled 只在 Python 记录；Sizegrip 没有该选项但服务端仍按禁用处理）；`cget('takefocus')` 对 Label/Frame 等也返回 `ttk::takefocus`（Tk 是 `''`）。
- ttk.Scale：禁用时 `value=` 丢失、`value=` 压过 `variable=` 并触发一次跟踪、`set()` 不立即调用 `command`（经服务端排队稍后才调）；改为 `value=` 直接写服务端（不通知），有变量时以变量为准，`set()` 禁用时什么也不做、否则立即调用 `command`。
- ttk.Style：设置跨主题继承（Tk 每个主题有自己的样式表，只有 layout 会找父主题）；`configure(样式, 选项)` 走了 lookup（会查 map 和父样式），`configure(样式)` 无设置时应返回 None；layout 不按样式链回退、设置时返回了布局本身、`''` 不设空布局；错误信息与 Tk 不同、`theme_settings` 拒绝未知键（CPython 忽略）；主题注册表是模块全局的，MPYOS 里第二次运行同一程序 `theme_create` 报“已存在”，新建 `Tk()` 也看到旧设置——改为挂在根窗口上；`<<ThemeChanged>>` 改为排队、连续切换只发一轮、发给每个控件（各自触发所在窗口的绑定），与 Tk 相同。
- VM：`from tkinter.ttk import *` 不看 `__all__`，把 ttk 的混入类 `Widget` 覆盖了 tkinter 的 `Widget`；IMPORT_STAR 改为有 `__all__` 时按它导入（名字不存在报 AttributeError）。子模块导入失败后仍挂在父模块上，重试 `from pkg import child` 拿到半初始化的模块（例如旧存档里的旧 tkinter 遇到新 ttk）；失败清理时一并从父模块解绑，ttk 也在 tkinter 过旧时直接报 ImportError 提示重启电脑。
- 演示：单块 320×240 放不下，最后的按钮被压扁，开窗口出现任务栏后只剩 2 px；双击的提示马上被第二次单击的 command 覆盖（Tk 里双击时 command 也会执行两次，是演示的问题）。改为两个按钮放一行、单击计数单独显示；测试检查打开窗口后所有控件都不小于请求尺寸。

验证（2026-10-09）：
- 单元测试 455 个全部通过（新增：`TkTtkTest` 3 个——Tab 跳过窗口并从点击得到焦点的控件继续、点击不取焦点的控件仍把键盘交给其窗口、右键/按键打断双击，Scrollbar 改用 Tk 实测值；`TkPythonTest` 3 个——跟踪/事件/绑定、ttk 状态/Scale/Style、`from tkinter.ttk import *`；`ImportTest` 2 个——失败的子模块重试仍失败、`__all__`），`gradlew build` 通过。
- 游戏内探针（`build/tk-ttk-probe`，端口 25568，沿用 tk-desktop 的运行目录）11 项中 10 项通过：程序启动、Separator 刻线、双击只触发一次 `<Double-1>` 且两次单击都执行 command、点击-右键-点击不算双击、方向键移动 Canvas 圆点、`<Key>` 收到 `a` 但不收到有自己绑定的 Up、拖 Scale 到 1.00 时跟踪和 Progressbar 跟随、拖 Sizegrip 把窗口从 160×90 改到 200×120、右键 Sizegrip 到达窗口的 `<Button-3>`、无 Python 错误。未通过的一项是探针预期写得太严：勾选框点击后状态是 `focus selected`（点击 ttk 控件会得到焦点，与 Tk 的 `ttk::clickToFocus` 相同），探针已改为只检查有 selected、没有 alternate，未重跑。截图已查看：整个界面在 320×240 内，按钮行与“单击 4 次”、任务栏、改大小后的窗口都正常。
- 未覆盖：真实鼠标在 Sizegrip 上的移动/滚轮事件（单元测试碰不到 `TileTkDisplay.input`，探针只测了右键）。

## 19. MPYOS 工具 tkedit：Tk 版文本编辑器

需求（2026-10-09，用户）：`-fullscreen` 先不做；给 MPYOS 加一个 `tkedit.py` 工具，做 Tk 版本的文本编辑器。

设计：
- `/bin/tkedit.py`，`tkedit [文件]`，由 shell 以 `args`/`sh` 运行（文件读写走 `sh.fs`，路径用 `sh.resolve`）；不在 shell 里运行时退回 `osfs.shared_vfs()`。根窗口按 `root.maxsize()` 占满显示器：工具栏（新建、打开、保存、另存、查找、折行）、Text + 竖向滚动条（不折行时加横向）、可隐藏的查找/替换栏、状态栏（文件名与 `*`、行:列，点行:列跳行）。
- 快捷键在根窗口绑定 `<Key>`，按 `state`（Control=4，排除 AltGr 的 Alt=8）和 LWJGL 键码分派：Ctrl+S/Shift+S/O/N/Q/F/H/G、F3/Shift+F3；返回 `'break'`。
- 对话框用 Toplevel：打开/另存为（Listbox 列目录，文件夹在前，双击进入或打开，输入相对/绝对路径，覆盖要二次确认）、未保存确认（保存/不保存/取消，保存成功后才继续原操作）、跳到行。同时只开一个对话框。
- 查找：取光标前后两段文本在 Python 里找，换算成 Text 索引时行号用 `str.count('\n', 0, i)`、列用码点数（VM 的 str 长度按 UTF-16 计，Text 索引按码点计；`points()` 对代理对两种迭代方式都正确），不区分大小写时比较小写（长度变化时退回区分大小写）。替换全部在 Python 里拼好后整体替换，失败（超出 Text 上限）时恢复原文。
- 文件：读字节后严格按 UTF-8 解码，失败、含控制字符或超出 Text 上限时拒绝并保留当前文档与修改标记；记住 CRLF，保存时写回。
- 为此修的底层问题：VM 的 `str.find/rfind/index/count/startswith/endswith` 不支持 CPython 的 start/end（`rindex` 没有，startswith/endswith 不收元组，`replace` 不收 count），已按 CPython 切片语义补齐；`bytes.decode`/`bytearray.decode`/`str(bytes, ...)` 遇到非法 UTF-8 时悄悄换成 U+FFFD，改为默认严格、抛 `UnicodeDecodeError`（新增类型，消息与 CPython 相同），支持 `'replace'`、`'ignore'`；MPYOS `fs.read_file` 按 4096 字节分块各自解码，跨块的多字节字符会出错，改为读完再解码；tkinter 的 Ctrl+字母 `event.keysym` 原为控制字符，改为字母（与 Tk 相同），并补上 F1～F12、Insert。

评审（2026-10-09，3 个方面各一轮查找 + 一轮核实；29 条全部成立，去重后 27 处，已全部修复）：
- 严格解码的连带影响（最重要）：BIOS 也按块解码 `/init.py`（OC 每次最多读 2048 字节），`init.py` 超过 2 KB 且中文跨在读取边界上时开不了机。三份 BIOS（`bios/bios.py`、`bios/bios_debug.py`、LiveCD 的 `/bios.py`）都改为读完再解码（bios.py 3689 字节，仍在 4096 以内）。世界里已有的 EEPROM 带着旧代码：新增 `BiosVersions`，EEPROM 代码与历史上发布过的某版 BIOS 完全一致（按去掉 CR 后的 SHA-256 比对，含被截到 4096 字节的最早一版）时改用随 jar 附带的 BIOS，改过的副本不动；`BiosVersionsTest` 从 git 历史取出每个旧版本核对，以后改 BIOS 漏登记会失败。另外：shell 读命令文件失败（如不是 UTF-8）会让整个 shell 退回最小控制台，现在只报错；`urllib.parse.unquote` 遇到非 UTF-8 的 %xx 改为替换字符（CPython 的默认）；`cat`、`grep` 改为替换显示，不会因一个二进制文件中断。
- 解码与 CPython 的差异：编码名被忽略（latin-1 报错、ascii 收非 ASCII）——现在支持 UTF-8、Latin-1、ASCII 及其别名，其他报 LookupError；改用自己按 CPython 算法写的 UTF-8 解码，错误位置、“byte/bytes in position”的措辞和 `'replace'` 产生的 U+FFFD 个数都与 CPython 相同（JDK 的解码器对编码的代理项只给 1 个）；错误处理名改为真出错时才检查，增加 `backslashreplace`、`surrogateescape`；`UnicodeDecodeError` 有 encoding/object/start/end/reason 属性，5 个参数构造时的文字与 CPython 相同。
- 大整数：`str` 的起止参数和 `replace` 的 count 不收 ≥2**62 的整数（如 `sys.maxsize`）；切片 `'abc'[0:sys.maxsize]` 原来得到 `'ab'`，`'abc'[:2**32]` 得到 `''`（原有问题）。都改为按符号夹紧。`str.lower/upper` 和格式化里的大写改用 `Locale.ROOT`（土耳其语环境的服务器会把 I 变成无点 ı）。startswith/endswith 元组里有非 str 时的报错文字与 CPython 对齐。
- 编辑器：光标落在一个匹配中间时向前、向后、绕回都找不到它，改为在整篇里找；单个替换先删后插，插入超出上限时匹配文字丢失，改为失败时恢复；保存时 OC 先清空文件再写，磁盘满时原文件变空，改为先写临时文件再改名覆盖；不区分大小写时文本里有一个“İ”就整体失效；选中带制表符的文字按 Ctrl+F 会清空查找框并报错；文件名在 emoji 处截断时未保存对话框报错成空窗口；单独的 CR 和混用换行在保存时被悄悄改写（改为按多数决定并在打开时提示）；替换全部失败、打开被拒时光标和修改标记不能复原；“超过 512 KB”的提示漏了 262143 字的上限；对话框里到不了 `/mnt`，另存为失败时对话框先关了；点过按钮/滚动条后按空格会再次执行（改为 `takefocus=0`）；对话框打开时不重新查挂载；`python /bin/tkedit.py 文件` 时忽略参数；未保存对话框出现时按键仍进文档（改为把焦点给“保存”）；目录列表每项 3 次同步调用（改为一次插入）；对话框尺寸用的是启动时的分辨率（改为每次取当前画布，并关掉编辑器的任务栏）。
- 顺带补上 `focus_get()`（服务端新增查询 `focused`）。

验证（2026-10-09）：
- 单元测试 464 个全部通过（新增 `StrSearchArgsTest` 3 个、`MpyosFsTest` 3 个、`BiosVersionsTest` 2 个、`TkPythonTest.tkeditEditsFilesThroughPython` 1 个——在 VM 里用内存文件系统跑真实的 tkedit.py：打开、查找/绕回/光标在匹配中、替换、保存、磁盘满时原文件不变、只读、拒绝的文件保持光标选区、CRLF 与混用换行、İ、制表符选区、emoji 文件名、对话框（覆盖确认、失败不关、/mnt、目录进出）、跳行、折行、Ctrl+F 的真实按键信号、退出），`gradlew build` 通过。
- 游戏内探针（`build/tk-edit-probe`，端口 25568，沿用 tk-desktop 的运行目录，假的内存文件系统）9/9：启动并载入文件、单块 320×320 上工具栏/文本区（304×276）/状态栏都在、打字后状态栏带 `*`、键盘 Ctrl+S 保存、Ctrl+F 后在查找框打字回车选中 1.9–1.14、再回车到 2.4–2.9、打开对话框里双击 other.txt 载入、修改后 Ctrl+Q 弹出未保存对话框并选“不保存”退出（只保存过一次）、无 Python 错误。第一轮 8/9，失败项是探针在画面刷新前就检查，截图显示文件已载入；改为等画面后重跑全过。截图已查看。
- 未覆盖：真实 OC 硬盘上的临时文件改名覆盖（单元测试与探针用的是假文件系统；OC 源码里两种文件系统的 rename 都会覆盖已有目标）、`python /bin/tkedit.py` 的启动方式、旧 EEPROM 在真实存档里的自动换用。

## 20. 后台运行（`cmd &`、`tkedit -w`）与程序的生命周期

需求（2026-10-09～10，用户）：tkedit 能不阻塞（`tkedit -w`，类似 javaw），直接回到 shell、开一个窗口；不考虑多个程序共用一块屏，不指定屏幕时自动分配一块空闲的；用户随后问 `python` 启动的程序开了线程、主程序结束后线程会不会泄露到 MPYOS（会：命令执行完就返回，线程留在 VM 里继续跑，没人管、停不掉），并建议不要为此在 VM 运行时上开口子，复用 threading/jasyncio，用轮询实现。

第一版（2026-10-09）：shell 把 `cmd &` 放进协作式线程；`pullSignal` 在有 Tk 窗口时跳过 Tk 事件；窗口记下创建它的任务；服务端 `busy` 查询供自动选屏；`jobs`/`kill`。游戏内 8/8。

第一版评审（3 个方面查找 + 核实，19 条全部成立）：所有程序共用一个 `_update()`，一个程序的回调、定时器和 `SystemExit` 会在另一个程序的线程里执行（高）；窗口按“当前任务”记主人，`jasyncio.run` 或子线程里开的窗口认错主人、被提前关掉；先查 `busy` 再 `claim` 有竞态，两个程序会抢到同一块屏，身份不检查的 `_roots.pop` 还会误删对方；占屏的电脑重启后旧占用永远算“被占”；全被本机占用时报错文字不对；后台任务读终端会抢走 shell 的全部按键且 `kill` 不掉；后台任务的报错写在输入行上被擦掉；`_depth` 在线程间共享；`threading._active` 泄露结束的线程（连同命令源码）；`tkedit -w` 的相对路径在加载时才按 shell 当前目录解析；`jobs` 让同一条 done 报两遍；`busy` 逐块查询多花时钟周期。

重做（按用户建议，不改 VM 运行时）：
- 新增冻结模块 `stdlib/_program.py`：以 `__jasyncio_current_coro()`（主帧为 None）识别池任务，记录每个任务属于哪个程序；`threading.Thread`、`jasyncio` 的任务和 `run()` 协程组在创建时继承创建者的程序（`adopt`），结束时 `forget`；`stop(程序)` 后，该程序的任务在下一个等待点抛 `SystemExit`（`check()`）。等待点：启动脚本里的 `pullSignal` 和 `time.sleep`、Tk 的 `mainloop`、jasyncio 协程的每次挂起；只做计算的线程停不下来（约定的代价）。jasyncio 的默认协程组改为每个程序一个。`threading` 线程结束时离开 `_active` 并释放 target（与 CPython 相同）。
- 启动脚本：`pullSignal`/`time.sleep` 做 `check()`；后台程序调用 `pullSignal` 时，键盘信号（key_down/key_up/clipboard）留给 shell（`pollSignal(list, keepTk, keepInput)`）。
- shell：每条前台命令是程序 `cmd:N`（执行期间主帧属于它），结束时等它的非守护线程（Ctrl+C 从信号队列里取，不动其他信号）、再停掉其余任务并关窗；命令里再调命令属于调用者；`cmd &` 是 `job:N`，在守护线程里运行，拿到 `_JobShell`（启动时的工作目录快照）和 `_JobTty`（输出存到下一个提示符再显示、不能读键盘、不能画屏）；`kill` = 停止 + 关窗，没有窗口的任务也能停；`jobs` 先报告结束的再列运行中的。
- tkinter：窗口的主人 = `_program.current()`；`_update(主人)` 只执行该程序窗口的事件和定时器，别的程序的信号暂存在 `_pending`（每块屏最多 256 条），由它自己的循环执行；`mainloop` 是等待点；`Tk()` 在第一次可能让出的调用前就在 `_roots` 里预留屏幕，不指定屏幕时用一次 `claim_free`（服务端原子判断“另一台正在运行的电脑占着就不给”），失败或消失的屏幕跳过；`_forget` 只删自己；找不到 `_program` 时退回“都是主程序”（单独拷到磁盘上的 tkinter.py）。
- 服务端：`claim_free` 取代 `busy`；`TileTkDisplay.onMessage` 收到占屏电脑的 `computer.stopped`/`computer.started` 时释放显示器（OC 的文件系统、显卡也靠这两条消息收尾）。

第二轮评审（2026-10-10，3 个方面各一轮查找；用户嫌子 agent 消耗太大，核实只跑完 1 个方面，其余由主会话对照代码逐条核实；去重后 22 处全部成立，其中两处的修法按核实结果调整，均已修复）：
- Ctrl+C 被命令自己的线程吃掉（高）：shell 等非守护线程时，这些线程的 `pullSignal` 先于主帧取走 Ctrl+C，shell 一直等下去。改为等待期间把程序记入 `_program._waited`，`pullSignal` 的第三个开关让它们跳过 Ctrl+C（Java `pollSignal(..., keepInterrupt)`），其余按键照常给它们；`_ctrl_c` 扫描队列副本、按值删除，不再因别的任务同时取信号而 IndexError。
- jasyncio 的 `create_task` 按全局 `_driving` 栈顶找协程组（高）：另一个程序的协程在一步中途被切走时，主帧或线程新建的任务会加入它的组、算作它的程序；某个任务被压在下面时出栈被跳过，留下永久的旧条目，之后的任务加入已死的组、永远不运行。改为 `_driving_task()` 只认调用者自己所在池任务驱动的条目，出栈按身份删除；`__await__` 的等待链同样处理。
- 同一显示器的事件乱序：别的程序的循环把信号暂存进新列表，而属主一边执行旧列表一边直接执行新取到的信号。改为每块屏一个队列（`_pending`，`setdefault` 一步取得），所有程序取到的信号都按到达顺序进队，属主只从队头执行，嵌套的 `update()` 也保持顺序。
- 已在停止的程序的线程在收尾之后才开窗：shell 只清理一次，窗口和显示器一直被占到重启。`Tk()` 一开始就是等待点；占屏后发现程序在停止就释放并 `SystemExit`；提示符前对仍在停止中的程序再关一次窗口、删一次回调。
- `update()` 不是等待点：`while ...: root.update()` 的后台程序 `kill` 不掉。已加 `check()`（CPython 里已销毁窗口的 `update()` 不报错，WSL 实测，所以不按评审建议抛 TclError）；`mainloop()` 因窗口被 `kill` 关掉而结束时也检查一次，后面的代码不再执行。
- `Tk(**kw)` 的 configure 失败且 release 也失败时，预留的根窗口留在 `_roots`；预留改为 `_roots.setdefault`（检查和占位一步完成，线程无法插进中间）。
- 显示器错过占屏电脑重启（区块未加载，或快照恢复失败后重新启动）时，`claim_free` 永远把它算作被占。评审建议用世界时间减运行时间识别同一次运行，核实认为不对（OC 的 uptime 只在加载时增长）；改为 `MpyArchitecture.bootId`：每次新启动 VM 生成一个随机 id 并随快照保存，显示器占屏时记下它（存档里的可选键 `boot`），`claim_free` 时 id 变了就不算占着。
- 停止只到下一次唤醒才生效：在 `await sleep(300)` 里的协程要等 300 秒。`_program.stop()` 调用钩子，jasyncio 叫醒该程序各协程组里睡着的成员。
- `SystemExit` 在收尾的每个挂起点都会再抛一次，`finally` 里的 `await` 做不完。改为每个任务给一次，2 秒内不再给（收尾吞掉它或一直不结束时，2 秒后的下一个等待点再给一次）。
- `micropython.schedule`（以及 proto_run）创建的协程结束后不从 `_program._tasks` 删除，程序永远留在 `_stopping`；`_spawn` 改为套一层结束时自删的生成器（线程的驱动仍由 `Task._run` 自删）。`_default_groups` 每个程序一条、永不删除：组结束时删除。
- `_thread.start_new_thread` 的线程不是守护线程，命令结束时 shell 要等它（CPython 和 MicroPython 都不等）：改为守护线程。`Thread(daemon=None)` 改为与 CPython 一样继承创建者的守护属性，后台命令的运行线程算作程序的主线程（其子线程默认非守护）。
- 后台命令的 `os` 相对路径跟着 shell 的当前目录走，它的 `os.chdir` 也会改前台命令的目录：osfs 改为每个程序一个当前目录（shell 给命令和后台命令各设一个，结束时删除）；顺带修好前台命令的 `os.chdir` 会遗留给下一条命令的老问题。
- `kill` 中止嵌套命令后，调用它的脚本继续下一条：`_run_nested` 在程序被停止时把 `SystemExit` 继续抛出。
- 后台输出无上限地累积：最多保留 256 段，提示符前说明丢了多少。
- `event.listen`/`event.timer` 的回调不属于任何程序：后台命令借它们收到按键，`kill` 之后照样运行。改为记下注册它的程序，回调以该程序的身份运行，后台程序的回调收不到键盘信号，程序停止中不再调用，结束时 shell 删除它的回调；顺带修好 `event.timer(..., times=0)` 文档说无限重复、实际只运行一次的老问题，以及跑完的定时器 id 不删除。
- 后台命令里 `sh.spawn` 的 `[编号]` 直接画在正在输入的行上，`sh.run_command('cd')` 等内置命令在后台报 command not found：`spawn` 接受输出位置，`_JobShell` 有自己的 `cd`（只改它自己的目录）并转交其余内置命令。
- 有后台命令时 `exit`/`reboot` 直接关机：第一次只提示，第二次停掉后台命令、给它们一秒执行 `finally` 再关机。
- `requests` 在等待连接或读取正文时被停止，网卡连接不关闭：句柄归 `Response` 之前出任何异常（含 `SystemExit`）都先关闭。

验证（2026-10-10）：
- 单元测试 472 个全部通过（第一轮）；第二轮修复后 479 个全部通过。第二轮新增：`SignalQueueTest` 的 Ctrl+C 留给 shell、旧运行的占屏不算；`TkPythonTest` 的 `theShellsWaitForThreadsKeepsItsCtrlC`（拉信号的线程拿不到 Ctrl+C、`_thread` 与守护继承、后台命令的子线程）、`aStoppedProgramLeavesNothingBehind`（收尾后开的窗口、`update()` 循环、`mainloop()` 之后的代码、嵌套命令、事件回调与定时器）、`jobsKeepTheirOwnDirectoryAndOutput`（`os` 当前目录、输出上限、后台里 spawn、两次 exit）、`aWindowsEventsRunInOrderWhileOtherProgramsPoll`、`coroutinesFollowTheirProgram`（另一个程序协程中途被切走时建任务、schedule 不留账、停止叫醒睡着的协程且只给一次 `SystemExit`）。`shellHost` 改用真实的 osfs/event 和架构里真实的 `pullSignal` 启动脚本。新增：`SignalQueueTest`（Tk 事件与键盘信号的保留、`claim_free`、停机释放）；`TkPythonTest` 的 `shellRunsTkProgramsInTheBackground`（真实 shell.py、两块屏、后台 GUI、前台命令只关自己的窗、全被占时报错、对方停机后可用、kill、出错的任务、`tkedit -w` 与启动目录）、`aCommandsThreadsEndWithIt`（守护线程随命令停止且 finally 在原程序里执行、非守护线程让命令等待、Ctrl+C、`_active` 不泄露）、`backgroundJobsAreStoppableAndKeptOffTheTerminal`（停止没有窗口的任务、读终端报错、输出延到提示符、只报一次、工作目录快照）、`eachProgramRunsItsOwnTkEvents`（别的程序窗口的点击被暂存、由它自己的循环执行；停止经 mainloop 生效；jasyncio 协程收到 SystemExit）。
- 游戏内探针（`build/tk-bg-probe`，两块屏，主程序像 shell 一样等在 `pullSignal`，线程以 `job:1` 运行 tkedit）9/9：打字与 Ctrl+S、主程序收到自己的信号、查找、主程序 `Tk()` 分到另一块屏且两个窗口主人分别为 None 和 job:1、编辑器继续响应、停止 job:1 后编辑器在 mainloop 收到 SystemExit 并关窗、模拟 `computer.stopped` 后显示器被释放、没有 Tk 事件被 `pullSignal` 取走。第二轮修复后（新的三参数 `pullSignal`、每屏一个事件队列、`mainloop` 结束时的检查、带 boot id 的占屏）重跑仍是 9/9，截图已查看。

后续变更（2026-10-11，用户：“-w 默认，不再提供非 -w 的选项”）：tkedit 总是在后台运行，没有前台模式了。shell 的 `Shell.background` 为假、作业里的 `_JobShell.background` 为真：在提示符下运行时 tkedit 自己 `spawn` 成作业；已经是作业（`tkedit &`、作业里再调用）时就地运行，不会再开一个作业；不在 MPYOS shell 下（`python /bin/tkedit.py`）照常前台运行。旧的 `-w` 仍接受、不起作用。测试 `shellRunsTkProgramsInTheBackground` 改为不带 `-w`，并加 `-w` 与 `tkedit &` 的情况；同时修了测试宿主的假节点不支持 `network()`（同一台电脑在一块屏上开第二个程序时查启动号会抛异常），以及 `FrozenStdlibTest` 的正则在长输入上递归栈溢出、时好时坏的问题（改为逐行解析）。

## 21. ttk 阶段 2：四边 padding、relief、Labelframe、Notebook

需求（2026-10-10，用户）：继续实现 ttk。用户嫌子 agent 消耗太大后，本步不再开子 agent：Tk 的实测数据用被叫停的调研工作流已经写出的部分（padding、relief、Labelframe、Notebook 的规则、返回值和报错文字，Tk 8.6.14 + default 主题），代码地图用它已完成的两份；其余在主会话里做。

实现：
- **四边 padding**：TkWidget 的 padx/pady 拆成左、上、右、下四个（`-padx`/`-pady` 设两边，ttk 的 `-padding` 给四边），布局、绘制、命中、滚动、Entry/Text/Listbox 的位置计算都改用每边的内边距；存档不对称时多存一个 `padding` 数组。ttk 不再把 padding 按平均值画成对称的；经典控件不接受 `padding`（`unknown option "-padding"`）。ttk.Label 和 Tk 的 TLabel 一样自带 1 像素边框、本身没有 padding，`padding` 加在边框里面。
- **relief**：每个控件有 Tk 的 `-relief`，默认值照 Tk 8.6：Button 凸起，Entry、Listbox、Text、Scrollbar、Progressbar 凹陷，其余平的（改正了 Listbox、Scrollbar 画成凸起，Label/Frame 设了 bd 就画成凸起的老问题）。绘制记录多一个 relief 字节，协议升到 `mpy4oc_tk14`；控件本身（part 0）带它的 relief，内部零件（滚动条箭头、窗口装饰等）保持原样。客户端按 Tk 在 X11 上的算法（tkUnix3d.c）从背景色算亮、暗两色，用 Tk 实测的 9 组颜色核对；groove/ridge 是外半圈和内半圈两个方向相反的斜面，solid 是黑色，每圈先画右下再画左上，角上的像素归属与 Tk 相同；按住的部件画成凹陷。经典控件都接受六种 relief，报错文字照 Tk；ttk 的 Label、Frame、Labelframe 也接受，未设置时 `cget('relief')` 返回 `''`。
- **Labelframe**：经典 `tkinter.LabelFrame` 和 `ttk.Labelframe`（`LabelFrame` 是别名）。服务端一个 Labelframe 类、两种几何：标题在 12 个 `labelanchor` 之一，是文字或 `labelwidget`（它自己的子控件，`winfo_manager()` 为 `'labelframe'`；Tk 也允许兄弟控件，这里不行）。标题占的那条边并入内边距，所以 pack/grid/place 照常在里面布局；边框移到标题中线；最小宽度让标题放得下。经典版标题文字四周留 1 像素、离角 bd+4；ttk 版离外边 8 像素、没有文字也留一个字符高。几何用新工具 `tools/tk-labelframe-golden.py`（标题用像素大小的 Frame，与字体无关）在 WSL 的 Tk 8.6.14 上量出 176 个用例逐一核对：两种、12 个方向、三种标题大小、指定大小、padding、bd 0/1/5。
- **Notebook**：服务端 Notebook 类，标签列表与选中规则照 Tk 8.6 的 ttkNotebook.c：第一个加入的标签自动选中（禁用的不选，隐藏的显示出来）、选中禁用的标签没有效果、当前页被隐藏/禁用/移走/销毁时选它后面第一个可用的否则前面最近的、移动标签时当前页跟着页面走、`insert` 的序号必须是已有的标签（`'end'` 是追加或移到最后）。几何照 Tk 实测：标签宽 max(24, 文字 + 12)、高 文字 + 7，矮的标签贴着页面边框，Notebook 的 padding 在最外面，标签的 padding 在页面边框里面，`-width`/`-height` 决定页面区大小。只布局选中页。绘制：背景、页面的 1 像素凸起边框、每个标签（新的 TAB 浮雕：凸起、底边开口；选中的用 Notebook 的颜色、向下多画 1 像素盖住边框，未选中的 #c3c3c3，禁用的文字变灰，Notebook 有键盘时选中的标签有焦点框）。点标签、左右方向键在服务端换页（ActivateTab：键盘给新页里第一个 Tab 能到的控件，否则给 Notebook；CycleTab 跳过禁用的、到头绕回），再通知 Python。Python 的 `ttk.Notebook` 有 add/insert/forget/hide/select/tab/tabs/index/identify/enable_traversal，标签的写法（序号、页面或路径名、`current`、`@x,y`、`end`）、返回值和报错文字照 Tk；选中页一变（包括页面被 pack 到别处、被销毁，或玩家点击）就排队发 `<<NotebookTabChanged>>`。标签的 image/compound/underline 只记录不绘制，Ctrl+Tab 不支持。
- **控件路径名**：`str(控件)`、`name=`、`nametowidget()`、`children`、`winfo_name()`，编号与 CPython 相同（`.!frame.!label2`）；Notebook 的 `tabs()`/`select()` 和 Labelframe 的 `cget('labelwidget')` 返回它们。
- 演示 `tkttk` 改为 Notebook 两页：“控件”和“边框”（六种 relief、两种 Labelframe、Canvas）。

评审：本步没有另开评审（用户要求少用子 agent），由主会话写测试时逐条对照 Tk 的实测和源码规则自查；查出并改正的：`add()` 重新加入隐藏的标签时先记下状态再取消隐藏，结果又被设回隐藏（改为照 Tk 先显示再应用选项）；`cget('labelwidget')` 在没有标题控件时返回了根窗口的路径 `.`（服务端用 0 表示没有，而 0 也是根窗口的编号）。

验证（2026-10-10）：
- 单元测试 497 个全部通过。新增：`TkReliefTest`（Tk 的亮暗色 9 组、六种 relief 的每圈颜色、四边 padding 的布局与文字位置、各控件默认 relief 与 part 0、存档与旧存档、协议往返）、`TkLabelframeTest`（176 个 Tk 实测用例、文字标题与边框位置、labelwidget 的托管/替换/移走/销毁、报错文字、存档）、`TkNotebookTest`（几何、选中规则、报错文字、绘制与点击/方向键/identify、存档），`TkPythonTest` 的 `reliefAndPaddingThroughPython`、`pathNamesAndLabelframesThroughPython`、`notebookThroughPython`（含服务端点击标签后 Python 收到事件），以及 tkttk 演示两页都放得下。
- 游戏内探针（`build/tk-ttk2-probe`，沿用 tk-desktop 的运行目录）10/10：tkttk 启动、两个标签（底边开口、选中的为页面颜色并盖住页面边框）、页面 1 像素凸起边框、第一页控件、点“边框”标签后 Python 收到 `<<NotebookTabChanged>>`、六种 relief 到达客户端、两种 Labelframe 的标题、第一页不再绘制、窗口标题跟着事件变、点当前标签再按左方向键回到上一页、无 Python 错误。截图已查看：凸起/凹陷/groove/ridge/solid 的明暗与 Tk 一致，ttk Labelframe 标题离左边 8 像素、边框穿过标题中线，经典 LabelFrame 标题居中，选中的标签与页面连在一起。第一轮 9/10 的那一项是探针在窗口标题的场景更新到达前就检查了（截图里标题已经变了），改为等标题后重跑通过。

## 22. asyncio 按 CPython 3.12 重写，jasyncio 只做别名

需求（2026-10-10，用户）：问 asyncio 能不能也像线程那样用轮询实现、不在 VM 运行时上开口子；又问按 CPython 的语义“绑定到事件循环上下文”有没有意义、我们是 MicroPython 的单事件循环还是 CPython 的多事件循环。决定：按 CPython 来，尽量移除 jasyncio，除了做别名不要主动使用。

实现：
- **新冻结模块 `stdlib/asyncio.py`**（照 CPython 3.12 的 base_events / futures / tasks 等写成纯 Python）：事件循环跑在调用它的线程里，每个线程一个（`get_running_loop` 按线程找；`set_event_loop` 按线程记，主帧上再按程序分开，免得一条命令拿到上一条命令设的循环；线程里设成 None 时直接删掉记录）；`get_event_loop` 照 3.12：主线程没设过就新建一个，其他线程要先设。`_run_once` 照 CPython：先丢掉堆顶已取消的定时器，到期判断带时钟分辨率（1 毫秒），本轮里新排的回调下一轮才跑。空闲时用 `time.sleep` 等（每次最多 0.05 秒），所以别的线程 `call_soon_threadsafe` 交来的活一个 tick 内就会被看到。Future、Task 照 CPython 的 `__step`（cancel/uncancel/cancelling、CancelledError 带消息、在别的循环上 await 的报错、任务名 Task-N）；gather、shield、wait、wait_for、as_completed、timeout/timeout_at、sleep、to_thread、run_coroutine_threadsafe、run_in_executor（线程名 asyncio_N，关闭时像 CPython 那样另开一个线程去 join）；Runner/run（取消剩下的任务、收尾出的错交给异常处理器）；Lock、Event、Condition、Semaphore、BoundedSemaphore、Queue、PriorityQueue、LifoQueue。报错文字照 CPython；回调是否可调用只在 debug 模式下检查（与 CPython 相同）。没有垃圾回收的终结器，“Task exception was never retrieved” 改在循环关闭时报告。
- **程序**：每轮循环调一次 `_program.check()`。程序被停止时 `asyncio.run()` 收到 `SystemExit`，Runner 关闭循环时取消剩下的任务，协程在它停着的 `await` 处收到 `CancelledError`（收尾里还能 `await`），之后 `SystemExit` 接着往外抛。程序结束时删掉它在主帧上设的当前循环（`_program._on_end`）。
- **jasyncio** 只剩别名：`from asyncio import *`，标准库、MPYOS 和测试都不再用它。`threading` 改用新的线程原语重写（线程名 `Thread-N (target)`、守护属性继承、join 的报错与 CPython 相同），`micropython.schedule`、`_thread` 跟着改。
- **VM**：线程原语改名 `__thread_start` / `__thread_current` / `__thread_wait` / `__thread_sleep`（旧的 `__jasyncio_*` 名字留着，给以前存档里的代码用）；新增 `__is_generator_function`（`iscoroutinefunction` 用）、内置 `TimeoutError`（OSError 的子类）；异常的 repr 改成 CPython 的样子（`ValueError('x')`），只有一个参数的 KeyError 的 str 是参数的 repr；每个对象都有 `__class__`（MicroPython 编译的 `async with` 要用）。**`with` 的 `__exit__` 是 Python 写的就作为真正的子帧运行**（以前用 callSync 同步跑）：`asyncio.run()` 的 `with Runner()` 在 `__exit__` 里关闭循环，关闭时可能要等（join 执行器的线程、被取消的任务在收尾里 await），同步跑会一直空转到 “runaway call: sync fuel exhausted”。返回后剩下的步骤记在 Frame 已有的 `withEnterArg` 的负值里，存档格式不变；原生的 `__exit__` 照旧。
- 顺手修掉：空闲等待时浮点误差剩下不到 1 毫秒，`time.sleep` 换算成 0 毫秒、不让出 tick，循环会一直空转；现在按 1 毫秒的时钟分辨率处理（同 CPython 的 `_clock_resolution`）。
- 已知限制：推导式里的 `await` 编译不过（MicroPython 也一样），场景脚本里改写成循环。

评审：本步没有另开评审（不开子 agent），用 CPython 的实际输出做对照。

验证（2026-10-10）：
- `src/test/resources/asyncio/` 下 8 个场景脚本（basics、cancel、errors、locks、loop、queues、threads、wait），先在 WSL 的 CPython 3.12 上跑出标准输出（`tools/asyncio-golden.py`），`AsyncioTest` 在 VM 里按游戏的方式跑（每步一个 50 毫秒的 tick，`time.sleep` 按启动脚本的做法让出 tick），181 行逐行一致。
- `WithExitTest`（6 个）：`__exit__` 跨 tick 等待，覆盖正常离开、return、break、异常继续抛和被吞掉；在每种 `__exit__` 等待时存档，读回后能把这条指令做完。
- `TkPythonTest`：`eachProgramRunsItsOwnTkEvents` 的协程部分改用 asyncio（停止任务 → CancelledError → finally → SystemExit）；`coroutinesFollowTheirProgram` 重写（循环外 `create_task` 报 RuntimeError；另一个程序的协程在一步中途被切走时，各线程各跑各的循环、各属各的程序；`micropython.schedule` 不留记录；被停止的程序里睡 30 秒的协程马上被取消，收尾里还能 await；最后各种记录都清空）。
- 单元测试 511 个全部通过。本步没跑游戏内探针。

## 23. 多个程序共用一块屏：Tk() 也是桌面上的窗口

需求（2026-10-10，用户）：把 `Tk()` 改成带 – ⧠ × 的窗口，直接画在桌面上，多个程序共用一块屏。用户定的：
1. 只有同一台电脑的程序共用一块屏。玩家权限照旧按这台电脑的用户列表整块屏判断，不按窗口分（真要隔离就把玩家物理隔开）。
2. 不指定屏幕时开到本机已经在用的那块，没有就开到地址排第一的那块（跳过别的正在运行的电脑占着的）。
3. 显示器分辨率默认用方块组允许的最大值。
4. 不兼容存档里正在跑的旧 Tk 程序（读档后它报错，重开即可）。
5. 桌面（最外层界面）没有标题；外层的 × 只关自己的界面。
6. 不做按程序的配额，整块屏的上限（32 窗口、256 控件、16 KB 文字、64 图片）不动。
7. 桌面上不能放控件。

计划（做完一项打一个勾）：
- [x] 服务端程序表：一块屏一个主人电脑（地址 + 开机号），下面挂多个程序（各自的 token 和编号）。`claim`/`claim_free` 返回 `[token, 根窗口编号]`：同一台电脑同一次运行就再加一个程序；屏空着、主人停了或重启过就先清空；指定地址的 `claim` 照旧能接管别的电脑的屏；`claim_free` 遇到别的正在运行的电脑占着返回 `""`。`release` 只关这个程序的窗口和图片；最后一个程序走了，屏幕空出来，关掉所有观看者的界面。
- [x] 归属与隔离：窗口记着属于哪个程序，控件属于它所在的窗口，图片也记程序；上传图片的缓冲按程序分开。所有从 Python 传来的控件或图片编号都要查是不是这个程序的（操作对象、`create` 的父控件、`labelwidget`、Notebook 的页、`lift`/`lower` 的参照、Label/Button/Canvas 用的图片、图片操作）。0 号根是桌面，只能当 `window` 的父控件。
- [x] 操作表：删掉 `geometry`（画布大小、标题、背景）和桌面的 `wm attributes`（`-taskbar`），新增 `screen` 返回显示器大小；`focus` 在活动窗口属于别的程序时只记下那个窗口的焦点、不抢键盘；`focused` 只报本程序的控件。
- [x] 事件：`tk_command`/`tk_event`/`<Configure>`/`tk_changed`/`tk_close` 的 token 按控件所在窗口的程序填；外层界面的 × 只关自己的界面。
- [x] 显示器：分辨率默认是方块组的最大值，方块组变了回到新的最大值；删掉场景标题（`TkScene.title`、`setTitle`/`getTitle`、协议里的标题位），协议升到 `mpy4oc_tk15`。
- [x] 存档：程序表、窗口和图片属于哪个程序。
- [x] Python：`Tk()` 选屏顺序；根窗口的编号由服务端给；根和 Toplevel 用同一套窗口方法（`geometry`/`title`/`state`/`iconify`/`resizable`/`minsize`/`maxsize`/`attributes`/`lift`/`winfo_x` 等）；`_roots`、`_pending` 按 token；`winfo_screenwidth`/`winfo_screenheight` 用 `screen`；去掉 `-taskbar`。
- [x] MPYOS：tkedit（启动时最大化、对话框在编辑器窗口里居中、去掉 `-taskbar`）、tkdesktop 改写、tkimage 和其余演示里按“根 = 整块屏”写的地方。
- [x] 测试（共享、隔离、焦点、事件路由、释放、接管、选屏、分辨率、存档）、文档（tkinter.md 的显示器、桌面与窗口、画布分辨率；README）、游戏内探针。

实现中补充的：
- 外层界面的 × 后来去掉了（用户：tkedit -w 最大化时它挨着编辑器自己的 ×，只能关界面，没用）：外层只剩一圈细边框，关界面用 Esc。
- 关于第 6 条，用户后来说“控件不限制，反正 ops 最终会限制住性能上限”。实测（预热后）每次改动让服务器重排整屏，约 0.1～0.3 µs/控件（256 个约 0.05～0.08 ms，4096 个约 0.5～1.4 ms，1 万个约 2.1 ms），这部分不扣电脑的 ops；ops 只限调用次数（一次 configure 约 160 ops，同步三级每 tick 约 80 次“改+查”）。用户定为 4096，并要求超出时抛异常而不是画不出：控件上限 `TkTree.MAX_WIDGETS` = 4096；绘制记录上限提到 8192（一屏完整场景约 600 KB，仍在每玩家 1 MiB 的突发额度内）；程序的每次调用之后先用不排版的估算（`TkTree.mayOverflow`，`TkPaint.maxRecords/maxBytes`）看会不会画不下，可能时才排版核实（`drawsEverything`），画不下就报 “the display is full”，新建的控件/窗口撤销，删除类操作不报。
- 桌面上的东西都属于某个程序：服务端每次调用前记下“调用的是哪个程序”（`TkTree.caller`），`owned()`/`ownedImage()` 只给这个程序的控件和图片；玩家输入（不经程序）不查。
- 删掉的接口：程序设置画布大小、标题和桌面颜色的 `geometry` 操作，`root.attributes('-taskbar')`（MPY4OC 扩展），组件方法 `getTitle`/`setTitle`，外层界面 × 用的 `WindowClose` 包。外层的 × 现在和 Esc 走同一条路。
- 窗口编号：0 号是桌面，第一个程序的根窗口是 1 号，测试里写死的控件编号都往后挪了一位。
- 修了自己引入的一个问题：给 TkTree 加的 `release(int 程序)` 和原有的 `release(long 会话)` 重载冲突，`tree.release(13)` 会调到新的那个，改名 `releaseProgram`。
- 演示：tkdemo 窗口改成 280×200（加上标题栏放得进单块屏）；tkcanvas、tklayout、tklog、tktext 按“窗口加标题栏、边框和任务栏”检查显示器大小，都要 2×2；tkimage 打开文件时最大化窗口；tkdesktop 改成本程序的窗口上放开新窗口的按钮；tkedit 启动时最大化，对话框开在编辑器窗口中间。

评审：本步没有另开评审（不开子 agent），写测试时逐条对照上面的决定自查。

验证（2026-10-10）：
- 单元测试 518 个全部通过。新增 `TkSharedDisplayTest`（7 个：画不下时报错且新建的窗口撤销、两个程序各有窗口、互相碰不到对方的控件/窗口/图片、桌面只能当窗口的父控件、事件按窗口找程序、一个程序释放不影响另一个、最后一个释放后屏幕空出来；另一台电脑只有指定地址才能接管；后台程序的 focus_set 不抢键盘、`focused` 只报自己的；程序表和归属随 NBT 保存；旧格式存档清空桌面；两个程序同时上传图片不串）和 `TkPythonTest.tkOpensBesideThisComputersWindowsElseOnTheFirstFreeDisplay`（选屏顺序、全被别的电脑占着时报错）；原有窗口、关闭、后台运行、事件分派等测试按新模型改写。
- 游戏内探针（`build/tk-share-probe`，沿用 tk-desktop 的运行目录）12/12：同一台电脑的两个程序在一块屏上各开一个窗口（token 不同）、两个窗口都有标题栏和关闭按钮、任务栏两个按钮；点各自的按钮分别在各自的程序里执行（`None` / `job:1`）；点程序二窗口的 × 只关它（程序一的窗口还在，任务栏剩一个按钮）；外层界面的 × 只关界面，程序一照常运行，重新打开还在；没有 Python 错误。截图已查看：外层没有标题只有 ×，两个窗口并排、当前窗口标题栏是蓝色，任务栏按钮对应。


## 24. ttk 阶段 3：弹出层、Combobox、Spinbox、Entry 选项

需求（2026-10-10，用户）：继续实现 ttk。按第 18 步的分段，这一步是阶段 3。返回值、格式和报错照 WSL 里 Tk 8.6 的实测。

计划（做完一项打一个勾）：
- [x] Entry 选项：`show`（显示成同一个字符，复制出去的也是它）、`justify`（left/center/right）；经典 Entry 和 ttk.Entry 都有；存档可选键。
- [x] Spinbox：经典 `tk.Spinbox` 和 `ttk.Spinbox`，Entry 右边上下两个箭头。`from_`/`to`/`increment`/`values`/`wrap`/`format`/`command`，点箭头、按上下键在服务端调值（经典和 ttk 的算法各照 Tk），`command` 排队给 Python；方法照 tkinter（经典的 `invoke`/`identify`/`selection_*`，ttk 的 `get`/`set`）。
- [x] 弹出层：每块屏一个，画在所有窗口和任务栏之上；点外面就关（这一下不给别的控件）；当前用于 Combobox 的下拉列表。
- [x] ttk.Combobox：`values`/`height`/`postcommand`/`state`（readonly 时点哪都打开列表）、`current()`/`set()`/`get()`；点箭头或按 Down 打开列表，上下键移动、回车或点击选中，选中后发 `<<ComboboxSelected>>`。有 `postcommand` 时先让 Python 跑它再打开。
- [x] 输入校验：`validate`/`validatecommand`/`invalidcommand` 和 `%d %i %P %s %S %v %V %W`。程序的修改在 Python 里先校验；焦点进出也按模式校验。玩家的输入改了方案（见下）。
- [x] 演示 tkttk 加一页 `Inputs`；测试（Java 与 Python，含 Tk 实测值）；文档。
- [x] 游戏内探针。

实现说明（2026-10-11）：
- 玩家输入的校验没有用“服务端先改、不通过再改回去”：玩家连续打字时，后面的按键是在没通过的那次修改上做的，改回去会弄乱。改为服务端把这次修改扣住、通知 Python（`TkTree.notice`，按顺序、不合并），Python 校验后用 `entry validated` 回答，服务端这才改（文字在此期间被程序改过就丢弃），之后的输入排在后面依次处理（最多 64 个）。选中后打字照 Tk 分成删除、插入两次校验；粘贴照 Windows 上的 Tk：先删选区，再插到光标处。
- 新的 Kind（SPINBOX、COMBOBOX）只用于服务端和存档，绘制仍是 ENTRY 记录加 BUTTON/FRAME 部件（箭头是逐行画的三角），协议格式不变；下拉列表的记录 part 从 `TkDisplayList.POPUP_PART` 起，客户端据此在列表打开时把 Esc 发给服务端（只关列表），列表打开时程序收不到任何鼠标、键盘事件（Tk 的 grab）。
- 数字格式 `TkNumberFormat`：C 的 sscanf/printf（经典，含 glibc 的四舍六入五成双、Tk 对 `-format` 的四种 sscanf 写法检查、左对齐宽度只保留 |宽|-1 个字符的怪行为、按范围算默认格式）和 Tcl 的 scan/format（ttk）。经典 Spinbox 在 `values` 里找文字时只找得到纯 ASCII（Tk 用字符数比字节数），也照做了。
- 实测脚本：WSL `/tmp/tkgold/`（spin.py、gold2.py、gold3.py、combo.py、focus.py、keyvar.py）。
- 未做：按住箭头连发；Spinbox 的 `buttonbackground` 等外观选项、`bbox`、`scan_*`；Combobox 打开时的 Home/End 横向滚动。

验证（2026-10-11）：`gradlew build` 通过，单元测试 531 个全过。新增 `TkSpinComboTest` 9 个（数字格式、经典/ttk 步进序列、箭头与按键、下拉列表、列表放上方、扣住的校验修改、show/justify、粘贴、存档），`TkPythonTest.entriesSpinboxesAndComboboxesMatchTk`（校验、Spinbox、Combobox 的返回值与报错，玩家选列表项、在校验中的 Entry 里打字），演示测试加 `Inputs` 页。
- 游戏内探针（`build/tk-inputs-probe`，端口 25568，沿用 tk-desktop 的运行目录）11/11：程序启动、`show` 画成 `***`、Spinbox 两个箭头与 Combobox 箭头、点只读 Combobox 列表出现在框正下方（4 行 66 px）、点 green 一行后程序收到 `<<ComboboxSelected>>` 且框里是 green、列表打开时 Esc 只关列表不关界面、点标题栏关列表且不影响别的控件、点经典 Spinbox 上箭头变 1 并执行 command、在校验数字的 Entry 里键入 1 x 2 得到 12 且 x 触发 invalidcommand、无 Python 错误。截图已查看。

## 25. ttk 阶段 4：Treeview

需求（2026-10-11，用户）：继续实现 ttk 阶段 4 Treeview。返回值、默认值、报错、事件时机照 WSL 里 Tk 8.6 实测（`/tmp/tkgold/tree1.py`～`tree3.py`）。

设计：
- 服务端 `Treeview` 控件保存整棵树（项目 id、父子、text、values、open、tags）、列（id、宽、minwidth、stretch、anchor）、标题（text、anchor、有无 command）、显示列、选择、焦点项、滚动位置；只画看得见的行。自动 id 为 Tk 的 `I%03X`。上限 20000 项、每个控件 2 MiB 文字。
- 几何照 default 主题：边框 1、标题行 19、行高 20、每层缩进 20、展开标记 16 宽；默认列宽 200、minwidth 20。控件宽度变化时按 Tk 的 slack 算法（PickupSlack/DistributeWidth/ShoveLeft）伸缩列宽，拖标题分隔线按 DragColumn。
- 玩家：点行选择（extended：单击设为选择、Ctrl 切换、Shift 从焦点项到此按树序全选；browse 只选一个；none 不选，只移焦点）、点展开标记或双击展开/收起、Up/Down/Left/Right 按 Tk 的 Keynav、Return/space 展开收起、Prior/Next 翻页、滚轮滚行、点标题执行其 command、拖分隔线改列宽。`<<TreeviewSelect>>`（排队）、`<<TreeviewOpen>>`/`<<TreeviewClose>>`、标题 command 经 notice 交给 Python。
- 绘制用旧记录类型：外框 FRAME、标题 BUTTON、每格一条 LABEL（文字截到格宽）、展开标记 ▸/▾。标签的 foreground/background 画出来，其余只记录。
- Python `ttk.Treeview`：CPython 的全部方法（insert/item/delete/detach/move/reattach/get_children/set_children/parent/index/next/prev/exists/focus/selection*/column/heading/set/see/bbox/identify*/tag_*/xview/yview）；`tag_bind` 在 Python 里按事件位置找行。

计划（做完一项打一个勾）：
- [x] 服务端：树、列、显示列、选择、焦点、滚动、列宽算法；操作 `tree`；存档。
- [x] 绘制与玩家输入（点选、展开、键盘、滚轮、标题、分隔线），事件通知。
- [x] Python `ttk.Treeview` 与 `tag_bind`。
- [x] 测试（Java；Python 含 Tk 实测值）；演示（tkttk 加一页或新演示）；文档；游戏内探针。

实现说明（2026-10-11）：
- 服务端 `Treeview`（树、列、slack/DragColumn、玩家输入、绘制）、`TkTreeviewApi`（`tree` 操作）、`TkTreeviewPersistence`（存档可选键，脱离的子树也存）；Kind 加 TREEVIEW（只用于服务端和存档）。选中行底色照 default 主题 #4a6984。
- Python 侧记下 `values` 原来的类型（`item()` 的字典里整数仍是整数，与 Tk 相同）；标题的 command、标签的非颜色选项和 `tag_bind` 都在 Python 里。`tag_bind` 的处理在控件自己的绑定之前调用。
- 和 Tk 的差别：`<<TreeviewOpen>>`/`<<TreeviewClose>>` 在 Tk 里于展开状态改变之前同步发出，这里服务端先改再通知，处理函数里看到的已是新状态；`item(i, 'open')` 一律返回 1/0（Tk 对 `open='yes'` 会返回 True）。标签的 image、font，heading 的 image 只记录。
- 自查修复：标题默认锚点 center 被当成靠右（判断用了 `contains("e")`），标题格画了焦点框；已改并加测试 `anchorsPlaceTheText`。

验证（2026-10-11）：`gradlew build` 通过，单元测试 537 个全过。新增 `TkTreeviewTest` 5 个（玩家点选/Ctrl/Shift/展开标记/双击/键盘导航、标题与分隔线、只画可见行与标签颜色、锚点、存档），`TkPythonTest.treeviewMatchesTk`（tree1～3.py 的返回值、报错、事件、几何、列宽伸缩，以及玩家点标题和双击），演示测试加 `Tree` 页。游戏内探针（`build/tk-tree-probe`）11/11：标题与三个收起的文件夹（▸）、点 ▸ 展开并收到 `<<TreeviewOpen>>`、点行选中（#4a6984）、Down 下移、点 Size 标题执行 command、Left 回父项、再 Left 收起并收到 `<<TreeviewClose>>`、无 Python 错误；修复后重跑仍 11/11，截图已查看。

## 26. ttk 阶段 5：Style 改变外观

需求（2026-10-11，用户）：ttk 阶段 5。按第 18 步的分段，这一步让 `ttk.Style` 真正改变控件外观。

设计：
- 内置主题数据：default、clam、alt、classic 四个主题的全部样式设置（configure/map）、layout、element 名单与选项，从 WSL 的 Tk 8.6.14 导出，冻结为 `stdlib/tkinter/_ttkdata.py`（导出脚本 `/tmp/tkgold/themes.py`、`themes2.py`）。`ttk.Style` 的 configure/map/lookup/layout/element_names/element_options 对内置样式也照 Tk 返回（以前返回空）。
- 画法：ttk 控件按自己的样式链（`style=` 或类名，如 `Accent.TButton` → `TButton` → `.`）在当前主题里解析要画的选项：先各级的 map（按状态），再 configure 的值，与 lookup 同序。与状态无关的部分（padding、borderwidth、无状态的 relief、anchor、按钮最小宽度 `width`）作为控件选项；随状态变的颜色和 relief 作为规则表 `look` 发给服务端，服务端画的时候按控件当时的状态取值。程序在控件上直接设的选项（`background=`、`padding=`、`relief=` 等）优先于样式。
- 服务端状态位照 Tk 的顺序：active（鼠标在控件上，服务端跟踪）、disabled、focus、pressed、selected、background、readonly、hover，以及只在 Python 记录的 alternate、invalid、user1～3（Python 改变时同步给服务端）。
- 画出来的样式选项：background、foreground（各控件）；relief、borderwidth、padding、anchor；Entry/Combobox/Spinbox 的 fieldbackground、arrowcolor；Checkbutton/Radiobutton 的 indicatorcolor（选中时的填色）；Notebook 与 TNotebook.Tab 的颜色；Labelframe 与 TLabelframe.Label；Progressbar、Scale、Scrollbar 的 troughcolor；Treeview 与 Heading 的颜色和 relief。字体只有 unifont 一种，font 只记录；各主题元素的形状（clam 的平面渐变、alt 的复选框形状等）不另画，只换颜色与 relief。
- `theme_use`、`configure`、`map`、`theme_settings` 之后，这个根窗口下的 ttk 控件重新解析并更新外观。

计划（做完一项打一个勾）：
- [x] 内置主题数据与 Style 查询照 Tk（含 lookup、layout、element_names/element_options）。
- [x] 服务端：`look` 规则表、状态位（含鼠标所在控件）、各控件画法使用样式值；存档。
- [x] Python：解析样式链、控件选项与规则表、程序设的选项优先、样式或主题变化时更新；按钮最小宽度等主题默认尺寸。
- [x] 测试（Java；Python 含 Tk 实测值）、演示（tkttk 能切主题）、文档、游戏内探针（含截图对比四个主题）。

实现说明（2026-10-11）：
- 服务端 `TkLook`（每个键最多 32 条规则 [开位, 关位, 值]，最多 32 个键；颜色存 0xRRGGBB，relief 存名字）；`TkTree.state(w)` 合成状态位（程序给的 `held` 位 + disabled/readonly/selected/focus/pressed/hover，按钮在鼠标下另加 active）；`TkPaint` 与 `Treeview.paint` 各处改为先查 look 再用旧默认色。新控件选项 `look`、`held`、`minwidth`（按钮最小字符宽），都进存档。协议不变（仍用旧记录类型）。
- Python：`ttk.Style` 的注册表载入 `_ttkdata`，查询照 Tk（element 名按全名再逐级去前缀找，跨父主题）；样式或主题变化后 `after(0)` 合并成一次 `_restyle_root`。每类控件声明自己画哪些键（`_look_spec`）和哪些作为布局选项（`_layout_spec`）；程序直接设过的选项钉住，不被样式覆盖。`_padding`/`_pixels` 支持 c/m/i/p 单位（按 96 dpi）。
- 颜色名：以前只认 8 个名字，classic 主题的 `TProgressbar` 背景 `SteelBlue` 被丢掉、画成默认绿色（游戏内截图发现）。新增 `stdlib/tkinter/_tkcolors.py`：Tk 8.6 认的全部无空格颜色名 665 个（从 WSL Tk 8.6.14 的 `winfo rgb` 导出，脚本 `/tmp/tkgold/colors.py`），短表里没有时才载入；测试加 classic 进度条颜色与 `SteelBlue`/`grey50`。
- 和 Tk 的差别：各主题元素的形状不另画（clam 的高光/暗边、alt 的复选框样式等），只换颜色和 relief；font 只记录；`ttk.Frame` 不取样式的 borderwidth（与 Tk 实测尺寸 65x35 一致）；带空格的颜色名（`alice blue`）不认。

验证（2026-10-11）：`gradlew build` 通过，单元测试 541 个全过。新增 `TkLookTest` 3 个（规则按状态取值与取反位、鼠标所在控件的 hover/active、存档），`TkPythonTest.stylesChangeHowWidgetsLook`（内置主题查询值照 themes*.py 实测；default 与 clam 下按钮、复选框、进度条、标签页画出的颜色，悬停变亮、按下凹陷，程序设的背景优先，TButton 最小宽度，classic 的 SteelBlue），旧的 Style 测试按 Tk 实测改了期望，演示测试切主题。游戏内探针（`build/tk-style-probe`）7/7：四个主题下按钮、未选标签页、选中行的颜色（default D9D9D9/C3C3C3/4A6984，clam DCDAD5/BAB5AB/4A6984，alt 同 default，classic 选中行 C3C3C3），真实鼠标移到按钮上变 ECECEC，无 Python 错误；五张截图已查看，颜色名修复后重跑仍 7/7，classic 进度条为 SteelBlue。

## 27. ttk 阶段 6：Menu、Menubutton、OptionMenu

需求（2026-10-11，用户）：ttk 阶段 6 Menu。返回值、默认值、报错照 WSL 里 Tk 8.6 实测（`/tmp/tkgold/menu1.py`、`menu2.py`）。

Tk 实测要点：菜单默认 `tearoff=1`，第 0 项是 tearoff 项（类型 'tearoff'）；`index` 认 end/last/active/none/@y/数字/标签通配，数字超出取最后一项，负数和找不到报 `bad menu entry index`；`type`、`entrycget` 也按 index 解析；invoke 复选项切换变量（onvalue '1'/offvalue '0'），单选项设成 value，命令项返回命令的返回值，禁用、分隔、级联项返回 ''；复选项默认变量名是标签，单选项默认 `selectedButton`、value 默认是标签；分隔项只有 background，tearoff 项只有 background/state；entryconfigure 的五元组。菜单栏夹在标题栏和内容之间：`winfo_height`、`wm geometry` 的高都不含它，geometry 的 y 是整个窗口的顶。经典 OptionMenu 用 command 项（_setit），不设变量初值；ttk.OptionMenu 用 radiobutton 项并设默认值。

设计：
- 服务端 `Menu` 控件（Kind MENU，不参与排版，不直接画）：项列表（类型、label、accelerator、state、underline、是否选中、indicatoron、级联菜单 id、各项颜色）、菜单颜色、postcommand/<<MenuSelect>> 是否要通知、通知回调。项由 Python 整表发来（`menu` 操作的 entries），变量与命令都在 Python。服务端父控件是 Python 祖先里最近的 Frame（Tk 的菜单本是顶层窗口；Python 里删除父控件时显式删菜单）。
- 菜单栏：窗口选项 `menu`。标题栏下多一条 20 像素的菜单栏，`geometry` 的高与 `winfo_height` 不含它，y 指整个窗口的顶；设上或去掉菜单栏时窗口顶不动，内容区上下移。菜单栏只排一行，放不下的项不画（Tk 会折行）。
- 弹出的菜单链（每块显示器一条，与 Combobox 的列表互斥，同在最上层）：从菜单栏、Menubutton、程序的 `post`/`tk_popup` 打开；每级一个盒子（凸起边框、20 像素一行、分隔与 tearoff 8 像素、左边留勾选标记位、右边加速键和级联箭头）。打开时它拿走鼠标和键盘（Tk 的 grab）：移动时高亮跟随、停在级联项上打开下一级、在菜单栏上移到别的项换成那一项的菜单；按下项再在项上松开执行（command/复选/单选，执行前整条链关闭）；按在链外关闭全部且不往下传；Up/Down（跳过分隔与禁用项）、Right 打开级联或换到菜单栏下一项、Left 关一级或换到上一项、Return/space 执行、Esc 关一级；F10 打开当前窗口菜单栏的第一项；菜单里按带下划线的字母执行该项。
- 执行与通知：玩家执行一项时服务端发 `invoke` 通知，Python 的 `Menu.invoke` 按 Tk 改变量、调命令；服务端打开带 postcommand 的菜单时发 `post` 通知（先打开再运行 postcommand，项随后更新——与 Tk 先运行后显示不同）；绑了 `<<MenuSelect>>` 才发 `select` 通知。
- Menubutton（经典与 ttk）：服务端新控件（Label 加 menu、direction、indicatoron），按下打开它的菜单（direction below/above/left/right/flush），按住拖到项上松开执行；打开时经典的 raised 画成 sunken，ttk 为 pressed。OptionMenu（经典与 ttk）在 Python 里照 CPython 写。
- Python：`Menu` 的全部方法（add/insert 各类型、delete、entrycget/entryconfigure、index、invoke、type、post/unpost/tk_popup、activate、yposition/xposition）与选项默认值照实测；菜单项的变量变化时重发选中状态；默认变量名的全局变量放在根窗口的表里，`getvar`/`setvar` 只认这些名字。tearoff 项画成虚线，invoke 它什么也不做（Tk 会撕下成新窗口）。
- 存档：Menu 的项与颜色、Menubutton 的菜单与方向、窗口的菜单栏；打开着的菜单链不存。

计划（做完一项打一个勾）：
- [x] 服务端 Menu、Menubutton、菜单栏（窗口几何改为“标题栏 + 菜单栏”）、菜单链的绘制与玩家输入、通知、存档。
- [x] Python：Menu、Menubutton、OptionMenu（经典与 ttk），根窗口 `menu=`。
- [x] 测试（Java；Python 含 Tk 实测值）、演示、文档、游戏内探针。

实现说明（2026-10-11）：
- 服务端新类 `Menu`（项表、一级菜单的尺寸与绘制、作菜单栏时的排布与绘制）、`Menubutton`（Label 加 menu/direction/indicatoron，画成 LABEL 记录加指示器）、`TkMenus`（打开的菜单链：来源是菜单栏项、Menubutton 或程序；鼠标、按键、级联、执行）、`TkMenuApi`（`menu` 操作：entries/post/unpost/posted/activate/active/yposition/index_at）；Kind 加 MENU、MENUBUTTON（只用于服务端和存档，协议不变）。窗口的装饰高度改为 `TkWindow.top(window)`（标题栏 + 有菜单栏时 20），geometry、最大化、夹在屏内、改大小、默认位置都用它；`setMenu` 保持窗口顶不动。菜单链画在弹出层（parts 从 POPUP_PART 起），与 Combobox 列表互斥；客户端的 Esc 照旧发给服务端关一级。
- Python 侧 `Menu` 的服务端父控件是祖先里最近的 Frame（菜单可以以 Menu、Menubutton 为 master），`_window = True` 让父控件删除时显式删它；项的变量变化经 `Variable._menus` 重发整表；`getvar`/`setvar` 只认菜单用的全局变量名。`Widget.configure` 支持 `menu=`（窗口与 Menubutton），并把 `anchor` 的 `'c'` 等前缀认作 center（OptionMenu 用 `anchor='c'`）。
- 顺手修复：`TkPackLayout.measure` 把不认识的控件都当 Label（Menu 会类型转换出错）；窗口的绘制记录上限没算上一步加的 4 条边框高亮。
- 和 Tk 的差别：菜单栏只排一行；玩家打开带 postcommand 的菜单时先显示再运行它；tearoff 不能撕下；字体、图片、columnbreak 只记录；项的标签只能一行。

验证（2026-10-11）：`gradlew build` 通过，单元测试 549 个全过。新增 `TkMenuTest` 6 个（菜单栏位置与 geometry、下拉/跟随/级联/执行/菜单栏上的命令项/按在外面关闭、键盘 F10/上下/左右/Return/Esc/下划线字母、Menubutton 弹出与拖动执行与方向、程序 post 与 tk_popup 与 yposition/@y 与删除时关闭、存档），`TkPythonTest.menusMatchTk`（menu1/2.py 的返回值、默认值、报错，invoke 与默认变量，菜单栏不计入 geometry，经典与 ttk 的 Menubutton/OptionMenu，玩家用鼠标和 F10 选项后程序收到并更新勾选），演示 tkttk 加菜单栏（File、Theme）且主题改用 ttk.OptionMenu。游戏内探针（`build/tk-menu-probe`）15/15：菜单栏、点 File 下拉、真实鼠标停在 More 上打开级联、点 Inner 执行并全部关闭、点 Bold 切换变量、F10/上/下/Return、ttk.Menubutton 弹出并执行、OptionMenu 选值后显示新值、菜单栏命令项 Help、Esc 只关菜单不关界面、无 Python 错误；截图已看（修了高亮行右侧箭头盖住凸起边框的问题后重跑仍 15/15）。

## 28. ttk 阶段 7：Panedwindow、LabeledScale（其余）

需求（2026-10-11，用户）：阶段 7。第 18 步的分段里阶段 7 是“其余”：ttk 还没有的控件只剩 Panedwindow（连同经典的 tk.PanedWindow）和 LabeledScale。返回值、几何、报错照 WSL 里 Tk 8.6 实测（`/tmp/tkgold/paned1.py`、`paned2.py`）。

Tk 实测要点：ttk 分隔条 5 像素（clam 6），默认竖排；多出的空间按 weight 分（1:2:0 在 300 宽里得 87/143/60，缩到 101 得 21/10/60），没有 weight 时最后一个窗格占到底；`sashpos` 夹在两端、推开相邻分隔条；兄弟控件可作窗格。经典默认横排、分隔条 3、边框 1，`stretch` 默认 `last`，可伸缩窗格按当前大小比例分空间（c1 always + c2 last 在 500 宽里得 427/68），`sash place` 受 `minsize` 限制并设窗格的 `width`。LabeledScale 超出范围时变量改回上一个有效值。

计划（做完一项打一个勾）：
- [x] 服务端 `Panedwindow`（经典与 ttk 两套排布、分隔条、玩家拖动与悬停高亮、存档）、`paned` 操作。
- [x] Python：`tk.PanedWindow`、`ttk.Panedwindow`（`PanedWindow` 别名）、`ttk.LabeledScale`。
- [x] 测试（Java；Python 含 Tk 实测值）、演示（tkttk 加 Panes 页）、文档、游戏内探针。

实现说明（2026-10-11）：
- 服务端 `Panedwindow extends Frame`（Kind PANEDWINDOW，绘制用 FRAME 记录），照 ttkPanedwindow.c 的 PanedSize/PlaceSashes/ShoveUp/ShoveDown/AdjustPanes 和 tkPanedWindow.c 的 ComputeGeometry/ArrangePanes/MoveSash/AdjustForSticky/IdentifyCoords 写；窗格经 `container` 由它排（`winfo_manager()` 为 panedwindow），兄弟窗格在 measure 时补量一次；存档在所有控件建好后恢复窗格（兄弟可能在它后面）。`TkPanedApi`（insert/forget/panes/pane/sashpos/sash_coord/sash_place/identify）。玩家拖动分隔条记在 `TkTree.sashDrags`，悬停高亮与窗口边框一样用 #5DA9FF。
- Python：经典选项按 Tk 实测的默认值与五元组记录，画得出的发给服务端；`ttk.Panedwindow` 是 ttk 混入类加经典类，`_restyle` 时按 `Horizontal.Sash`/`Vertical.Sash` 样式取分隔条宽度。`Widget` 的 master 允许是 PanedWindow。LabeledScale 照 CPython 移植（没有 `<Map>`，创建时调用一次 `_adjust`；变量改名 `_ls_variable`，避免与控件的 textvariable 字段冲突）。
- 连带修正：`update_idletasks()` 运行 `after_idle` 的回调（以前不运行，主题切换后 `update_idletasks` 量到的还是旧尺寸），ttk 样式变化的重画改用 `after_idle`；`Variable.get()` 不再从 ttk Scale 读夹过的值（ttk::scale 不写回变量）；非字符串的 `text` 照 Tk 保留原值供 `cget`；经典布局里一处正负号写反（不可伸缩窗格何时被迫缩小）测出后已改。测试宿主的步数上限 50 万提到 200 万（演示多了一页后要更多步）。

验证（2026-10-11）：`gradlew build` 通过，单元测试 556 个全过。新增 `TkPanedTest` 6 个（ttk 按 weight 分配与增减、余数分法、sashpos 推开、clam 分隔条、竖排与兄弟窗格；经典默认 last 伸缩、sash place 与 minsize、always 按比例、padx/pady/sticky/hide/before、把手识别；玩家拖动与悬停高亮、存档），`TkPythonTest.panedwindowsMatchTk`（paned1/2.py 的返回值、几何、报错，LabeledScale），演示测试加 Panes 页。游戏内探针（`build/tk-paned-probe`）8/8：ttk 窗格排布、LabeledScale 初值、真实鼠标在分隔条上点亮、拖 ttk 分隔条 +40、拖经典分隔条 +20、拖滑块到 10 后标签显示 10 且在滑块上方、无 Python 错误；截图已看。

## 29. 对话框：messagebox、simpledialog、filedialog

需求（2026-10-11，用户）：messagebox/simpledialog/filedialog/Text。分两步：本步三个对话框模块和它们要的模态基础，第 30 步完整的 Text。返回值、报错、控件结构照 WSL 里 Tk 8.6 实测（`/tmp/tkgold/dialog1.py`：用 `after` 在对话框里点按钮）。

Tk 实测要点：tk_messageBox 是 `.__tk__messagebox`（ttk 控件：bot、top、msg、dtl、bitmap、按钮），transient、grab、焦点在默认按钮；报错 `bad -type value ...`、`bad -icon value ...`、`bad -default value "x": must be abort, retry, ignore, ok, cancel, no, or yes`；simpledialog 的 `.!_querystring.!frame.entry`、初值全选、焦点在 entry；文件对话框 `.__tk_filedialog`（contents：f1 的 Directory: 菜单与 up，icons 列表，f2 的 File name:、Files of type:、Open/Cancel），取消时 askopenfilename/askopenfilenames/askdirectory 返回 `()`。

计划（做完一项打一个勾）：
- [x] 服务端：wm transient（无任务栏按钮、总在主窗口上、随主窗口隐藏显示、存档）、每个程序的 grab（`grab` 操作；被挡的点击把对话框提到前面，窗口边框照常可用）。
- [x] Python：wait_window/wait_variable/wait_visibility、grab_*、transient、winfo_viewable 等；`tkinter/commondialog.py`、`messagebox.py`、`simpledialog.py`（照 CPython 移植）、`filedialog.py`（Tk 样式的三种对话框 + 旧的 FileDialog 类）。
- [x] 测试、文档、游戏内探针。

实现说明（2026-10-11）：
- 服务端 `TkWindow.master`/`hiddenWithMaster`、`TkWindow.transients`；`TkTree.raise` 连带提起 transient，`hide`/`show` 连带隐藏显示；`tasks()` 跳过 transient。`TkTree.grabs`（程序 → 控件）、`blocked`、`raiseGrab`；`TileTkDisplay.input` 对被挡的输入（菜单栏也算）只把对话框提到前面，不交给程序，拖动与松开照常（按下发生在挡之前的拖动不受影响）。grab 不存档。
- Python：`commondialog._modal` 记下原来的 grab 与焦点，对话框关后恢复（Tk 的 SetFocusGrab/RestoreFocusGrab）；`simpledialog` 基本是 CPython 3.12 源码（名字改编的私有属性改成普通名字）；`filedialog` 的列表用 Listbox（Tk 是图标画布），模式匹配自写（没有 fnmatch 模块），文件系统用 `os.listdir`/`os.stat`/`os.getcwd`。
- 连带修正与补齐：`bind`/`event_generate` 接受 `<Escape>`（只在 Python 里）；`Toplevel(class_=)`；只记录的选项（Button default、Label justify、Message aspect、exportselection）；CPython 的常量；测试宿主里内置方法不收关键字参数（测试里不用 `dict.update(k=v)`）。

验证（2026-10-11）：`gradlew build` 通过，单元测试 559 个全过。新增 `TkGrabTest` 2 个（transient 的任务栏、叠放、随主窗口隐藏显示、不许成环；grab 挡住本程序其他窗口而不挡别的程序、提到前面、新 grab 替换旧的、控件删除时放开），`TkPythonTest.dialogsMatchTk`（dialog1.py 的全部返回值与报错、消息框的标题/transient/grab/焦点/文字、关闭框的答案、simpledialog 的结构与三种问法、值不对时的警告和警告后 grab 回到原对话框、filedialog 在测试里造的文件系统上：列表与过滤、打开不存在的文件、覆盖确认、补扩展名、双击进入文件夹、多选、目录对话框、选项报错）。游戏内探针（`build/tk-dialog-probe`）6/6：问答框弹出（标题、Yes/No、详细说明）、它开着时点主窗口的按钮没有作用、点 Yes 得到 True、simpledialog 里打字后 Return 得到 bob、无 Python 错误；截图已看。

## 第 30 步：Text 标签、标记、撤销与查询

- [x] Java Text/TkTextApi：标签优先级与区间、颜色/下划线/删除线，任意标记及 gravity、索引修饰词与显示行、undo/redo、search/count/dump/bbox/dlineinfo、NBT 存档恢复。
- [x] Python Text：CPython 风格接口、tag_bind（含进入/离开标签、键盘）、Modified/Selection 通知，销毁后释放标签回调；None 和空元组经过真实 OC 转换的测试。
- [x] genStdlib：使用本项目 Java 编译器重新冻结 34 个模块。需先单独执行 genStdlib，再运行 test/build，避免同一次任务图里的 compileJava 仍使用旧生成文件。
- [x] Windows 原生 CPython/Tk 8.6.12 生成 text1/text2/text3 的 102 个金标准检查点；生成器 tools/tk-text-api-golden.py、公共用例 src/test/resources/tk-text-cases.py、TkPythonTest.textMatchesTk。
- [x] Java 功能和边界测试：标签/标记/顺序、撤销分组和键盘快捷键、清洁标志、NBT（含待分组与 redo 历史）、共享预算失败后重试、区间上限、正则工作量保护、水平滚动和叠加层预算、display 索引与坐标。
- [x] tkedit 启用 undo；整篇/单次替换改用原子的 replace；打开另一份文件清空旧历史。编辑器测试检查撤销、重做及加载后无旧历史。
- [x] docs/tkinter.md 更新 Text 接口、兼容范围、记录上限和不支持的显示选项。
- [x] 游戏探针发现并修复文件对话框在单块显示器上右侧按钮出屏的问题：约束模态窗口尺寸，目录和类型菜单随宽度伸缩；新增长路径、长过滤器名称的三种文件对话框布局测试。
- [x] 最终整体构建和游戏内探针。

本步补正：sel 不再假定为优先级第一个标签；传给 OC 的颜色二元组兼容数字键 Map；负列索引、最终换行搜索、报错原文和空返回与实测一致；样式覆盖层正确裁剪水平滚动且可同时画下划线/删除线；绘制预算包括 Tab 展开、双份文字和装饰记录。撤销、标记 gravity、修改标志以及标签边界顺序都会存档。

同位置的 dump 顺序按 Tk 的左/右 gravity 及创建顺序交错排列，标记和标签边界共用顺序号；依据 [Tk 8.6 的 SplitSeg/TkBTreeLinkSegment](https://github.com/tcltk/tk/blob/core-8-6-branch/generic/tkTextBTree.c) 并由原生 Tk 用例验证。保留字体、spacing、tabs、elide 等选项但不绘制，嵌入图片/窗口、peer 不支持。

验证（2026-10-11）：原生 Windows `gradlew.bat genStdlib` 后单独执行 `gradlew.bat build --offline` 成功，574 个单元测试全部通过（0 skipped），其中 Text 的原生 Tk 金标准 102 个检查点全部匹配。新增 TkTextFeaturesTest 13 个、TkPythonTest.textMatchesTk、文件对话框小屏布局测试；旧 Text、Hardening、Relief、tkedit 及联合存档恢复测试全部通过。

游戏内探针 `build/tk-text-api-probe`：本地 runServer + runClient，13/13 通过。检查真实 OC 桥接的错误原文/None/空元组/search count、颜色/下划线/删除线、标签点击/进入/离开、Ctrl+Z/Ctrl+Shift+Z、messagebox 的模态及返回值、simpledialog 输入和 Return、filedialog 初始文件名完整显示、Save 点击及路径返回、无 Python 错误。Text、编辑后、消息框、文件框四张截图已检查。探针使用真实 MpyArchitecture 与网络输入；文件框使用隔离的内存文件系统夹具，未写玩家磁盘。测试程序退出后两端自动关闭，并恢复测试目录原来的探针 jar。

## 第 31 步：Tk 命令默认后台与 shell Tab 补全

需求（2026-10-12）：自带 Tk 命令像 tkedit 一样默认后台运行；Tab 补全命令名，有歧义时输出候选。

- [x] 10 个 Tk 示例沿用 tkedit 的启动检查：从 shell 启动时进入后台，已在后台时直接运行；直接用 Python 执行脚本保持前台语义。
- [x] 新增真实 shell/MPY VM 回归：11 个 Tk 命令逐一检查直接启动、显式后台启动、事件循环回调、关闭后清理，以及不重复创建任务。
- [x] shell 从内置命令和当前 `/bin/*.py` 收集、去重、排序候选；唯一匹配补全并加空格，有歧义补公共前缀并列候选，无匹配保留输入。
- [x] Terminal.read 支持彩色提示符重画；兼容 Tab 字符与按键码；行中补全保留候选后方的参数和光标。
- [x] MpyosShellTest：实时命令列表、内置命令、缺失磁盘、真实键盘输入流程、候选显示、提示符颜色、行中参数与 `&`、REPL 回调兼容。
- [x] 追加路径补全（2026-10-12）：所有参数支持文件/目录，`cd` 只补目录；相对/绝对路径、`./`、`../`、挂载盘，隐藏项需输入点前缀。目录补 `/` 可继续补下一层，文件补空格。按 shell 的引号规则识别光标所在的参数，自动引用空格及嵌入引号，并保留后方参数；歧义时先算未引用路径的公共前缀，再生成可继续编辑的输入，避免截断引号。
- [x] 新增 MpyosShellTest 4 项：路径/挂载盘/目录筛选、空格及两种引号往返解析、真实 Tab 输入逐层补全和候选绘制、光标在行中时保留引号及 `&`。原有 3 项命令名和 REPL 兼容测试仍通过。
- [x] 中英文 README、Tk 文档及启动提示更新；说明已有硬盘需更新 `/lib/shell.py`、`/lib/term.py` 和 `/bin/tk*.py` 后重启。
- [x] 完整构建：2026-10-12 追加路径补全后，原生 Windows `gradlew.bat build --offline` 通过，583 个测试全部通过（0 failed / errors / skipped）。本步新增 11 个 Tk 命令的后台启动回归及 MpyosShellTest 7 项；启动提示符合 80 列限制，最终 JAR 内的 shell、term 和提示文件已逐一核对。

## 第 32 步：OpenOS 风格命令查找与 cwd

需求（2026-10-12）：`spawn`、`run_command` 支持路径；相对路径按当前工作目录 cwd 解析。对照实际依赖 OpenComputers 1.12.50-GTNH 的 OpenOS 源码。

- [x] 默认搜索路径 `/bin:/usr/bin:/home/bin:.`，当前目录放最后；内置命令优先。原文件名优先，不存在时补 `.py`；目录不可执行；含 `/` 的路径按 cwd 解析并跳过搜索路径。
- [x] 共用 `command_path`；`find_command` 保留返回源码的接口；`which` 返回实际路径；Tab 从全部搜索目录收集候选，并支持显式脚本路径。
- [x] 前后台程序的 shell 视图各自持有 cwd 和搜索路径，`sh.cwd` 跟随程序的 `os.chdir()`；后台任务捕获启动时的 cwd/path，显式 `cwd=` 按调用者 cwd 解析。
- [x] 执行时注入 `__file__` 和 `__name__`，错误使用实际脚本文件名；保留 Tk 默认后台和直接 Python 执行的语义。
- [x] 真实 MPY VM 回归新增 4 项：搜索优先级、扩展名/无扩展名/目录与挂载路径、Tab 搜索目录及命令路径、嵌套执行与后台子任务的 cwd/path 继承、`which` 及 Python 参数路径一致性。定向测试 MpyosShellTest 9 项和 TkPythonTest 59 项全部通过。
- [x] 中英文 README 和 Tk 文档更新 API、安装升级说明。
- [x] 完整构建与最终 JAR 资源检查：2026-10-12 原生 Windows `gradlew.bat build --offline` 成功，587 个测试全部通过（0 failed / errors / skipped）；最终 JAR 中的 `shell.py`、`which.py` 与工作区源码逐字核对一致。

参考：[OpenOS 默认 PATH](https://raw.githubusercontent.com/GTNewHorizons/OpenComputers/1.12.50-GTNH/src/main/resources/assets/opencomputers/loot/openos/boot/02_os.lua)、[shell.resolve](https://raw.githubusercontent.com/GTNewHorizons/OpenComputers/1.12.50-GTNH/src/main/resources/assets/opencomputers/loot/openos/lib/shell.lua)。这里用 `.py` 扩展名和 `sh.path` 属性，没有引入完整的环境变量 API。

## 第 33 步：Python 环境变量接口

需求（2026-10-12）：将命令搜索路径接入 `os.environ` / `os.getenv()`。

- [x] 冻结 `os` 提供字符串环境映射、getenv、常用映射操作；环境隔离在各 OC 电脑的 VM 内，裸程序初始为空，MPYOS 为缺省 PATH 设置 OpenOS 风格值。
- [x] `sh.path` 改为 PATH 的兼容属性；命令查找、`which`、Tab 使用实时 PATH，空或缺失 PATH 禁用裸文件名搜索，显式路径仍可执行。
- [x] 独立程序启动时复制调用者环境，同程序线程和嵌套 run_command 共用环境；后台任务捕获启动时副本，退出清理期间保留线程环境，结束后回收。
- [x] 补充 VM 的 `dict(mapping)` 转换，使 `dict(os.environ)` 可用于独立快照。
- [x] 新增环境映射/错误输入/VM 隔离/存档恢复、PATH 与补全联动、父子程序和线程共享隔离、停止后 finally 环境保留的回归测试。
- [x] 中英文 README 与 Tk 文档说明接口、作用域、默认 PATH 配置方式。
- [x] 验证（2026-10-12）：原生 Windows 单独运行 `genStdlib` 重新冻结 34 个模块，74 个定向测试通过；随后 `gradlew.bat build --offline` 成功，593 个测试全过（0 failed / errors / skipped）。最终 JAR 的 shell.py 和冻结 os 字节码与生成源码核对一致。

## 后续可选
- Toplevel 多屏实测。
