# QuickShop-Hikari 运行原理报告

> 生成自 codebase-analyzer｜分析时间：2026-08-16
> 路径缩写：`QS/` = `quickshop-bukkit/src/main/java/com/ghostchu/quickshop/`，`API/` = `quickshop-api/src/main/java/com/ghostchu/quickshop/api/`。

---

## 1. 双阶段引导总览

QuickShop 采用「**胖壳 + 大脑**」双阶段引导：`QuickShopBukkit`（JavaPlugin 外壳）负责依赖装载与平台检测，`QuickShop`（核心类）负责全部业务装配。

```mermaid
sequenceDiagram
    autonumber
    participant S as Paper 服务器
    participant JB as QuickShopBukkit<br/>(JavaPlugin 外壳)
    participant LB as libby<br/>BukkitLibraryManager
    participant PL as PaperPlatform
    participant Q as QuickShop<br/>(核心大脑)
    participant F as FoliaLib 调度器

    S->>JB: onLoad()
    JB->>LB: loadLibraries()<br/>(QuickShopBukkit.java:63)
    Note over LB: 读 jar 内 libraries.maven 清单<br/>镜像测速选仓库→逐库下载注入 classpath<br/>(QuickShopBukkit.java:163-227)
    JB->>JB: new UnirestLibLoader(this)<br/>(HTTP 客户端, :126)
    JB->>PL: loadPlatform()<br/>(QuickShopBukkit.java:65)
    Note over PL: Spigot→抛异常禁用<br/>Paper→new PaperPlatform()<br/>(QuickShopBukkit.java:230-270)
    JB->>Q: initQuickShop()<br/>new QuickShop(this,logger,platform)<br/>quickShop.onLoad()<br/>(QuickShopBukkit.java:272-277)
    Q->>Q: 13 步早期初始化<br/>(见 §2)
    S->>JB: onEnable()
    JB->>JB: abortLoading 检查 +<br/>BarrelShops 冲突检查<br/>(QuickShopBukkit.java:98-105)
    JB->>Q: quickShop.onEnable()<br/>(QuickShopBukkit.java:108)
    Q->>Q: 42 步主初始化<br/>(见 §3)
    Q->>F: folia.runLater(economyLoader::load, 1)<br/>经济延迟 1 tick 装载<br/>(QuickShop.java:869)
    S->>JB: onDisable()
    JB->>Q: quickShop.onDisable()<br/>(QuickShopBukkit.java:83)
    Q->>Q: 14 步关停序列<br/>(见 §9)
    JB->>JB: 注销监听/服务/频道<br/>platform.shutdown()<br/>(QuickShopBukkit.java:85-91)
```

**Bootstrap.java 的真实身份**：`bootstrap/Bootstrap.java:13-34` 只是双击 jar 时的 Swing 弹窗提示（"QuickShop is a Paper plugin"），被设为 Shade 后 Manifest 主类（根 pom.xml:301）以防用户误执行。真正的引导在 `QuickShopBukkit`。

---

## 2. onLoad() 早期启动序列（QuickShop.java:403-435）

```mermaid
flowchart TD
    A["onLoad() 开始"] --> B["instance = this 单例赋值<br/>(QuickShop.java:405)"]
    B --> C["registerService()<br/>Bukkit ServicesManager 注册 QuickShopProvider<br/>优先级 High (:437-453)"]
    C --> D["bootError = null 重置<br/>Util.setPlugin(this)"]
    D --> E["initConfiguration()<br/>MainConfig + serverUniqueID 生成<br/>(QuickShop.java:487-509)"]
    E --> F["ReloadManager.register(this)<br/>注册热重载"]
    F --> G["new BuildInfo(BUILDINFO 资源)"]
    G --> H{"runtimeCheck(ON_LOAD)<br/>环境自检 (:455-485)"}
    H -->|DISABLE_PLUGIN| H1["禁用插件 return"]
    H -->|STOP_WORKING| H2["setupBootError + 注销监听<br/>+ 禁用 TabCompleter"]
    H -->|通过| I["new PrivacyController"]
    I --> J["new SimpleRegistryManager<br/>注册 ITEM_EXPRESSION 注册表"]
    J --> K["new MetricManager + initPlatforms"]
    K --> L["new FastPlayerFinder<br/>UUID↔名称映射"]
    L --> M["loadTextManager()<br/>SimpleTextManager 加载翻译<br/>失败→禁用插件 (:511-522)"]
    M --> N["InventoryWrapperRegistry.register<br/>BukkitInventoryWrapperManager"]
    N --> O["onLoad 完成"]
```

