# Velox · 安卓浏览器

> **把「首帧之前的那 300 毫秒」和「每帧里的那 8 毫秒」全部抢回来。**
>
> 引擎用系统 WebView（Chromium，与 Chrome 同源），所以 Velox **不宣称自研内核**。
> 我们要超越的不是渲染引擎，而是**时序编排**与**交互结构**——这是 Chrome 因为
> 「跨平台一致性 + 多进程架构」而难以在本平台极致化的部分。

[![Build](https://img.shields.io/badge/APK-1.8MB%20release-success)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF)]()
[![minSdk](https://img.shields.io/badge/minSdk-26-blue)]()
[![Telemetry](https://img.shields.io/badge/telemetry-0%20bytes-brightgreen)]()

---

## 1. 一句话结论（来自 42 分钟前置研究，全文见 [`docs/research/RESEARCH.md`](docs/research/RESEARCH.md)）

浏览器体验的短板早已不在引擎，而在三个可以被工程手段拿走的时间段：

| 时间段 | 事实依据 | Velox 的做法 |
|---|---|---|
| **首帧前 182–400ms**（WebView 首次初始化，阻塞主线程） | Chromium issue 41237034 / Android 官方文档 | 引擎异步预热 + 渲染进程预热 + **零引擎首帧快照** |
| **点击前 130–300ms**（网络与解析） | Google Search prefetch 实测 −67ms；Shopify 移动端 −180ms；prerender 场景 LCP −43% | **触摸意图引擎**：按下即推测目标，三档推测（预连接/预取/预渲染） |
| **每帧 8.3–16.7ms**（120Hz 预算） | RAIL 模型；Chrome 2023→2026 才把 Android 滚动 jank 降 48% | 工具栏三态**叠层**：永不改变 WebView 几何 → **零 viewport 重排** |

此外补上四项「用户抱怨最多但对手结构性缺位」的能力（79% 移动用户对广告沮丧、85% 愿为此换浏览器；移动端底部区域点按命中率 96% vs 顶部 61%）：
**内核级拦截**、**单手可达的底部交互**、**手势级导航**、**零遥测**。

---

## 2. 已实现的「超越」机制（每条都指向代码）

### 2.1 冷启动：让首帧完全不需要 Chromium

`new WebView()` 首次会同步拉起 Chromium（中端机 182–400ms，二次仅 ~4ms）。Velox 分三步拿走它：

1. `KernelWarmup`（[`KernelWarmup.kt`](app/src/main/java/com/velox/browser/browser/KernelWarmup.kt)）
   - `WebViewCompat.startUpWebView`（webkit 1.16 稳定 API，把可后台化的初始化搬到后台线程）
   - `Profile#warmUpRendererProcess()`（预 fork 渲染进程）
   - `Profile#preconnect(上次停留站点)`（省掉 DNS+TCP+TLS 的 1~3 个 RTT）
   - 全部挂在 `Looper.myQueue().addIdleHandler`，**不阻塞 `Application.onCreate`**
2. `SnapshotStore` + `SnapshotView`（[`SnapshotStore.kt`](app/src/main/java/com/velox/browser/browser/SnapshotStore.kt)、[`SnapshotView.kt`](app/src/main/java/com/velox/browser/ui/SnapshotView.kt)）
   - 上次会话的页面快照以 WebP 落盘（≤720px，质量 70），冷启动时直接贴图出帧
   - 首次绘制成本 ≈ 一次位图绘制（<16ms），引擎在后台继续启动，`onPageCommitVisible` 到达后淡出
   - 无痕标签页的快照**只存内存，永不落盘**
3. Baseline Profile（[`baseline-prof.txt`](app/src/main/baseline-prof.txt)）覆盖启动热路径；Google 官方数据：冷启动最高 **40%**，独立实测 22%/26%/30%/36%

### 2.2 触摸意图引擎：移动端唯一「早于点击」的高置信信号

桌面有 hover，移动没有——Chrome 官方也承认移动端「前两条结果之外」的预取无收益而未启用。
Velox 用三级阶梯（[`IntentEngine.kt`](app/src/main/java/com/velox/browser/browser/IntentEngine.kt)）：

| 级 | 触发信号 | 动作 | 相对点击的提前量 |
|---|---|---|---|
| L0 | 页面加载完成 | 对出现频率最高的 6 个外链 origin `preconnect` | 常数，成本≈1 个握手 |
| L1 | **手指按下**命中 `<a>` | `Profile#prefetchUrlAsync`（只取 HTML） | 约 80–150ms |
| L2 | L1 且目标是主内容链 | `WebViewCompat#prerenderUrlAsync`（整页渲染） | 约 150–300ms |

纪律（写在代码里，不是口号）：
- 省电/省流量/低电量只允许 L0，绝不做电池刺客；
- 同时最多 1 个 L2、2 个 L1；
- 手指抬起未跳转、滑动取消、页面切换 → 立即 `CancellationSignal.cancel()`；
- 注入脚本的 touch 监听全部 `passive: true`，且只做 `elementFromPoint` 纯几何命中，不写 DOM、不触发重排。

### 2.3 零重排的工具栏：消除浏览器自己制造的 jank

地址栏自动隐藏会改变 viewport 高度 → `100vh` 元素重排 → 滚动中内容跳动（这是社区里长期无解的抱怨，只能靠站点用 `svh` 自救）。

Velox 的 [`ChromeBar`](app/src/main/java/com/velox/browser/ui/ChromeBar.kt) 是**叠层**：
- 三态（常驻 / 折叠细条 / 隐藏）之间只改自身 `translationY`，**WebView 的 layout 参数在整个会话内不变**
- 因此滚动、收放工具栏、进全屏都**不产生任何 viewport 变化**
- 支持拖动调节、上滑唤出、点击手柄切换；动画 170ms，跑在 `PathInterpolator(0.2,0.9,0.1,1)` 上

> 可证伪：页面内挂 `ResizeObserver(document.documentElement)`，滚动+收放 30 次，
> 计数必须为 **0**。大于 0 即视为本机制失效（见 `bench/RUNBOOK.md` §2）。

### 2.4 内核级拦截：零主线程阻塞

- 规则来自公共源（StevenBlack hosts + Peter Lowe + EasyList），编译期降维成 **host 后缀匹配**：
  121,032 条域名 / 273 条例外，最终资产 **1.1MB**
- 运行时结构：**Bloom 快速否决（177KB）→ 有序 64 位 FNV-1a 哈希二分确认 → 例外表优先**
- 判定在 `shouldInterceptRequest` 的回调线程完成，**零分配、零 IO、零正则、零锁**（`BlockList.kt` 用 `ThreadLocal<CharArray>` 复用缓冲）
- 为什么这点重要：`shouldInterceptRequest` 是**同步串行**回调，任何阻塞都会拖慢该页面的**所有**资源请求（社区大量事故案例）
- 实测（JVM 单测，见 `BlockListTest.lookupsAreConstantTimeEnough`）：**1 万次 URL 判定 ≈ 23ms → 约 2.3µs/次**

### 2.5 交互结构：把「够不着」变成「顺手」

- 全部主控在底部拇指区（底部命中率 ~96%，顶部 ~61%；49% 用户单手操作）
- 标签页总览卡片自带**上次可见快照**，解决「平均 2.8 秒找标签页」的问题
- 边缘手势：左缘右滑后退（跟随手指位移）、右缘左滑前进、顶部下拉刷新；未命中手势**不消耗**，普通滚动/点击/长按与原生一致
- 就近的菜单项按**使用频率**排序，而不是按功能分类

### 2.6 内存：预测式淘汰，而不是被动丢弃

WebView 全 App 只有一个 renderer，常驻越多、PSS 越高、主线程动画越容易掉帧。
`TabStore.liveTabBudget()` 按内存档位自适应（4GB→2，6GB→3，8GB+→4~5），
`enforceBudget()` 按「上次访问时间 / 是否播放媒体 / 是否当前」评分淘汰，
被淘汰的标签页保留元数据 + 快照，回切时**先出快照再加载**，用户看不到白屏。

### 2.7 崩溃恢复（WebView 脆弱点的补偿）

WebView 单 renderer 意味着一个页面崩溃可能带走全部标签页。
`onRenderProcessGone` → 清理所有视图 → 重建当前标签页，并把其余标签页标记为可恢复。
这是把「多进程安全隔离弱于 Chrome」这一劣势降到可接受范围的补偿机制。

---

## 3. 明确的能力边界（不吹牛）

| 项目 | 事实 |
|---|---|
| 渲染引擎 | 系统 WebView（Chromium）。**JS 吞吐/WASM/WebGL 与 Chrome 同级，不宣称领先** |
| 站点隔离 | WebView 单 renderer + 进程内 GPU，**隔离等级低于** Chrome 的站点隔离 |
| WebView 版本 | 由设备决定（可通过 Play 商店的 Android System WebView 更新） |
| 预取收益 | 条件性指标：仅在「推测命中」时成立，省电/省流量档位会主动退让 |
| 量化目标 | 见 [`docs/BENCHMARK.md`](docs/BENCHMARK.md)；**未跑完 `bench/RUNBOOK.md` 的维度不得对外宣称** |

---

## 4. 构建与安装

```bash
# 环境：JDK 17、Android SDK 34、Gradle 8.7
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 生产包（含 baseline profile，约 1.8MB）
./gradlew testDebugUnitTest  # 16 个单元测试（含性能断言）
```

安装：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> 仓库内 `release/velox-1.0.0.apk` 为已签名成品包（自签名密钥，仅用于分发与侧载）。
> 生产发布前请替换为你自己的 keystore（`keystore.properties`，已在 `.gitignore` 中）。

### 重新编译拦截规则（可选）

```bash
python3 tools/build_blocklist.py \
  --out app/src/main/assets/blocklist.bin \
  --hosts dl/stevenblack-hosts.txt --hosts dl/peter-lowe-hosts.txt \
  --easylist dl/easylist.txt
```

---

## 5. 仓库结构

```
app/src/main/java/com/velox/browser/
├── VeloxApp.kt              应用入口（IdleHandler 延后所有重活）
├── MainActivity.kt          唯一 Activity：叠层视图编排 + 生命周期
├── browser/
│   ├── KernelWarmup.kt      引擎异步启动 / 渲染进程预热 / 预连接
│   ├── KernelProfile.kt     Profile 隔离、预取、预渲染
│   ├── IntentEngine.kt      触摸意图三级推测
│   ├── BrowserView.kt       WebView 配置、拦截、快照、JS 桥
│   ├── Interceptor.kt       零阻塞请求拦截
│   ├── TabStore.kt          标签页预算与预测式淘汰
│   ├── SnapshotStore.kt     首帧快照（WebP）
│   └── HistoryStore.kt      本地历史（SQLite，后台线程）
├── filter/BlockList.kt      Bloom + 有序哈希 两级判定
├── ui/                      ChromeBar / HomeScreen / TabSwitcher / Sheet / IconView …
├── metrics/LatencyLogger.kt 本机采样（0 出网）
└── util/Urls.kt             输入解析（URL vs 搜索）

docs/research/RESEARCH.md    前置研究（42 分钟，20+ 次定向检索）
docs/ARCHITECTURE.md         架构与性能红线
docs/BENCHMARK.md            指标、目标值、证伪条款
bench/RUNBOOK.md             实机复现手册 + 脚本
tools/build_blocklist.py     规则编译器
```

---

## 6. 验证「超越」的方式（可被第三方证伪）

1. 按 `bench/RUNBOOK.md` 在**中端机（6–8GB / A7x 级 / Android 13）**上跑 30 次冷启动；
2. 用 `bench/compare.py` 对比 Chrome 同条件数据；
3. 未达 `docs/BENCHMARK.md` 目标 → **删除 README 中对应主张**（不是淡化措辞）。

我们主动把失败处理写进文档，因为「可被证伪」才是工程主张，否则只是营销。

---

## 7. 许可与致谢

- 代码：Apache-2.0
- 拦截规则：StevenBlack/hosts、Peter Lowe's Ad Server list、EasyList —— 各自遵循其原始许可
- 研究引用来源全部列在 [`docs/research/RESEARCH.md`](docs/research/RESEARCH.md)，包括与我们结论相左的证据

**Velox 不做的事**：不建账号、不云同步、不上传使用数据、不注入广告、不改写页面内容（除用户主动开启的拦截样式修正与阅读模式）。
