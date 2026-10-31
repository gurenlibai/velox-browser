# Velox 架构设计

## 1. 总览

```
┌──────────────────────────────────────────────────────────────┐
│ VeloxApp (Application)                                       │
│  ├─ KernelWarmup     : 引擎异步启动 + 渲染进程预热 + 预连接      │
│  ├─ SnapshotStore    : 形态快照（首帧不依赖引擎）               │
│  ├─ TabStore         : 标签页元数据 + 存活策略 + 会话恢复        │
│  └─ Settings/Stats   : 本地存储、零遥测                        │
├──────────────────────────────────────────────────────────────┤
│ MainActivity (单 Activity + 叠层视图)                          │
│  ├─ SnapshotView     : 冷启动首帧（无引擎）                     │
│  ├─ BrowserView     : WebView 容器 + 生命周期编排               │
│  │   ├─ KernelProfile : 默认/隐私双 Profile，隔离 Cookie 存储    │
│  │   ├─ Interceptor   : host 级拦截（Bloom + 反向 Trie）        │
│  │   ├─ IntentEngine  : touch-down/驻留/阅读进度 → 预取          │
│  │   └─ FindInPage / Media / Downloads / Permission 处理        │
│  ├─ ChromeBar        : 三态工具栏（叠层，永不改变 WebView 几何）  │
│  ├─ Overlays         : 标签页/菜单/历史/快速跳转（自绘，无外部依赖）│
│  └─ EdgeGestures     : 返回/前进/刷新手势                        │
└──────────────────────────────────────────────────────────────┘
```

**设计第一原则：WebView 的几何尺寸在整个会话内不再变化。**
所有浏览器 UI 都是**叠层**（`FrameLayout` 上的兄弟节点），通过 `translationY` 位移来显隐，绝不用「占位布局随滚动收起」的做法。这一条直接消除了研究中记录的「地址栏收起 → viewport 变化 → 100vh 重排 → 内容跳动」这一类 jank。

---

## 2. 关键子系统

### 2.1 KernelWarmup：把 300ms 摊到用户看不见的地方

事实：`new WebView()` 首次会同步拉起 Chromium（中端机 182–400ms），二次仅 ~4ms。
方案（三层，逐层降级，任一层可用即可）：

1. `WebViewCompat.startUpWebView(context, config, receiver)`（webkit 1.16+）：把**能后台做的部分搬到后台线程**，把必须主线程做的切块执行。
2. `Profile#warmUpRendererProcess()`（可用时）：预先 fork renderer，规避首导航时的进程创建。
3. `Profile#preconnect(origin)`：对「上次会话最后停留的站点」预建 TCP/TLS/QUIC，省掉 DNS+握手往返。

调用时机：`Application.onCreate` 中 **post 到 IdleHandler / 主线程队列尾部**，绝不阻塞首帧。
失败/不支持 → 静默降级到「首次 new WebView 时的同步初始化」，行为与普通浏览器一致（不劣化）。

```kotlin
// 关键：不阻塞 Application.onCreate
Looper.myQueue().addIdleHandler {
    KernelWarmup.startAsync(appContext) { /* 预热完成，仅记时间戳 */ }
    false
}
```

### 2.2 首帧策略：Live Snapshot（不依赖引擎）

Chrome 的做法（Freeze-Dried Tabs）拿到 13%；Velox 的目标是**首帧完全不需要引擎**：

- 离开页面 / `onPause` 时，在后台线程把 **URL + 标题 + 滚动位置 + 一张降采样位图** 写入本地（`filesDir/snapshots`）。
- 冷启动时 `SnapshotView` 立即显示该位图（典型 <16ms 绘制）。
- **顺序是硬约束**：第一帧只允许纯 View；`WebView` 的创建/首次 `attach` 一律推迟到首个
  `Choreographer` 帧回调之后（`MainActivity.deferEngine`）。否则「零引擎首帧」不成立。
- 引擎就绪后 `onPageCommitVisible` 到达时淡出快照。
- 与 Chrome 的差异：Chrome 的 freeze-dried tab 是一份可交互的轻量文档渲染；Velox 的取舍是**零引擎 + 零解析**，因此首帧更早，代价是快照不可交互（点击即进入真实页面，`WebView` 已在下方就绪，通常无感）。

> 证伪条件：若实测冷启动 TTID 未领先，则该策略在同一 PR 中回退，不保留。

### 2.3 IntentEngine：移动端唯一的「点击前」高置信信号

桌面靠 hover 预取；移动无 hover。Velox 用三级信号，**代价从低到高，逐级升级**：

| 级 | 信号 | 动作 | 代价 |
|---|---|---|---|
| L0 | 页面打开（可预测的下一步） | `Profile.preconnect(origin)` | 一个 TCP/TLS 握手 |
| L1 | **手指按下**（`ACTION_DOWN` 命中链接，通过注入的命中测试上报） | `Profile.prefetchUrlAsync`（只取 HTML） | 一次文档请求 |
| L2 | 链接在视口内驻留 ≥400ms 且是页面主内容链 | `prerenderUrlAsync`（整页渲染，隐藏态） | 一次完整渲染（最贵） |

