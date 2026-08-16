# QuickShop-Hikari 工作流分析报告

> 生成自 codebase-analyzer｜分析时间：2026-08-16
> 路径缩写：`QS/` = `quickshop-bukkit/src/main/java/com/ghostchu/quickshop/`。

---

## 1. CI/CD 管线全景

项目使用 GitHub Actions（7 个工作流，.github/workflows/），覆盖构建→质检→发布→翻译分发→仓库维护全链路：

```mermaid
flowchart TB
    subgraph TRIG["触发源"]
        PUSH["push 或 PR 到 hikari 与 cleanup"]
        RELEASE["release published"]
        CRON["cron 每日 01:55"]
        MANUAL["workflow_dispatch 手动"]
    end

    subgraph CI["持续集成"]
        MAVEN["maven.yml — Maven CI<br/>JDK21 temurin + maven 缓存<br/>mvn package 排除 itemsadder<br/>-T 1.5C -P github<br/>上传 quickshop-snapshots artifact<br/>Codecov 步骤(实际无测试数据)"]
        QODANA["code_quality.yml — Qodana<br/>JetBrains 静态扫描<br/>PR 检出真实 HEAD 全历史"]
        STYLE["intellij_style.yml<br/>PR 自动 IDEA 格式化提交<br/>.contributing/QuickShop_Style.xml"]
        JDOC["javadoc.yml<br/>push hikari→gh-pages Javadoc"]
    end

    subgraph CD["持续发布"]
        PUBLISH["mc-publish.yml — release 触发<br/>① mvn 构建<br/>② mc-publish@v3.3 → Modrinth(id=ijC5dDkD)<br/>+ GitHub Discussion<br/>主 jar + 全部 Compat/Addon jar<br/>game-versions 1.18.2–1.21.1<br/>③ Maven 依赖图提交<br/>④ CrowdinCopyDeploy 构建翻译<br/>上传 Bunny CDN/S3<br/>⑤ CloudFlare 缓存清除"]
        OTA["sync-crowdinota.yml — 手动<br/>Crowdin 项目 695407 →<br/>CDN 上传+缓存清除"]
    end

    subgraph MAINT["仓库维护"]
        STALE["stale_issues.yml<br/>waiting-reply 14天 stale<br/>30天关闭"]
        MERGE["Mergify (.mergify.yml)<br/>PR 自动合并/回退"]
        CROWDIN_CFG["crowdin.yml 配置<br/>社区翻译同步"]
    end

    PUSH --> MAVEN
    PUSH --> QODANA
    PUSH --> STYLE
    PUSH --> JDOC
    RELEASE --> PUBLISH
    MANUAL --> OTA
    CRON --> STALE
    MAVEN --> MERGE
```

### 1.1 maven.yml 构建流水线细节

```mermaid
flowchart LR
    A["actions/checkout@v6"] --> B["setup-java@v5<br/>JDK 21 temurin<br/>cache: maven"]
    B --> C["mvn package<br/>--batch-mode --update-snapshots<br/>-T 1.5C -P github<br/>排除 compatibility:itemsadder<br/>(itemsadder 需外部仓库不可用)"]
    C --> D["upload-artifact@v5<br/>quickshop-snapshots<br/>所有 target/*.jar"]
    D --> E["codecov-action@v3<br/>⚠ 项目零测试<br/>该步骤上传空数据"]
```

证据：.github/workflows/maven.yml:6-33；排除 itemsadder 的原因见 maven.yml:24 注释与 pom.xml:508（itemsadder 依赖无 Maven 仓库）。

### 1.2 mc-publish.yml 发布流水线（release 触发）

```mermaid
flowchart TD
    A["GitHub Release published"] --> B["JDK21 mvn package"]
    B --> C["Kir-Antipov/mc-publish@v3.3"]
    C --> D1["Modrinth<br/>project: ijC5dDkD"]
    C --> D2["GitHub Discussion<br/>Announcements"]
    D1 --> E["上传文件:<br/>quickshop-bukkit/target/QuickShop-Hikari-*.jar<br/>+ 全部 Compat*/Addon* jar<br/>loaders: spigot/paper/purpur<br/>game-versions: 1.18.2–1.21.1<br/>java: 17/21<br/>声明约 25 个可选依赖"]
    D2 --> E
    E --> F["Maven 依赖图提交"]
    F --> G["CrowdinCopyDeploy-action uploads3<br/>项目 524354/分支 126<br/>构建翻译产物"]
    G --> H["CloudFlare 缓存清除<br/>运行时 Crowdin OTA 生效"]
```

