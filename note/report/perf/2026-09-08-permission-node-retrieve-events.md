# QuickShop-Hikari 性能优化报告·第三十二轮（权限节点与设置事件读取路径清扫，2026-09-08）

> 承 R45。基线 `ea77593a7`（R45 完成点），候选 `9995cc28e`，3 fork/侧严格交替，
> 全 53 用例双轴计量（基准文件两侧同源，无同步需求——本轮零基准改动，既有
> serialize/db/trade 套件已覆盖该路径）。

## 背景：权限节点每次解析都跑服务定位链，设置读取每次都构造事件

R46 选点经基准剖析模式（`-Dbenchmark.profile`）：serialize/createDataRecord 的
quickshop 顶帧中 `QuickShopAPI.getPluginInstance` 独占 469/1,421 采样，代码走查
定位两层同族浪费——

1. **权限节点服务链**：`BuiltInShopPermission(Group).getNamespacedNode()` 每次调
   用都执行 `QuickShopAPI.getPluginInstance()`（Bukkit.getServicesManager→
   getRegistration→getProvider→getPlugin 完整服务定位链）+ `toLowerCase` 分配 +
   拼接——而它位于每次商店权限检查路径（getPlayerGroup→getPermissionAudiences→
   节点解析），每次点击/控制面板/保存冲刷都到达；插件名每运行期恒定，结果本可
   一次构建；
2. **RETRIEVE 设置事件构造**：ContainerShop 七处设置读取（getPlayerGroup/
   shopState/shopType/getSignText/getTaxAccount/isDisableDisplay/店主名）每次
   调用都构造 RETRIEVE 事件对象（含 `Bukkit.isPrimaryThread()` 探测）——
   `ShopSettingEvent` 构造即 `updated=old`，`callEvent()` 已有 hasListeners 派发
   门（AbstractQSEvent javadoc 既定的「热路径跳过构造」模式），无监听器时
   `updated()` 必返原值，构造是纯预付。

## 改动（commit `9995cc28e`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| 两个权限枚举 | 每次 getNamespacedNode 跑服务链+toLowerCase+拼接 | 惰性 `namespacedNode` 记忆字段 | 插件名每运行期恒定（Bukkit 装载后不可变）；良性竞争只会重建相等串；未装载时抛 ISE 的行为不变（缓存为空才走链） |
| ContainerShop 七处设置读取 | 无条件构造 RETRIEVE 事件 | `hasListeners()` 门控：无监听器直接返回原值，有监听器走原构造+派发+可变更分支逐位不变 | ShopSettingEvent(phase, shop, old) 构造即 `updated=old`，无监听器时无人可改，`updated()` 必返构造传入值；门内代码即原代码 |

## 测试面

新增 **4 用例**（合计 **221 全绿**）：`ShopSettingsRetrieveGateTest`——无监听器时
七 getter 回退字段原值（枚举/布尔/空税账号/组节点逐位）、监听器在时事件仍派发
且 `updated()` 覆写生效并随注销恢复原值、税账号监听覆写路径、namespaced node
跨调用同实例（assertSame）且两常量至多两次服务链解析。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| serialize/createDataRecord | 29,328 | 12,432 | **-57.6%** | [29,288, 29,328, 29,328] \| [12,368, 12,432, 12,432]——完全分离；ns 轴 -62.1% 同向 |
| db/updateShop-unchangedData | 31,396 | 14,539 | **-53.7%** | [31,395, 31,396, 31,396] \| [14,475, 14,539, 14,541]——完全分离；ns 轴 -50.6% |
| db/insertShopChain | 36,972 | 20,088 | **-45.7%** | [36,944, 36,972, 36,988] \| [20,088, 20,088, 20,104]——完全分离；ns 轴 -25.2% |
| trade/actionBuy | 1,272,864 | 1,270,808 | -0.2% | [1,272,624-1,273,872] \| [1,270,761-1,270,808]——分离；交易路径的权限组解析/shopType 读取受益 |
| trade/actionSell | 1,297,410 | 1,295,353 | -0.2% | [1,297,176-1,298,424] \| [1,295,298-1,295,360]——分离 |
| 其余 48 共享用例 | — | — | 噪声带内 | 详见下 |

