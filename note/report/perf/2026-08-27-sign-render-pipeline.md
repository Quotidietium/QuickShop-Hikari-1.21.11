# QuickShop-Hikari 性能优化报告·第十三轮（签名渲染管线，2026-08-27）

> 基准程序与方法论同前（本轮起 40 用例 × 8 套件——listener 套件新增 signRender，
> 两侧同体直调、行为随 jar 区分）；同态交替 A/B，基线 commit `8fb189e11` = R24 闭合并
> 提交文档后的 HEAD，各 3 fork 交替取中位数。

## R25：签名渲染单次库存扫描 + 配置读取缓存（commit c344a5f33..13653087b）

### 问题

每次木牌刷新（每笔交易后、每次漏斗向商店箱移动物品后——`shop.update-sign-when-inventory-moving`
默认开启，经 `SignUpdateWatcher` 每 10 tick 排空、`runAtLocation` 落主线程/区域线程）执行
`SimpleShopLayoutProvider.render()` 渲染 4 行牌面。默认模板 header + trading 两行触发**同一
库存量的两次全量扫描**：

- `renderHeader` → `shop.inventoryAvailable()` → selling 走 `getRemainingStock()`、buying 走
  `getRemainingSpace()`（**扫描 #1**：54 槽 × `matches()` + `ShopInventoryCalculateEvent` 派发）；
- `renderTrading` → `shop.shopType().remainingStock(shop)` → 内置类型下是**同一个方法**（**扫描 #2**）。

叠加配置读取：`layoutTemplate()` 每次渲染 4 次 `shop.layout.<TYPE>.lineN` 路径查询（YamlConfiguration
路径分割+树行走）；`renderItem()` 每次渲染读一次 `shop.force-use-item-original-name`；
`ContainerShop.setSignText(List)` **每块牌子**读两次 config（sign-glowing/sign-wax）+ dyeColor
（一处刷新通常 1~2 块牌，漏斗持续供料的商店每 500ms 一轮）。

### 改动

1. **单次扫描共享**（`SimpleShopLayoutProvider.render()`）：当 `shop.shopType()` 为内置
   `SellingType`/`BuyingType` 时，header 的可用性与 trading 行的余量**可证同源**（selling 两边都
   调 `getRemainingStock()`，buying 都调 `getRemainingSpace()`，`isSelling()` == `!isBuying()`
   使 `inventoryAvailable()` 的分支序完全由该值决定）——惰性计算一次 `shopType().remainingStock(shop)`
   供两行共用；无限店走 `-1` 快路径。**第三方 IShopType 保持独立双查**：其 `remainingStock()`
   与 `Shop#inventoryAvailable()` 所查可能不同源，精确性优先于节省（`instanceof` 判定边界，
   子类未覆写时同样受益、覆写则自动落回保守路径）。
2. **layout 模板按店型缓存**：`ConcurrentHashMap<identifier, String[]>`，首次读取后复用；
   provider 注册 `Reloadable`，reload 时清空（与 `QuickShopItemMatcherImpl` 同一失效纪律）。
3. **renderItem 标志快照**：`force-use-item-original-name` 构造/reload 时读一次。
4. **setSignText 循环外提**：dyeColor / sign-glowing / sign-wax 提出每牌子循环（同一次刷新
   内 config 不可能变化，结果恒等）。
5. 顺带修复基准侧两处 `when()` 桩未闭合时构造 provider 的 Mockito 嵌套调用陷阱
   （`UnfinishedStubbingException`，新构造器注册 reload manager 所致——先构造后挂桩）。

