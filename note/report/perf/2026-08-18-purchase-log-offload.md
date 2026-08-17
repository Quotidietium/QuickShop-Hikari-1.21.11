# QuickShop-Hikari 性能优化报告·第八轮（每笔交易日志序列化卸载，2026-08-18）

> 基准程序与方法论同前（本轮起 35 用例 × 7 套件——trade 套件新增 purchaseLogListener，
> 直调 `InternalListener.shopPurchase`，基线（急构造）与候选（延迟捕获）两侧同构可比）；
> 同态交替 A/B，基线 commit `e3f79d3bb` = R19 闭合并提交文档后的 HEAD。

## R20：购买日志渲染卸载出主线程（commit 849f722b2 之前四笔）

### 问题

`logging.log-actions` 默认开启时，每笔成功交易的 `InternalListener.shopPurchase` 在
**主线程**急切构造日志对象：`saveToInfoStorage()`（商店快照含物品 Base64 编码）+
`Util.serialize(getItem())`（第二次物品编码）+ `getItemStackName`（名称渲染）+
`LegacyComponentSerializer.serialize`，随后 `QuickShop.logEvent` 再在主线程做
`Gson.toJson`——LogWatcher 只是把成品字符串入队。基准（400 字节编码桩）测得主线程
成本 **180μs/笔**（真实物品的 NMS serializeAsBytes 只会更贵）。

### 改动

1. **LogWatcher 懒队列**：`log(String)` 与新增 `logLazy(Supplier<String>)` 共用一个
   并发队列（保全局顺序）；求值/序列化移到 watcher 的异步 tick；失败条目隔离
   （debug 记录，不损队列）；`close()` 先排空再关。
2. **QuickShop.logEventLazy(Supplier)**：文件模式异步 toJson；数据库模式经
   Hikaricp 执行器构造后走既有 `insertHistoryRecord`。原 `logEvent(Object)` 不变。
3. **InternalListener.shopPurchase 懒构造**：全部重序列化移入 Supplier，主线程只捕获
   闭包。权衡：快照读取至多延后一个 watcher 周期（≤0.5s）——诊断级漂移（快照内容
   无每笔交易字段），已在代码注释记录。

### 结果（同态交替 A/B，5 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| trade/purchaseLogListener | 180,106 | 3,698 | **-97.9%** | 基线 5 fork 全部 176~183μs，候选 5 fork 全部 3.7~4.3μs——零交叉，全项目最干净的分离 |
| trade/actionBuy / actionSell | 3,652,859 / 3,757,896 | 3,656,926 / 3,755,301 | ±0.1% | 交易主链无回归（日志成本原本在监听器直调用例中单独计量） |
| text/forLocaleWithArgs / NoArgs | 17,400 / 226 | 18,873 / 2,113 | 污染窗口，见下 | R20 未触碰文本路径 |

**text 套件测量污染说明（2026-08-18 R21 已破案并修复）**：本轮 A/B 的 10 个 fork 中
9 个出现 text 用例劣化（withArgs 14~27μs、部分 noArgs 1.9~11.8μs），而两侧代码在文本
路径上完全相同（基线 e3f79d3bb 已含 R19）。当时的甄别方法（裸 `java` 复测：withArgs
899/978ns、noArgs 190/191ns，零回归）结论正确；**真实根因在 R21 破案：基准 harness 的
Mockito mock 逐调用记录无限累积**——随基准用例增多，每个 JVM 数百万次 mock 调用的记录
元数据涨至 GB 级，挤占堆并使分配敏感路径（带参渲染）在 GC 压力下劣化 10~50 倍；R21 已
将全部热点 mock 改为 `stubOnly()`（免记录），此后全序运行 text 套件恢复 926ns 稳态。
此前若依赖本轮污染窗口的 text 数字作对照，应改用 R19 的干净数据或 R21 修复后的复测。
机器可读数据：`benchmark/results/round20-baseline/` 与 `round20/`（5 fork 交替）。

### 测试（104 用例全绿，100→104）

LogWatcherLazyTest ×4：懒/急条目全局有序；失败 Supplier 隔离；close 排空；
求值仅发生在冲刷时（入队零求值）。

## 结论

R20 把每笔交易的购买日志序列化从主线程完全卸载（180μs → 3.7μs 主线程残留，**-97.9%**，
5v5 fork 零交叉）；qs.log 内容、顺序、轮转行为不变，失败条目隔离，关停排空。真实生产
收益大于基准值（400 字节编码桩低于真实 NMS serializeAsBytes）。八轮累计（R15–R20）：
玩家交易全链 23.0ms → ~3.66ms（约 **-84%**）；每笔交易主线程残余成本 = 双库存扫描各一次
+ 事务提交 + Vault 同步转账 + 收据渲染（带参 1μs 级）+ 日志入队（4μs 级）。
