# Skill Blueprint: quickshop-compat-generator

> 自动生成自 codebase-analyzer｜分析时间：2026-08-16
> 源模块：`compatibility/*`（30 个既有模块 + compatibility/common 基类）

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| **推荐 Skill 名称** | `quickshop-compat-generator` |
| **用途** | 为任意第三方 Minecraft 插件生成完整的 QuickShop-Hikari 兼容模块（Maven 模块 + 代码 + 注册项） |
| **AI 替代等级** | 🤖 完全 AI 化（评分 28/30） |
| **实施优先级** | 🥇 Quick Win |
| **源文件数** | 30 模块 × ~2 文件 + 基类 1 |
| **源代码行数** | 典型模块 150-300 行（worldguard/Main.java 225 行） |

## 2. 触发场景与关键词

- "为 XXX 插件写一个 QuickShop 兼容模块"
- "新增 compatibility/towny 这样的适配器"
- "让 QuickShop 支持 XXX 的区域保护"
- "写一个 compat 模块对接 XXX"

**推荐 description 触发词：**
```yaml
description: >-
  Generate QuickShop-Hikari compatibility modules for third-party Bukkit/Paper
  plugins (region protection, skyblock, custom items). Triggered by: "兼容模块",
  "compat module", "为...写适配", "QuickShop 兼容".
```

## 3. 输入输出契约

### 主要函数接口（生成的子类必须实现）

| 函数 | 输入 | 输出 | 副作用 | 模板位置 |
|---|---|---|---|---|
| `init()` | 无（abstract） | void | 读配置+初始化第三方钩子 | CompatibilityModule.java:95 |
| `onLoad()` | 无 | void | 注册旗标/钩子（须调 super.onLoad()） | :64-73 |
| `onEnable()` | 无 | void | registerEvents+init（通常继承不动） | :82-93 |
| 事件处理器 | `ShopCreateEvent` 等 | void 取消/放行 | 拦截建店/交易 | 参照 worldguard/Main |

### 可用基类 API

```java
// CompatibilityModule 提供给子类（CompatibilityModule.java:26-112）
QuickShopAPI getApi();
List<Shop> getShops(String worldName, int minX, int minZ, int maxX, int maxZ);  // :35
List<Shop> getShops(String worldName, int chunkX, int chunkZ);                  // :50
void recordDeletion(QUser qUser, Shop shop, String reason);                      // :105
// QSConfigurationReloadEvent 自动触发 reloadConfig()+init()                   // :97-103
```

### 拦截用 QuickShop 事件（API/event/）

| 事件 | 用途 | 典型处理 |
|---|---|---|
| `ShopCreateEvent` | 建店前区域/权限检查 | `event.setCancelled(true)` |
| `ShopPermissionCheckEvent` | 商店内权限对接 | 覆盖 `event.hasPermission()` |
| `ShopPurchaseEvent` | 交易前检查（如旗标） | 取消交易 |
| `ShopLoadEvent`/`ShopDeleteEvent` | 同步第三方数据 | 通知/记录 |

### 错误码/失败模式

| 情形 | 处理 |
|---|---|
| 目标插件未安装 | compat 模块自身 plugin.yml `depend` 保证不加载 |
| 旗标冲突（FlagConflictException） | 复用已注册旗标或禁用自身（worldguard/Main onLoad 模式） |
| 第三方 API 调用异常 | try-catch + getLogger().warning，不抛出（防连锁崩服） |

## 4. 依赖清单

### 外部服务
| 服务 | 用途 |
|---|---|
| 目标插件 API 文档/Javadoc | 生成集成代码（**必须联网核实最新 API**） |
| Maven Central / 插件仓库 | 目标插件 provided 依赖坐标 |

### 内部模块
| 模块 | 关键接口 |
|---|---|
| quickshop-api | `QuickShopAPI`、`Shop`、事件族 |
| compatibility/common | `CompatibilityModule` 基类 |
| 根 pom.xml | `<modules>` 注册（pom.xml:472-524）+ `<compat.xxx>` 版本属性（:60-97） |
| mc-publish.yml | artifact 上传列表（:35-76） |

