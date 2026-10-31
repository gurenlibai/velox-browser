# 贡献指南

## 性能红线（提交前自查，违反者不予合并）

1. `Application.onCreate` / `Activity.onCreate` 不做磁盘 IO、不 `new WebView()`。
2. `shouldInterceptRequest` 内禁止：IO、正则、字符串拼接日志、加锁、对象分配。
3. `onScrollChanged` / `onDraw` / `onBindViewHolder` 内禁止分配与 `findViewById`。
4. 主线程单次任务 >8ms 视为缺陷（120Hz 预算的一半）。
5. 禁止滚动劫持；注入的 touch 监听必须 `passive: true`。
6. 任何新增 UI 都必须是**叠层**，不得改变 WebView 的 layout 尺寸
   （否则会重新引入 viewport 重排 jank）。

## 「超越」主张的准入规则

新增任何性能/体验主张时，必须同时提交：

- 对应的可测量指标定义（参照 `docs/BENCHMARK.md` 的格式）；
- 可复现步骤（脚本放 `bench/`）；
- **证伪条件**：什么情况下该主张作废。

未达标 → 删除主张，不做措辞降级。

## 代码风格

- Kotlin，无第三方 UI 框架依赖（自绘以保证启动开销可控）；
- 所有 `WebView` 引用必须在 `onDestroy` 前 `destroy()` 并置空；
- 新逻辑优先写进可 JVM 单测的纯函数（`filter/`、`util/`）。

## 提交信息

```
perf(startup): 引擎预热改到 IdleHandler，冷启动 P90 -38ms
feat(ui): 工具栏支持拖动吸附
fix(filter): userinfo 形式的 URL 未被正确解析 host
```
