# QuickShop-Hikari 性能优化报告·第五轮（剩余每笔交易 DB 写批处理，2026-08-17）

> 基准程序：`benchmark/`（db 套件再增 externalCacheUpdateSingle / externalCacheBatch100，共 **34 用例 × 7 套件**）
> 方法论：同态交替 A/B（基线 commit `a79b95d9e` = R16 闭合并提交文档后的 HEAD，`git worktree` 交替安装，
> 各 3 fork 中位数）；环境同前（JDK 21.0.10 / Windows 11 / Mockito 5.14 / H2 MODE=MYSQL 内存库 / 真实 EasySQL）。

## R17：qs_external_cache 与 qs_messages 批处理（commit b4c6ad872..e323f4381）

### 问题定位

R16 处理了 qs_log_purchase 后，每笔交易仍剩两类直写：

1. **qs_external_cache REPLACE**：`InternalListener.shopInventoryCalc` 在库存计算事件值变化时
   （每笔成功交易必变）直接 REPLACE 一行——高频交易下每秒与指标同量级的单行语句，
   而同一商店连点时这些中间值没人读（浏览菜单/异步回退只在读时取最新）。
2. **qs_messages INSERT**：店主离线时每笔交易一条 `saveOfflineTransactionMessage`——
   玩家收件场景只在加入/flush 时读取。

### 改动

1. **通用 `BatchingQueue<T>`**（b4c6ad872）：并发队列 + 定时/阈值冲刷 + **可选按键去重**
   （同键保末值，冲刷时 LinkedHashMap 折叠）。冲刷只可能把队列拆成多批，不丢不重。
2. **`DbWriteBatcher`**：两条队列——外部库存缓存（按 shopId 去重，`createReplaceBatch`）与
   离线消息（保序追加，`createInsertBatch`）；对应 `SimpleDatabaseHelperV2` 新增批量方法
   （**具体类方法，不动 API 接口**；原单条 API 保持）。
3. **接线**（83818ac2a）：InternalListener/MsgUtil 生产端入队（回退直写当批量器缺席）；
   读方链式冲刷保精确新鲜——`queryShopInventoryCacheInDatabase` 先 `flushInventoryCacheAsync`
   再查询，`MsgUtil.flush` 先 `flushMessagesAsync` 再 select；关停 `flushSync` 排空。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| db/externalCacheBatch100（每条摊销） | —（基线无此路径） | 960,606/条 | 对单条 **-67.7%** | createReplaceBatch，候选三 fork 中位数 96.1ms/百条 vs 单条 2.97ms |
| db/externalCacheUpdateSingle | 3,351,304 | 2,971,572 | -11.3%（db 套件同向带内） | 直写 API 本轮未改语义 |
| db/metricInsertBatch100 | 297,597,167 | 297,458,767 | -0.0%（R16 成果在基线中，无回归） | — |
| trade / lookup / text / serialize 对照（主线） | — | — | ±5% 内 | — |
| db/insertShopChain | 3,253,127 | 3,949,844 | +21.4%（已知高方差的 db 漂移用例，R16 同会话为 -16.1%，两轮同摆动） | — |
| listener/searchShopContainer | 26,960 | 31,892 | +18.3%（R15/R16 两轮分别 +14.2%/-9.4%，同摆动带） | — |
| lookup/getShopByLocation-hit | 3,412 | 4,314 | +26.4%（R2 报告即标注的高方差用例） | — |

说明：`externalCacheBatch100` 为候选专属用例（运行时反射探测注册）；批处理收益以候选侧
「单条 vs 批 100 摊销」同会话对比量化：**每条 2.97ms → 0.96ms（-67.7%）**，高于指标批
（-32.6%）因为 REPLACE 免去 locate 环节。生产语义收益更大：同店连点的中间值在窗口内折叠
（DbWriteBatcher 按 shopId 保末值），N 笔连续交易对同一商店只产生 1 行 REPLACE，而不是 N 行。
机器可读数据：`benchmark/results/round17-baseline/` 与 `round17/`（3 fork 交替，零套件失败；
本轮另有首轮作废记录——基准模块跨包引用包私有 ShopCacheRow 导致编译错误被脚本吞掉，
已修复并将可见性修正独立提交）。

### 语义与红线

- **外部库存缓存可见性**：同一商店一个窗口内的中间值折叠为末值落地（这些中间值本无读者）；
  读者（浏览菜单 MarketUtils、跨区异步回退 ContainerShop.getRemainingStock）经链式冲刷读到
  精确最新值，无陈旧窗口。
- **离线消息**：追加语义不变（窗口内有序）；`MsgUtil.flush`（玩家加入/上线）先冲刷后读取，
  到达顺序与旧实现一致；周期清理前队列已由读路径与关停排空覆盖。
- **API 兼容**：批量方法仅在具体类 `SimpleDatabaseHelperV2` 上新增；接口与既有单条方法零改动。
- **资金路径零改动**。

### 测试（91 用例全绿，87→91）

DbWriteBatchTest ×4（真实 H2）：同店五连写收敛为末值一行；离线消息三条有序落地；
单条直写仍可用；空冲刷无操作。

## 结论

R17 把每笔交易剩余的两类直写（qs_external_cache REPLACE、qs_messages 离线消息 INSERT）并入
批量器：外部缓存每条摊销 -67.7%（H2），同店连点 N 行→1 行；离线消息保序批插。读方全部链式
冲刷（浏览菜单/异步回退/玩家加入收件），无陈旧窗口；直接 API 与接口零改动；91 用例全绿。
三轮（R15–R17）后每笔成功交易的 DB 语句数：指标 1→1/窗口、外部缓存 1→1/窗口（同店折叠）、
离线消息 1→1/窗口，全部主线程提交成本降为队列入队（约百 ns 级）。
