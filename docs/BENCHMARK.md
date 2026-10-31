# Velox 基准与验证协议（可证伪）

## 0. 原则

- **只比同条件**：同设备、同 WebView 版本、同网络、同冷缓存、同被测站点、≥30 次迭代取 P50/P90/P99。
- **只测用户可见时刻**：以「用户看到有意义内容」为终点，不以内部事件为终点。
- **公开原始数据**：`bench/results/*.csv`，任何人可复现。
- **失败即撤稿**：任一主张未达标 → 立即删除 README 中的对应措辞（不是淡化，是删除）。

## 1. 设备档位

| 档位 | 代表 | 用途 |
|---|---|---|
| 低 | 4GB RAM / A53 级 / Android 11 | 决定是否「可用」 |
| 中 | 6–8GB / A7x 级 / Android 13 | **主张的主战场** |
| 高 | 12GB / 旗舰 / 120Hz / Android 14 | 检验 8.3ms 预算 |

## 2. 指标与采集方法

### 2.1 启动
| 指标 | 采集 | 备注 |
|---|---|---|
| TTID | `Activity.reportFullyDrawn()` + `adb shell am start -W` | 只作交叉验证 |
| TTFD | Macrobenchmark `StartupTimingMetric` | 主指标 |
| 快照首帧 | `SnapshotView.onDraw` 打点 | 用于证明「首帧不依赖引擎」 |

对 Chrome 的对齐口径：`am start -W`（TotalTime）+ Chrome 自身 `reportFullyDrawn`（可用 `dumpsys gfxinfo` 辅助）。Chrome 的 Freeze-Dried Tabs 也会生效，因此**必须对比它开启后的表现**，不能挑对手未优化的状态。

### 2.2 滑动
- `FrameMetrics`（API 24+）：慢帧 = CPU 耗时 >16.7ms；卡死帧 >700ms。
- 采样页：长文（2000+ 字）、图片瀑布流、无限滚动列表、含 fixed header 的页面各 3 个。
- 报告：P50/P90/P99 帧时长 + 慢帧占比 + **内容位移量**（通过注入 `ResizeObserver` 统计 viewport 高度变化次数，Velox 目标 = 0）。

### 2.3 加载
- 起点：`onPageStarted` 内记录 `SystemClock.uptimeMillis()`（与 didCommit 对齐）。
- 终点：注入 `PerformanceObserver` 采集 LCP，同时用 `onPageCommitVisible` 兜底。
- 分组：**命中预取 / 未命中预取**分别统计（不得把未命中混入平均值）。

### 2.4 内存
- `dumpsys meminfo <pkg>` 的 TOTAL PSS（含 renderer，需分别取 `:sandboxed_process0`）。
- 回切重载率：切到后台 5 分钟后回切，统计 `onPageStarted` 是否被重新触发。
- 10 标签页 / 30 标签页两档。

### 2.5 易用性
| 指标 | 方法 |
|---|---|
| 单手任务完成时间 | 5 个任务（新开标签→搜索→返回→切标签→加入书签），KLM 预测 + 实机计时，右手单手 |
| 任务步数 | 记录从首页到目标状态的最小点击数 |
| 遮挡面积 | 页面加载完成后，注入脚本统计 `position:fixed` 且面积 >15% 视口的元素数量与总面积 |

## 3. 目标值与证伪条件

| 维度 | 目标（中端机，P90，vs Chrome 同条件） | 未达标处理 |
|---|---|---|
| D1 冷启动 TTID | ≤ Chrome −100ms | 删除 D1 主张 |
| D1 冷启动 TTFD | ≤ Chrome −250ms | 同上 |
| D2 温启动 TTFD | ≤ Chrome −150ms | 删除 D2 |
| D3 加载（命中预取） | ≤ 同站未命中 −100ms | 删除 D3 的「命中」条款 |
| D3 预取成功率 | ≥25% 的可预判导航 | 若 <10%，关闭 L2 级预取 |
| D4 慢帧率 | 不高于 Chrome | 若更高，回退注入脚本 |
| D4 内容位移 | = 0 次 viewport 高度变化 | 若 >0，修 UI 叠层策略 |
| D5 回切重载率 | ≤ Chrome 的 50% | 否则调整淘汰阈值 |
| D6 主任务步数 | ≤ Chrome | 否则重排 UI |
| D6 遮挡面积 | ≤ Chrome 的 30% | 否则扩充规则集 |
| D7 遥测 | 0 出网 | 任一非用户请求的出网 → 视为 P0 缺陷 |

## 4. 复现脚本（骨架）

```bash
# 冷启动 30 次，输出 TTID 中位数
for i in $(seq 1 30); do
  adb shell am force-stop com.velox.browser
  adb shell am start -W -n com.velox.browser/.MainActivity | grep TotalTime
done

# 帧指标
adb shell dumpsys gfxinfo com.velox.browser framestats > bench/framestats.txt

# 内存（含 renderer）
adb shell dumpsys meminfo com.velox.browser | head -40
```

## 5. 反作弊声明

- 不使用「预烘焙首页」制造虚假首帧：快照仅来自用户真实上次会话。
- 不在测试环境关闭拦截以提高或降低负载；拦截开关在两组都保持默认。
- 不选择「对己方有利的站点」：被测站点清单在测试前冻结并提交到仓库。
