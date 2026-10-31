# Velox 前置深度研究报告

> 项目：Velox（拉丁语「快」）— 安卓浏览器
> 研究窗口：2026-10-31 07:38 → 08:20 UTC（连续 42 分钟，含 20+ 次定向检索与 4 轮交叉验证）
> 结论性质：所有「超越」主张必须在本文档中找到**技术依据 + 可验证指标 + 失效边界**。凡无依据者，代码中不得宣称。

---

## 0. 研究方法

| 手段 | 说明 |
|---|---|
| 一手资料优先 | Chromium 官方文档（googlesource/developer.android.com）、Android Developers、Chromium issue tracker、AOSP 源码 |
| 竞品行为逆向 | 从 Chromium 的设计决策（Freeze-Dried Tabs、PartitionAlloc、Isolated Splits、process model）反推其**结构性弱点** |
| 量化门槛 | 只接受带毫秒/百分比/样本条件的数据，并标注其适用设备档位 |
| 交叉验证 | 同一结论至少 2 个独立来源，否则标注为「单一来源/待实测」 |
| 可证伪 | 每条超越主张都配一条「如何证伪我」的实测协议（见 `BENCHMARK.md`） |

---

## 1. 浏览器架构与性能瓶颈

### 1.1 主流架构事实

- Chromium on Android 采用**多进程**：browser(UI) 进程 + 每站点 renderer 进程 + 独立 GPU 进程 + utility 进程，跨进程用 Mojo IPC；renderer 由 zygote fork 而来以摊薄启动成本。
  来源：[Process Model and Site Isolation](https://chromium.googlesource.com/chromium/src/+/main/docs/process_model_and_site_isolation.md)、[多进程模型综述](https://awjunaid.com/operating-system/what-are-the-main-components-of-the-multiprocess-model-in-google-chrome/)
- WebView 则是**进程内嵌引擎**：browser 部分跑在 App 进程内，GPU **不单独起进程**（与 HWUI 渲染管线合并），且**全 App 仅一个 renderer**（少数特例）。
  来源：[WebView and memory — Android Developers](https://developer.android.com/topic/performance/memory/guide/webview-memory)、[Chromium web-platform-compatibility](https://chromium.googlesource.com/chromium/src/+/HEAD/android_webview/docs/web-platform-compatibility.md)
- **关键判断**：多进程换来安全隔离与稳定性，代价是**每帧跨进程往返**（input → browser → renderer → compositor → GPU）与**进程冷启动**。Chromium 团队自己承认这是一条必须靠工程手段「填坑」的路径：2023→2026 他们把 Android 滚动 jank 降低了 **48%**，而 jank 的成因被明确描述为「信息要在多个进程间传递，任一环节延迟都会造成可见卡顿」。
  来源：[Chrome on Android 滚动优化](https://samsungmagazine.eu/en/2026/07/24/android-chrome-scroll/)、[Chrome Cut Android Scroll Jank 48%](https://apogeewatcher.com/blog/chrome-android-scroll-jank-what-to-check-on-your-site)

### 1.2 渲染引擎与启动成本的真实数量级

| 事实 | 数据 | 来源 |
|---|---|---|
| 浏览器冷启动到首屏（桌面/移动混合样本） | Chrome 1.42–1.8s，Firefox 1.11–1.4s，Edge 0.9s（样本口径不一，仅作量级参考） | [Tech Insider 2026](https://tech-insider.org/chrome-vs-firefox-2026/)、[2026 对比](https://speed-drain.com/blog/best-web-browsers-2026-comparison/) |
| Android WebView **首次**构造（引擎加载） | 中端机 **182–400ms**，二次构造仅 ~4ms | [Chromium issue 41237034](https://issues.chromium.org/issues/41237034)、[WebView 内核原理](https://juejin.cn/post/7644512340549746714) |
| WebView 首次初始化到发起网络连接 | 首次 192.79ms / 二次 142.53ms，用户体感还要再加 70–700ms | [WebView performance analysis](https://www.mo4tech.com/webview-performance-experience-analysis-and-optimization.html) |
| 第二次构造为何快 | 内核以 APK 形式存在，加载后进程内共享 | 同上 |
| Baseline Profile 对冷启动的收益 | 官方「最高 40%」；实测 22% / 26% / 30% / 36% 多组独立数据 | [Android Developers Blog](https://android-developers.googleblog.com/2022/01/improving-app-performance-with-baseline.html)、[Macrobenchmark 实测](https://medium.com/androiddevelopers/measure-and-improve-performance-with-macrobenchmark-560abd0aa5bb)、[Bluesky PR](https://github.com/bluesky-social/social-app/pull/12092) |

**判断 1**：*引擎本身的渲染能力（Blink+V8）各家已趋同，可超越的空间不在「渲染引擎」，而在「首帧之前的 300–500ms」与「每帧的进程/线程编排」。* 这是一条**不改变引擎、只重构时序与所有权**的路线，风险可控、收益可测。

---

## 2. 安卓流畅度的关键因素（启动 / 滑动 / 加载 / 内存）

### 2.1 启动
- 冷启动链路：图标点击 → system_server 起 starting window → zygote fork → Application → Activity.onCreate → 首帧。其中 onStart/onCreate/onResume 总耗时超过 ~400ms 即被用户明确感知为「卡」。
  来源：[Android 冷启动](https://blog.csdn.net/weixin_57727992/article/details/123397377)、[冷启动优化实践](https://cloud.tencent.com/developer/article/2527221)
- **Freeze-Dried Tabs**：Chrome 用「近似截图的轻量页快照（可滚动/缩放/点链接）」抢占首帧，真页面后台加载后无缝替换，换来 **13% 启动提升**；配合 App Bundle 与 PartitionAlloc，另有 7.5% 启动 / 5% 内存 / 2% 加载收益。
  来源：[Chrome 89 官方解读](https://www.pcmag.com/news/chrome-89-is-faster-and-uses-less-memory)、[Engadget](https://www.engadget.com/google-chrome-89-saves-memory-loads-faster-125713806.html)
- **Isolated Splits**：把 browser 进程代码拆包，仅保留 renderer/GPU 必需部分在 base module，其余按需加载。
  来源：[chromeunboxed](https://chromeunboxed.com/chrome-for-android-speed-milestone-2022)

### 2.2 滑动
- 60Hz 每帧预算 16.7ms，120Hz 只有 8.3ms；RAIL 要求触摸到绘制 <100ms、每帧 <16ms。
  来源：[RAIL 模型](https://www.smashingmagazine.com/2015/10/rail-user-centric-model-performance/)、[web.dev Long Tasks](https://github.com/w3c/longtasks)
- 感知阈值：直接触摸的**落点反馈**可被感知的 JND 均值约 **64ms**（20–100ms 区间）；拖动时低至 2.38ms 即有感。滚动被视为「超敏感」交互，50ms 的滞后就会被描述为 janky。
  来源：[How fast is fast enough? CHI'13](https://www.tactuallabs.com/papers/howFastIsFastEnoughCHI13.pdf)、[感知延迟研究综述](https://www.codestudy.net/blog/what-is-the-shortest-perceivable-application-response-delay/)
- **地址栏自动隐藏是一个被普遍忽视的 jank 源**：地址栏收放会改变 viewport 高度，导致 `100vh` 元素重排、`resize` 事件在滚动中触发、页面内容在手指下跳动。这是「浏览器自己制造的 jank」，可用 `svh` 规避，但**只有浏览器能从根本上消除**。
  来源：[Chrome Android 地址栏隐藏导致页面跳动](https://stackoverflow.com/questions/24944925/)、[100svh 修复记录](https://github.com/verastack-labs/verastack-labs.github.io/pull/69)

### 2.3 页面加载
- 导航优化（Chromium 2022）为 Android 页面加载带来 **15%** 提升，做法是「把关键导航时刻优先排到 UI 线程」。
  来源：[chromeunboxed](https://chromeunboxed.com/chrome-for-android-speed-milestone-2022)
- Speculation Rules 是当前唯一「结构性」的加载加速手段，且**移动端收益明显小于桌面**：
  - Google Search 预取前两条结果：Android LCP **−67ms**；
  - Shopify 全站 conservative prefetch：桌面 −130ms、移动 **−180ms**（全分位、全指标均值）；
  - Cloudflare Speed Brain：成功预取者 LCP −45%；Ray-Ban 用 prerender：移动 LCP 4.69s→2.66s（**−43%**）；
  - 监控口径：prerender 导航 p75 LCP 320ms vs 普通 1800ms；中等 eagerness 下约 **28%** 的导航能被成功命中；
  - Google 明确说：移动端（无 hover）对「前两条之外」的预取**没有可测收益，因此未启用**。
  来源：[Chrome for Developers](https://developer.chrome.com/blog/search-speculation-rules)、[corewebvitals.io](https://www.corewebvitals.io/pagespeed/speculation-rules)、[uxify](https://uxify.com/blog/speculation-rules-api/)
- WebView 侧已有对应 API：`Profile#prefetchUrlAsync` / `Profile#preconnect` / `Profile#addQuicHints` / `WebViewCompat#prerenderUrlAsync`，以及 `startUpWebView`（后台线程异步启动引擎）。
  来源：[Speculative loading in WebView](https://developer.android.google.cn/develop/ui/views/layout/webapps/speculative-loading)、[Optimize WebView startup](https://developer.android.com/develop/ui/views/layout/webapps/optimize-webview-startup)

**判断 2**：*「预判用户下一步并提前预热」是当下最高杠杆的性能手段，但它的瓶颈不是网络，而是**意图信号的获取时机**。桌面有 hover，移动没有——所以移动端唯一早于点击、且高置信度的信号是 **touch-down / 视口驻留 / 阅读位置**。谁能把这条信号链做进浏览器内核层，谁就能拿到移动端那 100–300ms。*

### 2.4 内存
- WebView：App 进程 = browser 进程（含 GPU），renderer 独立记账；首次初始化最贵，之后每新建一个 WebView 继续增内存，且关闭不主动回收。
  来源：[WebView and memory](https://developer.android.com/topic/performance/memory/guide/webview-memory)、[mo4tech](https://www.mo4tech.com/webview-performance-experience-analysis-and-optimization.html)
- 浏览器级策略：freeze / discard / sleep 三档，discard 释放最多但回切要重载。
  来源：[RAM 与标签页](https://www.digitalcitizen.life/how-modern-browsers-use-ram-and-why-closing-tabs-does-not-always-help/)、[Chrome 后台标签停止加载](https://en.androidayuda.com/chrome-android-stop-background-tabs/)

**判断 3**：*内存不是「省得越多越好」，而是「把内存留给用户下一步要看的页面」。Chrome 的 discard 是**被动**的（系统压力/超时），因此「回切重载」永远存在。**主动的、带预测的淘汰**才是差异化。*

---

## 3. 用户痛点（有数据的需求缺口）

| 痛点 | 量化证据 | 来源 |
|---|---|---|
| 广告/弹窗/订阅遮挡 | 移动浏览器用户 **79%** 对在线广告感到沮丧（> 安全 69% > 隐私 68%）；**85%** 至少「有点可能」为更好的拦截而换浏览器；61% 因广告轰炸反而更不买 | [Harris Poll × eyeo](https://www.mediapost.com/publications/article/405748/)、[CXM Today](https://cxmtoday.com/cx/are-ads-more-frustrating-for-consumers-than-privacy-risks/) |
| 弹窗难关 / 内容慢 | 智能手机用户最普遍障碍（50%）是侵入式广告与 cookie 弹窗；第二是加载慢（27%） | [IrishDentist/EAA 调查](https://www.irishdentist.ie/pop-ups-cookies-and-banners-why-browsing-on-a-smartphone-is-complicated-especially-for-genz/20083/) |
| 单手不可达 | 49% 单手、36% 托持、15% 双手；67% 右手拇指；底部区域点按命中率 ~96% vs 顶部 ~61% | [Hoober 1,333 人研究](https://www.scotthurff.com/posts/how-to-design-for-thumbs-in-the-era-of-huge-screens/)、[NN/g 转述](https://www.alphonsolabs.com/sticky-header-bottom-navigation-mobile/) |
| 找标签页成本 | 用户平均花 ~2.8s 找正确的标签页（Chrome 标签管理调查） | [UXPA 转述](https://lifetips.alibaba.com/tech-efficiency/five-best-android-web-browsers) |
| 定制性缺失 | Chrome 直到近期才测试底部地址栏；Edge 2026 才跟进 | [Edge 底部地址栏](https://androidexperto.com/after-chrome-microsoft-edge-plans-to-move-address-bar-to-bottom-on-android/) |
| 隐私 | Chrome 在移动浏览器数据收集排名中最高 | [The Register 讨论](https://forums.theregister.com/forum/all/2025/05/07/mobile_browser_data_collection/) |

**判断 4**：*用户抱怨的不是「引擎不够快」，而是「被挡住、够不着、找不到、被跟踪」。性能要服务于这四件事，否则再快也没有体感。*

---

## 4. 竞争格局与「可超越性」的结构性分析

- 安卓移动端 Chrome 约 **67–69%**，Samsung Internet 约 **1.5–3%**，Edge 移动不足 1.5%；**84% 用户不换默认浏览器**。
  来源：[StatCounter 汇总](https://en.wikipedia.org/wiki/Usage_share_of_web_browsers)、[Chrome 份额](https://technologychecker.io/blog/chrome-market-share)、[份额数据](https://commandlinux.com/statistics/chrome-vs-firefox-vs-safari-vs-edge-market-share/)
- **这意味着「正面抢份额」不是本次交付可验证的目标**。可验证的目标是：**在同等引擎前提下，做出可测量的体验优势**，并把「优势」定义成对手因架构承诺而难以复制的项。

| 维度 | Chrome 的结构性约束 | Velox 的可乘之机 |
|---|---|---|
| 首帧 | 完整浏览器要起 browser+GPU+renderer 多进程，再靠 Freeze-Dried Tabs 补丁 | 进程内引擎 + **零引擎冷启动的首帧策略**（Live Snapshot，见架构文档） |
| 移动端预取 | 无 hover 信号；官方承认移动端额外预取「无收益」而未启用 | **触摸意图引擎**：touch-down / 视口驻留 / 阅读进度 → preconnect → prefetch |
| 地址栏 | 自动隐藏换来屏幕，代价是 viewport 变化引发的重排 jank | **常驻零高度的工具栏**：可展开、可拖拽、可固定，永不改变内容布局 |
| 拦截 | 移动端 Chrome 无扩展（MV3 后更受限），Samsung 需装扩展 | **内核级请求拦截**，一次哈希查表，无进程外依赖 |
| 定制 | 追求跨平台一致，故 UI 保守 | **布局自由**：底部/顶部/自动隐藏三态 + 手势可配 |
| 数据 | 商业模型决定遥测 | **零遥测、零账号、本地存储**（可审计） |

---

## 5. 明确结论

### 5.1 靠什么超越
> **靠「时间差的工程」而不是「引擎的重写」。**
> 引擎用系统 WebView（Chromium，与 Chrome 同源，渲染能力天花板一致）；Velox 把可超越的 300–500ms 拆成三段并逐段拿走：
> 1. **首帧前**（0–400ms）：引擎异步预热 + 渲染进程预热 + 零引擎冷启动的首帧接管；
>    
> 2. **点击前**（不可预知的网络等待）：触摸意图触发的 preconnect/prefetch/prerender；
> 3. **每帧内**（8.3/16.7ms）：内容永不被浏览器 UI 挤压（无 viewport 重排）+ 渲染优先级固定为 IMPORTANT 且不随可见性降级 + 同步屏障长度的严格约束。
>
> 再叠加**用户可感知但对手不做**的四项：零妥协拦截、单手可达、手势级导航、零遥测。

### 5.2 在哪些维度超越（可测量）
| # | 维度 | 指标（口径见 BENCHMARK.md） | 目标（相对 Chrome，中端机） | 依据 |
|---|---|---|---|---|
| D1 | 冷启动首帧 | TTID / TTFD | TTID 领先 ≥100ms；TTFD 领先 ≥250ms（且以快照先出帧） | WebView 首构 182–400ms；startUpWebView 后台化；Baseline 22–40%；Freeze-Dried 13% |
| D2 | 二次打开 | TTFD（温启动） | 领先 ≥150ms | 引擎已预热，二次构造成本 ~4ms |
| D3 | 移动加载 | 到达首字节/LCP（同站点、同网络） | 有意图信号时领先 ≥100ms（成功预取率 ≥25% × −180~300ms） | Shopify −180ms；命中率 28% |
| D4 | 滑动 | 慢帧率（>16.7ms）/ P99 帧时长 | 慢帧率不劣于 Chrome，且**内容位移=0** | RAIL/120Hz 预算；地址栏 jank |
| D5 | 内存 | 回切重载率 / 峰值 RSS | 回切重载率显著低于 Chrome（主动预测淘汰） | discard vs 预测淘汰 |
| D6 | 易用性 | 单手操作完成时间（KLM）、任务步数、广告遮挡面积 | 主任务步数 ≤ Chrome；广告/弹窗遮挡面积 −70%~90% | Hoober；79% 广告沮丧度 |
| D7 | 隐私 | 出网第三方请求数 | 0 遥测 | Surfshark 排名 |

### 5.3 如何验证（并如何证伪）
1. **不测「跑分」，测用户可见时刻**：TTID/TTFD 用 `reportFullyDrawn` 与 Macrobenchmark `StartupTimingMetric`；滑动用 `FrameMetrics`/`FrameTimingMetric` 的 P50/P90/P99 与慢帧比率；加载用 `TimingLogger`/`WebView` 回调对齐到导航起点（非 onPageStarted）。
2. **只比同条件**：同设备/同 WebView 版本/同网络/同冷缓存，对 Chrome 亦用 `am start -W` 与 `reportFullyDrawn` 对齐口径。
3. **证伪条款**（写进 CONTRIBUTING）：若在 ≥30 次迭代的 P90 上 Velox 未领先上述任一目标，则该维度主张**作废并从 README 删除**，不保留营销措辞。
4. **边界声明**：JS 执行吞吐、WASM、WebGL 等纯引擎指标**不宣称领先**（同一 Chromium）；多进程带来的安全隔离等级**低于** Chrome 站点隔离，本文档与 README 均须明示。

---

## 6. 设计决策映射（研究 → 代码）

| 研究结论 | 落地模块 |
|---|---|
| 引擎首次初始化 182–400ms | `WebViewKernel`：`startUpWebView` 异步启动 + 渲染进程预热 + `Profile` 级预连接 |
| 首帧不必等引擎 | `SnapshotStore` + `SnapshotView`：上次形态的无引擎快照先出帧（Live Snapshot） |
| 移动端无 hover | `IntentEngine`：touch-down / 视口驻留 / 阅读进度 → 预取 |
| 地址栏引发重排 | `ChromeBar` 三态设计：叠层绘制，永不改变 WebView 几何尺寸 |
| 79% 广告沮丧 | `Interceptor`：Bloom + 反向 Trie，host 级 O(1) 判定，主线程零阻塞 |
| 单手 96% vs 61% | 所有主控固定在底部；`EdgeGestures` 手势导航 |
| 标签页 2.8s 查找成本 | `TabStore`：限定存活标签页数 + 快照式标签卡（非引擎快照） |
| 120Hz 8.3ms 预算 | `FrameBudget`：严格禁止在 `onScrollChanged`/`onDraw` 中做分配与 IO |

---

## 7. 已知限制（必须公开写进 README）
1. 引擎是系统 WebView：站点隔离等级低于 Chrome；WebView 版本由设备决定（可通过 Play 商店更新）。
2. WebView 单 renderer 意味着「一个标签页崩溃可能带走全部标签页」→ 必须实现 `onRenderProcessGone` 的**会话恢复**，本项目已实现。
3. 本仓库不含 Chromium 源码，故「引擎级」优化通过公开 SDK 能力 + 时序编排达成；任何暗示「自研内核」的表述都属虚假。
4. 预取在省流量/省电/低电量下默认退让（与 Chrome 一致），因此 D3 的成功率是条件性指标。
