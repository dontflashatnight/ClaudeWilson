# ClaudeWilson 架构

## 一句话

一次扫描 = **选目标 → 采集一次 → 不可变快照 → 35 个纯规则 + 目标专属检查模型 + 5 维暂存诊断台账 → 门禁与三段视角渲染**。
任何“读不到”都必须以 `UNKNOWN` 出现在模型里，不允许画成通过；目标私有目录坚持**零残留写入**。

## 目标模型

目标不是 UI 分支，而是数据：

```
model/TargetRegistry
  ├─ Claude   (com.anthropic.claude)  full   /data/local/tmp/claudewilson/claude.json
  └─ ChatGPT  (com.openai.chatgpt)    full   /data/local/tmp/claudewilson/chatgpt.json
        │
        ├─ Targets.current()       当前目标，持久化在 SharedPreferences
        ├─ TargetSpec              package / market / domain / support / config path
        └─ TargetEvidence          包形态 + 名称级 store 清单 + 目标可见性证据
```

两个目标各有独立的纯数据检查模型与只读采集器：

```
model/ClaudeChecklist         Claude 检查项定义 + 跨项约束（纯数据）
model/ChatGptChecklist        ChatGPT 检查项定义 + 跨项约束（纯数据）
model/DiagnosticArchiveParser 只读解析 Sentry / Datadog / Bootstrap / Statsig / Model 缓存
model/PersonaMatchEngine      历史上传观测与当前人设的漂移/禁区/污染判定
model/PreflightVerdict        飞行前门禁聚合（就绪 / 安全运行中 / 阻断）
ClaudeRiskCollector           Claude 固定键 + 暂存诊断（192 KB 限流）+ 系统快照
ChatGptRiskCollector          ChatGPT 固定键 + 暂存诊断（192 KB 限流）+ 系统快照
LedgerDb / LedgerIngestor     本地 SQLite v2 台账（upload_observations / target_http_events / target_diag_signals / incidents）
TargetViewPanel               两端共用的三段目标视角（保镖 / 报表 / 6 维诊断）
```

支持级别：

| 级别 | 能力 |
|---|---|
| `diagnostic` | 只读观测，不发布配置 |
| `environment` | hooks + 地区/人设写入；关闭本地身份与数据清理操作 |
| `full` | environment + 数据清理与本地身份重置 |

新增目标 = 在 `model/TargetRegistry` 增加一个 `TargetSpec` + 可选 checklist/collector；UI、`RootShell`、`ProbeRegistry` 不写目标包名字面值。

## 分层架构（Android 13–17 / API 33–37）

