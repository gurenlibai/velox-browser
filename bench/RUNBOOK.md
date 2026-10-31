# Velox 性能实机验证手册

> 目的：让「超越」主张可被任何第三方在当前设备上复现、并被证伪。
> 未跑完本手册的维度，README 中不得出现对应措辞。

## 0. 环境记录（必须写进报告）

```
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell dumpsys webviewupdate | grep -i "Current WebView package"
adb shell cat /proc/meminfo | head -1
```

## 1. 冷启动 TTID / TTFD（D1）

```bash
./bench/startup.sh com.velox.browser  30 > bench/results/velox_startup.txt
./bench/startup.sh com.android.chrome 30 > bench/results/chrome_startup.txt
./bench/compare.py bench/results/velox_startup.txt bench/results/chrome_startup.txt
```

判定：P90 上 Velox 的 TotalTime 必须比 Chrome 低 ≥100ms，TTFD 低 ≥250ms；
否则 `docs/BENCHMARK.md` 的 D1 条款触发撤稿。

> 对齐说明：`am start -W` 的 TotalTime 与 TTID 口径接近；TTFD 需在应用内
> `reportFullyDrawn()`（Velox 已在首帧后调用）后用 `dumpsys gfxinfo` 或 Perfetto 对齐。

### 1b. 验证「首帧不依赖引擎」（本项目最硬的一条主张）

```bash
adb shell am force-stop com.velox.browser
adb logcat -c
adb shell am start -n com.velox.browser/.MainActivity
adb logcat -s VeloxLatency | grep -E "startup\."
```

必须看到 `startup.snapshotVisible` 与 `startup.toFirstFrame` **早于** `startup.engineCreatedAt`。
顺序颠倒即表示本机制失效，README 的相关表述必须删除。

## 2. 滑动流畅度（D4）

```bash
adb shell dumpsys gfxinfo com.velox.browser reset
# 在同一长文页面滑动 10 次后：
adb shell dumpsys gfxinfo com.velox.browser > bench/results/velox_gfx.txt
./bench/jank.py bench/results/velox_gfx.txt
```

同时验证「零内容位移」：页面加载完成后执行

```js
new ResizeObserver(() => console.log('viewport changed')).observe(document.documentElement)
```

在 Velox 下滚动、收放工具栏 30 次，`viewport changed` 应为 **0** 次（本项目的结构性优势）。

## 3. 移动端加载（D3）

- A 组：普通点击（预取可能未命中）；
- B 组：手指按下后预取/预渲染命中。

记录 `onPageStarted → onPageCommitVisible` 耗时，比较分布。
Velox 的本地采样会打印到 logcat：

```bash
adb logcat -s VeloxLatency | grep -E "page.commitVisible|intent.dispatch"
```

## 4. 内存与回切（D5）

```bash
adb shell dumpsys meminfo com.velox.browser | head -20
adb logcat -s VeloxLatency | grep page.start   # 切后台 5 分钟再回切，看是否重新触发
```

## 5. 拦截有效性（D6）

在 20 个新闻/电商站点上统计：
- 被拦请求数（菜单 → 性能 → 「拦截」一行）；
- 遮挡类浮层数量（`position:fixed` 且面积 >15% 视口）。

Velox 目标：遮挡面积 ≤ Chrome 的 30%。

## 6. 结果归档

把原始输出放到 `bench/results/`，并在 `docs/BENCHMARK.md` 的目标表旁附上实测值。
任何一项不达标 → 删除 README 中对应主张（不是淡化）。
