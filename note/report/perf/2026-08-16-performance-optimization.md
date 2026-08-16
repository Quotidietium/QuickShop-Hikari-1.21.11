# QuickShop-Hikari 性能优化报告（2026-08-16）

> 基准程序：`benchmark/`（独立 Maven 模块，19 用例 × 5 套件）
> 方法论：4 预热 + 7 采样 × ≥250ms 批次循环，取中位数；每轮 3 个独立 JVM fork，
> fork 间再取中位数；DCE 黑洞；对比工具 `benchmark/src/main/java/.../Compare.java`。
> 环境：JDK 21.0.10（Temurin/Oracle HotSpot 64-Bit Server VM）、Windows 11、
> Mockito subclass mock maker、H2 2.1.214 (MODE=MYSQL) 内存库、真实 EasySQL 层。

## 结论速览

七轮优化全部完成，**最终同态 A/B 对比**（优化前代码经 git worktree 重建后与最终代码
在相同时段交替测量，各 3 fork 取中位数，排除会话级机器漂移）：

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 轮次 |
|---|---:|---:|---:|---|
| lookup/getShopById | 141,699 | 77 | **-99.9%** | R2 |
| lookup/getAllShopsByOwner | 282,271 | 111 | **-100.0%** | R2 |
| db/locateShopDataId | 28,101 | 114 | **-99.6%** | R3 |
| text/forLocaleNoArgs | 18,765 | 233 | **-98.8%** | R4 |
| db/updateShop（内容未变） | 359,882 | 67,001 | **-81.4%** | R3+R5 |
| serialize/createDataRecord | 121,721 | 59,878 | **-50.8%** | R3+R5 |
| db/listShops（2k 行全量读） | 2,614,601 | 1,612,585 | **-38.3%** | R6 |
| db/insertShopChain（建店链） | 655,223 | 411,913 | **-37.1%** | R3 |
| text/findRelativeLanguages | 408 | 96 | -76.6% | R4 |
| lookup/getAllShops | 134,089 | 112,615 | -16.0% | R2 |
| lookup/getShopByRuntimeUuid-uncached | 78,129 | 60,714 | -22.3% | R7 |
| economy/safeCommit（税/无税） | 82,908 / 57,660 | 79,576 / 55,981 | -4.0% / -2.9% | R5 |
| text/forLocaleWithArgs | 23,456 | 23,888 | +1.8%（噪声内） | — |
| lookup/getShopByLocation 命中/未命中 | 4,376 / 4,352 | 4,162 / 4,510 | -4.9% / +3.6%（噪声内） | R2 |

完整机器可读数据：`benchmark/results/`（baseline-final/ 与 final/ 为同态 A/B；
baseline/ 与 round2..round7/ 为逐轮演进数据；machine-check/ 与 round6-verify/
为会话漂移对照实验）。

## 各轮优化内容与归因

| 轮次 | commit | 切入点 | 关键改动 | 归因指标 |
|---|---|---|---|---|
| R2 | aaae8d88b | 内存查找表 | id→shop、owner→shops 二级索引（注册/注销/setShopId 回填/所有权转移四处维护）；位置查找 ThreadLocal 探针+扫描兜底；runtime UUID 缓存 50→1024；getAllShops 去 PerfMonitor | getShopById 142μs→77ns；getAllShopsByOwner 282μs→111ns |
| R3 | 6ab6ae9bf | DB 写路径 | dataIdByRecord 去重备忘录（免全字段 SELECT）；persistedRecordByShop 不变跳写；dataIdByShopId 指标定位缓存；encodeStack 双次调用合一；insertMetricRecord 补 return；DataTables 改初始化即换绑（修复连接池重建后指向死池的潜在缺陷） | updateShop -81%、insertShopChain -37%、locateShopDataId -99.6% |
| R4 | e18a133f8 | 文本管线 | (locale,path)→原始模板缓存；无参文本 MiniMessage 解析结果共享（不可变组件）；ProxiedLocale 实例缓存；reset/register 失效 | forLocaleNoArgs -98.8%、findRelativeLanguages -76.6% |
| R5 | b2fb84349 | 事件分发 | AbstractQSEvent 零监听器快路径（callEvent/callCancellableEvent；保持平台契约返回值） | createDataRecord 内 4 个 RETRIEVE 事件免派发（累计 -50.8%） |
| R6 | 5b7fa8d94 | 读链反序列化 | QUserImpl.deserialize 以序列化串驻留（8192/5min），万店几十主场景命中率高 | listShops -38.3%（同态对照 machine-check 归因） |
| R7 | d8a70bf35 | runtime UUID | shopRuntimeIdLookup 索引，回退扫描 O(loaded)→O(1)（保持仅已加载语义） | uncached/cached 用例从 1.77× 差距收敛到 1.04× |

