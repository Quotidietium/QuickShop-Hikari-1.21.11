# QuickShop-Hikari 性能优化报告·第十二轮（开箱守卫扫描快路径，2026-08-26）

> 基准程序与方法论同前（本轮起 39 用例 × 8 套件——listener 套件新增 inventoryCheck，
> 两侧同体直调、行为随 jar 区分）；同态 A/B，基线 commit `1c2736c96` = R23 闭合并提交
> 文档后的 HEAD。本轮为紧凑轮：单一热点，改动 6 行。

## R24：inventoryCheck 虚拟展示免扫（commit 41e7c7e64）

### 问题

`DisplayProtectionListener` 在 **InventoryOpenEvent（全服每次打开任意容器）** 上调用
`Util.inventoryCheck`：遍历容器**每个槽位**调用 `AbstractDisplayItem.checkIsGuardItemStack`。
虚拟展示模式（`shop.display-type=2`，本分支默认）下守卫物（真实掉落物展示的防拾取标记栈）
**根本不会被创建**——`checkIsGuardItemStack` 内部第 4 行就是同一模式判定并返回 false。即：
默认配置下，每次开箱都在做 **54 次迭代 × 每槽一次 `display-type` 配置查询 + isDisplayEnabled
+ 枚举映射**的纯废扫描（人肉估算 5~20μs/次；玩家频繁开箱的自动化/仓库服务器持续付出）。

### 改动

`Util.inventoryCheck` 在进入槽位循环前判定一次
`AbstractDisplayItem.getNowUsing() == DisplayType.VIRTUALITEM` 即整段返回——与逐槽判定的
结果**逐项可证等价**（同一判定式，只是从每槽一次提升为每次开箱一次）。非虚拟模式
（CUSTOM/900）行为不变。连带收益：`InventoryPickupItemEvent`（漏斗逐物品吸取，高频）等
直接调用 `checkIsGuardItemStack` 的路径不受影响（API 未动）。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| listener/inventoryCheck（54 槽守卫扫描，虚拟模式） | 2,094,046 | 8,135 | **-99.6%** | 基线三 fork 2.06~2.12ms vs 候选 8.10~8.18μs，**六 fork 零重叠**；mock 放大两侧绝对值（真实配置查询 ~百 ns 而非 mock 的 ~35μs），但 54 次迭代→1 次判定的结构性比例在任何成本模型下成立（真实约 15~30μs/次开箱 → ~0.3μs） |
| 其余 38 个共享用例 | — | — | **全部 ±4% 内** | 零回归；历轮中最干净的共享用例 A/B |

机器可读数据：`benchmark/results/round24-baseline/` 与 `round24/`（每侧 3 fork，对比脚本
`benchmark/results/compare-round24.py`）。

### 语义与红线

- 等价性论证：虚拟模式下 `checkIsGuardItemStack` 恒 false（其内部即含该判定），跳过循环
  与逐槽调用同果（不移除任何物品）；非虚拟模式零改动。`UtilInventoryCheckTest` ×2
  （虚拟模式不触碰迭代器/非虚拟模式仍逐槽扫描）钉死两翼行为；全量 118 用例绿。

## 结论

R24 消除了默认配置下每次开箱的 54 槽恒假扫描（每次开箱 54 次迭代与配置查询 → 1 次模式判定；基准 **-99.6%**）。十二轮
累计：交易全链约 -84%，DB 写全批量化，文本/日志全缓存化，区块包/展示物路径 O(1) 化，
浏览菜单库存快照化，高频杂项事件快路径化。