---

## 3. onEnable() 主初始化序列（QuickShop.java:755-885）

42 步完整序列按阶段分组：

```mermaid
flowchart TD
    subgraph P1["阶段一：调度器与菜单框架 (759-791)"]
        A1["new FoliaLib(javaPlugin)"]
        A2{"服务器类型?"} -->|Folia| A3["FoliaChat/Click/Close 监听<br/>+ FoliaMenuHandler"]
        A2 -->|Paper| A4["Paper 三件套 + PaperMenuHandler"]
        A2 -->|Bukkit| A5["Bukkit 三件套 + BukkitMenuHandler"]
        A3 --> A6["new GuiConfig(this)"]
        A4 --> A6
        A5 --> A6
        A6 --> A7["MenuManager 注册 5 菜单<br/>History/Keeper/Browse/Trade/Staff"]
        A7 --> A8["registerService() 再次注册"]
    end

    subgraph P2["阶段二：自检与基础设施 (793-821)"]
        B1["runtimeCheck(ON_ENABLE)<br/>PerfMonitor 计时"]
        B2["initConfiguration()"]
        B3["loadErrorReporter()<br/>Rollbar (auto-report-errors 开关)"]
        B4["loadItemMatcher()<br/>work-type: 0/1/3 三选一<br/>+ ServiceInjector 可替换"]
        B5["new ItemMarker"]
        B6["loadRegistry()<br/>ItemExpression×4 处理器"]
        B7["SimpleShopItemBlackList"]
        B8["Util.initialize()"]
        B9["loadVirtualDisplayItem()<br/>失败→自动关 display-items 写盘"]
        B10["loadSignHooker()<br/>(per-player-shop-sign 开关)"]
        B11["initDatabase() 见 §4"]
        B12["异步 bakeCaches 用户名预热"]
    end

    subgraph P3["阶段三：领域装配 (823-860)"]
        C1["PermissionManager (static)"]
        C2["SimpleShopPermissionManager"]
        C3["registerDisplayAutoDespawn"]
        C4["PermissionChecker"]
        C5["loadCommandHandler<br/>SimpleCommandManager + 动态注册命令"]
        C6["new SimpleShopManager"]
        C7["SimpleRankLimiter"]
        C8["signUpdateWatcher / shopSaveWatcher<br/>start(0, 5min)"]
        C9["ShopLoader.loadShops()<br/>见 §5"]
        C10["bakeShopsOwnerCache (异步)"]
        C11["tagManager.loadAllFromDB()"]
        C12["QuickShopInteractionManager"]
        C13["registerListeners() 11 监听器"]
        C14["ControlPanelManager.initialize<br/>+ SimpleShopControlPanel"]
        C15["registerDisplayItem<br/>定时检查+DisplayProtectionListener"]
        C16["registerShopLock (shop.lock)"]
        C17["MsgUtil.clean()"]
        C18["registerUpdater (updater 开关)"]
    end

    subgraph P4["阶段四：延迟任务与集成 (866-885)"]
        D1["folia.runLater(economyLoader::load, 1)<br/>★ 经济延迟装载"]
        D2["registerTasks()<br/>Calendar/Sign/Log/OngoingFee/Purger"]
        D3["BungeeCord outgoing 频道"]
        D4["QSConfigurationReloadEvent"]
        D5["load3rdParty: PlaceholderAPI"]
        D6["runtimeCheck(AFTER_ON_ENABLE)"]
        D7["addHook WorldEdit/FWorldEdit<br/>canEnable→enable"]
    end

    P1 --> P2 --> P3 --> P4
```