```
┌───────────────────────────────────────────────────────────────────────────┐
│  UI & 编排层（com.walter.claudewilson，扁平包，57 个文件）                  │
│  MainActivity · Dashboard（单页 ScrollView）                               │
│   ├─ PreflightBar          飞行前门禁（就绪 / 安全运行中 / 阻断）           │
│   ├─ PersonaPanel          6 宫格人设工作台 + PersonaEditSheets 底部抽屉    │
│   ├─ NetworkPanel          Shell 侧全维度网络探测与修复                    │
│   ├─ DataCleanPanel        3 列遥测条（沙盒/浏览器/进程）+ 域级 Cookie 清理 │
│   ├─ AppVisibilitySheet    HMA 预设分组隐藏（保活 OAuth / Play Billing）    │
│   └─ TargetViewPanel       三段视角：保镖 · 报表（HTTP/5 维诊断）· 6 维诊断 │
│                                                                           │
│  动作通道：RootShell / ShizukuShell / RootActions / RegionActions /        │
│           EnvSession / TelephonyReconciler / SystemBuild / LedgerDb       │
└───────────────────┬───────────────────────────────────────┬───────────────┘
                    │ 不可变模型 / 台账                       │ 特权 Shell（root / Shizuku）
                    ▼                                       ▼
┌───────────────────────────────────────────────────────────────────────────┐
│  model/：纯数据与纯规则（62 个文件，零 android.*，零 CJK 文案）             │
│  Snapshot · Finding · Report · TargetRegistry · TargetSpec · RiskModel     │
│  ClaudeChecklist · ChatGptChecklist · PreflightVerdict · Ipv6Policy        │
│  NetworkRules · AppVisibilityPolicy · BodyguardPolicy · BodyguardEventCodec│
│  DiagnosticArchiveParser · DiagnosticClassifier · PersonaMatchEngine       │
│  PersonaSynth · SimPolicy · GeoTable · WifiProfile · BleProfile · ...      │
└───────────────────────────────────▲───────────────────────────────────────┘
                                    │ List<Finding>（每个 probe 恰好一个）
┌───────────────────────────────────┴───────────────────────────────────────┐
│  rule/：35 个纯规则 Probe + ProbeRegistry（36 个文件，零 android.*）        │
└───────────────────────────────────▲───────────────────────────────────────┘
                                    │ Snapshot（不可变）
┌───────────────────────────────────┴───────────────────────────────────────┐
│  platform/：唯一设备采集层（5 个文件）                                      │
│  AndroidSnapshotCollector · Shell · TablesLoader · Attest · Diag           │
└───────────────────────────────────▲───────────────────────────────────────┘
                                    │
┌───────────────────────────────────┴───────────────────────────────────────┐
│  assets/catalog & ipv6-prefixes.json：地区、运营商、机型、禁区与基线目录     │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│  hook/（Vector / LSPosed 模块，21 个文件，运行在目标进程）                   │
│  HookEntry（按 TargetRegistry 路由）· HookConfig（按目标 JSON）             │
│  BuildHooks · TelephonyHooks · IdentityHooks · SystemPropertiesHooks       │
│  LocationHooks · WifiHooks · BleHooks · SensorHooks · WebViewHooks         │
│  NetworkHooks · RootArtifactHooks · BodyguardHooks · TelemetryHooks        │
│  NativeHooks → libcwpersona.so（ARM64 · 16KB 页对齐 · BTI/PAC）            │
│    open/openat/fopen(/proc/*) · __system_property_* · dl_iterate_phdr ·    │
│    getifaddrs                                                              │
└───────────────────────────────────────────────────────────────────────────┘
```

## 核心链路

### 1. 一次扫描与台账入库

```
Dashboard.startScan()（后台线程）
  ├─ Targets.refresh()                 包状态、版本哨兵、payload 存在性
  ├─ TargetEvidenceCollector           pm/dumpsys + 名称级 store 清单（不读正文）
  ├─ AndroidSnapshotCollector          单次 getprop / 挂载 / 包 / attestation / 网络
  ├─ ClaudeRiskCollector / ChatGptRiskCollector
  │     ├─ 固定键观测（无 token / cookie 值 / 聊天正文）
  │     ├─ 只读提取 5 维暂存诊断（Sentry / Datadog / Bootstrap / Statsig / Model）
  │     └─ 落盘 LedgerDb v2（target_http_events / target_diag_signals / upload_observations）
  └─ ScreenUpdates.post → PreflightBar / PersonaPanel / NetworkPanel / DataCleanPanel / TargetViewPanel
```

### 2. 地区切换事务与可逆还原

```
EnvSession（phase: OFF | APPLYING | ACTIVE | REVERTING | PARTIAL，单调 token）
  ├─ 首次 apply 自动抓取宿主机 baseline（仅首次或活动订阅变化时更新）
  ├─ RootShell.applyRegion [txLock 串行]：时区 → locale → 构建/运营商/SIM 属性
  ├─ RootActions.syncIpv6Policy [IPV6_SYNC_LOCK 串行]：
  │     默认关闭 IPv6（全非 lo 接口 disable_ipv6=1 + 清理全局 v6）+ 零轮询
  │     `ip -6 monitor address link` 防回弹守护（单实例原子写入，恢复时全量清理）
  ├─ RootActions.publish：动态注入内核发现的隧道网卡（tun_flags + POINTOPOINT）
  │     与默认浏览器包名 → /data/local/tmp/claudewilson/<target>.json
  └─ 关闭（✕）：按首次 baseline 还原，回读校验失败标 PARTIAL 并保留 baseline
```

