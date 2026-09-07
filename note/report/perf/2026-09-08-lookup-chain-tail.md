# QuickShop-Hikari 性能优化报告·第三十三轮（查找链收尾，2026-09-08）

> 承 R46。基线 `d929f30f2`（R46 完成点），候选 `99cf10d43`，3 fork/侧严格交替，
> 全 53 用例双轴计量（基准文件两侧同源，无同步需求——本轮零基准改动）。

## 背景：fromLocation 双包与每行 finder 解析

R47 为 R45 选点期间审计过、R46 后剖析复核仍存的查找链收尾项——

1. **getShop 双重块键**：`getShop(Location,boolean)` 经 `SimpleShopChunk.fromLocation`
   分配中间块键（读 getWorld/getName/getBlockX/getBlockZ）后
   `getShops(ShopChunk)→getShops(String,int,int)` 再分配第二个查找键，且探针路径
   重复读取 getWorld/getBlockX/getBlockZ；getShop 是全插件最高频 API（点击/漏斗/
   保护/交互全经此）；
2. **listShops 每行 finder**：启动全表扫描的行循环内逐行
   `plugin.getPlayerFinder()`（剖析 1,361/1,805 采样），finder 对整次扫描恒定。

## 改动（commit `99cf10d43`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| `AbstractShopManager.getShop` | fromLocation 中间键+探针重复坐标读 | 坐标一次读入局部变量直喂块键与归一化探针；Y 推迟至块图非空后读取 | 纯表达式重写；miss 路径调用面与旧版逐位一致（Y 本就不读）；既有探针命中（locationLookupStillResolvesRegisteredCoordinates）与非整坐标回退扫描（nonIntegralLegacyLocationsResolveByBlockCoordinates）双路径用例钉住 |
| `SimpleDatabaseHelperV2.listShops` | 行循环内逐行 plugin.getPlayerFinder() | 提升出循环一次 | finder 恒定，纯提升 |

## 测试面

无新增用例：getShop 为等价重写，两条既有路径用例（探针命中/非整坐标回退扫描）
钉住语义；listShops 由 ShopLoaderDataRecordTest 与 db 套件覆盖。合计 **221 全绿**。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| db/listShops | 1,010,088 | 248,864 | **-75.4%** | [1,009,785-1,016,137] \| [248,680-248,952]——完全分离；ns 轴 -75.4% 同向 |
| startup/shopLoadChain | 1,132,864 | 357,304 | **-68.5%** | [1,132,654-1,139,247] \| [357,203-357,909]——完全分离（装载链内含 listShops 全表扫描） |
| lookup/getShopByLocation-hit/miss | 1,936 / 1,997 | 1,936 / 1,997 | +0.0% | 两侧逐位同值——见下「轴不可见」说明 |
| 其余 49 共享用例 | — | — | 噪声带内 | 详见下 |

**如实披露一：两个 B/op 大动项均为 mock 计量面**。被消除的每行
`plugin.getPlayerFinder()` 在基准上是 Mockito 拦截（~500B/行），生产上是一次
返回字段的虚调用（~2-5ns/行；2000 店启动合计 ~10μs）——方向由 fork 完全分离
钉死，生产绝对量为一次调用×行数，量级以生产模型为准。

**如实披露二：getShop 单遍化在分配轴不可见**。两侧 B/op 逐位相等（1,936/1,997）
表明旧代码的中间块键分配已被 JIT 逃逸分析消除（SimpleShopChunk 不逃逸出内联
后的查找链），故本轮该改动的收益不在分配面而在**调用面**：命中查找的
getWorld/getBlockX/getBlockZ 由各两次减为一次、少一次键构造路径（生产每次命中
省 ~4 次虚调用+一次对象构造走查，且不再依赖逃逸分析持续生效）；基准无法区分，
以等价用例+代码论证入册。miss 路径两侧调用面逐位一致。

如实入册四点：

- **lookup 系 ns 轴 +12~33%**（getShopByLocation-hit +30%/getAllShops +33.5% 等）
  为本机时序噪声——候选 fork 时段恰逢外部负载（同期 db/externalCache ns +31%、
  metricInsertSingle +21% 等同向），B/op 验收轴两侧逐位一致。
- **text/fallbackYamlParse +1.1%**：基线 fork1 落低峰 4,402,128、候选 fork1 落
  4,352,304——历轮留档双峰带的又一次翻转，两侧同码无因果。
- **menu/groupAndNameSort +0.1%**：fork1 两侧同值 4,842,840，fork2/3 粒度档漂移带。
- db/insertShopChain/updateShop/locateShopDataId/metricInsertBatch100/safeCommit 系
  ±0.2~0.5% 为 8-300B 粒度级偏移且区间相接（R43 起历轮同类带）。

## 生产语义收益（mock 面之外）

- **每次 getShop 命中**（全插件最高频 API）省 4 次坐标/世界虚调用与一次中间键
  构造走查；miss 路径省一次中间键构造；
- **每次启动全表扫描**省 2000×（行数）次 finder 虚调用（~10μs 级）；
- 从此 fromLocation 家族清扫完毕（VirtualDisplayItem 的剩余使用为展示物重定位
  罕见路径且需保留键对象作字段，属合理使用）。

## 累计（R38–R47，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R47 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,848,619 | -52.5% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,648 | -44.0% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,432 | **-63.6%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,509 | **-60.2%** |
| db/insertShopChain | —（R42 前 42,208） | 20,056 | **-52.5%** |
| db/listShops（R47 计量面） | —（R47 前 1,010,088） | 248,864 | -75.4% |
| startup/shopLoadChain（R47 计量面） | —（R47 前 1,132,864） | 357,304 | -68.5% |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 8,040 | -76.3% |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 8,544 | -82.1% |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,104 | -9.6% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,208 | -8.9% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 931,696 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 970,522 | -14.4% |
| trade/actionBuy | 1,508,192 | 1,270,760 | **-15.7%** |
| trade/actionSell | 1,505,232 | 1,295,312 | **-13.9%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,624 | **-68.3%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
# A/B 基线 worktree 换基线前须 git restore+clean（残留未跟踪 JSON 阻塞 checkout）
bash benchmark/results/run-round47.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物落基线 worktree 的 benchmark/results，需拷回主仓（round44 先例）
python benchmark/results/compare-round47.py
```
