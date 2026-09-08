# QuickShop-Hikari 性能优化报告·第三十四轮（点击→快速建店门控链，2026-09-09）

> 承 R47。基线 `86f7ebcf1`（R47 完成点），候选 `32464b23c`，3 fork/侧严格交替，
> 全 56 用例双轴计量。基准模块改动（listener 三用例 + RegistryAccess 桩）随候选
> 入库并同步至基线 worktree，两侧同源（R40 基线同步先例）。

## 背景：每一次手持物品的挥拳都跑一遍建店预检

默认 `interaction.yml` 将站定左键的 shopblock/容器/告示牌全部映射到
TRADE_INTERACTION——即生存模式下**每一次手持物品的左键点击**（挖矿挥拳、拆除、
点箱子）与映射了行为的一切匹配点击，都会走
`PlayerListener.onClick → searchShop → interaction 谓词 → behavior 解析 →
TradeInteraction.handle(shop==null, item≠null) → Util.createShop(...)`。
而 createShop 的原头部在一切廉价判别门**之前**预付：

1. `QUserImpl.createFullFilled(player)`——分配 + 两次玩家读，唯一使用点在全部
   门通过后的 ShopCreateEvent；
2. `item.clone()`——生产为 ItemStack NBT 深拷贝（R42 实测 encodeStack 同类面
   10-50μs/次），其后才轮到 air 门；
3. `getItemMaxStackSize` 映射探测 + 栈数量截断（只突变克隆体，调用方不可见）；
4. 两个 legacy 开关 `disable-quick-create`/`shop.disable-quick-create` 的
   **每次两趟 config 树行走**——R30/R32 清扫法遗漏点（建店路径而非交易路径）。

无建店权限玩家点箱子（经济服多数玩家形状）更要走到双权限查询才停下；分发链上
`QuickShopInteractionManager.behavior(InteractionType)` 每次匹配点击做 2×
`identifier().toLowerCase()` 分配 + containsKey+双 get，`interaction(event,click)`
与 `behavior` 各付一次 Optional 包装。

## 改动（commit `ad8a46bb3`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| `Util.createShop` 门序 | QUser→block→gameMode→**克隆+截断**→air(克隆体)→canBeShop→**config×2**→权限 | block→gameMode→air(直读 item.getType)→canBeShop→开关快照→权限→**之后**克隆+截断+QUser（构造点移至 ShopCreateEvent 前） | 克隆在旧序中只被纯读门跨越（canBeShop/config/perm 均不突变 item），克隆内容逐位不变；截断只改克隆体；air 判 `item.getType()`≡`clone.getType()`；各 Log.debug 随门迁移；QUser 构造无副作用且晚于其原位置的一切纯读 |
| 快速建店开关 | 每调用两趟 `getConfig().getBoolean` | 双键 OR 快照为单 volatile Boolean，挂 `Util.initialize()` reload 钩子；null 未初始化回退逐次活读（世界名单快照同款回退模式） | OR 语义与任一键为真即拒一致；reload 经 initialize 重读（R30/R32/R39 既有钩子）；活读回退保持启用前原语义 |
| `InteractionManager`（API） | 仅 Optional 变体 | 新增 `interactionOrNull(event,click)`/`behaviorOrNull(type)` 默认方法（委托 Optional 变体），旧 API 逐位保留 | API 加法兼容（R41 getMaterial/R44 provider 参透先例）；默认方法行为≡旧路径 |
| `QuickShopInteractionManager` | behavior(type)：2× toLowerCase 分配 + containsKey + behaviorMapping.get + behaviors.get | `behaviorOrNull` 单次 `behaviorMapping.get(memo 小写)` + 单次 `behaviors.get`；小写标识按实例 memo 于 ConcurrentHashMap（良性竞争只重建相等串，R46 namespacedNode 同款） | containsKey-false ≡ get-null；映射值为未知标识（含 NONE 占位）时 behaviors.get→null ≡ 旧 Optional.empty；interaction 循环首匹配语义与迭代序不变 |
| `PlayerListener.onClick` | Optional 链（2×包装 + isEmpty） | OrNull 直查 + null 判断 | 分支结构/Log.debug 文本/最终 handle 调用面逐位一致 |

