# QuickShop-Hikari 性能优化报告·第二十六轮（库存定位链成本剥离，2026-09-06）

> 承 R38 分配轴方法论与 R39 JFR 定向归因。基线 `f97083504`（R39 完成点），
> 候选 `94d3b4e08`，3 fork/侧严格交替，全 **50** 用例双轴计量（新增
> `trade/locateSymbolLink` 用例，基线 worktree 仅同步基准模块以保持两侧同名单）。

## 背景：每次 shop.getInventory() 都在跑一遍必败的 Gson 解析

R40 会话对 HEAD 的 JFR 复查（`jdk.ObjectAllocationSample` 全栈 + 首个 quickshop
业务帧归因，方法同 R38/R39）显示：交易套件 18.3 GB 采样中，除已被 R34/R35/R38/R39
处置的扫描与操作层大头外，剩余的真实业务分配集中在**库存定位链**：

```
ContainerShop.getInventory()                       ← 每笔交易 1 次（预检/提交共享）
  → BukkitInventoryWrapperManager.locate(symbolLink)
    → CommonUtil.isJson(symbolLink)                ← 全量 Gson 解析，仅为判别格式
      → JsonParser.parseString("2;1000;64;1000;world")
        → char[]/int[] 等解析器内部结构（trade 套件采样 27.8 MB）
        → 必然失败 → JsonSyntaxException 构造 + fillInStackTrace + catch
```

关键事实：**生产占绝对多数的新格式链接（`v;x;y;z;world`，`BlockPos.serialize`
产物）对 Gson 而言必然解析失败**——即每次库存定位都触发一次异常构造与栈回填。
逐用例剖析模式（`-Dbenchmark.profile=trade/tradeServiceBuy`）佐证定位链采样：
isJson 21 + locateNew 14 + fromLocation 10 + ensureThread 19 + isValid 8 样本，
是扫描/操作 mock 层之外最大的连续真实成本块。

次级发现：`PerfMonitor` 构造器每次受监测块分配一个 `Instant`（`Instant.now()`），
且墙钟测量受 NTP 调整影响；热路径上的受监测块包括每次 locate/mklink、每次库存
事务 commit/rollback、每次经济事务 commit/rollback。

## 改动（commit `94d3b4e08`）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `BukkitInventoryWrapperManager.locate` 格式判别 | `CommonUtil.isJson(symbolLink)`：对链接串做完整 Gson 解析（新格式必败 → 每次定位一次 `JsonSyntaxException` 构造+栈回填+捕获） | 首字符判别：`'{'` 前缀 → 旧格式（Gson 路由），否则新格式（BlockPos 路由） | **内部两种格式路由严格一致**：旧格式链接是 Gson 对象序列化产物，必以 `{` 开头（Gson 无前导空白）；新格式 `v;x;y;z;world` 必以数字开头。等价性由基线事实闭环：R39 及此前所有基准的 trade 用例在旧代码下均成功走 locateNew，证明 `isJson("2;…")==false` ≡ 首字符非 `{`。损坏输入两侧同样以 `IllegalArgumentException` 收场（locate 的 catch 统一包装两分支异常；仅病态输入的消息文本不同）；`null`/空串仍走 locateNew 失败路径，与原 `isJson` 的 false 分支一致 |
| `PerfMonitor` 计时基 | `Instant.now()` 起始 + `Duration.between`（每次受监测块一个 Instant 分配；墙钟非单调） | `System.nanoTime()` 起始 + `Duration.ofNanos`（零额外分配；单调钟） | 消息文本逐位保持（`getTimePassed()` 返回的 Duration 语义与毫秒展示格式不变，既有 `LogRingAndLazyMessageTest` 的布局断言全绿）；限额判定（WARNING 级）免疫 NTP 调整反而更正确；移除无外部引用的 `getStartTime()`（全仓检索仅 BatchBukkitExecutor 有同名方法，非本类） |

### 测试面

新增 **3 用例**（合计 **188 全绿**）：`SymbolLinkRoutingTest`——
①新格式链接经 BlockPos 路由（验证 `world.getBlockAt(x,y,z)` 以正确坐标命中）；
②旧格式链接（用生产同源 `JsonUtil.standard().toJson(CommonHolder/BlockHolder)`
构造）仍走 Gson 路由并解析出同一方块；③损坏输入（`{` 前缀垃圾与非 `{` 垃圾）
保持 `IllegalArgumentException`。