**为什么经济延迟 1 tick**：给 Vault 等经济插件在 ServicesManager 注册的时间窗口；另有 `EconomySetupListener` 监听 `PluginEnableEvent` 在任何插件启用时重试 `economyLoader.load()`（QS/listener/EconomySetupListener.java:16-21），双保险解决加载顺序问题。

---

## 4. 数据库初始化（setupDatabase, QuickShop.java:1162-1212）

```mermaid
flowchart TD
    A["initDatabase()"] --> B["HikariUtil.createHikariConfig()<br/>读 config.yml database.properties<br/>全部透传 HikariCP (HikariUtil.java:13-31)"]
    B --> C{"database.mysql ?"}
    C -->|true| D["MYSQL 模式<br/>jdbc:mysql://host:port/db?useSSL<br/>设置 user/password (:1175-1183)"]
    C -->|false| E["H2 模式<br/>Driver.load()<br/>jdbc:h2:数据目录/shops;MODE=MYSQL<br/>执行 SET MODE=MYSQL (:1187-1196)"]
    D --> F["new SQLManagerImpl(HikariDataSource)<br/>QuickShop-Hikari-SQLManager"]
    E --> F
    F --> G["setExecutorPool(<br/>QuickExecutor.getHikaricpExecutor())<br/>★ 每任务一个虚拟线程"]
    G --> H["new SimpleDatabaseHelperV2<br/>构造器内: checkTables →<br/>checkColumns → checkDatabaseVersion<br/>(SimpleDatabaseHelperV2.java:62-71)"]
    H --> I["DatabaseIOUtil.performBackup(startup)<br/>按 backup-policy 配置 CSV→zip"]
    I --> J["返回 true 成功"]
    H -->|版本>20| K["IllegalStateException<br/>拒绝降级 (:73-82)"]
    C -->|异常| L["bootError = BuiltInSolution.databaseError()<br/>禁用插件"]
```

连接池默认参数（config.yml:296-308）：maxPoolSize=10、connectionTimeout=60s、prepStmtCache=250。

### 4.1 数据库版本迁移链（DatabaseUpgrade.upgrade, SimpleDatabaseHelperV2.java:1053-1163）

```mermaid
flowchart LR
    V3["版本 ≤3<br/>抛异常不支持"] --> V8["<9<br/>purgeIsolated<br/>清孤立数据"]
    V8 --> V10["9→10<br/>加 benefit 列"]
    V10 --> V11["10→11<br/>players 表加 cachedName"]
    V11 --> V12["11→12<br/>UUID 字段扩 VARCHAR(128)"]
    V12 --> V13["12→13<br/>全表转 utf8mb4"]
    V13 --> V14["13→14<br/>world 扩到 255"]
    V14 --> V15["14→15<br/>log_purchase 建 3 索引"]
    V15 --> V16["15→16<br/>强制 zip 备份"]
    V16 --> V19["16/17/18→19<br/>加 encoded 列"]
    V19 --> V20["16-19→20<br/>加 shop_state 列<br/>旧 type=2 迁为 state"]
```

> 每步迁移前 `fastBackup()` 先执行 `performBackup("database-upgrade")`（:187-195）。全新库直接写版本 20（:88-90）。

---

## 5. 商店加载流程（ShopLoader.java:77-141）

