# Redmi-Watch-Bridge

<img src="icon.png" width="96" alt="Redmi-Watch-Bridge">

把小米运动健康能用 **SPP** 连上的手环接到 ColorOS **设备空间** 和 **OPPO 健康**。

> 本仓库是 [MiaM1ku/Miband-OPlusBridge](https://github.com/MiaM1ku/Miband-OPlusBridge) 的一个分支（fork），沿用原作者的全部功能与协议实现，仅以独立仓库名发布。所有核心工作归属原作者。

## 与原作者版本的区别

本分支基于原作者 1.11.5 版本，在其「桥接 → OPPO 健康」单目标写入之外，增加了第二个同步目标 **小米健康**（`com.mi.health`），让同一只手环的数据在 OPPO 健康和小米健康两端都能看到：

- **双端同步**：步数、心率、血氧、压力、睡眠等明细，在一次手环数据拉取后同时投递到 OPPO 健康与小米健康。
- **手动同步**：健康页新增「立即同步到小米健康」按钮，默认回看近 7 天，只补齐小米健康里缺失的记录，已有数据自动跳过。
- **自动同步**：息屏状态下、一轮历史同步落盘后自动补写一次，带 10 分钟间隔下限与亮屏保护，避免在用户操作时打断小米健康。
- **同步日志**：主页新增「日志」页签，记录每次补写的结果（写入条数 / 状态 / 时间），支持刷新与清空。
- **界面重构**：主页由单页长列表改为「概览 / 健康 / 设备 / 日志 / 设置」五页签，并采用模拟毛玻璃的卡片化视觉。
- **今日概览**：概览页新增今日健康数据（步数 / 心率 / 睡眠 / 血氧）与数据管线状态一览。
- **关闭手机计步**：OPPO 健康页新增「关闭手机计步」开关，收回 `ACTIVITY_RECOGNITION` 权限，让 OPPO 首页步数只来自手环、不再累加手机传感器计数。
- **稳定性加固**：修复日汇总步数快照滞后导致的步数不一致、断连后的离线宽限期、配对预算跨进程持久化等若干问题。
- **兼容 OPPO 健康 6.11+**：健康 6.11 起设备空间不再渲染桥接投影进去的手环卡片，原「防重复让位」逻辑会让手环在设备空间彻底消失。现在按健康版本区分处理，并在让位期间做周期自检，避免状态永久卡死。

其余功能（协议、连接接管、通知转发、睡眠暂停音乐、历史步数核对等）与原作者一致，未做改动。

每次改动的明细见 [CHANGELOG.md](CHANGELOG.md)。

> 注意：以上差异针对原作者的 **1.11.5** 基线；若原作者后续发布新版本，本分支的改动需自行迁移，不保证跟随上游。

在设备空间里管理这只手环，查看连接与电量，并把步数、心率、静息心率、步行平均心率、睡眠写入 OPPO 健康。静息心率取自手环每天的日报；步行平均心率按当天走路分钟的步数和心率推算，所以两者都按天各一条，和 OPPO 手表自己的口径一致。手机通知和来电转到手环，当前播放走 OPPO 健康自己的音乐控制。首页可打开「入睡后暂停音乐」：手环报入睡后暂停手机播放，醒来后保持暂停。通过睡眠记录报告入睡的手环，会在记录同步后暂停。手环查找手机会走 OPPO 健康自己的响铃，健康设备页也可以让这只手环响。健康首页若在导入完成前就打开，导入结束后会再读一次。日常在设备空间和 OPPO 健康里使用。小米手环 11 已实机验证。手环 8 / 8 Pro / 9 / 9 Pro / 10 / 10 Pro 及 NFC、活力版，官方重连是 SPP 或 GATT + WearAuthV2 即可导入接管。SPP V2、SPP V1（8 Pro）和纯 BLE（fe95）在连接时按采集 profile 分支。纯 BLE 用已导入的密钥重连，系统蓝牙配对断开后照样能连；SPP 连接依赖系统蓝牙配对。

接管期间连接留在前台服务里重连。健康还在、服务被系统杀掉时，健康读卡片或转发通知会把服务拉起来。控制中心可以添加「手环」，下拉或点一下会拉起连接。后台限制拦住时用 root 启动。服务这边先把健康进程绑回来，绑定中断时改用 root 拉起健康。连接还在建立时，通知先留着，连上后续发；普通通知保留 60 秒，来电保留 15 秒。健康在后台拉起的是连接服务。通知保持一条折叠的「手环已连接」。


包名：`io.github.miam1ku.mibandoplusbridge`

点击链接加入原作者群聊【Miband-OPlusBridge】：https://qm.qq.com/q/eEk3EsgSH0

## 安装

1. 安装支持 libxposed API 102 的框架（Zygisk），例如 [Vector](https://github.com/JingMatrix/Vector)。1.6.8 之后的版本按 API 102 加载，框架要支持这个 API。
2. 安装本模块 APK，在 LSPosed 中启用，作用域勾选：
   - `com.heytap.mydevices`（设备空间）
   - `com.heytap.health`（OPPO 健康）
   - `com.mi.health`（导入绑定时需要）
   - `com.coloros.alarmclock`（时钟）
3. 在 KernelSU 里对本应用授权。拿到 root 后，本应用会在 ColorOS 里给自己和 OPPO 健康打开「自启动」和「关联启动」。这两项开着时，更新模块后 OPPO 健康会拉起桥接。更新后打开一次本应用，也能把桥接拉起来。
4. 打开「小米手环桥接」：检查 Root → 导入绑定（从本地数据库读密钥）→ 添加到健康。手环 8 及以后使用已确认的蓝牙参数，导入后直接添加即可。
5. 第一次安装，或从 1.6.8 及以前的版本升级时，强停「设备空间」和「OPPO 健康」后再打开。之后的更新会热重载。
换到本包名后重新导入。旧包名 `io.github.oplusband.bridge` 的数据留在原包里。

## 界面

首页从上到下：手环状态和一个主按钮 → 首次设置清单（三步，完成后隐藏）→ 待处理事项（附近设备、通知、OPPO 健康通知使用权）→ 功能 → 帮助 → 管理 → 高级设置。界面跟随系统深色模式。

## 历史步数汇总核对

同步后，桥接会核对近期历史日期的完整分钟记录、设备日总与健康合并日总。满足完整性和设备归属校验的差异，显示在「设备详情 → 日汇总核对」。分钟证据覆盖全天 1440 分钟，并与健康逐分钟的步数、热量、距离精确一致后，才提供校正入口。

核对当天实际步数后，点「核对并校正」确认指定日期。合并日总也可能包含真实手机计步；需要保留这部分数据时，选择「保留现状」。确认绑定当前账号、设备、日期和证据指纹，数据变化后重新核对。

校正在健康已有数据库服务中执行：事务内重取证据，只下调获准的偏大合并步数，其余运动指标保留，目标完成状态和同步标记由健康原生流程更新。提交后再次读回确认，再刷新核对记录。重新打开健康页面可查看汇总。

## 热重载

模块在 `META-INF/xposed/module.prop` 里声明 `autoHotReload=true`。宿主进程运行时安装新版 APK，框架会在宿主里换成新代码，宿主进程持续运行：旧代码先停掉自己的线程、广播接收器和内容观察者，新代码移除旧 hook 并重新装上。已经打开的页面要刷新一次才显示新代码画的内容。

- 只有 versionCode 变了才会触发。开发时用 `python3 tools/build.py :app:assembleDebug -PhotReloadVersionCode=<更大的数>`，再 `adb install -r`。之后要装回正常版本号，用 `adb install -r -d`。
- 被系统冻结的后台进程，下次启动时直接加载新版本。
- 从 1.6.8 及以前升级的那一次，按第 5 步强停后再打开。

## 协议测试

首页 → 高级设置 → 调试 → **协议测试**：

- 设备信息（名称、型号、固件、电量、连接；MAC 只显示末位）
- 测试通知 / 撤回
- 测试来电 / 结束来电
- 同步免打扰（同步开关 + 当前状态）
- 测试闹钟响铃 / 关闭 / 稍后响
- 测试查找手机 / 停止查找（OPPO 健康自己的响铃）
- 发送测试音乐
- 检查更新（GitHub Releases）

手环连上后即可测试。

首页「帮助」→ **发送调试日志**：导出 `band-debug-<时间>.zip`，优先交给 QQ，也可选其他应用。包内是
`band-debug.txt`（会话日志：阶段、型号、队列、帧类型）、`transport-observation.json`（最近一次连接的传输参数）
和 `captures/*.jsonl`（最近 3 份协议采集，一行一条，`payloadBase64` 是原始帧，`tx`/`rx` 是方向）。
协议采集在高级设置里手动开窗，120 秒后自动关闭。

## 发布

打 tag 后 GitHub Actions 会构建，并把同一个 `app-release.apk` 发到两个仓库：

```
git tag 32-1.2.9
git push origin 32-1.2.9
```

- 源仓库：https://github.com/MiaM1ku/Miband-OPlusBridge
- LSPosed 仓库：https://github.com/Xposed-Modules-Repo/io.github.miam1ku.mibandoplusbridge

tag 格式：`{versionCode}-{versionName}`。`workflow_dispatch` 只更新源仓库。

仓库 Secrets：

- `OPLUSBAND_KEYSTORE_BASE64`
- `OPLUSBAND_STORE_PASSWORD`
- `OPLUSBAND_KEY_ALIAS`
- `OPLUSBAND_KEY_PASSWORD`
- `XPOSED_REPO_PAT`：对 `Xposed-Modules-Repo/io.github.miam1ku.mibandoplusbridge` 有 Contents 写权限的 PAT。`GITHUB_TOKEN` 只管本仓库。配好这个 secret，tag 发布才会继续镜像到 LSPosed 仓库；源 Release 照常先完成。

## 鸣谢

协议对照用了这些项目：

- [AstroBox-NG](https://github.com/AstralSightStudios/AstroBox-NG)（AstralSightStudios）
- [Gadgetbridge](https://gadgetbridge.org/)

## 许可证

AGPL-3.0-or-later。见 `LICENSE`。