## 测试面（合计 235 全绿，新增 14）

- **UtilCreateShopGateTest（9）**：泥土挥拳零克隆零 QUser（verify clone/getName/
  getUniqueId never）、air 门先于 canBeShop（getState(false) never）、创造模式
  跳过克隆、无权限走完 sell+buy 双查询但零克隆、快照生效期零 config 读且零权限
  查询、活读回退含 OR 短路（第二键 never）、reload 刷新快照（关→开后进入权限
  拒绝）、授权路径**恰一次**克隆并抵达 no-double-chests 消息（钉住克隆仍在真实
  创建路径上）、旁观模式先于一切；
- **InteractionDispatchTest（5）**：真实 interaction.yml 装载（临时 dataFolder +
  services 链，走生产 QSConfig 路径）下站定左键解析 TRADE_INTERACTION 且跨调用
  同实例、NONE 映射解析 null 且 Optional 变体同意、右键告示牌解析 CONTROL_PANEL、
  OrNull 与 Optional API 逐位一致（含 AIR 无匹配）、接口默认方法委托正确；
- **RegistryAccess 桩（测试+基准各一份）**：`Material.isAir()` 经
  `asBlockType→Registry.BLOCK.get` 触发 `Registry.<clinit>`，Paper 以 ServiceLoader
  解析 RegistryAccess、无实现则整个 Registry 类**永久失效**（一次失败即缓存）。
  桩用纯 JDK 动态代理（BLOCK 注册表按 NamespacedKey 答空气性，其余注册表 null
  查找），不用 Mockito——ServiceLoader 可能在无关测试的 Mockito 会话中触发本类
  初始化，mock 创建会污染会话（实测踩坑）。仅存在于测试与基准 classpath，不入
  生产 jar。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| listener/quickCreateGate | 20,224 | 10,144 | **-49.8%** | [20,224, 20,224, 20,256] \| [10,144, 10,144, 10,144]——完全分离；ns 轴 -50.5% 同向 |
| listener/quickCreateGateContainer | 42,504 | 21,848 | **-48.6%** | [42,504, 42,504, 42,536] \| [21,848, 21,848, 21,976]——完全分离；ns 轴 -48.7% 同向 |
| listener/clickDispatch | 55,640 | 55,288 | -0.6% | 区间相接——见「轴不可见」说明 |
| 其余 53 共享用例 | — | — | 噪声带内 | 详见下 |

用例形状：quickCreateGate＝生存玩家手持镐挥拳泥土（挖矿点击形状，直测
createShop，canBeShop 在 isShoppables 拒绝）；quickCreateGateContainer＝手持
物品点击可建店箱子且无 create 权限（走完快照/权限门）；clickDispatch＝空手挥拳
全程 onClick（真实 QuickShopInteractionManager + 预写 interaction.yml 映射，
rateLimit 反射换 0ms ExpiringSet 使每 op 建模每个 125ms 限流窗口的首次点击——
窗口内重复点击在两侧都只付 Guava 门）。

**如实披露一：mock 计量面低估生产收益**。基准上被消除的 `item.clone()` 是一次
Mockito 拦截（~1.4KB/次），生产上是 **NBT 深拷贝**（复杂物品栈 10-50μs/次）——
生产中每次挖矿挥拳省下的主体远大于基准数值所示；`QUserImpl.createFullFilled`
基准上是 2 次拦截+1 分配，生产上是 1 分配+2 字段读；两趟 config 行走基准上是
2 次 map 查找拦截，生产上是 YamlDocument 树行走。方向由 fork 完全分离钉死，
绝对量以生产模型为准。