```mermaid
flowchart TD
    A["ShopLoader.loadShops(world=null)"] --> B["databaseHelper.listShops(world, deleteCorruptShops)<br/>三表 INNER JOIN<br/>data⋈shops⋈shop_map<br/>(SimpleDatabaseHelperV2.java:526-553)"]
    B --> C["逐条 loadShopFromShopRecord<br/>→ supplyAsync(workStealingPool)<br/>并行度=database.loader-threads"]
    C --> D["loadSingleShop (:144-215)"]
    D --> E{"世界已加载?<br/>(ShopLoader.java:146-156)"}
    E -->|否| F["LOAD_AFTER_CHUNK_LOADED<br/>等区块加载"]
    E -->|是| G{"区块已加载?"}
    G -->|否| F
    G -->|是| H["加入 shopsLoadInNextTick<br/>主线程统一 loadShop (:108-114)"]
    D --> I["new DataRawDatabaseInfo(dataRecord)<br/>反序列化: shopType/shopState/物品<br/>(platform.decodeStack 优先,失败走旧格式)<br/>permissions JSON / extra YAML (:301-376)"]
    I --> J["new ContainerShop(...)"]
    J --> K["shopNullCheck: item非AIR/owner非空<br/>(shopNullCheck :251-278)"]
    K --> L["registerShop(shop, persist=false)<br/>只进内存查找表"]
    L --> M["ChunkListener.onChunkLoad<br/>(ChunkListener.java:30-47)"]
    F --> M
    M --> N["shop.handleLoading()<br/>(ContainerShop.java:1418-1450)"]
    N --> O{"getInventory()==null?<br/>容器丢失"}
    O -->|是| P["delete-corrupt-shops 开关<br/>→ deleteShop 或卸载"]
    O -->|否| Q["ShopLoadEvent(可取消)"]
    Q --> R["isLoaded = true"]
    R --> S["checkDisplay() 生成展示物"]
    S --> T["可选 scheduleSignUpdate"]
```

内存查找表结构：`Map<world, Map<ShopChunk, Map<Location, Shop>>>`（AbstractShopManager.java:71），配合 Guava Cache 3 分钟过期（SimpleShopCache.java:46）三级查找。

---

## 6. 玩家交互识别流程（点击 → 行为）

```mermaid
sequenceDiagram
    autonumber
    participant P as 玩家
    participant PL as PlayerListener
    participant IM as QuickShopInteractionManager
    participant B as InteractionBehavior
    participant SM as SimpleShopManager

    P->>PL: PlayerInteractEvent
    PL->>PL: 过滤: 主手 only (:93)<br/>冒险模式去重 + 125ms 限流 (:97-108)
    PL->>PL: searchShop(block, player) (:137-178)
    Note over PL: 墙牌→attached 方块商店(标记SIGN)<br/>双箱→非店主强制另一半 (:157-170)<br/>容器无商店→CONTAINER<br/>其他→SHOPBLOCK
    PL->>IM: interaction(event, click)
    IM-->>PL: InteractionType<br/>(12 种: 潜行/站立×左/右×店/容器/牌)
    PL->>IM: behavior(type)
    IM-->>PL: Behavior (interaction.yml 映射)
    alt 无商店且手持物品
        PL->>B: TradeInteraction.handle
        B->>SM: 弹聊天提问价格
        P->>SM: 聊天输入价格
        SM->>SM: handleChat → actionCreate (:918→492)
    else 已有商店
        PL->>B: TradeDirect / TradeUI / ControlPanel
        B->>SM: buyFromShop / sellToShop
    end
```

---

## 7. 核心数据路径一：购买交易（actionSelling）

### 7.1 完整时序图

