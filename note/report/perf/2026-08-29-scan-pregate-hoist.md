# QuickShop-Hikari 性能优化报告·第二十三轮（扫描层跨类型预闸与逐槽解析提升，2026-08-29）

> 基准程序与方法论同前（**49 用例 × 9 套件**）；同态 A/B，基线 commit `84e88d04a`
> （R34 完成点），候选 = R35 优化（`dc60e1bc5`），4 fork/侧紧邻交替。基线工件经
> git worktree 构建安装（json commit 字段已校正）。

## 背景：R34 之后，交易链的剩余大头是「每交易两次全库存扫描」

R34 后用新增剖析模式（`-Dbenchmark.profile`）重测 `tradeServiceBuy`：真实插件侧
热点集中在 `Util.countItems`（186 采样）/`Util.countSpace`（148）——交易预览对
源背包与目标背包各做一次全量扫描，**每个槽位**都要走：

1. `ContainerShop.matches`：`plugin.getItemMatcher()` 解析 + instanceof 分派 +
   （builtin 分支）原型引用；
2. `QuickShopItemMatcherImpl.matches` 全流程：`isSimilar`（NMS 组件比较，mock 面
   为一次桩调用）→ 事件闸 → shopId 查找 → `typeMatches`（`getType`×2）。

对背包里**不同材料**的槽位（典型商店背包过半槽位），上述全部工作只为得出 false。

## 改动（commit dc60e1bc5）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `QuickShopItemMatcherImpl.matches` | 异类槽位走完 isSimilar→事件闸→shopId→typeMatches | 顶部预闸：**无监听器注册且原型无 shopId 时，材料不同直接 false**；原型 Material 与 shopId 由单条目缓存供给（扫描复用同一原型实例时零额外调用） | 等价论证：跨类型匹配只有事件与 shopId 两条出路（事件被无监听器排除、shopId 被空值排除），其余全部路径（isSimilar、workType 1/2、meta 匹配器）都要求等类型——且原有类型闸本就在无出路时否决这些路径；有监听器或 shopId 非空时预闸整体跳过 |
| `ContainerShop.countStockItems/countStockSpaces`（新增） | — | 快速计数器：matcher 解析、原型引用、单位大小、最大堆叠**每扫描一次**（原每槽一次）；builtin 分支与 `matches()` 完全同源（同一 `this.item` 原型、同一 matcher）；第三方 matcher 分支＝原泛型循环（含防御克隆契约）；countSpace 的原型在有监听器时走 `getItem()`（事件可见源），与泛型路径同源 | 结果与泛型循环逐槽同值（单测等价对照）；`CountableInventoryWrapper` 的优先级不变（第三方可数包装器契约在前） |
| `Util.countItems/countSpace(inv, shop)` | 泛型循环（每槽 `shop.matches`） | `CountableInventoryWrapper` 检查后，对 `ContainerShop` 分发到快速计数器；非 ContainerShop 的 Shop 实现走原泛型循环 | 纯内部分发，API 不变 |

### 测试面

`ContainerShopMatchesTest` 增三用例：预闸路径完全不触 `isSimilar`（verify never）、
跨类型但 shopId 相同仍可达 shopId 比较并匹配成功（证明预闸不吞 shopId 语义）、
ContainerShop 快速计数与泛型循环逐值等价 + 第三方 matcher 回退逐槽可见。
合计 **179 用例全绿**（含既有 shopId 缓存/监听器旁路/免克隆等全部前置用例零改动）。

## 基准结果（4 fork/侧交替，**六用例八 fork 完全分离**，其余 43 全重叠零回归）

| 用例 | 基线中位 ns/op | 候选中位 ns/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| trade/countSpaceScan41（41 槽空间扫描直测） | 706,514 | 371,566 | **-47.4%** | [700,702,710,920]K \| [367,368,375,484]K——**完全分离** |
| trade/countItemsScan54（54 槽库存扫描直测） | 931,693 | 522,670 | **-43.9%** | [877,914,950,1108]K \| [490,493,553,661]K——**完全分离** |
| trade/tradeServiceSell（完整卖出服务链） | 2,320,167 | 1,452,135 | **-37.4%** | [2211,2318,2322,3435]K \| [1417,1417,1487,1561]K——**完全分离** |
| trade/tradeServiceBuy | 2,334,560 | 1,477,726 | **-36.7%** | [2216,2327,2342,2893]K \| [1409,1471,1485,2021]K——**完全分离** |
| trade/actionSell（玩家动作链） | 2,810,251 | 1,898,109 | **-32.5%** | [2706,2795,2826,3657]K \| [1879,1894,1902,1917]K——**完全分离** |
| trade/actionBuy | 2,714,969 | 1,923,687 | **-29.1%** | [2714,2715,2715,4226]K \| [1889,1915,1933,1956]K——**完全分离** |
| 其余 43 共享用例 | — | — | 区间全重叠 | 零回归带 |

如实入册三点：

- **listener/signRender +4.6%（全重叠）**：该用例的库存扫描走 mock `Shop.matches`
  桩（剖析证据：`Shop$MockitoMock.matches`），不经过真实匹配器与 ContainerShop
  分发，本round对其零改动；中位偏移为窗口噪声。
- **trade/inventoryTxCommit +6.2%（全重叠）**：候选四 fork [505-661]K 与基线
  [472-716]K 交错，R34 的 -36% 收益保持在两侧（本轮基线即含 R34）。
- **startup/shopLoadChain +21.8%（区间重叠）**：连续第二轮出现候选侧抬升
  （本轮基线侧反含 7.3M 离群）。该链为纯 DB 装载，不经过扫描/匹配/库存操作
  代码；跨会话漂移证据：R33 期中位 9.2M → 本会话两侧 5-6M。判窗口噪声。

生产语义收益同 mock 面方向：每个异类槽位省去一次 isSimilar（NMS 组件比较）与
两次 `getType`，每次扫描省去 41-54 次 matcher 解析与原型获取；对 NBT 复杂物品
（附魔/命名/潜影盒）的背包，isSimilar 省略的组件比较在实机上远大于 mock 面所示。

机器可读数据：`benchmark/results/round35-{baseline,}-fork{1,2,3,4}.json`
（对比脚本 `compare-round35.py`，驱动脚本 `run-round35.sh`）。

## 结论

R35 把全库存扫描从「每槽全流程匹配」降为「每槽一次枚举比较 + 每扫描一次解析」，
六个交易链用例八 fork 完全分离：扫描直测 **-44/-47%**、交易服务链 **-37%**、
玩家动作链 **-29~-32%**，其余 43 用例零回归。第二十三轮累计主线收益：交易全链
约 -90%（R2 基线 → 本轮，mock 计量面口径）。
