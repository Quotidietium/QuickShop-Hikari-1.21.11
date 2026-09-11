# R52：市场浏览链物品读取收敛

> 轮次：R52（第五十三轮性能优化）
> 日期：2026-09-12
> 基线：`e8924bea2`（R51 闭合并入册点）｜候选：本轮工作树
> 基准：`benchmark/` 独立模块全 60 用例双轴（B/op 分配轴 + ns/op 时序轴），3 fork/侧严格交替
> 产物：`benchmark/results/round52-baseline-fork{1,2,3}.json` / `round52-fork{1,2,3}.json`，
> 驱动脚本 `benchmark/results/run-round52.sh`，对比脚本 `benchmark/results/compare-round52.py`

## 背景与定位

R51 收尾时对剩余大头用例做调用面复核：`menu/groupAndNameSort`（4.87M B/op，R41 计量面）
与 `menu/browseStockPipeline`（2.15M）之外，**浏览管线的物品读取层**仍按「每读一防御
克隆」付费（生产为 NBT 深拷贝，10-50μs/次，R48 留档）：

| 读取点 | 原克隆数 | 说明 |
| --- | --- | --- |
| `groupShopsByItem` 每店 | 1/店 + 1/新组 | 探针与类型门用 getItem() 拷贝；MarketItemGroup ctor 再克隆一次 |
| `searchShops` 每店 | 1/店/搜索 | matchesSearch 纯读也付防御拷贝 |
| `matchesSearch` 命中路径 | 3×meta | hasItemMeta 判定后 getItemMeta()×2（Bukkit 契约每次返回新副本） |
| `GroupedItemPage` 每组图标 | 3/组 | 三次 getRepresentativeItem() 全为 type-only 读 |
| `searchGroups` 每组 | 1/组/搜索 | 同为只读探针 |

一次默认开页（N 店、G 组）：N+G 克隆；带搜索再 N；逐页渲染再 3×可见组。

## 改动

1. **`ContainerShop.getItemDirect()`**（`@ApiStatus.Internal`）：暴露活原型引用，只读消费
   契约——内建匹配器双参只读的同一信任模型（R35 起全库存扫描与 `matches()` 已在传活
   栈）；任何可能突变的消费者必须继续用 `getItem()`。第三方 `Shop` 实现不经此路径。
2. **`groupShopsByItem`**：内建匹配器下 ContainerShop 探针直读活原型；新组代表物仍经
   `MarketItemGroup` ctor 的所有权克隆（每店 1+每新组 1 → 每新组 1）。第三方匹配器/
   第三方 Shop 实现保持逐店防御拷贝（无只读契约不可冒险，用例钉住）。
3. **`searchShops`**：matchesSearch 纯读（type/hasItemMeta/getItemMeta 按 Bukkit 契约
   自带副本/getDisplayName 读 meta 副本），ContainerShop 直读活原型，零克隆。
4. **`matchesSearch`** 三重 meta → 单次 getItemMeta（行为逐值保持：hasDisplayName 为假
   时仍回退 false）。
5. **`GroupedItemPage`** 三次 getRepresentativeItem() → 一次 getRepresentativeItemUncloned()
   （同包只读契约）+ 局部 Material 复用；**`searchGroups`** 探针同改。

### 等价性论证

- **活探针（内建匹配器/纯读消费者）**：`QuickShopItemMatcherImpl.matches` 对双参只读是
  既有事实（R35 论证、全库存扫描与 ContainerShop.matches 传活栈先例）；matchesSearch
  的全部读取（getType/hasItemMeta/getItemMeta/getDisplayName）按 Bukkit 契约要么是
  纯读、要么返回自带副本。组代表物所有权边界不变（ctor 克隆照付）。
- **第三方安全边界**：非内建匹配器 → 逐店 getItem() 拷贝；非 ContainerShop 的 Shop
  实现 → getItem() 契约。用例 `thirdPartyMatcherKeepsThePerShopDefensiveCopy` 钉住。
- **页面 type-only 读**：getRepresentativeItemUncloned 为 menu.browse 包内既有只读
  契约（R41 引入，groupShopsByItem 探针已用）；三次读合并为一次读取+局部复用，值
  同源不变。

### 新增用例（4，合计 275 全绿）

`MarketLiveProbeTest`：getItemDirect 跨调用同实例+探测零克隆；分组恰每新组一次所有权
克隆、加入组零克隆、分组内容与首遇顺序保持；搜索活探针零克隆+自定义名/材质名语义
逐值；第三方匹配器保持逐店拷贝契约（2/1 计数含组所有权拷贝）。

## 基准结果