```mermaid
sequenceDiagram
    autonumber
    participant P as 买家(主线程)
    participant SM as SimpleShopManager
    participant E as QSEconomyTransaction
    participant TS as SimpleTradeService
    participant IVT as SimpleInventoryTransaction
    participant V as VaultProvider
    participant ML as MetricListener(异步)
    participant DB as 数据库(虚拟线程)

    P->>SM: actionSelling(...) (SimpleShopManager.java:561)
    SM->>SM: 权限 quickshop.other.use /<br/>BuiltInShopPermission.PURCHASE / 自交易检查 (:566-574)
    SM->>SM: shopIsNotValid 一致性检查 (:473-489)
    SM->>SM: calculateTax → ShopEnhancedTaxEvent (:591-594)
    SM->>SM: ShopPurchaseEvent 可取消可改价 (:597-603)
    SM->>E: builder().from(买家).to(店主)<br/>.fromTax/.toTax/.taxer/.benefit.build() (:606-622)
    Note over E: 构造期计算:<br/>amountAfterTax=amount×(1-toTax)<br/>fromAmount=amount×(1+fromTax)<br/>totalTax=toTax+fromTax<br/>触发 EconomyTransactionEvent (:79-124)
    E-->>SM: completable() 余额预检 (:340)
    SM->>TS: shop.sell → executeBuyFromShop (:629)
    TS->>TS: normalizeAmount + previewBuyFromShop<br/>冻结/类型/库存/背包空间 (:259-321)
    TS->>IVT: from(箱).to(背包).item.amount.build()<br/>failSafeCommit() (SimpleTradeService.java:113-121)
    IVT->>IVT: RemoveItemOperation(箱).commit<br/>快照+分批移除
    IVT->>IVT: AddItemOperation(背包).commit<br/>快照+分批添加
    Note over IVT: 任一失败→rollback LIFO<br/>→restoreSnapshot 恢复双方库存
    SM->>E: transaction.safeCommit() (:664)
    E->>V: EconomyWithdrawOperation(买家, fromAmount)
    V->>V: balance 预检 + withdrawPlayer<br/>(VaultProvider.java:278-309)
    E->>V: EconomyDepositOperation(店主, amountAfterTax)
    Note over E: 有分成时: 逐受益人按比例 deposit<br/>余额给 owner (:433-455)
    E->>V: checkTax → Deposit(税号, totalTax) (:462-477)
    SM->>SM: sendPurchaseSuccess 收据 (:977-990)
    SM->>ML: ShopSuccessPurchaseEvent (:671)
    ML->>DB: insertMetricRecord → qs_log_purchase<br/>(MetricListener.java:92-110)
    SM->>SM: notifyBought 店主离线消息(异步) (:1174-1203)
```

### 7.2 变量级数据变换表（购买 64 个钻石 @ 10 元/个，税率 5% 场景）

| 步骤 | 变量 | 类型 | 值/状态变化 | 代码位置 |
|------|------|------|------------|---------|
| 入口 | `amount` | `int` | `64`（一组，TradeDirect 固定 1 stack） | ShopUtil.java:294 |
| 税计算 | `TaxRates` | `TaxRates` | `{from: 0.05, to: 0.05}`（basic provider 输出） | SimpleShopManager.java:591 |
| 组装交易 | `amount` | `BigDecimal` | `64 × 10 = 640.00` | SimpleShopManager.java:616-622 |
| 构造期 | `amountAfterTax` | `BigDecimal` | `640 × 0.95 = 608.00` | QSEconomyTransaction.java:93-97 |
| 构造期 | `toTax` | `BigDecimal` | `640 - 608 = 32.00` | :99 |
| 构造期 | `fromAmount` | `BigDecimal` | `640 × 1.05 = 672.00`（fromTax=0 时即 640） | :102-106 |
| 库存预检 | `TradePreview` | `TradePreview` | `{ok: true, matchStock: 64, matchSpace: 64}` | SimpleTradeService.java:259-321 |
| 库存过户 | `snapshot` | `Object` | 箱/背包各一份不可变快照 | RemoveItemOperation.java:42 |
| 经济提交 | `processingStack` | `Deque<Operation>` | `[Withdraw(买家,672)] → push → +[Deposit(店主,608)]` | QSEconomyTransaction.java:549 |
| 审计 | `ShopMetricRecord` | bean | `{buyer, type: PURCHASE_BUYING_SHOP, amount: 64, money: 640, tax: 32}` | SimpleDatabaseHelperV2.java:480-502 |
| 离线消息 | `content` | `String(JSON)` | 序列化收据入 qs_message 表 | MsgUtil |
| 复杂度 | — | — | 查找 O(1)（哈希表）；库存计数 O(n)（遍历槽位） | Util.countItems |

### 7.3 库存事务提交/回滚状态机