### 计量面

新增基准用例 **`trade/locateSymbolLink`**（trade 套件，50 用例）：对生产同形链接
`"2;1000;64;1000;world"` 走真实 `BukkitInventoryWrapperManager.locate`，隔离计量
定位链全成本（格式判别 + BlockPos 反序列化 + 世界/方块状态解析 + PerfMonitor 跨度
+ Log.performance 记录）。基线 worktree 仅同步 `TradeBench.java`（基准模块单文件，
不触碰主代码），两侧同名单可比。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| trade/locateSymbolLink | 16,104 | 11,312 | **-29.8%** | [16,104, 16,104, 16,184] \| [11,312, 11,312, 11,312]——完全分离 |
| trade/actionBuy | 1,314,536 | 1,299,008 | **-1.2%** | [1,303,640, 1,314,536, 1,319,688] \| [1,297,888, 1,299,008, 1,299,568]——完全分离（max cand < min base） |
| trade/actionSell | 1,339,864 | 1,323,560 | **-1.2%** | [1,328,192, 1,339,864, 1,344,592] \| [1,323,040, 1,323,560, 1,324,120]——完全分离 |
| trade/tradeServiceBuy | 936,528 | 932,320 | **-0.4%** | [936,504, 936,528, 944,096] \| [931,576, 932,320, 932,440]——完全分离 |
| trade/tradeServiceSell | 976,112 | 971,290 | **-0.5%** | [975,872, 976,112, 983,968] \| [970,920, 971,290, 972,024]——完全分离 |
| 其余 45 共享用例 | — | — | ±2% 内 | fork 区间全部重叠（零回归带） |

分配量与定位次数自洽：每定位节省 4,792 B（16,104→11,312）；tradeServiceBuy 每
op 恰 1 次定位（预检/提交共享，省 ~4.2KB），actionBuy/Sell 每 op 约 3 次定位
（action 层的独立 preview 入口无预定位共享，省 ~15KB）——与各自 delta 吻合。

时序轴（参考）：locateSymbolLink **-16.3%**（20.6→17.2 μs）、actionSell -7.5%、
tradeServiceSell -6.5%、actionBuy -4.0%；其余用例 ±10% 内抖动（无代码改动的
countItemsScan54 亦现 -10.2%，为本机 ns 轴噪声水平的典型样本，如实留档不采信）。

如实入册两点：

- **lookup/getShopByLocation-hit/miss +1.7%/+2.0%、runtimeUuid +1.2%**：本轮对
  查找路径零改动；fork 区间全部重叠（如 hit：base [1,936,1,936,1,976] vs cand
  [1,960,1,968,1,968]），属这几个 mock 重组装用例已知的 ±1-2% B/op 抖动带。
- **text/fallbackYamlParse +1.1%**：双峰用例（4.35M/4.64M 两模式）模式混合翻转，
  与 R38/R39 观察一致，本轮对该路径零改动。

## 生产语义收益（mock 面之外）

- 每次 `shop.getInventory()`（每笔交易至少一次、action 层约三次；商店交互、
  库存校验、木牌渲染的库存读取同样经过）省去：一次注定失败的 Gson 全量解析
  （解析器缓冲结构）+ **一次 `JsonSyntaxException` 构造与栈回填**（真实服务器上
  为微秒级、且在主线程）——生产链接为 20-40 字符时解析失败发生在首字符后不久，
  但异常路径本身即主要成本；
- 每个受监测块（locate/mklink/库存事务/经济事务的 commit 与 rollback）省一次
  `Instant` 分配与 `Instant.now()` 的墙钟读取路径，计时改用单调钟后免疫 NTP 跳变；
- 格式判别从 O(串长) 解析降为 O(1) 字符比较。

## 累计（R38–R40，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R40 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| trade/countItemsScan54 | 391,576 | 277,528 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 932,320 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 971,290 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,299,008 | -13.9% |
| trade/actionSell | 1,505,232 | 1,323,560 | -12.1% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| trade/locateSymbolLink（R40 新增计量面，R37 时代码同现基线） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round40.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
python benchmark/results/compare-round40.py
```
