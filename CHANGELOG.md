# 更新记录

本文件记录 `Redmi-Watch-Bridge` 相对上游 [MiaM1ku/Miband-OPlusBridge](https://github.com/MiaM1ku/Miband-OPlusBridge) `1.11.5` 基线的改动。

格式参照 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号沿用上游的 `{versionCode}-{versionName}` 口径。

---

## [未发布] 2026-11-25

### 修复

- **设备空间里手环卡片消失（OPPO 健康 6.11+）**
  OPPO 健康 6.11 起，`WatchProvider` 仍会把桥接投影进去的手环当作「已有卡片」返回，但设备空间不再渲染这一行。桥接据此让位（`yield=true`）后，Health 侧又不显示，结果两边都空，手环从设备空间彻底消失。
  现在按 Health 版本区分：`versionCode >= 6110000` 时不再让位，直接把桥接卡片保留在设备空间；低于该版本仍沿用原有的防重复让位逻辑。

- **让位状态可能永久卡死**
  `dropDuplicateSpaceCard()` 只由桥接 provider 的数据变化触发。若 Health 侧的卡片在桥接数据不变的情况下被删除（例如在健康里重置设备），恢复逻辑永不运行，`yield` 一直为 `true`。
  增加 30 秒周期的自检：让位期间定期重新核对 Health 侧是否还有卡片，一旦消失立即恢复桥接卡片；恢复后自动停止自检。

- **日汇总步数与手环不一致**
  `steps_day` 日汇总快照的 `startMs` 固定在当天 00:00，值会滞后于分钟明细。原逻辑里「权威样本」可以穿透「旧样本丢弃」判断，导致 00:00 的旧值覆盖实时值；`stepDays` 又在有日报时完全忽略明细求和。
  现在当天步数单调不减，且分钟明细求和大于日报时采用明细，与 `todaySteps` 口径一致。

### 新增

- **「关闭手机计步」开关**（健康页 → OPPO 健康）
  收回 `com.heytap.health` 的 `ACTIVITY_RECOGNITION` 权限，让 OPPO 健康首页步数只来自手环，不再累加手机自身传感器的计步。开关状态实时回读权限确认，OPPO 健康若弹窗要回权限，回到本应用时会重新收回。

### 变更

- 修正界面与日志中若干不符合 `CopyStyleTest` 的文案（禁用「不再 / 找不到 / 未开启 / 失败」等否定式表述）。

---

## [1.11.5+mihealth] 基线

以上游 `1.11.5` 为起点，在「桥接 → OPPO 健康」之外增加 **小米健康** 作为第二个同步目标，并重构主页为五页签（概览 / 健康 / 设备 / 日志 / 设置）。详见 [README](README.md#与原作者版本的区别)。