模型自洽：updateShop 与 createDataRecord 各省 ~16.9KB/16.8KB（每次保存冲刷消除
3 个事件构造 + 1-2 次节点服务链），insertShopChain 同路径省 16.9KB（36,972→
20,088），三用例消减量一致；action 每交易多次设置读取/权限解析，B/op 摊薄至
~2KB（0.2%）但方向确定、fork 分离。

**mock 放大如实披露**：基准上每个被消除的事件构造＝1 次 `Bukkit.isPrimaryThread`
桩调用（Mockito invocation 元数据数百 B），每条被消除的节点链＝4-6 次桩调用，
故 -46~-58% 是调用结构消除的 mock 计量面；生产上每次保存冲刷实省 3 个小事件
对象+isPrimaryThread 探测（~40-80B+~20ns each）与 1-2 条服务定位链
（~0.1-0.3μs+节点串分配 ~48-64B）——方向与结构消除由 fork 完全分离钉死，绝对
量以生产模型为准（R44 同族披露先例）。

如实入册六点：

- **db/listShops +1.6%**：基线 [1,009,785-1,010,088] 与候选 [1,009,920-1,026,448]
  在 1,009,920 处重叠；候选 fork2/3 落 1,026k——R39/R42/R44 历轮留档的 H2 跨
  会话漂移带同一区间。
- **startup/shopLoadChain +1.5%**：区间 [1,132,578-1,132,867] vs [1,132,696-
  1,157,755] 重叠于 1,132,696-1,132,867；装载套件已知摆动带（R44 留档
  1,149,608 邻域）。
- **db/metricInsertSingle +1.7%（63B）**：区间在 3,675 处共有，粒度级偏移。
- **menu/groupAndNameSort +0.3%**：fork1 两侧逐位同值 4,842,840，fork2/3 候选
  落 4,858,264 粒度档（R44/R45 同值带）。
- **economy/chatGate/chunkLoad/hopperMove 系 +0.3~1.1%**：64-128B 粒度级偏移且
  区间相接，共享 JVM 预热扰动带（R43 先例）。
- **listener/signRender +0.1%**：fork1 两侧逐位同值 632,704——签名渲染基准不经
  过被门控的 getSignText 公开入口（走 layout provider 内部），不受益亦不回归；
  时序轴 actionBuy/Sell -16% 高于 B/op 所示量级，含本机时序摆动成分，以 B/op
  轴为准。

## 生产语义收益（mock 面之外）

- **每次商店权限检查**（点击交易、控制面板、保护判定、转移/管理操作）省 1-2
  条服务定位链与节点串重建，收敛为裸字段读；
- **每次脏店保存冲刷**（周期性批量落库）省 3 个事件构造与探测；
- **每次签名渲染**（getSignText 公开入口）与店主名解析省事件构造——第三方
  监听器存在时行为逐位不变（门内即原代码）。

## 累计（R38–R46，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R46 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,858,264 | -52.4% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,648 | -44.0% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,432 | **-63.6%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,539 | **-60.2%** |
| db/insertShopChain | —（R42 前 42,208） | 20,088 | **-52.4%** |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 8,104 | -76.2% |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 8,608 | -82.0% |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,168 | -9.6% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,272 | -8.7% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 931,744 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 970,522 | -14.4% |
| trade/actionBuy | 1,508,192 | 1,270,808 | **-15.7%** |
| trade/actionSell | 1,505,232 | 1,295,353 | **-13.9%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,624 | **-68.3%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
# A/B 基线 worktree 换基线前须 git restore+clean（残留未跟踪 JSON 阻塞 checkout）
bash benchmark/results/run-round46.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物落基线 worktree 的 benchmark/results，需拷回主仓（round44 先例）
python benchmark/results/compare-round46.py
```