证据：mc-publish.yml:29-99；运行时侧 SimpleTextManager.java:98（crowdinHost=`https://qshikari.b-cdn.net`）与 :79（拉取路径 `/hikari/crowdin/lang/%locale%/messages.yml`）构成「发布→CDN→服务器 OTA 拉取」闭环。

---

## 2. 开发协作工作流

```mermaid
flowchart LR
    A["开发者 fork/branch"] --> B["提交 PR → hikari"]
    B --> C{"自动化检查"}
    C --> D["Qodana 静态扫描"]
    C --> E["Maven CI 构建"]
    C --> F["IDEA 格式化机器人<br/>自动提交格式修正"]
    C --> G["CodeRabbit (.coderabbit.yaml)<br/>AI 代码审查"]
    D & E & F & G --> H["人工 review"]
    H -->|通过| I["Mergify 自动合并"]
    I --> J["push 触发 javadoc 发布"]
    J --> K["打 tag → GitHub Release"]
    K --> L["mc-publish 自动发布<br/>Modrinth + 翻译 CDN"]
    subgraph I18N["翻译子流程"]
        T1["Crowdin 社区翻译<br/>(44+ 语言)"] --> T2["CrowdinCopyDeploy<br/>定时/手动同步"]
        T2 --> T3["Bunny CDN + CloudFlare"]
        T3 --> T4["服务器运行时 OTA 拉取<br/>use-crowdin-ota: true"]
    end
```

---

## 3. 核心业务工作流一：商店创建（含完整决策树）

参与者：玩家、QuickShop、经济系统（Vault）、保护插件（兼容模块）、数据库。

```mermaid
flowchart TD
    A["玩家手持商品<br/>右键点击容器方块"] --> B["TradeInteraction.handle<br/>记录 pending 建店上下文<br/>(TradeInteraction.java:68)"]
    B --> C["弹聊天: 请输入价格"]
    C --> D["玩家聊天输入"]
    D --> E["ChatListener 捕获<br/>(ChatListener.java:27-46)<br/>→ SimpleShopManager.handleChat (:918)"]
    E --> F["actionCreate (:492)"]
    F --> G{"Util.parse 价格<br/>格式合法? (:503)"}
    G -->|否| G1["提示无效价格<br/>结束"]
    G -->|是| H{"数字位数/上限检查<br/>(:518-534)"}
    H -->|超限| H1["提示超出范围"]
    H -->|通过| I["folia 调度到方块线程 (:536)"]
    I --> J["mklink 生成容器 symbolLink<br/>(BukkitInventoryWrapperManager.java:96)"]
    J --> K["new ContainerShop<br/>shopId=-1 占位, SELLING, ACTIVE<br/>(SimpleShopManager.java:547-552)"]
    K --> L["createShop 校验链 (:715-792)"]
    L --> M{"isReachedLimit(owner)<br/>商店数上限? (:731)"}
    M -->|是| M1["拒绝: 达到上限"]
    M -->|否| N{"Util.canBeShop(block)<br/>容器合法? (:735)"}
    N -->|否| N1["拒绝: 非有效容器"]
    N -->|是| O{"黑名单/堆叠权限/保护插件检查<br/>重复商店/双箱/告示牌位置 (:740-792)"}
    O -->|失败| O1["拒绝并给出原因"]
    O -->|通过| P{"priceLimiter.check<br/>价格上下限? (:795)"}
    P -->|超限| P1["拒绝: 价格越界"]
    P -->|通过| Q["ShopCreateEvent<br/>PRE_CANCELLABLE (:808)"]
    Q -->|被取消| Q1["终止"]
    Q -->|通过| R{"shop.cost > 0<br/>建店费? (:823-835)"}
    R -->|是| S["QSEconomyTransaction<br/>.safeCommit() 扣费"]
    S -->|余额不足| S1["拒绝: 没钱"]
    R -->|否| T
    S -->|成功| T["makeShopSign 放告示牌<br/>claimShopSign (:843-854)"]
    T --> U["addShopToLookupTable<br/>内存查找表"]
    U --> V["registerShop(persist=true)<br/>createData→createShop→<br/>createShopMap 三步入库<br/>(AbstractShopManager.java:473-494)"]
    V -->|DB失败| W["processCreationFail<br/>回滚创建 (:209-220)"]
    V -->|成功| X["shop.setShopId(自增键)"]
    X --> Y["loadShop → handleLoading<br/>生成展示物"]
    Y --> Z["setSignText 渲染木牌<br/>ShopCreateEvent POST"]
```