### 3. 现代代理内核全链路隐身

不依赖对特定代理网段或特定客户端的脆弱穷举，采用**动态发现 + 机制级泛化**：

- **接口隐藏**：发布期由内核 `/sys/class/net/*/tun_flags`、`IFF_POINTOPOINT`、`link/none` 自动发现任意 TUN 网卡，运行期 `NetworkInterface.isPointToPoint()` + Native `getifaddrs` 同步隐匿，并辅以现代代理前缀静态兜底（`tun` / `Meta` / `sing-box` / `mihomo` / `flclash` / `netproxy` / `xray` / `v2ray` / `awg` / `warp` / `tailscale` 等）。
- **`LinkProperties` 泛化**：凡来自隧道接口（`isTunnel == true`），无论代理内核分配何种 TUN 网段（`172.19.0.1/30`、`198.18.0.1/16`、`10.10.14.1/30`、`26.26.26.1/30`、`100.64.x.x`），统一改写为本机实时物理网卡 IPv4 及真实前缀长度；TUN 对端/私有 DNS 改写为公网 `1.1.1.1`（避免 Chromium 异步 DNS 走 LAN 绕过）；异常 MTU（如 `9000` / `1420`）收敛为 `1500`；剥离 TUN 子网路由与 `::/0 unreachable`。
- **`NetworkCapabilities` & `NetworkInfo`**：屏蔽 `TRANSPORT_VPN`，修正并强制 `NET_CAPABILITY_NOT_VPN`（`hasCapability` + `getCapabilities`），剥离 `VpnTransportInfo{sessionId=...}` 与 `toString()` 痕迹，同步净化遗留 `NetworkInfo`（`TYPE_VPN` / `extra: VPN:...`）。

## 硬性不变量（由编译与构建守卫强制）

1. **领域层纯净**：`model/` 与 `rule/` 编译时**不传入 `android.jar`**，并由 `test.py` 强制禁止 `import android.*` 与中文文案。
2. **零 `INTERNET` 权限与 DEX 边界**：`build.py` 校验 Manifest 权限集合固定、DEX 无越界网络/定位 API、原生库满足 16 KB `PT_LOAD` 对齐 + ARM64 BTI/PAC + 无 Termux 路径。
3. **零目标目录残留**：默认禁用目标内 `hookcheck.txt` 持久化写入（仅显式 `debugSelfCheck` 调试时开启）；所有控制与诊断数据仅落在 ClaudeWilson 自身沙盒与 `/data/local/tmp/claudewilson/`。
4. **不启动目标应用**：无 `am start` / `monkey` / `attach`，注入只在用户正常启动目标进程时由 Vector 加载。

## 当前规模

| 层 | 文件数 | 说明 |
|---|---:|---|
| UI 与编排（扁平包） | 57 | Dashboard / 面板 / 抽屉 / 采集器 / SQLite 台账 / 特权通道 |
| `model/` | 62 | 纯数据与纯规则（零 `android.*`） |
| `rule/` | 36 | 35 个纯规则 Probe + Registry |
| `platform/` | 5 | 唯一设备采集层 |
| `hook/` | 21 | Vector 模块与 Native 桥接 |
| `app/jni/` | 1 | `cwpersona.c` → ARM64 16KB BTI/PAC `libcwpersona.so` |
| 离线自测 | — | `python test.py` 当前 **837 项断言** |

## 构建与外部依赖

- `tools/target_watch/` 在干净仓库中仅保留构建与自测依赖的 3 个结构化元数据文件（`hook_coverage_registry.json` 与 `baselines/*/latest.json`）；构建支持通过 `CW_OUT_DIR` 将中间产物输出到仓库外。
- 早期 `finject` / `BundledScript` / `root-mode/`（Frida 调试脚本）与旧 `RiskModelPanel` / `AccountLayerPanel` 已全部移除。
