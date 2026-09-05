# QuickShop-Hikari 性能优化报告·第二十五轮（商店哈希契约修复与世界过滤快照，2026-09-05）

> 承 R38 分配轴方法论（B/op 近确定性验收轴 + JFR 全栈归因）。基线 `51a7d0e9b`
> （R38 完成点），候选 `c9389220c`，3 fork/侧严格交替，全 49 用例双轴计量。
> 本轮含一项**生产级缺陷修复**（事件派发发生在 hashCode 内），性能收益为其副产物。

## 背景：runtimeUuid 查找 28,400 B/op 的 JFR 全栈实证

R38 收官时留档的线索（`lookup/getShopByRuntimeUuid` 30,312 B/op、cached/uncached
完全同价）经定向 JFR 归因（栈内必须同时含 `getShopFromRuntimeRandomUniqueId`）
拿到完整调用链：

```
loadedShops.contains(shop)                       ← 每次 runtimeUuid 解析
  → ConcurrentHashMap.get → ContainerShop.hashCode()   ← Lombok @EqualsAndHashCode 生成
    → isDisableDisplay()                          ← getter 哈希（Lombok 默认经 getter 读字段）
      → ShopDisplayEvent.RETRIEVE(...) + callEvent()
        → AbstractQSEvent.<init> → Bukkit.isPrimaryThread()
```

Lombok `@EqualsAndHashCode` 默认**经 getter 方法**读取字段，而 `ContainerShop`
的 getter 是事件包装（`isDisableDisplay`/`shopState`/`shopType`/`getTaxAccount`
构造并 `callEvent` RETRIEVE 事件；`getItem` 返回防御克隆）。因此旧 `hashCode()`
单次调用＝**数次 Bukkit 事件对象构造与派发 + Bukkit.isPrimaryThread + 两次
ItemStack 克隆（生产上为 NBT 深拷贝）+ 全字段哈希**，由所有 Set/Map 成员操作支付：
`loadedShops.contains`（runtimeUuid 解析）、`SignUpdateWatcher.pendingShops`
（**每笔交易**的排程去重 add 与 drain remove）、命令层 HashMap 等。

**隐性缺陷**（正确性）：可变字段（price/item/unlimited/…）参与哈希——字段在
"已入 pendingShops、未 drain"窗口内变更时，remove 落入错误哈希桶而 miss，
产生永久幽灵条目（内存泄漏）并使后续去重判定失真。`SignUpdateWatcher` 的注释
（"shops carry identity equality"）自 R31 起就假设了实际上不成立的身份语义。

另一发现：`Util.isBlacklistWorld` **每次调用物化两个配置列表**
（`getConfig().getStringList` ×1-2），位于 `isValid → canBeShop` 热路径——
商店点击、交易校验、runtimeUuid 解析每次都付。

## 改动（commit `c9389220c`）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `ContainerShop.hashCode()`（手写，Lombok 让位已 javap 验证） | getter 链读取全部字段：构造并派发多个 RETRIEVE 事件、调 Bukkit.isPrimaryThread、克隆两次 item、可变字段入哈希 | `return this.location.hashCode();` | equals() **未动**（Lombok 字段比较保留，含同实例 `==` 短路）；契约自洽：equals-true 必共享 final `location` 字段 ⇒ 同哈希；常数时间、终身稳定（修复哈希桶漂移类隐患）。**注**：全仓唯一同型风险类排查——其余 @EqualsAndHashCode 类均为值对象（普通 getter），无事件包装 |
| `Util.isBlacklistWorld` | 每次调用 `getStringList("shop.whitelist-world")` + `getStringList("shop.blacklist-world")` | 静态 `Set<String>` 快照（`Util.initialize()` 装载与 reload 刷新——与其他快照同钩子）；null 快照回退原配置读取路径 | 白名单非空优先、否则黑名单判定逐分支相同；reload 新鲜度同源（initialize 即注册的 reload 钩子）；快照构建跳过 null 元素（原实现在 null 元素上 per-call NPE，属不可达病态） |