**业务规则量化**（从 config.yml 与代码提取）：
- 建店费用：`shop.cost`（默认 0）
- 商店数上限：`limits.ranks` 按权限组（QuickShop.java:837 SimpleRankLimiter）
- 价格限制：`price-min`/`price-max`/`whole-number-prices` 等（SimplePriceLimiter）
- 双箱规则：非店主点击双箱强制选中另一半（PlayerListener.java:157-170）

---

## 4. 核心业务工作流二：交易执行决策树

```mermaid
flowchart TD
    A["行为触发<br/>TradeDirect/TradeUI/聊天数量"] --> B["actionTrade 解析数量<br/>(SimpleShopManager.java:1374)"]
    B --> C{"输入类型"}
    C -->|"数字 n"| D["amount = n"]
    C -->|"all"| E{"商店类型?"}
    E -->|buying| F["buyingShopAllCalc (:1248)<br/>按店主余额封顶"]
    E -->|selling| G["sellingShopAllCalc (:1443)<br/>按库存/背包空间封顶"]
    D & F & G --> H{"商店类型分发"}
    H -->|SELLING 店| I["actionSelling (:561)"]
    H -->|BUYING 店| J["actionBuying (:337)"]
    I --> K{"权限链 (:566-574)"}
    K -->|"quickshop.other.use 非自营"| K1["无权限→拒绝"]
    K -->|"BuiltInShopPermission.PURCHASE"| K2["店主未授权→拒绝"]
    K -->|自交易| K3["isShopOwner→拒绝"]
    K -->|通过| L{"shopIsNotValid<br/>一致性 (:473-489)"}
    L -->|"provider失效/容器空/Info过期"| L1["拒绝"]
    L -->|通过| M["税率+ShopPurchaseEvent"]
    M -->|事件取消| M1["终止"]
    M -->|通过| N["库存过户 failSafeCommit"]
    N -->|失败| N1["自动回滚→报错"]
    N -->|成功| O["经济 safeCommit"]
    O -->|失败| O1["经济补偿回滚<br/>⚠库存已过户风险点<br/>(SimpleShopManager.java:659-667)"]
    O -->|成功| P["收据+事件+指标+离线消息"]
```

---

## 5. 核心业务工作流三：商店删除

```mermaid
flowchart TD
    A["触发源"] --> A1["玩家 /qs remove"]
    A --> A2["容器被破坏<br/>BlockListener.java:57"]
    A --> A3["OngoingFee 余额不足<br/>OngoingFeeWatcher.java:39-99"]
    A --> A4["ShopPurger 定期清理"]
    A --> A5["加载期坏商店<br/>(ContainerShop.java:1428-1431)"]
    A1 & A2 & A3 & A4 & A5 --> B["SimpleShopManager.deleteShop<br/>(SimpleShopManager.java:1346)"]
    B --> C{"inDeletion 队列<br/>防重入 (:1349-1355)"}
    C -->|已在删除| C1["直接 return"]
    C -->|首次| D["ShopDeleteEvent PRE<br/>可取消 (:1356-1361)"]
    D -->|取消| D1["移出队列返回"]
    D -->|通过| E["遍历 signs 方块置 AIR<br/>拆除告示牌 (:1362-1364)"]
    E --> F{"shop.refund 配置<br/>退款? (:951-967)"}
    F -->|是| G["QSEconomyTransaction<br/>safeCommit 退还建店费"]
    F -->|否| H["unloadShop → handleUnloading<br/>关预览/移除展示物/isLoaded=false"]
    G --> H
    H --> I["unregisterShop(persist=true)<br/>removeShopMap + removeShop<br/>+ 缓存 invalidate<br/>(AbstractShopManager.java:261-272)"]
    I --> J["ShopDeleteEvent POST<br/>移出 inDeletion"]
```

---

## 6. 配置热重载工作流

```mermaid
sequenceDiagram
    participant OP as 管理员
    participant CMD as /qs reload
    participant RM as ReloadManager(simplereloadlib)
    participant Q as QuickShop.reloadModule
    participant L as 各 Reloadable 组件
    participant CE as QSConfigurationReloadEvent

    OP->>CMD: /qs reload
    CMD->>RM: reload()
    RM->>Q: reloadModule() (QuickShop.java:1364-1373)
    Q->>Q: registerDisplayAutoDespawn<br/>registerUpdater<br/>registerShopLock<br/>registerDisplayItem
    RM->>L: 逐个回调<br/>MainConfig/GuiConfig/InteractionConfig<br/>AbstractQSListener 子类<br/>SimpleTextManager
    RM->>CE: 广播配置重载事件
    CE-->>L: CompatibilityModule.onQuickShopReload<br/>自动 reloadConfig + init()<br/>(CompatibilityModule.java:97-103)
```