### 结果（同态交替 A/B，各 3 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| listener/signRender（54 槽全 4 行渲染） | 1,859,914 | 975,496 | **-47.6%** | 基线三 fork 1.854~4.042ms vs 候选 0.960~1.008ms——基线**最优** fork 仍高于候选**最差** fork 84%，六 fork 零重叠；mock 放大绝对值（迭代器/槽位 mock），但「两次扫描+5 次 config 查询 → 一次扫描+缓存」的结构性比例在任何成本模型下成立（真实扫描为 NMS `isSimilar`/meta 比对，成本高于 mock 桩） |
| 其余 38 个共享用例 | — | — | 中位数噪声带内 | db 套件 +1~15%（会话级方差，两侧第 3 fork 均见对称尖刺）、text/forLocaleWithArgs -35%（基线侧 fork2/3 漂移，该路径 R25 未触碰、代码与基线相同）；无候选侧可归因回归 |

机器可读数据：`benchmark/results/round25-baseline-fork{1,2,3}.json` 与 `round25-fork{1,2,3}.json`
（对比脚本 `benchmark/results/compare-round25.py`）。

**方法学留档（本轮踩坑）**：直接 `java -cp` 交换 bukkit 类路径时，前缀条目经 shell `printf`
写入遭遇 `\r`/`\t` 转义损坏（`F:\repo` → `F:`+CR+`epo`），损坏条目被 JVM 静默跳过、m2 中已安装的
候选 jar 兜底——首轮 A/B 实为**候选对候选**（signRender -0.3% 即同码噪声）。以 python 重写类路径
文件并用 Probe（`getProtectionDomain().getCodeSource()` + 候选独有三参重载计数 0 vs 2）验证两侧
加载来源后才重测。教训：类路径交换必须验证实际加载来源，不能信任 exit code。

### 语义与红线

- **等价性论证**：内置类型下 header 可用性公式 `unlimited || remaining > 0` 与
  `inventoryAvailable()` 逐分支等价（selling/buying 分支取值同源、内置类型不达 frozen 兜底分支）；
  第三方类型零改动（`SimpleShopLayoutProviderTest.thirdPartyTypeKeepsIndependentLookups`：header
  走 `inventoryAvailable()`、trading 走类型 `remainingStock()`，两者取值不同时各自保持）。
  模板缓存失效点 = reload（`/qs reload` 走 ReloadManager 调 `reloadModule()`，与匹配器同一纪律）；
  测试 `layoutTemplateIsCachedPerShopTypeUntilReload` 钉死。
- **可观测事件变化**：每次渲染的 `ShopInventoryCalculateEvent` 从 2 次降为 1 次（负载相同、
  计算真实发生时才派发）——与 R5（零监听器免派发）、R22（重复 destroy 包消除）同性质的冗余
  事件消除；监听器不能依赖「同一渲染内两次相同事件」。
- **API 兼容**：`IShopLayoutProvider` 接口零改动；`SimpleShopLayoutProvider` 新增 3 参
  `renderHeader/renderTrading` 公有重载（预计算值版本），旧 2 参签名语义不变（直调仍自行求值）；
  `layoutTemplate()` 仍返回新集合。扩展点为 `ShopManager.shopLayoutProvider(provider)` 整体替换。
- **测试**：`SimpleShopLayoutProviderTest` ×8（默认模板单次扫描计数 selling/buying、render 与逐行
  直调组合等价、第三方类型独立双查、无限店快路径、无 header/trading 模板零扫描（惰性）、
  模板缓存 reload 失效、renderItem 标志快照随 reload）；全量 **126 用例绿**（118→126）。

## 结论

R25 把木牌刷新的核心渲染从「同量双扫 + 逐渲染/逐牌子 config 查询」压到「单扫 + 按店型缓存」
（基准 **-47.6%**，六 fork 零重叠）。交易后与漏斗供料场景的木牌刷新是商店服务器的常态后台开销，
本轮使其扫描成本减半、配置开销归零。十三轮累计：交易全链约 -84%，DB 写全批量化，文本/日志全
缓存化，区块包/展示物路径 O(1) 化，浏览菜单库存快照化，高频杂项事件快路径化，木牌渲染单扫化。
