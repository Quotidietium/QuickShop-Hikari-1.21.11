# QuickShop-Hikari 性能优化报告·第三十轮（经济提交路径 provider 解析链收敛，2026-09-07）

> 承 R43。基线 `8e78643b3`（R43 完成点），候选 `0e9f82e1e`，3 fork/侧严格交替，
> 全 53 用例双轴计量（基准文件两侧同源，无同步需求——本轮零基准改动，既有
> economy/trade 套件已覆盖该路径）。

## 背景：每次转账操作都重跑一遍服务定位链

R44 选点经基准剖析模式（`-Dbenchmark.profile`）：economy/safeCommit 剖析的
quickshop 顶帧中 `QuickShopAPI.getInstance` 独占 770 采样（次席 getEconomyManager
387、provider 359）。代码走查定位根因——

`EconomyWithdrawOperation`/`EconomyDepositOperation`（quickshop-api）的
`commit()` 与 `rollback()` 每次都执行完整服务定位链：

```
QuickShopAPI.getInstance()                        // Bukkit.getServicesManager()（静态）
  → servicesManager.getRegistration(QuickShopProvider.class)   // 映射查找
  → rsp.getProvider().getApiInstance()            // 两次虚调用
  → getEconomyManager().provider()                // 管理器查找
```

而 `QSEconomyTransaction` 构造时已解析并持有 provider（`this.provider`，
`completable()` 的余额检查即用之）——**每笔交易的 2-3 个操作（取款/入账/税入账）
各自重跑整条链，回滚时再各跑一次**。另有同族小病：`QSEconomyManager.provider()`
每次调用做 `currentProvider.toUpperCase(Locale.ROOT)`（新 String 分配）后查映射。

## 改动（commit `0e9f82e1e`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| 两个 Economy 操作类 | 每次 commit/rollback 服务链解析 provider | 新增 provider 参透构造器重载（`transient` 字段）；旧三参构造器委托 `(..., null)` 保持逐次解析——**null 即回退旧链，独立构造的 API 调用方行为不变** | 注入时转账走交易解析的同一 core；provider 恒定时两行为逐位同值 |
| `QSEconomyTransaction` 五处操作构造点 | `new XxxOperation(a, b, world)`（操作内部自行解析） | 全部传入 `this.provider` | **一致性增益**：旧行为在理论窗口（构造到 commit 间 provider 被换）会出现余额检查用 core X、转账用 core Y 的跨 provider 不一致；新行为转账与授权检查严格同源（provider 为 null 时操作回退旧链，与旧行为完全一致） |
| `QSEconomyManager` | `provider()` 每次 `toUpperCase` 分配+查映射 | `useProvider` 存储时归一化（providers 键本就大写存储），`provider()` 裸 map get | `useProvider("myeco")` 与旧版 `get("myeco".toUpperCase())` 解析同一键；`currentProvider` 无其他读者 |

## 测试面

新增 **4 用例**（合计 **205 全绿**）：`EconomyOperationProviderInjectionTest`——
注入 provider 的 withdraw/deposit commit+rollback 走注入对象且**零服务链调用**
（测试不装 Bukkit 服务环境，若仍走链则直接失败）、旧三参构造器保持链解析行为、
**交易操作用构造时 provider**（构造后把管理器切到 providerB，commit 仍全程走
providerA，B 零调用——旧代码此测试失败）、大小写混合 useProvider 归一化等价。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| economy/safeCommitNoTax | 33,968 | 8,104 | **-76.1%** | [33,968, 33,968, 34,160] \| [8,104, 8,104, 8,128]——完全分离 |
| economy/safeCommitWithTax | 47,640 | 8,560 | **-82.0%** | [47,608, 47,640, 47,864] \| [8,560, 8,560, 8,680]——完全分离 |
| trade/actionBuy | 1,298,176 | 1,273,632 | **-1.9%** | [1,297,920-1,299,088] \| [1,272,936-1,274,064]——完全分离 |
| trade/actionSell | 1,322,904 | 1,298,184 | **-1.9%** | [1,322,728-1,323,640] \| [1,297,464-1,299,216]——完全分离 |
| 其余 49 共享用例 | — | — | 噪声带内 | 详见下 |

模型自洽：WithTax 比 NoTax 多移除一条链（税入账），-39.1KB vs -25.9KB，比值与
3 链/2 链吻合；action 每交易移除约 2 链 ≈ mock 面 24.5KB。

**mock 放大如实披露**：基准上被移除的每条链是 ~6 次 Mockito 调用（每次分配
 invocation 元数据），故 -76%/-82% 是调用结构消除的 mock 计量面；生产上每条链
≈ 6 次调用 + 两个映射查找（~0.2-0.5μs）+ toUpperCase 分配，每笔交易省 2-3 条
链、回滚同量，量级为百 ns~μs 级——方向与结构消除由 fork 完全分离钉死，绝对
量以生产模型为准。

如实入册三点：

- **db/listShops +1.0%**：基线自身 fork 带宽 [1,010,064-1,026,448] 且 fork3 与
  候选 fork1 同值 1,026,448——H2 跨会话漂移带（R39/R42/R43 先例），区间重叠。
- **startup/shopLoadChain +0.9%**：基线 [1,132,816-1,149,608] 与候选
  [1,149,608-1,153,894] 在 1,149,608 处相接——装载套件已知摆动带。
- chunkLoad +1.1%/metricInsertSingle +1.1%/chatGate +0.5% 为 128B/41B/64B 粒度
  级偏移且区间相接；时序轴 db 系 ±20% 与 hopperMoveProtect ns +20.0%（B/op
  +0.2%）为本机时序噪声，B/op 轴为准。

## 生产语义收益（mock 面之外）

- **每笔交易**省 2-3 条服务定位链（取款+入账+税入账）与回滚同量（~0.5-1.5μs
  + 若干小分配）；
- **转账一致性加固**：操作与授权余额检查严格同 core，封堵理论上的跨 provider
  半转账窗口（资金红线方向的安全增益）；
- `QSEconomyManager.provider()`（交易构造、InternalListener 余额日志、
  EconomySetupListener 等调用点）每次省一个 String 分配。

## 累计（R38–R44，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R44 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,862,114 | -52.3% |
| economy/safeCommitNoTax（本轮重计量） | —（R44 前 33,968） | 8,104 | **-76.1%** |
| economy/safeCommitWithTax（本轮重计量） | —（R44 前 47,640） | 8,560 | **-82.0%** |
| serialize/createDataRecord | —（R42 前 34,200） | 29,352 | -14.2% |
| db/insertShopChain | —（R42 前 42,208） | 36,948 | -12.4% |
| db/updateShop-unchangedData | —（R42 前 36,540） | 31,460 | -13.9% |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,168 | -9.6% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,272 | -8.6% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 932,176 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 971,372 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,273,632 | **-15.5%** |
| trade/actionSell | 1,505,232 | 1,298,184 | **-13.8%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,904 | **-67.3%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round44.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
python benchmark/results/compare-round44.py
```
