# QuickShop-Hikari 项目架构报告

> 生成自 codebase-analyzer｜分析时间：2026-08-16｜分析模式：采样分析（663 个 Java 文件，>500 判定为大型项目）
> 所有论断均附 `文件:行号` 证据。路径缩写：`QS/` = `quickshop-bukkit/src/main/java/com/ghostchu/quickshop/`，`API/` = `quickshop-api/src/main/java/com/ghostchu/quickshop/api/`。

---

## 1. 项目快照

| 项 | 值 |
|---|---|
| 项目名 | quickshop-hikari `6.3.0.0-SNAPSHOT-6`（根 pom.xml:9） |
| 类型 | Minecraft Paper 服务端商店插件（AGPL/GPL v3 双许可，pom.xml:100-111） |
| 语言/JDK | Java 21（pom.xml:16） |
| 构建 | Maven 多模块 + Takari Lifecycle + Shade 重定位（pom.xml:163-418） |
| 目标平台 | 仅 Paper（Spigot 已拒绝启动，QS/QuickShopBukkit.java:243-252）；Folia 兼容（plugin.yml `folia-supported: true`） |
| 代码规模 | 663 个 Java 文件（api 177 / bukkit 357 / addon 62 / compat 52 / common 12 / platform 3） |
| 测试 | **0 个测试**（无 src/test、无 @Test、无 surefire/jacoco 配置） |
| 贡献者 | Ghost_chu (12215 commits)、sandtechnology (1109)、creatorfromhell (180) 等，GitHub Actions + Mergify 自动化 |
| 分支策略 | 单主干 `hikari`（+维护分支 `cleanup`），PR 合并经 Mergify |

---

## 2. Maven 多模块架构

### 2.1 模块依赖图

```mermaid
graph TD
    subgraph AGG["quickshop-hikari 根聚合 pom"]
        direction TB
        COMMON["quickshop-common<br/>12 文件｜纯工具库<br/>CommonUtil/JsonUtil/QuickExecutor/GeoUtil"]
        API["quickshop-api<br/>177 文件｜公开 API 契约<br/>Shop/ShopManager/事件/经济接口"]
        PLAT_IF["platform/quickshop-platform-interface<br/>Platform 抽象接口"]
        PLAT_PAPER["platform/quickshop-platform-paper<br/>PaperPlatform 实现（3 文件）"]
        BUKKIT["quickshop-bukkit<br/>357 文件｜核心实现<br/>主类/商店/数据库/经济/命令/监听器"]
        COMPAT_COMMON["compatibility/common<br/>CompatibilityModule 抽象基类"]
    end

    subgraph EXTJARS["独立发布的扩展 jar（shade 时全部打进主 jar）"]
        direction TB
        ADDONS["addon/* 14 个模块<br/>discount/displaycontrol/dynmap/<br/>list/plan/limited/bluemap/<br/>discordsrv/quests/..."]
        COMPATS["compatibility/* 30 个模块<br/>worldguard/towny/lands/residence/<br/>plotsquared/slimefun/griefprevention/..."]
    end

    COMMON --> API
    API --> PLAT_IF
    PLAT_IF --> PLAT_PAPER
    COMMON --> PLAT_IF
    API --> BUKKIT
    PLAT_PAPER --> BUKKIT
    API --> COMPAT_COMMON
    API --> ADDONS
    COMPAT_COMMON --> COMPATS
    BUKKIT -.->|编译期不依赖扩展| ADDONS
    BUKKIT -.->|编译期不依赖扩展| COMPATS
```

依赖证据：quickshop-api/pom.xml 依赖 `quickshop-common`；quickshop-bukkit/pom.xml 依赖 `quickshop-api` 与 `quickshop-platform-paper`；platform-interface 依赖 `quickshop-common`。全部模块在根 pom.xml:472-524 的 `<modules>` 中聚合，Shade 插件（pom.xml:196-228）把 `com.ghostchu.quickshop.addon:*`、`com.ghostchu.quickshop.compatibility:*` 一并打进最终 fat-jar。

### 2.2 模块职责表

