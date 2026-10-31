# Changelog

## 1.0.0 — 2026-10-31

首个正式版本。核心命题：**在不重写引擎的前提下，把「首帧前 300ms」「点击前 200ms」「每帧 8ms」抢回来。**

### 性能
- 引擎异步预热：`startUpWebView` + `warmUpRendererProcess` + `preconnect`，全部在 `IdleHandler` 中启动，不阻塞 `Application.onCreate`
- 零引擎首帧：上次会话的 WebP 快照直接出帧（<16ms），真实页面在后台加载后淡出替换
- Baseline Profile 覆盖启动与首页热路径
- 触摸意图引擎：L0 预连接 / L1 预取 / L2 预渲染 三级推测，命中即省 130–300ms
- 工具栏三态叠层：会话内 WebView 几何不变 → 零 viewport 重排（可用 `ResizeObserver` 验证计数为 0）
- 标签页活跃上限按内存档位自适应，超出时按评分预测式淘汰，回切先出快照

### 拦截与隐私
- 内核级拦截：121,032 域名 + 273 例外，Bloom(177KB) + 有序 64 位哈希两级判定，约 2.3µs/次
- `shouldInterceptRequest` 内零分配、零 IO、零正则、零锁
- 默认阻断第三方 Cookie；证书错误一律不忽略
- **零遥测**：无账号、无云同步、无使用数据上传；性能采样只留在本机

### 易用性
- 底部拇指区交互（底部命中率 ~96% vs 顶部 ~61%）
- 标签页卡片带真实快照
- 边缘手势：左缘后退、右缘前进、下拉刷新（未命中不消耗手势）
- 阅读模式、页内查找、桌面版 UA 切换、分享
- 无痕会话使用独立 WebView Profile，退出即销毁

### 稳定性
- `onRenderProcessGone` 全量恢复（WebView 单 renderer 的补偿）
- 规则资产损坏时降级为空规则集，不阻断浏览

### 已知限制
- 引擎为系统 WebView，站点隔离等级低于 Chrome；JS/WASM/WebGL 性能与 Chrome 同级（不宣称领先）
- 预取收益为条件性指标，省电/省流量档位主动退让
