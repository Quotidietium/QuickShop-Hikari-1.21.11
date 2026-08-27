# QuickShop-Hikari 性能优化报告·第十八轮（交易链配置访问清扫，2026-08-27）

> 基准程序与方法论同前（45 用例 × 8 套件不变）；同态 A/B，基线 commit `ba3a3912e` =
> R29 闭合并提交文档后的 HEAD。本轮为**系统性清扫轮**：以全源码 grep 甄别法替代此前
> 的定向发现，清算配置访问在热路径上的全部逃网者。

## R30：交易链配置读取快照化收尾（commit 362c8945a..28ec82018）

### 方法

全源码清点 205 处 `getConfig().get*` 调用，按文件聚合后逐调用点甄别频率与等价性：
确认 QuickShopTaxManager（5 处）、ModernCustomMatcher、QuickShopItemMatcher、各监听器
init 等均已在构造/reload 快照 ✓；InteractionManager 零配置访问 ✓；命令/管理面板路径
按用户级频率判定不值得动 ✓。逃网者集中在**交易提交链**：

1. **`actionBuying:386` / `actionSelling:624`**：每笔成功交易各一次
   `getBoolean("shop.pay-unlimited-shop-owners")`——而 init() 第 207 行已有同名键的
   `payUnlimitedShopOwner` 快照字段（1308/1318 行既有消费同源），属纯粹的漏网。
2. **店主通知 lambda `:1206`**：每笔交易异步任务内一次 `getBoolean("show-tax")`。
   注意该键与 init 里已快照的 `shop-tax.show` **不同源**——新增独立字段
   `notifyShowTax`（易错点，报告特意留档：两个 "show tax" 配置键并存）。
3. **`ShopUtil` selling/buyingShopAllCalc 两处**：玩家输入 "all" 的数量计算每次读同一
   键——静态工具类无法持有实例字段，新增 `static volatile payUnlimitedShopOwners`
   + `refreshConfigSnapshots()` 公开刷新钩子，挂入既有全局 reload 入口
   `Util.initialize()`。

### 结果

| 项目 | 说明 |
|---|---|
| 结构性收益 | 每笔交易固定省 2 次、含通知路径省 3 次 YamlConfiguration 树行走（单次约百 ns~μs 级）；"all" 输入路径同免 |
| 基准呈现 | 全链 ~3ms 被 trade 主链稀释为千分位级，低于本机噪声底——**不做构件级拆分计量**（避免 mock 绝对值失真的既往教训）；以紧邻交替六 fork 的共享用例作零回归证明 |
| 共享用例 | 44 例全部回归噪声带：trade 主链 ±5%（tradeServiceBuy +4.6%/Sell +1.6%、actionBuy +5.3%/actionSell -3.4%），三个同码对照项 itemNameFlags/formatInternalPrice/getShopById 均 ±5% 内，候选第三 fork 偶发尖刺两侧对称 |

机器可读数据：`benchmark/results/round30-{baseline,}-fork{1,2,3}.json`
（对比脚本 `benchmark/results/compare-round30.py`）。

### 方法学留档（本轮新形态污染与处置）

两轮先行测量出现**环境负载型污染**：候选侧三 fork 中同码纯扫描用例
（countItemsScan54/iterateOnly54）也整体 +50%，且候选单独重跑不恢复——说明污染来自
时段差而非代码。处置：**删除全部数据、改为六 fork 连续交替重测**（B,C,B,C,B,C 紧邻），
使两侧分摊同时段噪声后对比恢复归因力（40+ 用例回到 ±5% 带）。结论：当基线取自安静窗
口而后续窗口变嘈杂时，「只重跑慢的一侧」会把环境差误归因为回归——必须双侧同窗。

### 语义与红线

- :386/:624 为纯读取源替换（同一键、同一初始化点、reloadModule→init 刷新链完整）；
- notifyShowTax 是新键快照但读点唯一，缺省 false 与缺键一致；
- ShopUtil 快照经 Util.initialize 刷新（该钩子自启动起注册于 ReloadManager），
  惰性字段初值 false ≙ 缺键默认，先于任何交易路径完成初始化；
- 测试面：行为无外部变化，由既有 144 用例（含 TradeObservationTest/action 层用例）
  与本轮基准的 trade 主链零回归背书。

## 结论

R30 以系统性扫查法闭环了交易链上最后的逐调用配置访问（每笔交易 -2~3 次树行走，
量级低于基准分辨率，以零回归+结构论证入册）。十八轮累计主线收益不变：交易全链约
-84%，DB 写批量化，文本/日志缓存化，区块包 O(1)，菜单快照化，扫描类事件快路径化，
木牌渲染/排程、监听器与价格格式化的配置访问全快照化，数值格式化线程安全化。
