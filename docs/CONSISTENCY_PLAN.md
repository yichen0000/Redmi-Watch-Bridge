# 健康数据一致性改造方案

目标：**OPPO 健康 · 小米运动健康 · 手环屏幕 · 桥接状态文件，四处的「今日」数字相等。**

本文只写「要改什么、为什么、怎么验收」，不含代码。

---

## 0. 先把「一致」定义清楚

不定义清楚这四条，改完还会反复。四条缺一条都不算一致：

| 维度 | 定义 | 现状 |
| --- | --- | --- |
| **数值口径** | 当日总量 = 手环日报 `steps_day`；当日取 `max(日报, 分钟明细求和)` | 桥接已如此（`BandHistoryParser.todaySteps`），OPPO 侧没有 |
| **时间基准** | 内部一律真实 epoch + **本地日历日**；写入别的 App 时按对方约定换算 | 小米健康已修（`timeIn0Tz = time + offset`），OPPO 用真实 epoch，两者不冲突 |
| **日界** | 本地 00:00（不是 UTC 00:00，不是 16:00） | 小米健康已修；OPPO 待核 |
| **新鲜度** | 允许滞后，但必须**可见** | 卡片「截至」+ 管线「手环拉取」已具备 |

---

## 1. 差异清单（为什么会不一样）

| 位置 | 数字从哪来 | 偏差来源 | 状态 |
| --- | --- | --- | --- |
| 手环屏幕 | 手环自家计数 | — | 基准 |
| `band-state.json` | `todaySteps()` → `recordDailySteps()` | 单调不减 + 时间戳只前进 | ✅ 已修 |
| 小米健康 | `step_record` 分钟明细，自己汇总成 `daily_report` | 曾因 `timeIn0Tz` 符号反了，日界滑到 16:00 | ✅ 已修；停用状态下补写已实测（见 §2 P2） |
| OPPO 健康首页 | `content://com.heytap.health.sporthealthprovider/self/sport`（`OHealthStepWriter:444`） | 见下 3 条 | ❌ 未修 |

OPPO 侧三个具体问题（都已核对代码）：

1. **只增不减**：`OHealthStepWriter.covers():217-233` 判定「已覆盖」用的是 `>=`。一旦某天写入了偏大的值（比如把手机计步和手环叠加了），之后就再也写不进正确的小值，当天只能一路偏大。
2. **口径混用**：`OHealthStepWriter:97-99` 用 `preferLiveTotal(stepDays, band.stepsToday, ...)` 把手环实时总量盖到当日，但历史日仍走日报；而首页读的是 provider 缓存（`:444`），缓存与表 1002 可能不是同一次写入。
3. **睡眠写两份**：`OHealthSleepWriter` 写 1010 原始分钟段，`OHealthSleepDerivedWriter` 又写 1049 索引 / 1071 睡眠心率 / 1052 日统计 / 1011。同一夜既有原始段又有桥接自算的派生汇总，OPPO 再汇总一次就会重复或对冲。

---

## 1.5 实测基线（2026-11-26 21:20，`mode=NATIVE`）

改之前先把四个数摆出来，避免拿猜测当需求：

| 指标 | 手环 | `band-state` | OPPO 首页 | 小米健康 `daily_report` |
| --- | --- | --- | --- | --- |
| 步数 | 6632 | 6632 | **6632** | 6552 |
| 心率 | 82 @20:50 | 82 | 82 @20:50 | 20:05 截止 |
| 血氧 | 99 | — | 99 @20:50 | 97 @20:00 |
| 压力 | — | — | 35 @20:20 | — |
| 睡眠 | 8:49（529 分） | — | **8:37（517 分）** | **517 分** |

两条差异都**不是错**，都不该"对齐"：

1. 小米健康差 80 步 = 21:03 补写的 4 分钟（33+1+34+12）。它停用中，`daily_report` 停在 20:05，这 4 条在 `_draft_*` 队列里等它下次启动汇总。手写 `daily_report` 才是造假：它自己的聚合器一启动就会覆盖，中间那段时间用户看到的是编的数。
2. 睡眠差 12 分钟：两个 App 都是 517，手环写 529 = 517 + `sleep_awake_duration=12`。**手环把夜间清醒算进"睡眠时长"，App 只算睡着的时间。** 把 529 灌进 OPPO 等于把醒着的时间记成睡眠。

顺带验证：小米健康 `daily_report.timeInZero = 1795651200`（本地 11-26 08:00 = 当天 UTC 零点），正是 `timeIn0Tz = time + 28800` 再按 UTC 日取整的结果，反向印证了 §1 的符号修正。

**结论**：OPPO 侧不需要"改数字"，需要的是**看见**——见 §2 P1′。

---

## 2. 改造方案（按优先级，P0 必须做）

### P0 · 单一真值来源