新用例 `menu/browseGroupPipeline`：120 个真实 ContainerShop（8 材质交替）+ 克隆记账
物品桩（每次 clone() 产全新自足 mock，逐调用镜像生产 NBT 深拷贝），驱动
`processGroups` 默认开页管线（filter ALL→无库存过滤→无搜索→分组→组名排序）；组统计
走已桩的 DB 缓存查询不触库存扫描。两侧同体（R24 先例）。

### B/op 轴（主验收轴，近确定性）

| 用例 | 基线 B/op（3 fork） | R52 B/op（3 fork） | Δ | 分离度 |
| --- | --- | --- | --- | --- |
| `menu/browseGroupPipeline` | 8,341,324 [8,324,713 / 8,341,324 / 8,390,961] | 3,635,688 [3,635,464 / 3,635,688 / 3,635,816] | **-56.4%** | 三 fork 零重叠，候选侧逐位贴紧 |

其余 59 共享用例 B/op 全部 ±1.2% 区间内零回归，多数逐位 ±0：`browseStockPipeline`
2,152,256 逐位同值、`groupAndNameSort` -0.1%、`actionBuy/actionSell/clickTradeEntry`
+0.0%、`signRender`/`displayResend` 逐位 0——其中 `groupAndNameSort`/`browseStock`
的 mock Shop 走的正是非 ContainerShop 回退路径，逐位不变直接佐证第三方/回退路径
未受影响。`text/fallbackYamlParse` 两侧同中位 4,402,128（候选 fork3 落高峰
4,644,048，双峰带既有形态，中位不受扰）；`db/metricInsertBatch100` -1.1%、
`db/locateShopDataId` -1.2% 为粒度带内。

**消减量模型自洽**：8,341,324 − 3,635,688 = 4,705,636 ≈ 120 克隆差 × 39.2KB
（R51 由 inventoryTxCommit/tradeService 两用例独立标定的单次记账克隆成本）=
4.70M——基线 120 次逐店防御克隆+8 次组所有权克隆 → 候选仅 8 次组所有权克隆，
克隆差恰 120，逐个拟合。

### ns/op 轴（本机参考）

`browseGroupPipeline` **-62.3%**（11.90ms→4.49ms）。未触碰路径 +8~30% 正向漂移
（`displayResend` +30.3%、`groupAndNameSort` +15.3%——后者 B/op -0.1% 逐位近同、
`signRender` +12.3%），为历轮留档的本机双向噪声带形态（B/op 逐位一致的未触碰路径
同样漂移，与代码无因果）。

### 生产收益换算

mock 面每次记账克隆 ≈39.2KB 的分配成本，生产面对应一次 NBT 深拷贝（10-50μs/次，
R48 留档）。默认开页（N 店）从 N+G 次深拷贝降为 G 次（G=物品族数，典型远小于 N）；
带搜索的开页再省 N 次；逐页渲染每组省 3 次 meta 级读取与克隆。以 500 店 40 物品族
的典型市场计：默认开页 540→40 次深拷贝，搜索开页 1040→40 次。

## 累计视图（R38–R52，分配轴，对 R37 后基线 fb785e159；括注为该用例首计轮）

| 用例 | R37 后基线 | R52 后 | 累计 |
|---|---|---|---|
| menu/browseGroupPipeline（R52 计量面） | —（R52 前 8,341,324） | 3,635,688 | **-56.4%** |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,855,415 | **-52.4%** |
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| trade/actionBuy | 1,508,192 | 1,055,032 | **-30.0%** |
| trade/actionSell | 1,505,232 | 1,079,584 | **-28.3%** |
| trade/tradeServiceBuy | 1,121,977 | 810,568 | **-27.7%** |
| trade/tradeServiceSell | 1,133,787 | 849,384 | **-25.1%** |
| trade/inventoryTxCommit | —（R51 前 408,392） | 330,003 | **-19.2%** |
| trade/clickTradeEntry（R51 计量面） | —（R51 前 2,557,128） | 1,446,451 | **-43.4%** |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 5,976 | **-82.4%** |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 6,456 | **-86.5%** |
| listener/displayResend（R50 计量面） | —（R50 前 100,360） | 34,344 | -65.8% |
| trade/shopClickDispatch（R50 计量面） | —（R50 前 21,104） | 10,832 | -48.7% |
| listener/quickCreateGate（R48 计量面） | —（R48 前 20,224） | 10,144 | -49.8% |
| listener/rateLimitGate（R49 计量面） | —（R49 前 40） | 24 | -40.0% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,520 | **-63.4%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,588 | **-60.1%** |
| db/insertShopChain | —（R42 前 42,208） | 20,184 | **-52.2%** |
| db/listShops（R47 计量面） | —（R47 前 1,010,088） | 249,034 | -75.3% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,688 | -42.7% |

## 复现

```bash
bash benchmark/results/run-round52.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
python benchmark/results/compare-round52.py