```mermaid
stateDiagram-v2
    [*] --> BUILT: builder().build()
    BUILT --> COMMITTING: failSafeCommit()
    COMMITTING --> COMMITTING: executeOperation(op) 成功则入栈
    COMMITTING --> ROLLING_BACK: 任一 op 失败
    COMMITTING --> COMMITTED: 全部 op 成功
    ROLLING_BACK --> ROLLING_BACK: LIFO 弹栈逐个 op.rollback()
    ROLLING_BACK --> ROLLED_BACK: 栈空恢复完成 restoreSnapshot 兜底
    ROLLED_BACK --> [*]: 返回 false
    COMMITTED --> [*]: 返回 true
```

证据：SimpleInventoryTransaction.java:69-88（commit）、:154-163（failSafeCommit 触发 rollback）、:189-221（LIFO 弹栈 + 快照恢复）。

### 7.4 经济事务状态机（含已知缺陷标注）

```mermaid
stateDiagram-v2
    [*] --> INIT: builder().build() 计算税额并触发事件
    INIT --> PRECHECK: safeCommit()
    PRECHECK --> CANCELLED: ⚠ 未调 onFailed (:393-397)
    PRECHECK --> FAILED_EARLY: completable()=false 余额不足→onFailed
    PRECHECK --> WITHDRAWING: 预检通过
    WITHDRAWING --> ROLLBACK_ALL: withdraw 失败
    WITHDRAWING --> DEPOSITING: from 扣款成功入栈
    DEPOSITING --> DEPOSITING: 分成模式下逐受益人入账
    DEPOSITING --> ROLLBACK_ALL: 任一 deposit 失败→onFailed
    DEPOSITING --> TAXING: to 方全部成功→onSuccess
    TAXING --> COMMITTED: ⚠ totalTax>0 时 return 写反不入账 (:462-464)
    TAXING --> COMMITTED: taxer 为空或税失败仅 onTaxFailed 不回滚
    ROLLBACK_ALL --> COMPENSATED: rollback(true) LIFO 反向操作
    COMPENSATED --> [*]: 返回 false
    COMMITTED --> [*]: 返回 true
```

> ⚠ 两处疑似缺陷（代码现状）：`checkTax` 守卫条件 `if(totalTax > 0) return;`（QSEconomyTransaction.java:464）与注释意图相反；`onCommit` 取消分支未调用 `callback.onFailed`（:393-397）。静态分析结论，实际影响需结合调用方确认。

---

## 8. 商店对象状态机

### 8.1 类型 × 状态双轴（ContainerShop.java:998-1024, 1173-1176）

```mermaid
stateDiagram-v2
    direction LR
    state Type {
        [*] --> SELLING
        SELLING --> BUYING: silentbuy 三阶段事件
        BUYING --> SELLING: silentsell
        SELLING --> FROZEN_TYPE: freeze
        BUYING --> FROZEN_TYPE: freeze
        FROZEN_TYPE --> SELLING: unfreeze
    }
    state ShopState {
        [*] --> ACTIVE
        ACTIVE --> STATE_FROZEN: freeze 命令
        STATE_FROZEN --> ACTIVE: unfreeze
    }
    note right of Type
        类型轴与状态轴相互独立
        isFrozen() = shopType.isTradingBlocked()
        或 !shopState.isTradingAllowed()
        (ContainerShop.java:1173-1176)
        任一轴冻结即阻断交易
    end note
```

### 8.2 持久化生命周期（脏标记机制）

```mermaid
stateDiagram-v2
    [*] --> MEMORY_ONLY: ShopLoader 加载 registerShop persist=false
    MEMORY_ONLY --> PERSISTED: createData→createShop→createShopMap 三步入库
    PERSISTED --> DIRTY: 任一 setter 调用 setDirty()
    DIRTY --> SAVING: ShopDataSaveWatcher 每 5 分钟触发 Shop.update()
    SAVING --> PERSISTED: updateShop 写库成功 CAS 防重入
    SAVING --> DIRTY: 写库失败下轮重试
    PERSISTED --> DELETED: deleteShop 全流程
    DELETED --> [*]
```

### 8.3 区块级加载/卸载（isLoaded）