**做法**：`band-state.json` 的 `stepsToday / stepsAtMs` 是「今天」的唯一真值。OPPO 与小米健康只做**投影**，不得各自再聚合一遍。

- OPPO：`OHealthStepWriter` 只接受 `preferLiveTotal` 之后的 `StepDay`，禁止在 writer 内部再做 `Math.max` 之类的粘滞（当前无，但 `covers` 的 `>=` 等价于粘滞）。
- 小米健康：`MiHealthSync` 已按明细投影，`daily_report` 由 MH 自己算；补写结束后把 `daily_report.STEP.steps` 与本机 `stepsToday` 对一次账，不一致就记日志（不改写 MH 的汇总，避免和它的聚合器打架）。

**理由**：现在有 3 个地方各自算「今天」，任何一个算错都会让另外两个显得错，排查成本极高。收敛到一个真值，其他全是投影。

**风险**：低。`band-state` 已有单调与时间戳规则。

### P1 · OPPO 健康：先能看见，再谈纠正

原方案（把 `covers()` 的 `>=` 改成「以手环值为准覆盖写」）**做不到**，已核对：

- `insertSportHealthData` → 宿主 `processOldStat(old,new)` 是 **max 合并**（`OHealthStepAggregateHook:67-68` 的注释），写一条更小的值会被丢掉，`covers()` 反而会"确认成功"。
- 表 1002 的 `delete` 是**空实现**：1002/1010/1017 都继承 `SportDataStore.delete`，返回 0 且不删行（`OHealthHealthImportHook:1150-1154` 的注释），所以不能"先删后插"。
- 唯一能改小的地方是宿主的合并钩子（`processOldStat` / `getStat`），它的 `Mutation` 机制目前只为 `-2/-4` 聚合行开放，且要绑一份 `StepAggregateReview` + 人工 approve（`OHealthStepAggregateHook:204-227`，`StepAggregateAuditStore:190-208`）。

**所以 P1 拆成两步，第一步已落地（`abcee92` 之后）：**

**P1′（已做）· 让"偏大"不再沉默**

| 位置 | 新增 | 含义 |
| --- | --- | --- |
| `OHealthStepWriter.writeStat` | `OHEALTH_STEP_STAT_ABOVE date= stored= expected=` | 手环设备行（`mode=-3`）步数高于手环当日总量 |
| `OHealthStepAggregateHook.audit` | `OHEALTH_STEP_DEVICE_ABOVE date= stored= expected=` | 补上审计盲区：此前只认空 device 的 `-2/-4` 行，带设备号的行永远判不到 |
| `OHealthStepWriter.updateSportCache` | `OHEALTH_STEP_CACHE_ABOVE cache= band=` | 首页读的缓存高于手环总量 |

三条都只记日志、**不改数值**。理由：在还没出现过的场景上贸然降值，等于用一次写操作替换一个数字，而"哪个才是真的"没有证据。

同一轮还修了一个会**丢数据**的缺陷：`writeStat` 原先只在「要写的值变了」时才读库，值没变就跳过；健康里清一次数据后当天行没了、记忆键还在，这一天就再也不会被写回。现在当天每次同步都核对行是否还在。

**P1″（未做）· 真正允许降值**

前置条件：把 `Mutation` 的适用范围扩到「设备行 + `mode=-3` + 本桥接 deviceId」，在合并时把 old 实体的 `total_steps` 换成手环值，并沿用现有 `verifyPreserved()` 的逐列校验（只允许 steps/sync/时间戳类字段变化）。
触发条件必须**可证伪**，不能是"想让它相等"：

1. 该日已闭合（`date < 今天`），手环日报已定稿；
2. 库里的值 **不等于**本桥接 `metric:<device>:<date>` 里记的值（说明不是我们写的），或库里值 > 该日我们写过任何值的最大值；
3. 变化后要能读回校验。

**风险**：中高。`Mutation` 目前只在 `:SportDaemonService` 进程安装，writer 在主进程，跨进程要复用现有的 `HealthQueueProvider` 队列；做之前先在测试机造一次"故意写偏大"复现，能稳定修回来再上。

### P1-old（作废，留档）

1. ~~`covers()` 改成「同日 / 同设备 / 同 mode 时，以手环值为准覆盖写」~~ → 被宿主 max 合并吃掉，无效。
2. ~~首页 provider 与表 1002 在同一次 hook 内用同一个 `day.steps` 写~~ → 已如此（`publishToday`），无需改；但 `SportDataAdapter` 在主进程 `ClassNotFoundException`（见 21:27 日志），缓存这条路径实际没生效，首页显示的数来自表 1002 的聚合。
3. 日界：`StepDay.date` 与 OPPO 日键同为本地日历日，已核对无误。

### P2 · 小米健康：停用状态下也能补写

~~现状：NATIVE 模式下 `com.mi.health` 被 `pm disable-user`，`MiHealthStore.startHost()`（`:152`）的 `am start` 拉不起被停用的包。~~

