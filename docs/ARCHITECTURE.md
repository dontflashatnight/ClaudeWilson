# ClaudeWilson 架构

## 一句话

一次扫描 = **选目标 → 采集一次 → 一个快照 → 35 个纯规则 + 目标自己的检查模型 → 一份报告 → 一个渲染器**。
任何“读不到”都必须以 `UNKNOWN` 出现在报告里，不允许变绿。

## 目标模型

目标不是 UI 分支，而是数据：

```
model/TargetRegistry
  ├─ Claude   (com.anthropic.claude)  full        /data/local/tmp/claudewilson/claude.json
  └─ ChatGPT  (com.openai.chatgpt)    full
        │
        ├─ Targets.current()       当前 tab，持久化在 SharedPreferences
        ├─ TargetSpec              package / market / domain / support / config path
        └─ TargetEvidence          package shape + name-level local store inventory + target su view
```

两个目标各有一层数据模型，不是 probe：

```
model/ChatGptChecklist        65 项定义 + 22 条跨项约束（纯数据）
model/ClaudeChecklist         67 项定义 + 17 条跨项约束（纯数据）
model/RiskModel               约束求值（导出/测试用；UI 不展示判定）
ChatGptRiskCollector          ChatGPT 目标数据 + 自检 + payload + 系统值
ClaudeRiskCollector           Claude 设备 id / Segment / Sift / Sentry / GrowthBook /
                              datatransport / 自检 + 系统值
RiskModelPanel                两端共用的目标视角：id + 值 + 来源，不做比较
TargetDataEdit                ChatGPT DataStore 的单字段可逆写入
```

检查项不是“系统属性列表”：`device.uuid`、`device.id`、`sdk.segment.user`、
`sdk.firebase.mcc`、`target.data.country_hint` 这类值必须来自目标自己的数据/自检；
控制 app 的 root 视角不参与目标判定。Claude 的卡片不再渲染 probe 报告，也不使用
控制面的网络/出口卡；两端看到的是同一种“目标观测”行。

支持级别：

| 级别 | 能力 |
|---|---|
| `diagnostic` | 只读，不发布配置 |
| `environment` | hooks + 地区/人设写入；本地身份/数据操作关闭 |
| `full` | environment + 本地身份/数据操作 |

新增目标 = 增加一个 `TargetSpec` + 可选 adapter/fixture；UI、RootShell、ProbeRegistry
不回写包名字面值。`HookEntry` 只对 `hooksEnabled()` 的目标装载模块，两个目标当前都是
`full`；没有 root/Vector 时不会进入目标进程。

## 分层

```
┌──────────────────────────────────────────────────────────────────────┐
│  Dashboard (UI, 扁平包 com.walter.claudewilson)                        │
│  header · target strip · persona space · region · cleanup · risk      │
│                                                                       │
│   渲染 ──────────────► RiskModelPanel（两端共用）                       │
│   动作 ──────────────► RootShell / RegionActions / RootActions         │
│   目标 ──────────────► Targets (TargetSpec/Registry)                  │
└───────────────┬───────────────────────────────────┬──────────────────┘
                │ RiskModel                         │ privileged shell
                ▼                                   ▼
┌───────────────────────────┐            ┌──────────────────────────┐
│  RiskModelPanel           │            │  RootShell               │
│  检查项卡片渲染器          │            │  (tz/locale/属性/包操作)  │
└───────────────┬───────────┘            └──────────────────────────┘
                │ 读
                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  model：纯数据与纯规则类型，零 android.*                               │
│  Snapshot · Finding · Report · Status · Reason · Layer                │
│  TargetSpec · TargetRegistry · TargetEvidence                         │
│  CheckItem · CheckConstraint · RiskModel · ChatGptChecklist            │
│  ClaudeChecklist                                                      │
│  BuildValues · SimValues · PlatformFacts · TlsFacts · NetworkFacts     │
│  AttestationFacts · Tables · Text · Fp · PersonaSynth · Probe         │
└───────────────▲──────────────────────────────────────────────────────┘
                │ List<Finding>（每个 probe 恰好一个）
┌───────────────┴──────────────────────────────────────────────────────┐
│  rule：35 个 probe + ProbeRegistry，零 android.*                       │
│  env.* · platform.* · network.* · identity.* · target.* · account.*   │
└───────────────▲──────────────────────────────────────────────────────┘
                │ Snapshot（不可变）
┌───────────────┴──────────────────────────────────────────────────────┐
│  platform：唯一碰设备的一层                                           │
│  AndroidSnapshotCollector · Shell · TablesLoader · Attest · Diag      │
│  TargetEvidenceCollector 在 app 层，用特权 shell 读名称级证据          │
└───────────────▲──────────────────────────────────────────────────────┘
                │
┌───────────────┴──────────────────────────────────────────────────────┐
│  assets/catalog (12 个 txt)：所有名单与阈值都在这里                     │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│  hook (Vector 模块，运行在 hooks-enabled 目标进程)                      │
│  HookEntry（按 TargetRegistry 路由）· HookConfig（按目标路径）          │
│  BuildHooks · TelephonyHooks · IdentityHooks · SystemPropertiesHooks   │
│  RootArtifactHooks · NetworkHooks · NativeHooks · SelfCheck            │
│                                                                       │
│  目标进程启动 → Vector 加载模块 → hook 框架 API → hookcheck.txt         │
│  NativeHooks → libcwpersona.so：open/openat/fopen + property_get       │
└──────────────────────────────────────────────────────────────────────┘
```

