# QuickShop-Hikari 性能优化报告·第二十八轮（保存路径物品编码 memo，2026-09-06）

> 承 R41。基线 `cc7100e1c`（R41 完成点），候选 `192bb21b4`，3 fork/侧严格交替，
> 全 51 用例双轴计量（基准文件两侧同源，无同步需求）。

## 背景：每次保存都重跑一遍 NBT 全量编码

`ContainerShop.createDataRecord()`（每次脏店冲刷、每次插入）与
`saveToInfoStorage()`（每笔购买的日志冲刷快照、移除日志）每次调用都执行
`platform.encodeStack(...)`——生产上为 ItemStack NBT 全量拷贝 + Base64 编码
（~10-50 μs/次）——尽管物品栈只在两类事件间变化：`setItem`（换引用）与
allow-stack 配置翻转（数量）。频繁的脏因（价格、店主、权限、extra）都不触碰
物品。此外上游存在一个别名缺陷：`setItem` 将**调用方传入的裸引用**存入
`this.originalItem`/`this.item`（构造器却克隆——调用方是玩家手持活栈，后续外部
原位突变会渗入商店状态）。

## 改动（commit `192bb21b4`）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `ContainerShop` 编码 memo | 每次保存/日志快照全量 `encodeStack` | 双槽缓存（item/originalItem 各一组 (引用,数量)→编码，transient + @EqualsAndHashCode.Exclude 防 equals 污染） | 同 (引用,数量) 键必同码：物品变化仅经 setItem 换引用与 reloadModule 数量翻转（全仓突变点清单核对：构造器 224/229/238、setItem 493/494、reloadModule 2106-2108、其余皆只读）；`getItem()` 编码参数不变（克隆与源同 NBT 同数量）；并发为良性竞（双方算出同值） |
| `setItem` 赋值 | `this.item = event.updated(); this.originalItem = item;`（裸引用，与构造器的快照纪律不一致） | 双双 `.clone()` 防御快照 | 事件序列与事件内容不变；快照化使 (引用,数量) 键在突变纪律下可靠，同时封堵外部活栈原位突变渗入商店状态的隐患（构造器本就克隆，属一致化） |

### 测试面

新增 **2 用例**（合计 **192 全绿**）：`ShopItemEncodeCacheTest`——
①同栈态重复保存恰一次 `encodeStack`（Mockito 调用数契约）+ 编码值稳定 +
`saveToInfoStorage` 同 memo；②`setItem` 换栈后重编码并再次命中。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| serialize/createDataRecord | 34,200 | 29,328 | **-14.2%** | [34,200, 34,200, 34,512] \| [29,288, 29,328, 29,552]——完全分离 |
| db/insertShopChain | 42,208 | 37,176 | **-11.9%** | [42,060, 42,208, 42,445] \| [36,944, 37,176, 37,360]——完全分离 |
| db/updateShop-unchangedData | 36,540 | 31,397 | **-14.1%** | [36,507, 36,540, 36,747] \| [31,397, 31,397, 31,659]——完全分离 |
| 其余 48 共享用例 | — | — | 噪声带内 | fork 区间全部重叠（零回归带） |

时序轴（参考）：createDataRecord **-16.7%**（59.7→49.8 μs）；其余 ns 抖动如实
留档（generateLookupParams +13.0% 而 B/op +0.0% 逐位不变，典型本机噪声）。

如实入册三点：

- **db/metricInsertBatch100 +1.1%**：H2/JDBC 用例跨会话漂移（fork 宽散布
  [65.6-66.3]K vs [66.3-69.7]K，cand fork3 离群 69.7K），与本轮零改动路径无因果，
  同 R39 的 metricInsertBatch100 -3.5% 甄别先例（环境方差，不计入）。
- **text/fallbackYamlParse -5.2%**：双峰模式混合翻转（本轮方向有利），零改动路径。
- **db/locateShopDataId +2.2%**（59→61 B，±2 B 粒度抖动）与 lookup 系 -0.8%：
  区间重叠的粒度级噪声。

## 生产语义收益（mock 面之外）

- **每次脏店冲刷**（价格/权限/extra 变更后的保存）省一次 NBT 全量编码
  （~10-50 μs，异步保存线程的真实 CPU）；
- **每笔购买的日志冲刷**（`ShopPurchaseLog` 懒快照在日志线程调
  `saveToInfoStorage`）省一次 originalItem 编码；
- 插入链（新店建档的 record 构建×查重+插入）编码次数减半；
- setItem 快照化封堵外部活栈突变渗入商店状态与 DB 的隐性数据完整性隐患。

## 累计（R38–R42，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R42 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 新增计量面） | 10,203,792 | 4,843,664 | -52.5% |
| serialize/createDataRecord | —（R42 前 34,200） | 29,328 | -14.2% |
| db/insertShopChain | —（R42 前 42,208） | 37,176 | -11.9% |
| db/updateShop-unchangedData | —（R42 前 36,540） | 31,397 | -14.1% |
| trade/countItemsScan54 | 391,576 | 277,528 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 932,342 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 971,928 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,298,848 | -13.9% |
| trade/actionSell | 1,505,232 | 1,323,400 | -12.1% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,624 | **-68.3%** |
| trade/locateSymbolLink（R40 新增计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round42.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
python benchmark/results/compare-round42.py
```
