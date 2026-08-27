# QuickShop-Hikari 性能优化报告·第十九轮（启动装载并行化，2026-08-27）

> 基准程序与方法论同前（本轮起 46 用例 × 8 套件——database 套件新增
> startup/shopLoadChain 启动全链 wall-clock 计量面；text 套件增补
> fallbackYamlParse 解析单价面，47 用例）；同态交替 A/B，基线 commit
> `34ba5fb20` = R30 闭合并提交文档后的 HEAD，各 3 fork 取中位数。
> 本轮覆盖启用链路全部三个方面：I 装载相位（见下）、II 语言包相位（本报告补充）、
> III 管理器装配序列（甄别证据入册）。

## R31：loadShops 读店循环并行化（commit ff583e3de..d9fa43fc8）

### 问题

`ShopLoader.loadShops`（启用时把全部商店从数据库载入内存的路径）的内存物化循环：

```java
for(final ShopRecord record : records) {
    loadShopFromShopRecord(...)   // CompletableFuture.supplyAsync(..., workStealingPool)
            .exceptionally(...)
            .join();               // ← 提交一个立刻等一个
}
```

任务被提交到 `database.loader-threads`（默认 CPU 核数）级 work-stealing 线程池，
但主循环**每提交一单立即 join**——流水线完全串行化，多线程池退化为「带额外移交
开销的单线程执行器」。启动的内存装载阶段（decodeStack Base64/NMS 反序列化、extra
YAML 解析、permissions JSON 解析、ContainerShop 构造与校验）无法利用任何并行度；
万店级服务器此段以秒计。这是典型的"伪异步"反模式：API 用了并发设施，语义却是串行。

### 改动

先全量收集 futures、再 `CompletableFuture.allOf(...).join()` 聚合等待——工作线程池
并行度恢复为 N（默认 CPU 核数）。**等价性保持清单**：

- 装载结果集合 `shopsLoadInNextTick` 为 CopyOnWriteArrayList、计数器为 AtomicInteger，
  本就按并发消费设计（该代码原作者显然预期过并行）；
- 逐店 `exceptionally(warn)` 错误隔离语义不变，单店失败不影响其余装载；
- `deleteCorruptShops` 的 FAILED 分支 DB 删除仍逐店触发（EasySQL 线程安全）;
- 主线程 `mainThreadRun(nextTick 列表装载)` 在全部完成后执行一次，时序与原版相同；
- 仅可观测差异：worker 日志交错顺序真实并行化（原版名义异步本就允许乱序）。

### 结果（同态交替 A/B，各 3 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| startup/shopLoadChain（2000 店全链：H2 fetch + 内存物化 + 注册） | 97,975,067 | 33,426,862 | **-65.9%** | 基线三 fork 93.3~112.4ms vs 候选 18.6~67.5ms——基线**最优** fork 高于候选**最差** fork 38%，六 fork 零重叠；DB fetch 段两侧同码同库（其基线单独计量约 1.2~4.2ms），差异几乎全部来自物化相位并行度恢复；候选内 fork 离散（18.6~67.5M）反映测量机负载波动对并行任务的调度敏感，最差 fork 仍显著快于基线最优 |
| db/listShops | 4,204,238 | 3,648,804 | -13.2% | 同码同向漂移（本轮测量机承载外部负载水位整体偏高，trade 主链同向偏低 15%，两侧同窗不改变相对归因） |

机器可读数据：`benchmark/results/round31-baseline-fork{1,2,3}.json` 与
`round31-fork{1,2,3}.json`（对比脚本 `benchmark/results/compare-round31.py`）。
mock 成本说明：decodeStack 桩为 400B Base64+对象重建（接近真实 NMS decode 下限），
真实服务器单店解码成本更高，并行收益比例只会更大。

### 语义与红线

- 无公开 API 变化；并行上限仍是用户可配的 `database.loader-threads`
  （保守服务器可设 1 完全复原旧行为——此时新版仅省去 per-record join 的移交开销）；
- 内存峰值增幅上界 = 同时在解码的店数 × 单店解码产物 ≤ 并行度 × 数 KB（记录本体
  本就已全部在内存），1000 店级实测无 OOM；H2 fetch 相位不变仍先行完成。

## 补充·分段 II：语言包相位（commit 23a8fc5ae；计量面 fallbackYamlParse）

### 甄别

- **fallback 双解析**：`SimpleTextManager.load()` 对 110KB `lang/messages.yml` 解析
  **两次**——en_us deploy 与 fillMissing 各自 fresh 调用 `loadBuiltInFallback()`。
  经证 `fillMissing` 只读 fallback（`mergeMissing` 仅写被补语言；en_us 自补分支因
  键集相同全 skip），单实例共享与双实例逐字节等价 → 实现为局部变量复用。
- **bundled 相位无可优化面（甄别证据）**：`loadBundled()` 扫描 jar 内
  `lang/<region>/messages.yml`，但本仓库 `src/main/resources/lang/` 仅含根级 en_us
  与 example 文件、**零个子目录翻译**——发行包中该扫描匹配 0~1 条目，多语言实际
  依赖 Crowdin OTA（网络可选项）或 override 文件。串行解析零条目无从并行，
  不实现（实现即死代码并行化）。

### 结果

| 项目 | 数据 | 结论 |
|---|---|---|
| text/fallbackYamlParse（110KB 单次解析单价，两侧同码） | 基线 5,725,761 vs 候选 4,588,520（-19.9%，fork 区间大重叠） | 单次解析成本 P≈**4.6ms**；结构性 2×→1× 即每启用/每次 `/qs reload` 省 ≈P，同码零回归符合预期 |
| round31b A/B 其余 47 共享用例 | 全部噪声带（47/47 重叠） | 零回归背书 |

## 补充·分段 III：管理器装配序列（甄别证据，无改动）

onEnable/onLoad 装配清单逐项核查后的结论：

| 装配段 | 状态 |
|---|---|
| initDatabase | 启用链最大同步段之一（HikariCP+建表迁移），为全部 DB 能力前置，不可延后；已有 PerfMonitor 标记 |
| shopLoader.loadShops | 分段 I 已并行化（-65.9%） |
| playerFinder.bakeCaches / bakeShopsOwnerCache / economyLoader.load(1tick) | 已异步或延迟 ✓ |
| tagManager.loadAllFromDB | 同步 DB 读但维持"启用返回即全功能可用"的 Paper 插件契约；异步化将使 tag 命令在启动窗口期报错 = 行为变化，红线边缘放弃并留证 |
| 各管理器构造器（PermissionManager/RankLimiter/Watcher 等） | O(对象) 轻量装配，无顺序约束收益 |

## 结论

R31 把启动读店循环从"伪异步串行流水"恢复为真并行物化（2000 店全链 wall-clock
**-65.9%**，六 fork 零重叠），万店服务器的启用装载段预计以秒级缩短且随核数扩展。
十九轮累计主线不变：交易全链约 -84%，另有 DB 写批量化、文本/日志缓存化、区块包
O(1)、菜单快照、扫描类事件快路径、木牌渲染/排程、配置访问全面快照化、数值格式化
线程安全化、以及本轮的启动装载真并行化。