### 测试面

新增 **6 用例**（合计 **185 全绿**）：

- `ContainerShopStableHashTest`：①哈希跨字段变更稳定 + HashSet 成员关系在变更后
  存活（幽灵条目回归测试）；②注册计数监听器后 `hashCode()` 零事件派发、
  pluginManager 零 callEvent；③equals 对称性与 equals⇒同哈希契约、
  异位置不等。
- `UtilWorldBlacklistSnapshotTest`：①快照后 50 次查询零配置读取；②白名单优先级；
  ③reload（再 initialize）拾取配置变更；tearDown 重置静态快照防跨测试类污染。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| lookup/getShopByRuntimeUuid-cached | 28,400 | 9,624 | **-66.1%** | [28,400, 28,400, 28,848] \| [9,624, 9,624, 9,744]——完全分离 |
| lookup/getShopByRuntimeUuid-uncached | 28,400 | 9,624 | **-66.1%** | 同上——完全分离 |
| trade/tradeServiceBuy | 995,504 | 937,864 | **-5.8%** | 两侧 fork 内差 <0.3%，完全分离 |
| trade/tradeServiceSell | 1,034,480 | 976,410 | **-5.6%** | 完全分离 |
| trade/actionBuy | 1,372,952 | 1,304,600 | **-5.0%** | 完全分离 |
| trade/actionSell | 1,397,051 | 1,329,147 | **-4.9%** | 完全分离 |
| 其余 43 共享用例 | — | — | ±1.1% 内 | fork 区间全部重叠（零回归带） |

时序轴（参考）：runtimeUuid **-57%/-60%**（37.1→14.8 µs、38.8→16.7 µs）、
tradeServiceBuy -27.6%；其余用例 ±13% 内抖动（B/op 零变的用例其 ns 偏移为
本机噪声，如 lookup-hit/miss +11-13% 而 B/op +0.0%）。

如实入册三点：

- **db/metricInsertBatch100 -3.5%（判环境方差，不计入战果）**：`insertMetricRecords`
  实现只以 Long 为键（与商店哈希无因果）；同码对照 R38 会话 66,014 vs 本会话基线
  68,679，且基线侧三 fork 宽散布 [66.5-68.8]K——该 H2/JDBC 用例跨会话漂移，
  非代码效应。
- **startup/shopLoadChain +1.5% / db/listShops +1.7%（区间重叠）**：均为 H2
  路径用例，候选侧 fork3 离群所致；与 R38 会话的同码数值对照亦漂移，判噪声。
- **text/fallbackYamlParse +4.3%（双峰采样）**：与 R38 相同的双模式用例
  （4.40M/4.59M/4.64M），两侧模式混合翻转；本轮对该路径零改动。

## 生产语义收益（mock 面之外）

- 每次 Set/Map 商店成员操作省去：多个 RETRIEVE 事件对象构造与**派发**（生产上
  有 InternalListener 等注册，每次派发走完整共享 HandlerList）、两次 ItemStack
  NBT 深克隆、全字段哈希——runtimeUuid 解析（展示物点击/静默命令）、
  **每笔交易的签名排程 add+remove**、装载/卸载 add/remove、命令层 Map 操作；
- 哈希稳定性消除 pendingShops 幽灵条目与去重失效隐患（正确性修复）；
- 每次 `isValid → canBeShop` 省两次配置列表物化与线性扫描（Set O(1)）。

## 累计（R38+R39，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R39 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 937,864 | -16.4% |
| trade/tradeServiceSell | 1,133,787 | 976,410 | -13.9% |
| trade/actionBuy | 1,508,192 | 1,304,600 | -13.5% |
| trade/actionSell | 1,505,232 | 1,329,147 | -11.7% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,624 | **-68.2%** |