```mermaid
stateDiagram-v2
    [*] --> UNLOADED: 在内存表但区块未加载
    UNLOADED --> LOADED: onChunkLoad→handleLoading 生成展示物
    LOADED --> UNLOADED: onChunkUnload→handleUnloading 移除展示物
    note right of LOADED
        交易与展示物操作
        仅在 LOADED 态有效
        handleLoading/Unloading
        均有幂等保护
        (ContainerShop.java:1421,1460)
    end note
```

---

## 9. onDisable() 关停序列（QuickShop.java:1241-1328）

```mermaid
flowchart TD
    A["onDisable 开始"] --> B["停 calendarWatcher"]
    B --> C["注销 RollbarErrorReporter"]
    C --> D["注销 PlaceholderAPI"]
    D --> E["卸载全部 loadedShops<br/>(:1259-1262)"]
    E --> F["BungeeListener 通知取消+注销"]
    F --> G["停 shopSaveWatcher"]
    G --> H["★ 脏商店持久化:<br/>收集 isDirty 商店 update() Future<br/>CompletableFuture.allOf<br/>.orTimeout(15s).join() (:1272-1283)"]
    H -->|超时 CompletionException| I["逐店 updateSync() 同步兜底<br/>失败仅告警 (:1285-1300)"]
    H -->|15s 内完成| J["shopManager.clear()<br/>清理展示物/副本"]
    I --> J
    J --> K["virtualDisplayItemManager.unload()"]
    K --> L["关 logWriter 刷盘"]
    L --> M["folia.cancelAllTasks()"]
    M --> N["updateWatcher.uninit"]
    N --> O["unload3rdParty: PAPI/SignHooker"]
    O --> P["EasySQL.shutdownManager<br/>关闭连接池 (:1324-1327)"]
```

---

## 10. 线程模型全景

```mermaid
graph TB
    subgraph POOLS["QuickExecutor 线程池清单 (quickshop-common QuickExecutor.java:13-36)"]
        V["HIKARICP_EXECUTOR<br/>★ 每任务一个虚拟线程<br/>命名 QuickShop-Database-Worker<br/>承载全部 SQL"]
        SS["SHOP_SAVE_EXECUTOR<br/>work-stealing 并行度 2<br/>商店保存"]
        CM["COMMON_EXECUTOR<br/>cachedThreadPool<br/>通用异步"]
        P1["PRIMARY_PROFILE_IO<br/>work-stealing 16<br/>玩家档案 IO"]
        P2["SECONDARY_PROFILE_IO<br/>work-stealing 2"]
        HQ["SHOP_HISTORY_QUERY<br/>ThreadPoolExecutor(1,2)"]
    end

    subgraph SCHED["FoliaLib 统一调度器"]
        MT["主线程/交易执行<br/>actionBuying/Selling"]
        RT["区域线程(方块操作)<br/>runAtLocation<br/>setSignText/actionCreate"]
        AT["异步定时<br/>runTimerAsync: 7 个 watcher"]
    end

    subgraph RULES["线程纪律"]
        R1["Util.ensureThread 断言<br/>主线程校验"]
        R2["交易在主线程<br/>Vault 同步阻塞调用"]
        R3["SQL 全异步虚拟线程<br/>executeFuture/executeAsync"]
        R4["消息通知 asyncThreadRun"]
    end

    MT --> RULES
    RT --> RULES
    AT --> RULES
    MT -->|"SQL 提交"| V
    AT -->|"商店保存"| SS
    MT -->|"档案查询"| P1
```

**要点**：SQL 全部跑在 Java 21 虚拟线程上（QuickExecutor.java:33-36）；交易（经济+库存）在主线程同步完成保证原子性；方块相关操作经 FoliaLib `runAtLocation` 调度到正确区域线程。

---

## 11. 定时任务清单（watcher/）