**如实披露二：clickDispatch 的分发收益在轴下**。每次匹配点击省 ~2 个 Optional
+ 1-2 个 toLowerCase（~28 字符小写行走）+ 2 次 map 操作——约 64-120B，淹没在
该用例 55KB 的 Guava 限流门 + mock 调用底座中（两轴均 ~0.6-0.7%，区间相接）。
真实但不可分辨，以等价用例 + 代码论证入册（R47 getShop 逃逸分析先例）；生产
每次匹配点击约省 100-200ns 与两次 map 查找。

如实入册四点：

- **text/fallbackYamlParse +5.5%（B/op）**：fork 值基线 [4,352,304, 4,402,128,
  4,593,648] 候选 [4,402,128, 4,644,048, 4,644,048]——历轮留档双峰带的又一次
  翻转（R44/R45 同带），该用例与点击链无共同代码路径；
- **listener/searchShopContainer ns +5.6%**：B/op 逐位同值（16,936）——searchShop
  本轮零改动，纯时序噪声；
- **lookup/getShopsInChunk ns +24.8%**：55→68ns 的 ns 级噪声带（R47 留档的
  lookup 系外部负载带同类），B/op 逐位同值（24）；
- lookup 系 ±0.4-1.2%（getShopByLocation 56B 档/R44-R45 留档带）、runtimeUuid
  ±0.4%、db 系 ±0.2%（H2 跨会话漂移带）、metricInsertSingle -1.0% 同带、
  groupAndNameSort -0.3%（粒度档）、forLocaleWithArgs -2.9%（48-56B 粒度档，
  候选三 fork 同值 1,600）——均区间相接或已知带内。

**方法学留档：基线 worktree 产物被下一 fork 的 git clean 清除**。run 脚本每
fork 基线段先 `git clean -qfd benchmark`（为 checkout 让路，R47 复现注记的同类
问题），fork1/2 的基线 JSON 在拷出前即被清掉；两 fork 的 B/op 中位数自运行日志
逐用例重建（harness 打印值即 JSON medianBytesPerOp，含 `reconstructedFromLog`
标记；fork3 为原始 JSON）。脚本已修为每 fork 基线跑完立即拷回。

## 生产语义收益（mock 面之外）

- **每次手持物品挥拳/点击方块**（全服生存玩家挖矿、放块、点箱的每 125ms 首次
  点击）：省 1 次 ItemStack NBT 深拷贝 + 1 次 QUser 分配 + 1 次栈大小映射探测；
- **无建店权限玩家点击可建店容器**：另省 2 趟 config 树行走（快照单布尔读）；
- **每次匹配点击的分发**：省 2 个 Optional + 1-2 个 String 小写重建 + 2 次 map
  查询；
- 有建店权限的真实创建路径**逐位不变**（克隆+截断+QUser 仍在，仅更晚）——用例
  `grantedWalkClonesExactlyOnceBeforeTheDoubleChestGate` 钉住。

## 累计（R38–R48，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R48 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,849,616 | -52.5% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,600 | -45.7% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,464 | **-63.6%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,708 | **-59.7%** |
| db/insertShopChain | —（R42 前 42,208） | 20,272 | **-52.0%** |
| db/listShops（R47 计量面） | —（R47 前 1,010,088） | 248,989 | -75.4% |
| startup/shopLoadChain（R47 计量面） | —（R47 前 1,132,864） | 358,358 | -68.4% |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 8,144 | -76.0% |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 8,576 | **-82.0%** |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,104 | -9.9% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,208 | -9.0% |
| listener/quickCreateGate（R48 计量面） | —（R48 前 20,224） | 10,144 | -49.8% |
| listener/quickCreateGateContainer（R48 计量面） | —（R48 前 42,504） | 21,848 | -48.6% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 931,872 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 970,650 | -14.4% |
| trade/actionBuy | 1,508,192 | 1,271,032 | **-15.7%** |
| trade/actionSell | 1,505,232 | 1,295,578 | **-13.9%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,784 | **-67.7%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round48.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物每 fork 自动拷回（本脚本已修；原始一轮的 fork1/2 基线 JSON 为日志重建件）
python benchmark/results/compare-round48.py
```
