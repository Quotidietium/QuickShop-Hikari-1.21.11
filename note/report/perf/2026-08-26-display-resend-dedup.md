# QuickShop-Hikari 性能优化报告·第十轮（展示物区块进入路径去冗余，2026-08-26）

> 基准程序与方法论同前（本轮起 37 用例 × 7 套件——listener 套件新增 displayResend，
> 两侧同构运行：候选走统一 resendChunkDisplays，基线回退历史监听体）；同态 A/B，
> 基线 commit `10aebb769` = R21 闭合并补记实机验证后的 HEAD。
> 本轮同时携带一项**实机发现的稳定性修复**（后端启用失败即插件整体崩溃，见文末）。

## R22：虚拟展示物区块进入重送路径去冗余（commit 62db2bc63..90c09fdba）

### 问题

玩家进入（或重收）含商店区块时，CHUNK_DATA 监听器对每个已生成展示物执行：

```java
target.getPacketSenders().add(player.getUniqueId());
target.sendDestroyPacket(player);   // ← 显式 destroy
target.sendFakeItem(player);        // 内部又以 destroy 开头（destroy→spawn→meta）
```

`sendFakeItem` 的首包就是 destroy（正是为覆盖客户端仍持有旧实体的重发场景），
监听器在其前又显式发一次 destroy——**每个玩家进入每个区块、每个商店展示物都多发
1 个 destroy 包 + 1 次 PacketHandlerSendDestroyEvent 事件分发**（4 包 → 应为 3 包），
发生在 Netty IO 线程上。该模式在全部五个工厂（packetevents v1_20/v1_21/v1_21_6、
protocollib v1_20/v1_21/v1_21_10）中重复，且存在两种结构变体（map 桶锁内直发 vs
收集后发），维护上每次改动需五处同步（R21 即同时改三个文件）。

### 改动

1. **统一入口**：`VirtualDisplayItemManager` 新增 `resendChunkDisplays(player, world, x, z)`
   （登记发送者 + 单次 destroy→spawn→meta 序列）与 `withdrawChunkDisplays(...)`（卸载
   路径：destroy + 注销）。两阶段结构——map 查询内只收集适用项，**发包移出
   ConcurrentHashMap 桶锁**（发包可能重入 Bukkit 服务，桶锁内直发是隐患）。
   五个工厂的 CHUNK_DATA/UNLOAD_CHUNK 监听器各缩为一行委托。
2. **重复 destroy 消除**：重送序列恰为 3 包（由 VirtualDisplayItemResendTest 按序断言）。
3. **构造与首扫去冗余**：`checkEnchants` 外层 clone 删除（内部 `asOne()` 已深拷）；
   `load()` 玩家扫描免 ArrayList 全量复制、`distanceSquared` 平方比较替代开方
   （阈值语义等价：d²>R² ⟺ d>R，R=viewDistance×16）。
4. **死代码清理**：packetevents v1_21_6 工厂的 `itemName` 死存储（Gson 序列化结果
   从未使用，每展示物创建一次全量组件→JSON 序列化纯浪费）；该工厂本身未注册
   （PacketEventsHandler 版本表只挂 v1_20/v1_21），现状留档于 04。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| listener/displayResend（4 展示物/区块包） | 166,408 | 131,303 | **-21.1%** | 基线三 fork 160.1~168.1μs vs 候选 130.7~133.4μs，**六 fork 零重叠**；基线=历史监听体（适用性事件+登记+显式 destroy+sendFakeItem），候选=统一 resendChunkDisplays（同路径每展示物少 1 包+1 事件） |
| 其余 36 个共享用例 | — | — | **±11% 内（多数 ±5%）** | 零回归；偏大的三项（lookup/getAllShops +7.7%、serialize/generateParams +11.0%、db/externalCacheBatch100 -10.8%）跨 fork 交错重叠且与展示物路径无交集，属会话噪声 |

机器可读数据：`benchmark/results/round22-baseline/` 与 `round22/`（每侧 3 fork，对比脚本
`benchmark/results/compare-round22.py`）。

### 语义与红线

- 客户端可见包序列：旧 = destroy,destroy,spawn,meta；新 = destroy,spawn,meta。
  首个 destroy 针对的是客户端尚未持有的实体 ID（区块刚进入），客户端对不存在实体
  的 destroy 是无害空操作；重发场景（客户端仍持有）由 sendFakeItem 自身的 destroy
  覆盖——**展示物可见性行为逐包等价**（实机 E2E：packetevents 2.13.1-SNAPSHOT 下
  `node test/bot/qs-display-check.js` DISPLAY-CHECK PASSED，含拉远卸载/拉回重载循环）。
- UNLOAD_CHUNK 路径：destroy + 注销发送者，顺序与语义不变，仅移出桶锁。
- VirtualDisplayItemResendTest ×3：sendFakeItem 恰好一次 destroy→spawn→meta 三包序列
  （重复 destroy 保持消失）、resendChunkDisplays 登记适用玩家/跳过未生成与不适用/
  其他区块不动、withdrawChunkDisplays 销毁并注销。全量 110 用例绿。

## 附：实机发现并修复的稳定性缺陷（commit 2994251fa）

R21 补验时换装支持 1.21.11 的 packetevents 前发现更深一层的问题：**packetevents
2.9.5 在 Paper 1.21.11 上加载成功但启用失败**（仅支持到 1.21.8），Paper 随即关闭其
PluginClassLoader——此时 `setHandler()` 只检查插件**存在**（getPlugin≠null）而不检查
**已启用**，照常选中 PacketEventsHandler → `NoClassDefFoundError`（Error，原
catch(Exception) 根本捕不住）→ `loadVirtualDisplayItem` 捕获后又 **rethrow** →
QuickShop 整体启用失败。

修复（红线优先）：

- `setHandler` 仅选择**已启用**（isPluginEnabled）的后端插件；
- `loadVirtualDisplayItem` 改捕 Throwable、内存级降级（display=false，商店交易完全
  不受影响）、不再 rethrow、不再持久改写 display-items 配置（临时后端故障不应永久
  改写管理员配置；装好可用后端重启即恢复展示物）。

运行环境要求留档：packetevents 需 ≥ 支持 1.21.11 的构建（如 codemc CI 2.13.1-SNAPSHOT）。

## 结论

R22 把展示物区块进入路径从「五份重复、桶锁内发包、每店多发一包」收敛为单入口、
桶锁外发包、恰好三包的重送序列（Netty 线程上每玩家每店少 1 包 + 1 事件分发，基准
**-21.1%**、六 fork 零重叠），并顺带修复了「后端启用失败 → 插件整体崩溃」的稳定性缺陷、
补齐了 R21 遗留的实机验证。十轮累计：交易全链约 -84%，每笔 DB 写全批量化，日志/文本全
缓存化，区块包路径 O(1) 化 + 展示物重送去冗余。