### 配置项（生成的模块 config.yml）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `create.default-allow` | bool | false | 是否默认允许建店（worldguard 模式） |
| `trade.default-allow` | bool | true | 是否默认允许交易 |
| `max-shops-in-region` | int | -1 | 区域内商店数上限 |

## 5. Skill 工作流设计

```markdown
### Step 1: 收集目标信息
问用户：目标插件名、Maven 坐标（或 repo）、集成意图（区域保护/天空岛/物品匹配/经济）

### Step 2: 联网核实目标 API
WebSearch/WebFetch 目标插件最新 Javadoc，确认：主类入口、区域/权限查询 API、事件

### Step 3: 选择最接近的范例
- 区域保护类 → 读 compatibility/worldguard/.../Main.java 作少样本
- 天空岛类 → compatibility/superiorskyblock 或 bentobox
- 物品类 → compatibility/ecoenchants 或 matcherplus

### Step 4: 生成四件套
① compatibility/<name>/pom.xml（照抄邻近模块，替换 artifactId 与目标依赖）
② src/main/resources/plugin.yml（main=...compatibility.<name>.Main, depend=[目标], softdepend=[QuickShop-Hikari]）
③ src/main/java/.../compatibility/<name>/Main.java（继承 CompatibilityModule + init() + 事件处理器）
④ 根 pom.xml 注册 <module> + <compat.name> 版本属性 + mc-publish.yml 登记

### Step 5: 自检清单
- [ ] init() 无编译错（对照基类 abstract 签名）
- [ ] 所有第三方调用有 try-catch
- [ ] onLoad 注册类钩子已调 super.onLoad()
- [ ] 事件用 @EventHandler 注解且本类已 registerEvents
- [ ] 遵循项目代码风格（final 参数、行宽、中文注释禁用）
```

**建议 Constraints：**
```markdown
- Always 联网核实目标插件 API 后再生码，禁止凭记忆写第三方 API
- Always 参照一个既有 compat 模块作为结构模板
- Never 修改 quickshop-api 或基类 CompatibilityModule
- Never 在 compat 模块中直接访问数据库或经济系统（只用 getApi() 暴露的门面）
- Never 使用目标插件 deprecated API
```

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|---|---|---|
| Read | 读范例模块/基类/根 pom | 必需 |
| Write | 生成 pom/plugin.yml/Main.java | 必需 |
| Edit | 登记根 pom 与 mc-publish.yml | 必需 |
| WebSearch/WebFetch | 核实目标插件 API | 必需 |
| Grep | 查找既有相似实现 | 可选 |

**建议 allowed-tools：** `Read Write Edit Grep WebSearch WebFetch`

## 7. 使用示例

### ✅ Do This
```
用户: "为 Residence 6.x 写一个 QuickShop-Hikari 兼容模块，要求保护区域内才能建店"
输出: compatibility/residence 完整模块（pom/plugin.yml/Main.java），
      Main.init() 读配置，监听 ShopCreateEvent 查 Residence.PermissionUtil，
      根 pom + mc-publish.yml 登记项，附构建验证命令 mvn -pl compatibility/residence package
```

### ❌ Not This
```
用户: "为 Residence 写兼容模块"
错误输出: 直接凭训练记忆写 Residence API 调用（版本可能已变更，编译失败）
```

## 8. 参考材料

- 基类：`compatibility/common/src/main/java/com/ghostchu/quickshop/compatibility/CompatibilityModule.java`
- 范例：`compatibility/worldguard/.../Main.java`（旗标+三事件拦截，225 行）
- 注册点：根 `pom.xml:472-524`、`.github/workflows/mc-publish.yml:35-76`
- 事件 API：`quickshop-api/src/main/java/com/ghostchu/quickshop/api/event/`