**2026-11-26 21:03 实测（`mode=NATIVE`、`com.mi.health enabled=3` 停用中）：补写完全可用。**

| 步骤 | 结果 |
| --- | --- |
| 健康页「立即同步到小米健康」 | `已补写 步数 4 条、卡路里 18 条、心率 5 条、血氧 5 条、压力 1 条（共 33 行）` |
| 落库核对（`fitness_data` 副本） | 4/18/5/5/1 行都在，`timeIn0Tz - time = +28800`，`_draft_*` 队列同步多出 4/18/5/5/1 行 |
| 再次点击 | `小米健康已是最新，这次无需补写`（peek 能看见刚写的行 → 幂等成立） |
| 当日数字 | `step_record` 今日合计 **6632** == `band-state.stepsToday` **6632**（逐日 11-21~11-26 亦全部相等） |
| 包状态 | 仍是 `enabled=3 / stopped=true`，`ownership.json` 仍是 `NATIVE`，没有被 `startHost` 意外拉起 |

即 `startHost()` 的 `am start` 失败是**预期且无害**的（`MiHealthStore:157` 只吞掉这个异常，写库在它之前就完成了）。

写完之后概览管线自动变成「小米健康补写 今天 21:04 · 无需补写」，说明「待补写」徽标的口径本来就是「还有分钟没写进去」，不是「MH 停用导致写不进去」——**这一段不需要改代码**。

NATIVE 模式下唯一真实的差别：MH 被停用期间不会自己跑聚合，所以它的 `daily_report` / 首页数字停在最后一次启动时的样子，`_draft_*` 里的活要等 MH 下次启动（= 交还所有权）才消费。这是「投影晚于真值」的正常表现，不需要修。

因此本节从「三个方案选一个」降级为「**维持现状**，只留一个可选打磨」：

| 选项 | 做法 | 结论 |
| --- | --- | --- |
| 维持 | 停用 + 照常写库 | ✅ 采用。实测通过，零改动 |
| 打磨（可选） | 补写回执区分「已写入 N 条」与「已汇总」，提示 MH 启动后才可见 | 小文案改动，不影响数据 |
| 临时 enable 后写 | 写前 `pm enable` → 写 → 再 `pm disable-user` | ❌ 切换窗口正是 MH 抢手环的窗口，收益为零 |
| 放弃停用改 Hook | Hook 掉 MH 的连设备路径 | 工作量大，只在需要 MH 常驻时才考虑 |

### P3 · 可观测与自检

1. 增加一个「对账」入口（放设置页调试区即可），四个数字并排：手环日报 / `band-state` / OPPO 首页 / MH `daily_report`，差异 > 0 就标红。
2. 「截至」语义固定为**数据时间**（最后一条分钟明细），管线里的「手环拉取」固定为**同步时间**，两者不混用。已如此，写进文档防止回退。
3. 每轮同步结束后记一行 `CONSISTENCY band=X oppo=Y mh=Z`，事后能定位是哪一段偏。

---

## 3. 验收标准

改完必须能跑通这四条，缺一条不算完成：

1. **当日一致**：走 1000 步 → 3 分钟内 手环 == `band-state` == OPPO 首页 == MH（容差 0）。
2. **日界一致**：本地 00:00 过后，四处同时归零；不得出现「MH 从 16:00 才开始算」这类偏移。
3. **能纠正**：人为让某天偏大 500 步（例如临时打开手机计步叠加），下一次同步后四处回到正确值。
4. **睡眠一致**：昨夜时长四处相差 ≤ 1 分钟，且总段数 = 桥接 `sleep_interval` 的段数（不得翻倍）。

---

## 4. 前置条件

- 必须先处于 **NATIVE**（`ownership.json` 的 `mode=NATIVE`），否则桥接连不上手环，什么一致性都无从谈起。当前已处于 NATIVE。
- OPPO 健康 6.11+ 的 `WatchProvider` 会让位逻辑（`OHealthDeviceHook.dropDuplicateSpaceCard`）保持现状，它只影响设备空间卡片，不影响数字。

---

## 5. 执行顺序

1. ~~P2-B（1 小时）—— 纯文案与状态机。~~ 已实测通过，无需改动。
2. **P1′（已做）**——三条"偏大"日志 + 当天行自愈（`OHealthStepWriter` / `OHealthStepAggregateHook`）。
3. P3-3（半天）—— 每轮同步记一行 `CONSISTENCY band=X oppo=Y mh=Z`，把 P1′ 的三条日志汇总成一句。
4. P0（半天）—— 真值入口收敛；实测下来 OPPO 侧已经只吃 `preferLiveTotal` 之后的值，收益主要是去掉 `publishSnapshot` 里那条与 `publishToday` 重复的路径。
5. **P1″（1~2 天，按需）**—— 拦截宿主合并、允许设备行降值。前提是先复现一次"故意写偏大"，否则不动。
