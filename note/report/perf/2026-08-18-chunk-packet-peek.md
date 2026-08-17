# QuickShop-Hikari 性能优化报告·第九轮（CHUNK_DATA 监听免全量解析，2026-08-18）

> 基准程序与方法论同前（本轮起 36 用例 × 7 套件——listener 套件新增 chunkPacketPeek，
> 候选专属运行时探测注册）；同态交替 A/B，基线 commit `e5e7833f0` = R20 闭合并提交文档后的
> HEAD。本轮同时携带一项**基准 harness 根因修复**（Mockito 调用记录泄漏，见文末），两侧
> 同享该修复，A/B 公平性不受影响。

## R21：展示物 CHUNK_DATA 包监听坐标 peek（commit 771f303f1..9e29c5d2b）

### 问题

PacketEvents（默认优先的包处理后端）的 CHUNK_DATA 发包监听器（三个版本工厂同型）在
**每个发给每个玩家的区块包**上执行：

```java
final WrapperPlayServerChunkData chunkData = new WrapperPlayServerChunkData(event);
...
final int x = chunkData.getColumn().getX();
```

`getColumn()` 触发 PacketEvents 的 `ChunkReader_v1_18`——逐垂直段（~24 段）解析调色板
方块存储与生物群系存储（对完整区块数据做一次全量重解析，单包常见几十至几百 KB）——
发生在 **Netty IO 线程**上，而后续的商店区块映射查询只需要列坐标 x/z（包头前两个
int）。玩家移动/传送触发的区块流送在人群密集的商店区是持续事件，这是交易路径之外
最大的隐性每包成本。

### 改动

新增 `ChunkPacketPeek`（packetevents 包）：保存 readerIndex → 读两个 int → 复位
readerIndex——O(1) 且不触碰载荷字节、不影响后续监听器；三个 packetevents 工厂
（v1_20 / v1_21 / v1_21_6）的 CHUNK_DATA 监听全部改用 peek，删除包装器构造。
ProtocolLib 工厂本就懒读（`getIntegers()` 结构修改器），无需改动；UNLOAD_CHUNK 包
只有两个 int，保持原样。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| listener/chunkPacketPeek（候选专属） | —（基线无此路径） | ~24,500 | 新路径计量 | 三 fork 24.3~24.6μs 稳定；该值为 **mock 计程**（ByteBufHelper 静态路由经 mock API，每次 ~8μs StackWalker），生产为直接算子调用（纳秒级）；结构性证明由 ChunkPacketPeekTest 与 ChunkReader_v1_18 源码对照承担（旧路径 = 全区块逐段解析） |
| 其余 35 个共享用例 | — | — | **全部 ±7.6% 内（多数 ±1%）** | 零回归；本 Harmony 为全项目最干净的 A/B |

**Harness 稳定性质变**：stubOnly 修复后，此前跨会话摆动 ±15~45%（db/listShops 曾在
1.6~171ms 间漂移）的用例，本轮 db/listShops 两侧中位数 1,202μs vs 1,199μs（±0.3%）；
text/forLocaleWithArgs 全部 6 个 fork 稳定在 906~934ns——R15 报告记录的"会话级漂移"中
有相当部分实为 mock 记录泄漏的堆压力，R21 起基本消除。机器可读数据：
`benchmark/results/round21-baseline/` 与 `round21/`（3 fork 交替，零套件失败）。

### 语义与红线

- 坐标读取与 `WrapperPlayServerChunkData.read()` 的前两个 `readInt()` 逐字节一致
  （ChunkData 包在所有受支持版本的头部布局均为 chunkX/chunkZ 两个 int）；
  readerIndex 复位保证对其他监听器零可见性。
- 展示物映射查询、后续包发送逻辑不变；仅"获取坐标的方式"变化。
- ChunkPacketPeekTest ×3（真实 netty ByteBufOperatorImpl 桥接）：读头部两 int、
  任意载荷尺寸（0~100KB）下 readerIndex 复位且零字节改动、从当前索引导读语义。

## 基准 harness 根因修复（R20"text 污染"破案）

**现象**：R20 A/B 起 text 套件在多数 fork 劣化 10~50 倍（两侧同码），R21 首轮冒烟升级为
text 套件 OOM（4GB 堆耗尽，7.5GB heap dump）。

**根因**：Mockito mock 默认记录每一次调用（供 verify）；基准 harness 的热点 mock
（Env 的 plugin、各套件的 inventory/world/platform/text 等）在完整运行中被调用
数百万次，调用记录元数据累积至 GB 级——挤占堆并使分配敏感路径在 GC 压力下劣化。
单独跑 text 套件（调用少）始终正常，因此此前误判为"会话级机器干扰"。

**修复**：`Env.hotMock`（`withSettings().stubOnly()`，免记录、基准从不 verify）替换
全部热点 mock 创建（Env 主 mock 与五套件 + CALLS_REAL_METHODS 的 manager）。修复后
全序运行：套件时长恢复正常（text 91s→7.3s、database 157s→27s）、OOM 消失、
`forLocaleWithArgs` 全序稳态 926ns 与 R19 干净窗口一致。**R20 报告的污染说明已更正。**

## 结论

R21 消除了展示物子系统在区块流送上的隐性每包全量解析（Netty 线程、每玩家每区块包，
几十至几百 KB 的 ChunkReader 重解析 → 两个 int 的 O(1) peek），并破案修复了基准 harness
的 Mockito 调用记录泄漏（R20"text 污染"与 OOM 的真实根因，报告已更正归因）。修复同时
使 A/B 方差从 ±15~45% 收敛到 ±1% 量级——此前多轮报告中的"机器漂移"注记自此可以更小
置信区间解读。九轮累计：交易全链约 -84%，每笔 DB 写全批量化，日志/文本全缓存化，
区块包路径 O(1) 化。