---

## 7. 数据库维护工作流

```mermaid
flowchart LR
    subgraph AUTO["自动维护"]
        S1["启动备份<br/>performBackup(startup)"]
        S2["迁移前备份<br/>fastBackup(:187-195)"]
        S3["5 分钟脏商店落库"]
        S4["7 天过期消息清理<br/>(MsgUtil.java:85)"]
    end
    subgraph MANUALDB["手动维护 (/qs database)"]
        M1["导出 CSV zip"]
        M2["purgeLogsRecords<br/>清 4 张 log 表<br/>(SubCommand_Database.java:154)"]
        M3["TableZipCsvBackup 导入恢复"]
    end
    subgraph CLEANUP["后台清理"]
        C1["ShopPurger<br/>purge.at-server-startup"]
        C2["purgeIsolated<br/>孤立数据兜底<br/>(SimpleDatabaseHelperV2.java:138-168)"]
    end
```

---

## 8. 异常恢复路径汇总

| 工作流 | 失败点 | 恢复机制 | 自动/手动 | 证据 |
|---|---|---|---|---|
| 启动-装库 | 无网络 | 本地 lib/ 缓存复用 | 自动 | QuickShopBukkit.java:137 |
| 启动-数据库 | 连接失败 | BootError + 禁用插件 | 手动修配置 | QuickShop.java:1205-1211 |
| 启动-经济 | Vault 未就绪 | 延迟 1tick + PluginEnableEvent 重试 | 自动 | QuickShop.java:869；EconomySetupListener.java:16-21 |
| 交易-库存 | 背包满/不匹配 | failSafeCommit LIFO 回滚 | 自动 | SimpleInventoryTransaction.java:154-221 |
| 交易-经济 | 余额不足/异常 | safeCommit 反向补偿 | 自动 | QSEconomyTransaction.java:370-379 |
| 保存-SQL | 写库失败 | dirty 保留下轮重试 | 自动(5min) | ShopDataSaveWatcher.java:29-37 |
| 关服-保存 | 15s 超时 | 逐店同步 updateSync | 自动+告警 | QuickShop.java:1285-1300 |
| 加载-坏商店 | 容器消失 | 删除或跳过待区块 | 配置决定 | ShopLoader.java:131-136 |
| 世界卸载 | 引用悬空 | unload 全部该世界商店 | 自动 | WorldListener.java:80-99 |
| 兼容模块 | 旗标冲突 | 复用已有 flag 或禁用自身 | 自动 | worldguard/Main.java onLoad |
| 更新检查 | API 不可达 | 1h 缓存重试 | 自动 | UpdateManager.java:60,116 |

**标记「缺乏容错」**：① 库存过户成功但经济提交失败时无自动逆向库存（见 §4 决策树 O1 分支）；② `checkTax` 正数税额不入账的疑似缺陷（QSEconomyTransaction.java:462-464）意味着税收审计依赖 qs_log_transaction 而非真实入账。

---

## 9. 测试策略现状

| 维度 | 现状 |
|---|---|
| 单元测试 | **0 个**（全仓库无 @Test/junit import） |
| 集成测试 | 无 |
| E2E 测试 | 无 |
| 覆盖率 | maven.yml:30-33 有 Codecov 步骤但无 surefire/jacoco 配置，上传的是空数据 |
| 替代质量手段 | Qodana 静态扫描（code_quality.yml）+ CodeRabbit AI 审查（.coderabbit.yaml）+ 社区实测 |

> 结论：质量保障完全依赖静态分析 + 人工 review + 社区运行反馈。对交易/经济这类资金敏感路径，这是显著风险（04 报告将「为交易核心补测试」列为 AI 辅助高优先级建议）。

---

## 10. 工作流总结

1. **发布链路高度自动化**：tag→Release→Modrinth 多 artifact 发布→翻译 CDN 推送→缓存清除，一条 release 流水线全部完成（mc-publish.yml:29-99）。
2. **翻译是独立生产线**：Crowdin(社区)→CopyDeploy(CI)→Bunny CDN→运行时 OTA（SimpleTextManager.java:99-108），翻译更新无需发版。
3. **业务工作流的防御性设计一致**：所有资金路径统一 safeCommit、所有创建路径先 PRE 事件后落库、所有删除防重入。
4. **最大缺口是测试**：7 条 CI 工作流无一运行测试，建议优先为 QSEconomyTransaction/SimpleInventoryTransaction/SimpleShopManager 三条资金链补 JUnit 测试。
