# QuickShop-Hikari 性能优化报告·第二十七轮（浏览菜单物品访问成本，2026-09-06）

> 承 R40。基线 `b056e6c56`（R40 完成点），候选 `a59e5293a`，3 fork/侧严格交替，
> 全 **51** 用例双轴计量（新增 `menu/groupAndNameSort` 用例，基线 worktree 仅同步
> 基准模块 MenuBench 单文件以保持两侧同名单）。

## 背景：菜单管线的每次「读类型」都是一次 NBT 深拷贝

R40 收官后对 menu 套件的逐用例剖析（`-Dbenchmark.profile=menu/browseStockPipeline`）
显示 mock 帧大头是 `isUnlimited`/`getShopId`/`isSelling`（生产为纯字段读，非成本），
但代码走查揭示同一管线上真正的生产浪费——**`shop.getItem()` 是防御克隆
（生产为 CraftItemStack NBT 深拷贝）**，而浏览菜单代码大量「克隆只为读类型」：

1. **`MarketUtils.groupShopsByItem`（分组主页对全部已加载商店执行）**：每店
   `getItem()`×2+N——类型门一次（`getItem().getType()`）、每个同材质组探测各一次
   （`matcher.matches(group.getRepresentativeItem(), shop.getItem())`，且
   `getRepresentativeItem()` 自身再返回一次防御克隆）、建组一次（构造器内又克隆）。
   200 店 8 材质场景 ≈ **1600+ 次 NBT 深拷贝每页渲染**。
2. **NAME 排序比较器**（`sortShops` 两个变体）：`Comparator.comparing(shop ->
   prettifyText(shop.getItem().getType().name()))`——**每次比较**重派生键（一次防御
   克隆 + 一次 getType + 一次 prettifyText 字符串构建），TimSort ≈ 2·n·log n 次。
   与 R38 修掉的 STOCK 排序完全同病，当时遗漏。
3. **页面构建**：GroupedItemPage 过滤、MainPage/ShopListPage 图标与名称共五处
   `getItem().getType()` 纯类型读取，每次一枚克隆；ShopListPage 每店两枚。
4. **`MarketItemGroup.getItemDisplayName`**：最多三次 `getItemMeta()`（生产各为
   元数据克隆）读一个显示名。

`ContainerShop` 本有 `public Material getMaterial()`（直读字段零克隆零事件），但未
进入 Shop API——菜单代码持有的是 `Shop` 接口，只能走 `getItem()`。

## 改动（commit `a59e5293a`）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `ShopMeta` 接口 | 无类型读取方法 | `default Material getMaterial() { return getItem().getType(); }`（与既有 `getItemUnitSize` 同型的「免克隆读取」契约文档） | 默认实现＝原调用方语义逐位一致；外部实现者不覆写即维持旧行为；`ContainerShop` 既有同名 public 方法自然成为覆写（字段直读） |
| `groupShopsByItem` | 每店 2+N 次 `getItem()` 克隆 | 类型门走 `getMaterial()`（零克隆）；**每店恰 1 次** `getItem()` 复用于组探测与建组；组探测走新增包私有 `getRepresentativeItemUncloned()` | 分组结果/首遇序不变（等价克隆换单实例复用，本店实例不跨店共享）；matcher 参数只读契约已由 R35 扫描传活栈确立 |
| `MarketItemGroup` | `getRepresentativeItem()` 每调用克隆（分组循环每店 N 次支付） | 公有方法不变；新增包私有免克隆读给分组循环 | 公有 API 防御语义保留 |
| NAME 排序（两变体） | 比较器每次比较重派生键 | 装饰排序法（`sortShopsByName`，键每店恰读一次，走 `getMaterial()`）+ `prettifiedName` 按 Material 域 memoize（ConcurrentHashMap，键域=枚举有限） | 键值与历史比较器逐字节相同（memoize 命中同一纯函数结果）；仅区分键值 + `List.sort` 稳定 ⇒ 排序结果与等键遭遇序严格一致（同 R38 STOCK 论证） |
| 页面构建五处 | `getItem().getType()` 各一枚克隆 | `getMaterial()` 零克隆（ShopListPage 每店再 hoist 单个 Material 局部量） | 类型值相同 |
| `getItemDisplayName` | `hasItemMeta() && getItemMeta().hasDisplayName()` + `getItemMeta().getDisplayName()`（最多 3 次元数据克隆；hasItemMeta 真而 meta null 时原实现 NPE） | 至多一次 `getItemMeta()` 并补 null 安全 | 正常栈显示名逐位一致；病态栈由 NPE 变为回退物名 |

### 测试面

新增 **2 用例**（合计 **190 全绿**）：`MarketBrowseItemAccessTest`——
①分组内容/首遇序等价 + **每店 `getItem()` 恰 1 次**的调用数契约
（Mockito 计数验证，基线行为下必失败）；②NAME 排序与历史比较器的参考实现
逐位等价 + 等键遭遇序稳定。

### 计量面

新增基准用例 **`menu/groupAndNameSort`**（menu 套件，51 用例）：120 商店 × 8
材质，真实 `QuickShopItemMatcherImpl` 分组 + NAME 排序全管线。**同一基准源文件在
基线侧也可编译运行**：Shop mock 的 default answer 将候选侧新增的 `getMaterial()`
桥接为 `getItem().getType()`（基线侧该方法不存在、桥接不触发），两侧各自测其
真实生产管线。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| menu/groupAndNameSort | 10,203,792 | 4,842,868 | **-52.5%** | [10,203,792, 10,203,792, 10,234,320] \| [4,842,845, 4,842,868, 4,846,680]——完全分离 |
| 其余 50 共享用例 | — | — | 0.0%/噪声带内 | 逐位 +0.0%（trade 全系、browseStockPipeline、log、serialize、economy 等 fork 值逐位相同）或区间重叠 |

时序轴（参考）：groupAndNameSort **-56.0%**（15.66→6.89 ms/op）；其余用例本机
噪声带内（db/updateShop +8.5%、serialize/createDataRecord -4.9% 等均 ns 轴抖动，
B/op 轴各自 0.0%/-0.4%，如实留档不采信）。

如实入册两点：

- **lookup 系 +0.8~1.2%**：fork 区间全部重叠（hit：[1,936,1,936,1,960] vs
  [1,936,1,960,1,992]），本轮对查找路径零改动，属 R39/R40 已记录的同款 ±1-2%
  抖动带。
- **text/fallbackYamlParse -4.2%**：双峰用例模式混合翻转（本轮方向相反），
  零代码改动路径，与历轮观察一致。

## 生产语义收益（mock 面之外）

- **分组主页渲染**：每店防御克隆 2+N → 1（200 店 8 材质从 ~1600 降至 200 次
  NBT 深拷贝每页）；组探测的 `getRepresentativeItem()` 克隆全部消失；
- **NAME 排序**：键派生从 ~2·n·log n 次克隆+字符串构建降为每店一次枚举查表
  （prettify 结果 memoize）；
- **列表/分组页图标构建**：每店 1-2 枚「读类型」克隆归零（`getMaterial` 字段直读）；
- `getItemDisplayName` 元数据克隆 ≤3 → ≤1。

## 累计（R38–R41，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R41 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 新增计量面） | 10,203,792 | 4,842,868 | -52.5% |
| trade/countItemsScan54 | 391,576 | 277,528 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 932,152 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 971,496 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,298,848 | -13.9% |
| trade/actionSell | 1,505,232 | 1,323,400 | -12.1% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| trade/locateSymbolLink（R40 新增计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round41.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
python benchmark/results/compare-round41.py
```