约束（缺一不可）：
- 省电模式/省流量/电量 <15% → 只允许 L0；
- 同一时刻最多 1 个 L2、2 个 L1；
- `CancellationSignal`：手指抬起但未跳转 / 视口移出 → 立刻取消；
- 与 Chrome 一致，预取请求携带匿名化意图（不带 Cookie 的新连接由 WebView 内部处理）。

命中测试注入代码（只在顶层框架、且用户未关掉预取时注入）：

```js
// 只做纯几何命中计算，不修改 DOM，不产生重排
document.addEventListener('touchstart', e => {
  const t = e.touches[0]; if (!t) return;
  const el = document.elementFromPoint(t.clientX, t.clientY);
  const a = el && el.closest && el.closest('a[href]');
  if (a) VELOX_INTENT.href(a.href, performance.now());
}, {passive: true, capture: true});
```

### 2.4 Interceptor：内核级拦截（零主线程阻塞）

- 规则形态：**host 后缀匹配**（占真实拦截收益的绝大多数，且可 O(1) 判定）。规则集编译为
  - `BloomFilter`（内存 ~128KB/10 万条，假阳性 <1%）做**快速否决**：未命中 → 直接放行；
  - 命中后查**反向域名 Trie** 精确确认（`ads.example.com` → `com.example.ads`），排除 Bloom 假阳性。
- 全部在 `shouldInterceptRequest` 的**回调线程**执行，纯内存比较，无 IO、无锁、无分配（复用 `StringBuilder`）。
- 首个字节立即返回空响应，避免等待——被拦请求不占用连接。

> 依据：`shouldInterceptRequest` 是**同步**调用且在同一条线程上串行处理，任何阻塞都会拖住所有请求（社区大量事故案例）。因此本项目的硬性红线：**该回调内禁止 IO / 正则 / 分配**。

### 2.5 ChromeBar：三态工具栏（叠层）

- **Collapsed**：仅一条 44dp 的细条 + 进度线；
- **Pinned**：完整地址栏 + 主控（可上下拖动改变高度，手指抬起后吸附）；
- **Hidden**：全屏沉浸，从底部上滑唤出。
- 三态之间用 `translationY` + `ValueAnimator`，`duration 180ms`，跑在渲染线程；**任何状态下 WebView 的 `layout` 参数不变**，因此不会触发页面 `resize`。

### 2.6 TabStore：带预测的淘汰

- 存活标签页上限 = `min(8, max(4, RAMGB * 2))`；
- 逐出顺序由「上次访问时间 × 站点类型 × 是否正在播放媒体」的评分决定，媒体/PiP/有表单输入的标签页受保护；
- 被逐出的标签页保留 **元数据 + 快照**，回切时走 2.2 的快照路径，因此「回切重载」被隐藏在一次淡入之后；
- 每个 `WebView` 在 `onDestroy` 时 `destroy()` 并置空引用；单例持有列表而非 View 引用，消灭经典 WebView 泄漏。

### 2.7 进程崩溃恢复（WebView 脆弱点）

WebView 只有一个 renderer：任一标签页 OOM/崩溃可能影响全部。必做：
`onRenderProcessGone` → 清理所有 `WebView` → 重建当前标签页 → 其余标签页标记为「需恢复」并提供一键恢复。
这是把「多进程安全隔离」的劣势（相对 Chrome）降到可接受范围的**补偿机制**。

---

## 3. 性能红线（Code Review 检查项）

1. `Application.onCreate` / `Activity.onCreate` 不做磁盘 IO、不 `new WebView()`。
2. 任何 `shouldInterceptRequest` 实现里禁止 IO、正则、日志字符串拼接。
3. `onScrollChanged`、`onDraw`、`onBindViewHolder` 内禁止分配与 `findViewById`。
4. 主线程单次任务 >8ms 视为缺陷（120Hz 预算的一半）。
5. 禁止 `requestDisallowInterceptTouchEvent(true)` 之外的滚动劫持；所有注入的 touch 监听必须 `passive: true`。
6. `WebView.setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)` —— 不因不可见而降优先级（否则回切白屏）。

---

## 4. 交付物结构

```
app/src/main/java/com/velox/browser/
  VeloxApp.kt            应用入口与生命周期
  KernelWarmup.kt        引擎预热
  MainActivity.kt        唯一 Activity，叠层视图编排
  browser/
    BrowserView.kt       WebView 封装与生命周期
    KernelProfile.kt     Profile / 预连接 / 预取
    Interceptor.kt       请求拦截
    IntentEngine.kt      触控意图与预取调度
    TabStore.kt          标签页与会话
    SnapshotStore.kt     形态快照
  filter/
    BloomFilter.kt
    SuffixTrie.kt
    RuleSet.kt           规则解析与装载
  ui/
    ChromeBar.kt         三态工具栏
    TabSwitcher.kt       标签页总览
    MenuSheet.kt         菜单
    EdgeGestures.kt      手势
    Feedback.kt          haptic/ripple
  metrics/
    LatencyLogger.kt     本地时延采样（不上传）
```
