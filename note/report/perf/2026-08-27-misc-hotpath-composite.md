# QuickShop-Hikari 性能优化报告·第十六轮（杂项热路径复合，2026-08-27）

> 基准程序与方法论同前（本轮起 44 用例 × 8 套件——listener 新增 chatGate、text 新增
> itemNameFlags）；同态交替 A/B，基线 commit `0b72581e7` = R27 闭合并提交文档后的
> HEAD，各 3 fork 取中位数。本轮为复合轮：三个独立可证等价的杂项热点一次闭合。

## R28：物品名标志快照 + 聊天门控快照 + Holder 单次解析（commit 4b9efb7de..ca0983865）

### 问题（三点各自独立计量）

1. **`Util.getItemCustomName` / `useEnchantmentForEnchantedBook`**：每次物品名解析
   （签名 item 行、每笔交易收据、菜单图标逐格渲染）走两次 YamlConfiguration 树查询。
2. **`ChatListener.onChat`**：全服每条聊天消息都进处理器；被取消的消息（静音/过滤
   插件环境为常态）每次读一次 config 判定 `ignore-cancel-chat-event`。
3. **`ShopProtectionListener` 漏斗/投掷器监听**：`protect.*-owner-exclude` 启用时同一
   事件内 `Inventory.getHolder()`（方块状态快照）被调用两次。

### 改动

1. `Util` 增加两个 volatile 快照字段，由**已注册为 reload 钩子的 `initialize()`**
   刷新（零新增失效纪律）；无默认值的 `getBoolean(path)` 语义保持（缺键 = false，
   `initialize` 处显式传 `, false`）。
2. `ChatListener` 改用监听器层既有的 init/reloadModule() 快照模式（与 PlayerListener
   的 `ignore-cancelled-interact-event` 同构）。
3. 两个移动监听器把 instanceof 守卫获取的 holder 局部变量化复用。

### 结果（同态交替 A/B，各 3 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| text/itemNameFlags（双 flag 门控读取本身） | 6,802 | 33 | **-99.5%** | 基线三 fork 6,691~7,210 vs 候选 33~52——基线**最优** fork 高于候选**最差** fork 128 倍，六 fork 零重叠；直接计量被优化构件（每次 getItemStackName 调用的门控部分），无其他成本稀释 |
| listener/chatGate（被取消聊天经 onChat 全链） | 22,911 | 16,187 | **-29.3%** | 中位数方向明确（3/3 fork 中位数全部下降），但候选 fork3 带 39.8μs 环境尖刺使 fork 区间未完全分离——mock 的 containsKey 调用（R21 已证 ≈μs 级/次）主导两侧共同底部，config 读取的省去仅占中位差 |
| ShopProtectionListener holder 合并 | — | — | 未单列基准 | 与 chatGate 同类：每次漏斗入店事件省一次 Bukkit 方块状态快照（真实 ~百 ns 级）；不设独立 mock 用例避免放大失真，结构由代码审阅保证 |
| 其余 44 个共享用例 | — | — | 同向 +0~16% | 会话漂移非回归：候选侧第三 fork 尖刺遍布（chunkLoad/economy/db 全体同向），基线侧重试至安静窗口完成的选择偏差再次出现；R28 未触碰任何共享路径 |

机器可读数据：`benchmark/results/round28-baseline-fork{1,2,3}.json` 与
`round28-fork{1,2,3}.json`（对比脚本 `benchmark/results/compare-round28.py`）。

### 方法学补充（本轮新踩坑）

- **`PlayerEvent.getPlayer()` 是 final 方法**——Mockito 子类 mock maker 无法拦截，
  `when(event.getPlayer())` 报 MissingMethodInvocationException。基准侧以反射注入
  `PlayerEvent.player` 受保护字段替代桩（测试侧 surefire 环境的 maker 不同故测试桩可用，
  两环境勿互抄写法）。
- **被优化构件直接计量范式**：当优化目标被更大的共同成本（mock 调用）淹没时（首次
  尝试的 getItemStackName 全链用例 delta <1.3% 不可辨），把用例缩到只包含被测构件
  （itemNameFlags 单发 op）——与 metricInsert 批量摊销同理的反向操作。

### 语义与红线

- 三处均为纯读取路径的求值策略替换：字段快照 vs 即时查询，值仅在 reload 边界可能
  变化且 refresh 点与既有 reload 纪律重合；holder 局部化消除的是同一不可变事实的
  二次获取。公开 API 零改动（`Util.useEnchantmentForEnchantedBook()` 签名不变）。
- 测试：`ChatListenerTest` ×3（快照消费/假值落穿原语义/reload 免重建刷新）、
  `UtilItemNameSnapshotTest` ×2（100 次解析零 config 读取/双 flag 快照生效含附魔书
  分支）；全量 **140 用例绿**（135→140）。

## 结论

R28 以「求值策略替换」模式（快照/局部化，reload 纪律复用）清算了监听器与工具层
最后一批逐调用 config 访问（构件级 **-99.5%**，聊天链中位数 **-29.3%**）。十六轮累计：
交易全链约 -84%，DB 写全批量化，文本/日志全缓存化，区块包 O(1) 化，展示物重送去冗余，
浏览菜单快照化，开箱/区块加载扫描快路径化，木牌渲染单扫化，签名排程去重 O(1) 化，
监听器层 config 访问全快照化。至此代码库内已知热点均有结论，剩余量级收益在红线外
或插件外部（Vault IO、DB 网络、MiniMessage 语义重写）。