## 方法学说明与噪声分析

1. **会话级漂移**：本机基准存在 ±15~45% 的会话级漂移（推测为后台负载/睿频状态）。
   R6 首次测量时所有用例（含未触碰路径）一致偏慢，遂用 round5 代码stash 复测
   （`results/machine-check/`）证明确系机器漂移而非代码回归。
2. **最终报告采用同态 A/B**：基线 commit `1bd4b1378` 经 `git worktree` 重建安装后
   与最终代码在同一天相邻时段交替测量（baseline-final vs final，各 3 fork），
   上表即该数据。
3. **高方差用例**：`getShopByRuntimeUuid-*` 两用例经过 `isValid()`→Mockito
   BlockState mock（含 extraInterfaces 代理），为全表噪声最大的用例（同构建跨会话
   波动 ±40%）；其真实改进以「uncached 与 cached 差距收敛」表述更诚实。
4. **mock 语义差异**：经济用例的 provider 为 ConcurrentHashMap 内存实现，事件派发
   走零监听器快路径，故其绝对值代表「纯事务逻辑开销」；真实服务器的 Vault 同步
   IO 之上，事务逻辑占比更小。
5. **encodeStack 成本剖面**：serialize 套件的 Platform.encodeStack 以 400 字节
   Base64 桩近似真实 serializeAsBytes+Base64；优化消除的是重复调用次数，
   真实编码器越贵收益越大。

## 安全性/稳定性/兼容性红线核查

- **API 兼容**：全部改动位于实现层内部数据结构；公开接口（`getShops()` 三层映射、
  `Map<Location,Shop>` 键语义、事件契约返回值）不变。位置查找语义为基线超集
  （非整数/带朝向历史坐标现在可命中，整数坐标行为不变）。
- **资金路径**：经济事务的金额计算、补偿回滚、税逻辑零改动；仅事件派发开销变化。
  52→56 个测试（含资金回归套件）全绿。
- **缓存失效纪律**：DB 写缓存 TTL 10min + removeData/removeShop/purgeIsolated/
  recovery 导入四处显式失效；文本缓存 reset()/register() 失效；索引在注册/注销/
  所有权转移处维护。多服务器共享数据库场景由 TTL 兜底（去重设计本就假设单写者）。
- **并发**：新索引/缓存均为 ConcurrentHashMap/写时复制/Guava Cache，读无锁；
  ThreadLocal 探针仅用于主线程/区域线程的同步查找。
- **行为修复（顺手，非性能）**：insertMetricRecord 异常分支缺 return（失败仍以
  null data 插入）；DataTables 一次性绑定（运行期重建连接池后指向死池）。
  二者均有独立测试或在 DbWriteCacheTest 覆盖。

## 复现步骤

```bash
# 1. 安装主工程（任一待测版本）
JAVA_HOME=<jdk21> mvn -T 1.5C -P github -DskipTests -pl quickshop-bukkit -am install
# 2. 运行基准（3 fork）
cd benchmark && for i in 1 2 3; do
  JAVA_HOME=<jdk21> mvn exec:exec -Dbenchmark.label=<标签> -Dbenchmark.fork=$i; done
# 3. 对比
"F:/Java/21/bin/java" -cp "target/classes;$(cat target/cp.txt)" \
  com.ghostchu.quickshop.benchmark.Compare results/<基线目录> results/<候选目录>
```

## 已知未优化项（评估后放弃）

- `saveExtraToYaml`/权限 JSON 的按需缓存：addon 可直接改 extra/权限内部结构，
  无可靠脏标记，缓存失效风险大于收益（红线优先）。
- `text/forLocaleWithArgs`（≈23μs）：带参文本的 MiniMessage 解析本质上是每参数
  一次组件序列化+全模板重解析，语义上不可缓存；如需进一步优化需改渲染契约。
- `db/insertShopChain` 剩余 412μs：建店链固有 3 条 SQL（INSERT data/INSERT shops/
  REPLACE map）+ 生成键往返，合并需改 DAO 契约。
- BigDecimal 经济运算链：金额精度红线，不做代数化简。