## 一次扫描的路径

```
Dashboard.rescan()            (后台线程)
  ├─ Targets.refresh()         包状态 + 每目标配置存在性
  ├─ TargetEvidenceCollector
  │     ├─ pm/dumpsys package  version / uid / installer / splits
  │     └─ /proc/1/root/data/… name-level store inventory（不读内容）
  └─ AndroidSnapshotCollector.collect(ctx, target.packageName(), …, evidence)
        ├─ Shell.all("getprop")                      1 个进程读全部属性
        ├─ /proc/mounts · 包枚举 · Os.stat(su 路径)   区分“不存在”与“读不到”
        ├─ KeyStore attestation（Attest，密码学未改）
        ├─ TrustManager issuers · 用户 CA 目录 · NetworkInterface · ConnectivityManager
        └─ TablesLoader.load(ctx)                    进程级缓存，只解析一次
  └─ ChatGptRiskCollector.collect(...)                ChatGPT：目标数据/自检/系统
  └─ ClaudeRiskCollector.collect(...)                 Claude：目标数据/文件/自检/系统
        └─ 目标自己的 CheckItem → CheckConstraint → RiskModel
  └─ ProbeRegistry.scan(snapshot)  → Report.of(35 个 Finding)    （仅扫描兜底）
  └─ ScreenUpdates.post → RiskModelPanel
```

## 一次 apply 的路径

```
Region chip（hooks-enabled 目标；ChatGPT 已是 full）
  └─ RootShell.applyRegion(…)
        ├─ cmd time_zone_detector / cmd alarm
        ├─ cmd locale：所有已安装 TargetSpec（+ 浏览器）
        ├─ SystemBuild build props 或 operator props
        ├─ carrier-config override
        └─ sim-props / simop
  └─ 自动对齐（auto_align 开启，仅 ChatGPT）
        ├─ PersonaState.clearCountryHint → payload 用 region ISO
        ├─ target 停止：TargetDataEdit.country_hint = region ISO（先备份）
        ├─ target 运行：只记 auto_pending_iso，下次扫描目标停止后补写
        └─ RiskModel.withValue 更新 target.data / target.payload
  └─ PersonaPanel.publishCurrent()
        └─ base64 → /data/local/tmp/claudewilson/<target>.json
  └─ 用户正常启动目标
        └─ Vector 加载 HookEntry
              ├─ TargetRegistry.byPackage → 只处理 hooks-enabled 目标
              ├─ HookConfig 读取 per-target 文件；legacy 仅作缺省回退
              ├─ RootArtifactHooks：隐藏已知 su/模块路径（File、Os、exec/ProcessBuilder）
              ├─ Build / VERSION / Telephony / AID / Locale / TimeZone / installer
              ├─ ro.product.locale / countryCodeHint 随 payload 下发
              ├─ NetworkInterface / LinkProperties / NetworkCapabilities / proxy
              └─ SelfCheck → /data/data/<target>/files/hookcheck.txt（含 su_system）
```

## 三条不变量（由结构保证，不靠自觉）

1. **`UNKNOWN` 是一等状态。** 聚合顺序 `FAIL > WARN > UNKNOWN > PASS`；
   `Report.isComplete()` 直接暴露盲区；顶部徽标把 `UNKNOWN` 计入，“读不到”不会渲染成“可信环境”。
2. **一个 probe 一个 `Finding`。** 接口签名强制返回，检查器不可能“忘了报告”。
3. **领域层不含文案、不碰 Android。** `model`/`rule` 编译时**不给 android.jar**，
   并且跑 CJK 检查；文案与 Intent 在渲染层。

## 目标视角：值 + 来源，不做判定