| Watcher | 周期 | 线程 | 职责 | 证据 |
|---|---|---|---|---|
| ShopDataSaveWatcher | 5 分钟 | 异步 | 扫描 isDirty 商店批量落库，saveTask.isDone() 防重入 | QuickShop.java:844-845 |
| SignUpdateWatcher | 10 tick | 异步 | 队列刷新木牌文本，单次限时 50ms 防卡顿 | QuickShop.java:1122 |
| LogWatcher | 10 tick | 异步 | 内存日志队列刷 qs.log，超 file-size MB gzip 轮转 | QuickShop.java:1123-1125 |
| CalendarWatcher | 1 秒 | 异步→主线程 | 检测跨秒/分/时/日/周/月/年发 CalendarEvent，≥HOUR 才落盘缓存 | QuickShop.java:1121-1129 |
| OngoingFeeWatcher | 配置 ticks | 异步→主线程交易 | 向店主收持续费用，余额不足删店 | QuickShop.java:1227-1239 |
| DisplayAutoDespawnWatcher | 配置 checkTime | **主线程** | 按玩家距离动态生成/移除展示物 | QuickShop.java:981-984 |
| UpdateWatcher | 1 小时 | 异步 | 检查更新，通知 quickshop.alerts 权限玩家 | UpdateWatcher.java:26-38 |

---

## 12. 生命周期钩子链（Bukkit 事件 → QuickShop 内部）

| 钩子 | 注册顺序 | 处理逻辑 | 代码位置 |
|---|---|---|---|
| onLoad | 1st | libby→平台→QuickShop.onLoad 13 步 | QuickShopBukkit.java:56-76 |
| onEnable | 2nd | 冲突检查→QuickShop.onEnable 42 步 | QuickShopBukkit.java:96-110 |
| ServiceRegisterEvent | 运行时 | 经济 provider 热插拔重载 | VaultProvider.java:316-332 |
| PluginEnableEvent | 运行时 | 经济缺失时重试装载 | EconomySetupListener.java:16-21 |
| QSConfigurationReloadEvent | reload 时 | compat 模块自动 reloadConfig+init | CompatibilityModule.java:97-103 |
| onDisable | last | 14 步关停+15s 超时兜底保存 | QuickShop.java:1241-1328 |

---

## 13. 错误处理与容错总表

| 流程步骤 | 可能失败点 | 处理方式 | 恢复策略 | 代码位置 |
|---------|-----------|---------|---------|---------|
| libby 装库 | 网络不可达 | IllegalStateException | 首次安装需联网，之后走本地 lib/ | QuickShopBukkit.java:222-223 |
| 平台检测 | Spigot/未知 | 抛异常禁插件 | 引导用户换 Paper | QuickShopBukkit.java:243-258 |
| 环境自检 | 版本/依赖不符 | BootError 优雅降级 | 保留 /qs paste 诊断 | QuickShop.java:469-480 |
| 数据库连接 | 连接失败 | bootError=databaseError | 禁用插件提示检查配置 | QuickShop.java:1205-1211 |
| 库存交易 | 背包满/物品不匹配 | failSafeCommit→rollback | LIFO 快照恢复 | SimpleInventoryTransaction.java:154-221 |
| 经济交易 | 余额不足/provider异常 | safeCommit→rollback | LIFO 反向操作补偿 | QSEconomyTransaction.java:370-379 |
| 商店保存 | SQL 失败 | 留 dirty 下轮重试 | 5 分钟 watcher 兜底 | ShopDataSaveWatcher.java:29-37 |
| 关服保存 | 15s 超时 | 逐店 updateSync | 同步兜底+告警 ID/位置 | QuickShop.java:1285-1300 |
| 建店费用 | 扣费失败 | 不建店 | safeCommit 保证不丢钱 | SimpleShopManager.java:823-835 |
| 容器消失 | getInventory==null | 删除或卸载商店 | delete-corrupt-shops 配置 | ContainerShop.java:1426-1436 |
| 错误上报 | 主线程异常 | 拒绝上报+去重 | 隐私审查后 Rollbar | RollbarErrorReporter.java:68-140 |

**缺乏容错标记**：经济交易的两段提交中「库存已过户但经济失败」路径仅靠日志提示回滚风险（SimpleShopManager.java:659-667），无自动逆向库存过户——依赖 failSafeCommit 之后的 safeCommit 顺序设计（库存先失败则不会动钱；钱失败时库存已过户需人工介入），是已知架构权衡。