| 模块 | 文件数 | 角色 | 关键内容 |
|---|---|---|---|
| quickshop-common | 12 | 基础设施层（无 Bukkit 依赖） | `QuickExecutor`（6 个线程池，QuickExecutor.java:13-36）、`JsonUtil`、`CommonUtil`、`GeoUtil`（镜像测速） |
| quickshop-api | 177 | 公开契约层 | `Shop`、`ShopManager`、`EconomyProvider`、40+ 个自定义事件（api/event/）、`CommandManager`、`InventoryWrapper` |
| platform | 3 | 平台适配层 | `Platform` 接口（Platform.java）→ `PaperPlatform`（注册命令、物品编解码、SLF4J Logger） |
| quickshop-bukkit | 357 | 核心实现层 | 主类 `QuickShop`（1447 行）、`ContainerShop`、`SimpleShopManager`、数据库、经济、命令、监听器 |
| addon/* | 62 | 官方功能扩展 | 折扣码、地图标记（dynmap/bluemap/squaremap/pl3xmap）、Discord、排行、迁移器 |
| compatibility/* | 52 | 第三方插件兼容 | 30 个区域保护/天空岛/物品插件的集成桥（全部继承 `CompatibilityModule`） |

### 2.3 构建管线（Maven Shade）

```mermaid
flowchart LR
    A["mvn package<br/>-T 1.5C -P github"] --> B["git-commit-id-plugin<br/>写入 git.* 属性"]
    B --> C["takari-lifecycle<br/>编译 Java 21 + 源码 jar"]
    C --> D["maven-shade-plugin"]
    D --> E["重定位 12 组包<br/>net.tnemc→shade.tne<br/>io.vertx→shade.io.vertx 等<br/>pom.xml:229-297"]
    E --> F["Manifest 主类=<br/>bootstrap.Bootstrap<br/>pom.xml:298-303"]
    F --> G["QuickShop-Hikari-*.jar<br/>含全部 addon/compat 子 jar"]
```

---

## 3. 技术栈全景

```mermaid
graph TB
    subgraph RUNTIME["Minecraft 服务端"]
        PAPER["Paper 1.20-1.21.11"]
        FOLIA["Folia 多线程区域调度"]
    end

    subgraph QSX["quickshop-hikari"]
        direction TB
        subgraph DEPS["核心依赖（libby 运行时下载，QuickShopBukkit.java:163-227）"]
            EASYSQL["EasySQL + HikariCP<br/>数据库访问"]
            H2["H2 MODE=MYSQL 或 MySQL"]
            BOOSTED["BoostedYAML<br/>自动版本迁移配置"]
            FOLIALIB["FoliaLib<br/>统一调度器"]
            ADVENTURE["Adventure + MiniMessage<br/>现代文本组件"]
            LIBBY["libby<br/>运行时依赖下载"]
        end
        subgraph INTEG["集成依赖（softdepend）"]
            VAULT["Vault 1.x / VaultUnlocked 2.x<br/>经济"]
            PE["PacketEvents / ProtocolLib<br/>虚拟展示物发包"]
            NBT["item-nbt-api<br/>物品标记"]
            PAPI["PlaceholderAPI"]
            WE["WorldEdit/FAWE"]
        end
        subgraph FW["自研/同作者框架"]
            MENUCORE["net.tnemc MenuCore<br/>GUI 菜单框架"]
            TNEITEM["net.tnemc item 抽象<br/>PaperItemStack/BukkitItemStack"]
            SRL["simplereloadlib<br/>热重载"]
            CROWDIN["crowdinota<br/>翻译 OTA"]
        end
    end

    EASYSQL --> H2
    QSX --> PAPER
    QSX --> FOLIA
    VAULT --> QSX
    PE --> QSX
```

---

## 4. quickshop-bukkit 包结构全景（357 文件）

```mermaid
graph TD
    ROOT["com.ghostchu.quickshop"] --> ENTRY["〈入口层〉<br/>QuickShopBukkit（JavaPlugin 外壳）<br/>QuickShop（核心大脑 1447 行）<br/>BootError/BuildInfo/ServiceInjector"]
    ROOT --> SHOP["〈领域层〉shop/<br/>ContainerShop 实体<br/>AbstractShopManager + SimpleShopManager<br/>ShopLoader/SimpleTradeService"]
    ROOT --> DB["〈数据访问层〉database/<br/>DataTables（12 张表枚举）<br/>SimpleDatabaseHelperV2（DAO）<br/>DatabaseIOUtil（备份）"]
    ROOT --> ECO["〈领域服务〉economy/<br/>EconomyLoader（延迟装载）<br/>QSEconomyManager<br/>provider/Vault + VaultUnlocked<br/>transaction/QSEconomyTransaction"]
    ROOT --> CMD["〈接口层〉command/<br/>QuickShopCommand（薄壳）<br/>SimpleCommandManager（60 子命令）<br/>subcommand/ + silent/"]
    ROOT --> LSN["〈接口层〉listener/<br/>15 个 Bukkit 监听器<br/>AbstractQSListener 基类"]
    ROOT --> MENU["〈表现层〉menu/<br/>5 个 MenuCore 菜单<br/>browse/history/trade/staff/keeper"]
    ROOT --> LOC["〈表现层〉localization/<br/>SimpleTextManager（4 层语言加载）<br/>postprocessing/ 4 后处理器"]
    ROOT --> CFG["〈基础设施〉config/<br/>MainConfig/GuiConfig/InteractionConfig"]
    ROOT --> REG["〈基础设施〉registry/ + permission/ + papi/"]
    ROOT --> UTIL["〈基础设施〉util/<br/>updater/reporter/matcher/<br/>paste/metric/logging"]
    ROOT --> WCH["〈后台任务〉watcher/<br/>7 个周期任务"]

    SHOP --> SHOP_SUB["cache/ display/ display/virtual/<br/>display/virtual/packet/ 下 packetevents 与 protocollib<br/>inventory/ operation/ interaction/ 下 interactions 与 behaviors<br/>sign/ tag/ tax/ history/ controlpanel/ datatype/"]
```

各包文件数佐证：`find quickshop-bukkit -type d`（54 个包目录）；`ShopLoader.java:47-454`、`ContainerShop.java`（约 1900 行）、`SimpleShopManager.java:107-1632`。

---

## 5. 运行时组件架构（QuickShop 主类管理器集群）

`QuickShop` 类（QS/QuickShop.java:181）实现 `QuickShopAPI` 接口，是**服务定位器核心**，聚合 40+ 个管理器字段（字段声明见 QuickShop.java:184-350）：

```mermaid
graph TB
    subgraph SHELL["引导外壳层"]
        JB["QuickShopBukkit<br/>(JavaPlugin, plugin.yml main)<br/>libby 装库 + 平台检测"]
    end

    QS["QuickShop 核心类<br/>单例 instance (QuickShop.java:202)<br/>实现 QuickShopAPI + Reloadable"]

    subgraph DOMAIN["商店领域服务"]
        SM["SimpleShopManager<br/>商店 CRUD/交易/聊天输入"]
        SL["ShopLoader<br/>DB→内存并行加载"]
        TS["SimpleTradeService<br/>买卖四方法门面"]
        IT["QuickShopInteractionManager<br/>12 交互×6 行为路由"]
        TG["QuickShopTagManager"]
        TX["QuickShopTaxManager<br/>basic/progressive"]
        CP["SimpleShopControlPanelManager<br/>12 组件"]
    end

    subgraph INFRA["基础设施服务"]
        DH["SimpleDatabaseHelperV2<br/>+ DataTables + HikariCP"]
        EL["EconomyLoader →<br/>QSEconomyManager →<br/>Vault/VaultUnlocked Provider"]
        PM["PermissionManager<br/>(仅 Bukkit 直通)"]
        TM["SimpleTextManager<br/>i18n + Crowdin OTA"]
        PF["FastPlayerFinder<br/>UUID↔名称缓存"]
        IM["ItemMatcher 策略×3<br/>QuickShop/Bukkit/Modern"]
        VD["VirtualDisplayItemManager<br/>PacketEvents→ProtocolLib"]
    end

    subgraph TASKS["周期任务（FoliaLib 调度）"]
        W1["ShopDataSaveWatcher 5min"]
        W2["SignUpdateWatcher 10t"]
        W3["LogWatcher 10t"]
        W4["CalendarWatcher 1s"]
        W5["OngoingFeeWatcher"]
        W6["DisplayAutoDespawnWatcher"]
        W7["UpdateWatcher 1h"]
    end

    subgraph EXTS["扩展点"]
        SI["ServiceInjector<br/>ServicesManager 反向注入"]
        HK["Hook 注册表<br/>WorldEdit/FWorldEdit"]
        RG["SimpleRegistryManager<br/>ItemExpression×4 处理器"]
        PAPI["QuickShopPAPI ×4 前缀"]
    end

    JB -->|"onLoad/onEnable/onDisable 委托<br/>QuickShopBukkit.java:56-110"| QS
    QS --> DOMAIN
    QS --> INFRA
    QS --> TASKS
    QS --> EXTS
    SM --> TS
    SM --> DH
    SM --> EL
```

### 5.1 服务清单（节选 25 项，全部 40+ 项见 QuickShop.java:184-350 字段区）

| 服务字段 | 类 | 初始化点 | 职责 |
|---|---|---|---|
| shopManager | SimpleShopManager | QuickShop.java:834 | 商店增删查/交易/聊天价格输入 |
| shopLoader | ShopLoader | :847 | DB 并行加载商店进内存查找表 |
| databaseHelper | SimpleDatabaseHelperV2 | :1201 | 12 张表 DAO + 迁移链 |
| sqlManager | SQLManagerImpl (EasySQL) | :1184/1195 | SQL 执行器（虚拟线程池） |
| economyManager | QSEconomyManager | :222 | 多经济 provider 注册表 |
| economyLoader | EconomyLoader | :221 | 延迟 1 tick 装载 Vault 系（:869） |
| textManager | SimpleTextManager | :515 | 4 层语言文件加载 |
| permissionManager | PermissionManager (static) | :824 | 权限检查（仅 BUKKIT 提供者） |
| interactionManager | QuickShopInteractionManager | :855 | 交互类型→行为映射 |
| itemMatcher | 三选一策略 | :912-922 | 物品匹配比对 |
| virtualDisplayItemManager | VirtualDisplayItemManager | :932 | 数据包假实体展示 |
| rankLimiter | SimpleRankLimiter | :837 | 按权限组限制商店数量 |
| playerFinder | FastPlayerFinder | :430 | UUID↔玩家名缓存 |
| sentryErrorReporter | RollbarErrorReporter | :903 | 错误上报（带隐私审查） |
| updateManager | UpdateManager | :1103 | Modrinth/Nexus 更新源 |

---

## 6. 核心域类图

> 说明：类图中接口以 `<<interface>>` 标注；`Shop` 的 API 完整签名为泛型 `Shop<P, L>`（价格/位置泛型），图中省略泛型参数。

### 6.1 商店领域（API 接口 vs 实现）

```mermaid
classDiagram
    class Shop {
        <<interface>>
        +getLocation() Location
        +getPrice() double
        +getItem() ItemStack
        +getShopType() IShopType
        +getShopState() IShopState
        +isLoaded() boolean
        +isDirty() boolean
        +update() CompletableFuture
        +sell() TradeResult
        +buy() TradeResult
        +checkDisplay()
        +handleLoading()
        +handleUnloading()
    }
    class ContainerShop {
        -isLoaded boolean
        -dirty boolean
        -displayItem AbstractDisplayItem
        -runtimeRandomUniqueId UUID
        +setSignText()
        +update()
    }
    class ShopManager {
        <<interface>>
    }
    class AbstractShopManager {
        +registerShop(shop, persist)
        +unregisterShop(shop, persist)
        +getShop(loc) Shop
        +getShopIncludeAttached(loc) Shop
    }
    class SimpleShopManager {
        +actionBuying()
        +actionSelling()
        +actionCreate()
        +actionTrade()
        +handleChat()
        +deleteShop()
        +createShop()
    }
    class SimpleTradeService {
        +executeBuyFromShop()
        +executeSellToShop()
        +previewBuyFromShop()
        +previewSellToShop()
    }

    Shop <|.. ContainerShop
    ShopManager <|.. AbstractShopManager
    AbstractShopManager <|-- SimpleShopManager
    ContainerShop ..> SimpleTradeService : buy/sell 委托
    SimpleShopManager o-- "N" ContainerShop : 内存查找表
```

两级继承的注释证据：AbstractShopManager.java:55 *“extract from SimpleShopManager because it is too big”*。`ContainerShop.buy/sell` 委托 TradeService：ContainerShop.java:303、:1671。

### 6.2 库存事务（命令模式 + 快照回滚栈）

```mermaid
classDiagram
    class InventoryTransaction {
        <<interface>>
        +commit() boolean
        +rollback(continueOnFail)
        +failSafeCommit()
    }
    class SimpleInventoryTransaction {
        -operations Deque
        +executeOperation(op)
    }
    class Operation {
        <<interface>>
        +commit() boolean
        +rollback() boolean
    }
    class AddItemOperation {
        +commit()
        +rollback()
    }
    class RemoveItemOperation {
        +commit()
        +rollback()
    }
    class InventoryWrapper {
        <<interface>>
        +createSnapshot()
        +restoreSnapshot(snap)
        +addItem()
        +removeItem()
    }
    class InventoryWrapperManager {
        <<interface>>
        +mklink(loc) String
        +locate(symbolLink) InventoryWrapper
    }
    class BukkitInventoryWrapperManager {
        +mklink(loc) String
        +locate(symbolLink) InventoryWrapper
    }

    InventoryTransaction <|.. SimpleInventoryTransaction
    Operation <|.. AddItemOperation
    Operation <|.. RemoveItemOperation
    SimpleInventoryTransaction o-- "2..N" Operation : 操作栈
    AddItemOperation --> InventoryWrapper : 快照与回滚
    RemoveItemOperation --> InventoryWrapper
    InventoryWrapperManager <|.. BukkitInventoryWrapperManager
```

证据：SimpleInventoryTransaction.java:24-269（commit :69-88，rollback :189-221）；AddItemOperation.java:42-65；BukkitInventoryWrapperManager.java:85-101（mklink）。

### 6.3 经济子系统

```mermaid
classDiagram
    class EconomyManager {
        <<interface>>
        +registerProvider(name, provider)
        +useProvider(name)
        +provider() EconomyProvider
    }
    class QSEconomyManager {
        -providers Map
        -currentProvider String
    }
    class EconomyProvider {
        <<interface>>
        +deposit(account, world, currency, amount)
        +withdraw(account, world, currency, amount)
        +balance(account, world, currency)
        +valid() boolean
    }
    class VaultProvider {
        +valid() boolean
    }
    class VaultUnlockedProvider {
        +deposit(currency, amount) 多货币重载
    }
    class EconomyTransaction {
        <<interface>>
        +completable() boolean
        +safeCommit() boolean
        +rollback(continueOnFail)
    }
    class QSEconomyTransaction {
        -processingStack Deque
        -fromAmount BigDecimal
        -amountAfterTax BigDecimal
        -totalTax BigDecimal
        +commit(callback)
    }
    class EconomyDepositOperation {
        +rollback() withdraw补偿
    }
    class EconomyWithdrawOperation {
        +rollback() deposit补偿
    }
    class EconomyLoader {
        +load()
        +setup()
    }
    class BenefitProvider {
        <<interface>>
    }

    EconomyManager <|.. QSEconomyManager
    EconomyProvider <|.. VaultProvider
    EconomyProvider <|.. VaultUnlockedProvider
    QSEconomyManager o-- EconomyProvider : current
    EconomyTransaction <|.. QSEconomyTransaction
    QSEconomyTransaction o-- EconomyDepositOperation
    QSEconomyTransaction o-- EconomyWithdrawOperation
    EconomyDepositOperation --> EconomyProvider
    EconomyWithdrawOperation --> EconomyProvider
    EconomyLoader --> QSEconomyManager : 装配
    QSEconomyTransaction --> BenefitProvider : 收益分成
```

证据：QSEconomyManager.java:37-99；VaultProvider.java:46；EconomyLoader.java:53-118；QSEconomyTransaction.java:54（processingStack）、:79-124（税计算）、:389-460（commit）。**本版本无 TNE/MixedEconomy 旧抽象**，仅 Vault 系双实现。

### 6.4 展示子系统（数据包虚拟物品）

```mermaid
graph TD
    ADI["AbstractDisplayItem<br/>(QS/shop/display/AbstractDisplayItem.java:46)"]
    VDM["VirtualDisplayItemManager<br/>选包处理器 + entityId 分配<br/>(Integer.MAX_VALUE 递减)"]
    VDI["VirtualDisplayItem 泛型类<br/>预生成 spawn/meta/velocity/destroy 四包<br/>(VirtualDisplayItem.java:79-91)"]
    subgraph PKT["两套 PacketFactory（按版本+已装插件择优）"]
        PE["packetevents/<br/>PacketEventsHandler +<br/>PacketFactoryv1_20 / v1_21 / v1_21_6"]
        PL["protocollib/<br/>ProtocolLibHandler +<br/>PacketFactoryv1_20 / v1_21 / v1_21_10"]
    end
    DT{"DisplayType 配置"}
    VIRTUAL["VIRTUALITEM(2) 当前唯一内建可用"]
    CUSTOM["CUSTOM(900) 第三方 DisplayProvider 注入"]

    ADI --> DT
    DT -->|display-type=2| VIRTUAL
    DT -->|900| CUSTOM
    VIRTUAL --> VDM
    VDM -->|"优先 addHandler(PacketEvents)"| PE
    VDM -->|"回退 addHandler(ProtocolLib)"| PL
    VDM --> VDI
    CUSTOM --> SPI["ServiceInjector.getInjectedService<br/>(ContainerShop.java:320)"]
```

> 事实澄清：枚举中 `REALITEM(0)/ARMORSTAND(1)/ENTITY_DISPLAY(3)` 已被注释禁用，当前仅 VIRTUALITEM 与 CUSTOM 可用（API/shop/display/DisplayType.java）。

### 6.5 交互路由子系统（数据驱动策略）

```mermaid
graph LR
    subgraph DETECT["InteractionType（12 种组合）interactions/"]
        I1["Standing×Left/Right×Shop/Container/Sign"]
        I2["Sneaking×Left/Right×Shop/Container/Sign"]
    end
    subgraph BEHAV["InteractionBehavior（6 种）behaviors/"]
        B1["TradeInteraction 弹聊天提问"]
        B2["TradeDirect 一次一组"]
        B3["TradeDirectAll 全部"]
        B4["TradeUI 菜单交易"]
        B5["ControlPanelUI 菜单面板"]
        B6["ControlPanel 聊天面板"]
    end
    CFG["interaction.yml<br/>行为映射配置"]
    QIM["QuickShopInteractionManager<br/>(QuickShopInteractionManager.java:65-277)"]

    CFG --> QIM
    DETECT --> QIM
    QIM -->|"behavior(type) 查表"| BEHAV
```

### 6.6 持久化架构（三表分离 + 数据行去重）

```mermaid
erDiagram
    qs_shop_map ||--|| qs_shops : "坐标指向商店ID"
    qs_shops }o--|| qs_data : "data 外键"
    qs_shops ||--o{ qs_tags : "shop id"
    qs_shops ||--o{ qs_log_purchase : "shop id"
    qs_shops ||--o{ qs_log_changes : "审计"

    qs_shop_map {
        varchar world PK
        int x PK
        int y PK
        int z PK
        int shop FK
    }
    qs_shops {
        int id PK
        int data FK
    }
    qs_data {
        int id PK
        varchar owner
        text item
        text encoded
        int type
        varchar shop_state
        decimal price
        bit unlimited
        mediumtext permissions
        longtext extra
        text inv_symbol_link
        mediumtext benefit
    }
    qs_tags {
        varchar tagger PK
        int shop PK
        varchar tag PK
    }
    qs_log_purchase {
        int id PK
        int shop
        varchar buyer
        int amount
        decimal money
        decimal tax
    }
```

12 张表全表清单见 `QS/database/DataTables.java:25-178`：DATA/SHOPS/SHOP_MAP/MESSAGES/METADATA/PLAYERS/EXTERNAL_CACHE/LOG_PURCHASE/LOG_TRANSACTION/TAGS/LOG_CHANGES/LOG_OTHERS。**数据行去重设计**：`updateShop` 先 `queryDataId` 按字段查重（SimpleDatabaseHelperV2.java:921-941），存在则复用 data id（:884-888），多个商店可共享同一 data 行。

---

## 7. 扩展点架构（Addon / Compatibility / SPI）

```mermaid
graph TB
    subgraph EXTMECH["四条扩展通道"]
        A1["① Bukkit ServicesManager<br/>QuickShopProvider 注册<br/>(QuickShop.java:437-453)"]
        A2["② ServiceInjector 反向注入<br/>第三方注册更好实现替换默认<br/>(ServiceInjector.java:20-29)<br/>用于 ItemMatcher/DisplayProvider"]
        A3["③ 自定义事件 40+<br/>api/event/ 全部 PRE→MAIN→POST 三阶段"]
        A4["④ 独立 jar 模块<br/>addon/* + compatibility/*"]
    end

    subgraph COMPAT_TPL["CompatibilityModule 模板（30 个实例共用）"]
        CM["CompatibilityModule 抽象基类<br/>extends JavaPlugin implements Listener<br/>(compatibility/common/.../CompatibilityModule.java:26)"]
        CM_M["+onLoad() saveDefaultConfig + 取 API<br/>+onEnable() registerEvents + init()<br/>+abstract init() 子类必须实现<br/>+getShops(world,minX,minZ,maxX,maxZ)<br/>+recordDeletion(qUser,shop,reason)<br/>+onQuickShopReload() 自动热重载"]
        WG["worldguard/Main.java (225行)<br/>注册 quickshophikari-create/trade 旗标<br/>监听 ShopCreateEvent/ShopPurchaseEvent"]
        CM --> CM_M
        CM -->|"extends"| WG
    end

    subgraph ADDON_TPL["Addon 模板"]
        AD["Main extends JavaPlugin implements Listener<br/>QuickShop.getInstance() 取核心<br/>如 addon/discount/Main.java"]
    end

    A4 --> COMPAT_TPL
    A4 --> ADDON_TPL
```

---

## 8. 关键设计模式清单（含证据）

| 模式 | 应用位置 | 证据 |
|---|---|---|
| **单例 + 门面** | `QuickShop.instance` + `QuickShop.folia()/menu()` 静态门面 | QuickShop.java:202,390-398 |
| **服务定位器** | `ServiceInjector.getInjectedService()` 经 Bukkit ServicesManager 查第三方替代实现 | ServiceInjector.java:20-29；用于 ItemMatcher（QuickShop.java:921）、DisplayProvider（ContainerShop.java:320） |
| **双阶段引导** | QuickShopBukkit（libby+平台检测）→ QuickShop（业务初始化） | QuickShopBukkit.java:272-277 |
| **策略** | ItemMatcher 三选一（config `matcher.work-type`）；平台三套 MenuHandler；税 basic/progressive | QuickShop.java:914-920 |
| **命令模式（Operation 栈）** | 经济 Operation（Deposit/Withdraw）与库存 Operation（Add/Remove）均 commit→push→rollback LIFO | QSEconomyTransaction.java:529-557；SimpleInventoryTransaction.java:154-221 |
| **观察者（三阶段事件）** | shopType/shopState/setItem 等全部 PRE→MAIN(可取消)→POST | ContainerShop.java:1007-1024 |
| **模板方法** | AbstractQSListener 统一 register/unregister + 自动热重载；watcher 统一 start/stop | AbstractQSListener.java:9-28 |
| **注册表** | SimpleRegistryManager（ItemExpression×4）；DataTables 表枚举工厂 | QuickShop.java:424-425；DataTables.java:226-294 |
| **Builder** | CommandContainer、QSEconomyTransaction、SimpleInventoryTransaction、ShopMetricRecord | API/command/CommandContainer.java:27-53 等 |
| **延迟初始化** | 经济系统延后 1 tick（等 Vault 注册）+ ServiceRegisterEvent 监听重试 | QuickShop.java:866-869；EconomySetupListener.java:9-21 |
| **优雅降级** | BootError：环境检查失败不崩溃，保留 /qs 显示错误 | QuickShop.java:469-480,530-537 |
| **数据驱动配置** | interaction.yml 决定交互→行为映射 | QuickShopInteractionManager.java:242-277 |

---

## 9. 函数级调用链示例（购买一组商品）

```
PlayerListener.onClick(PlayerInteractEvent)                    QS/listener/PlayerListener.java:87
 ├─ searchShop(block, player)                                   PlayerListener.java:137
 ├─ interactionManager.interaction(event, click)                QuickShopInteractionManager.java:201
 ├─ interactionManager.behavior(type) → TradeDirect             QuickShopInteractionManager.java:144
 └─ TradeDirect.handle → ShopUtil.buyFromShop                   util/ShopUtil.java:270→294
     └─ SimpleShopManager.actionSelling                         SimpleShopManager.java:561
         ├─ 权限/自交易检查                                      :566-574
         ├─ taxManager.provider().calculateTax                   :591-594
         ├─ ShopPurchaseEvent (可取消)                           :597-603
         ├─ QSEconomyTransaction.builder().build()               :606-622
         ├─ transaction.completable() 余额预检                   :624
         ├─ shop.sell → SimpleTradeService.executeBuyFromShop   ContainerShop.java:1667 → SimpleTradeService.java:64
         │   ├─ previewBuyFromShop 预检                          SimpleTradeService.java:259-321
         │   └─ SimpleInventoryTransaction.failSafeCommit        :121
         │       ├─ RemoveItemOperation(箱) + AddItemOperation(背包)
         │       └─ 失败→rollback LIFO→restoreSnapshot           SimpleInventoryTransaction.java:189-221
         ├─ transaction.safeCommit() 经济转账                    QSEconomyTransaction.java:370-474
         │   ├─ EconomyWithdrawOperation(买家)
         │   ├─ EconomyDepositOperation(店主/受益人分成)
         │   └─ checkTax → EconomyDepositOperation(税号)          :462-477
         ├─ sendPurchaseSuccess 收据                              SimpleShopManager.java:977-990
         ├─ ShopSuccessPurchaseEvent                             :671
         │   └─ MetricListener.onPurchase → insertMetricRecord   MetricListener.java:92-110
         └─ notifyBought → 店主离线消息（异步）                   :1174-1203
```

> 跨层调用标注：ShopUtil（util 层）→ SimpleShopManager（领域层）→ TradeService/EconomyTransaction（领域服务）→ VaultProvider（外部集成），共 4 层；异步点 3 处（物品过户在方块线程调度、指标写库在虚拟线程、通知在 cachedThreadPool）。

---

## 10. 架构总评

1. **六边形倾向的插件架构**：quickshop-api 定义端口（Shop/Economy/Inventory/Interaction），quickshop-bukkit 提供适配器，第三方通过 ServicesManager/SPI 注入替代实现——扩展性设计成熟。
2. **并发模型复杂但自洽**：主线程（交易）+ Folia 区域线程（方块操作）+ 虚拟线程（SQL）+ work-stealing（保存/档案 IO），通过 FoliaLib 调度器统一（QuickShop.folia()）。
3. **一致性靠补偿而非锁**：经济/库存都是「快照 + Operation 栈 + LIFO 回滚」，数据库无行锁，审计依赖 qs_log_transaction 事后对账——适合插件生态但要求 safeCommit 纪律（所有业务入口确实统一走 safeCommit，见 04 报告分析）。
4. **技术债**：SimpleShopManager 1632 行已被拆出 AbstractShopManager 仍在膨胀；QuickShop 主类 1447 行承担服务定位器+装配器双职责；零测试覆盖是最大风险点（详见 03/04 报告）。