- 风险区渲染 `RiskModel` 的观察值，不显示 `CONFLICT` / `BOUNDARY` 判定，也不做 `A ≠ B`。
- 分组：身份 / 账号 / 完整性 / 传输 / 目标进程 / 系统。
- 无值的原因由 `CheckItem.Coverage` 携带：`NOT_COVERED`（软件未覆盖）、`NO_RECORD`（目标
  还没产生记录）、`UNREADABLE`（存在但读不出）、`SERVER_ONLY`（本机没有）、`STALE`
  （目标已停止）、`OBSERVED`（有值）。
- 渲染层只在来源行写原因；`not-local` 显示 `—` + `仅服务端`。
- 目标未运行时，Claude 的 hook 字段优先显示 `claude.json` 的下次启动值。
- 地区字段仍由人设空间负责；Risk 只显示非地区字段。
- 控制应用 UI 只有中文/英文：系统语言为中文时中文，其它语言英文；地区切换不改 UI 语言。
- 目标语言优先由 hook payload 决定；Shizuku（无 hook）下改为写目标/浏览器的 per-app
  locale，关闭地区时按 baseline 复原。`system.locale` 本身不写。
- 地区一旦选中就是设备级状态；另一个目标标签锁定，直到点 `✕ 关`。切换标签本身不写文件。
- 地区按钮 500ms 防双击，`applyBusy` 期间不响应。
- `关联未定` 只表示对侧真正读不到；对侧已过期时本行显示已过期，对侧无记录/仅服务端时归不可改。
- Claude 的 trusted-device 与 TLS 已有本地读取，hCaptcha 标无记录，不再把结构性缺失当成未覆盖。

## 边界

- 无 `INTERNET` 权限；唯一的网络动作是“网络”卡按钮触发的 shell 侧探测。
- 应用自身不启动任何目标；有 root 时，用户正常启动目标后 Vector 模块在目标进程内生效。
- Claude / ChatGPT 都是 full；root + Vector 下可注入，地区/人设、本地数据清理和本地身份
  重置可选，但破坏性操作必须二次确认。Shizuku 无 hook，只做 shell 可及的部分。
  服务端账号/完整性仍是边界。
- 只读目标不发布配置；environment 目标不提供数据清理/逐应用 ID 改写；full 目标才有。
- 控制面看到 su 是 direct-su 的预期事实，不是 probe；目标可见性只由 `target.su-view` 读取目标
  自己的 `hookcheck.txt`。未运行过时显示“不可用”，不假装隐藏成功。
- 磁盘上的 `/system/bin/su` 是 KernelSU 的 `ksud` 同 inode 入口，不是旧 SuperSU 残留；direct-su
  控制面依赖它。删除磁盘文件需要 root broker/模块架构，当前方案只保证目标进程的文件/命令视图。
- `TargetEvidence` 只带固定 store 名、marker 类别、文件数量和目标自检的 su 可见性，不带文件内容、
  token、cookie 或账号。两个 collector 是唯一读取固定目标字段的类：ChatGPT 读
  `global_device_uuid.json`、`INSTALLATION`、cookie 的 UUID/时间戳/计数、Statsig 的 closed key、
  Firebase 的 metadata；Claude 读 `device_id_prefs.xml`、Segment/Sift/Sentry/Firebase 安装 id、
  GrowthBook 的 unit、datatransport 的本机 metadata、cookie 的**名字计数**。两者都不读 HMAC、
  OAuth token、cookie 值或邮件正文。UI 与审计日志对 `sensitive` 项只输出 masked。
- 构建断言：权限清单固定、`dexdump` 扫描禁止网络/定位 API 越界。

## 当前数字

| 层 | 文件 | 说明 |
|---|---|---|
| UI（扁平包） | 40 | Dashboard / panels / actions / collectors / TargetDataEdit |
| model | 35 | 含 TargetSpec / Registry / Evidence / CheckItem / RiskModel / 两套 Checklist |
| rule | 36 | 35 个 probe + registry |
| platform | 5 | 唯一设备采集层 |
| hook | 10 | 按 package 路由 + root artifact view |
| 自测 | — | 402 项断言 |

数字随开发变化；正确性由 `python test.py` 与构建断言保证，不靠此表。

## 已删除的旧结构

`finject`、`BundledScript`、`RootActions.attach/spawn` 与“预注入启动”按钮已从 app 删除；
目标进程由 Vector 在启动时加载模块，不需要 pid、attach 或 spawn。`root-mode/` 里的
`persona.js` / `inject.py` 只保留为 PC 侧调试脚本，不参与 APK。Shizuku 以 AAR 形式重新
接入，作为无 root 时的 shell 通道，不参与进程注入。

Native 层由 `assets/native_init` 加载 `lib/arm64-v8a/libcwpersona.so`，对 `/proc` 与
native property 做同一份人设的覆盖。
