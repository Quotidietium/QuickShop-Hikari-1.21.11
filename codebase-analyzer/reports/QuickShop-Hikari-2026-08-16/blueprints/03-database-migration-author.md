# Skill Blueprint: quickshop-db-migration-author

> 自动生成自 codebase-analyzer｜分析时间：2026-08-16
> 源模块：`quickshop-bukkit/.../database/`（DataTables + SimpleDatabaseHelperV2.DatabaseUpgrade）

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| **推荐 Skill 名称** | `quickshop-db-migration-author` |
| **用途** | 编写 qs_* 表结构变更的版本迁移（N→N+1），含建表枚举同步与备份策略 |
| **AI 替代等级** | 🧑‍💻 AI 辅助（评分 18/30，安全风险 2 分——迁移错误可毁库） |
| **实施优先级** | 🥈 Strategic |
| **源文件** | DataTables.java（294 行）+ SimpleDatabaseHelperV2.java（1163 行） |
| **当前模式版本** | LATEST_DATABASE_VERSION = 20（SimpleDatabaseHelperV2.java:60） |

## 2. 触发场景与关键词

- "给 qs_data 加一列"
- "写数据库升级 20→21"
- "新表迁移"
- "schema change / database migration"

**推荐 description 触发词：**
```yaml
description: >-
  Author QuickShop-Hikari database schema migrations (versioned upgrade steps,
  table enum sync, backup policy). Triggered by: "数据库迁移", "加列", "建表",
  "database upgrade".
```

## 3. 输入输出契约

### 迁移链契约（DatabaseUpgrade.upgrade, SimpleDatabaseHelperV2.java:1053-1163）

| 项 | 契约 | 证据 |
|---|---|---|
| 版本读取 | `qs_metadata.database_version`；读不到视为 20 | :109-126, 1070-1074 |
| 步进方式 | `if(version < N) { fastBackup(); method(); setDatabaseVersion(N); }` | :1085-1142 |
| 降级保护 | 库版本 > LATEST 抛 IllegalStateException | :73-82 |
| 备份前置 | 每步 `fastBackup()` → performBackup("database-upgrade") | :187-195 |

### 已有迁移方法清单（作少样本）

| 版本 | 方法 | 操作 | 行号 |
|---|---|---|---|
| <9 | purgeIsolated | NOT IN 子查询清孤立行 | :138-168 |
| 9→10 | upgradeBenefit | 加 benefit 列 | :197-207 |
| 10→11 | upgradePlayers | locale 扩长+cachedName | :255-268 |
| 11→12 | upgradeUniqueIdsField | 5 字段扩 VARCHAR(128) | :270-292 |
| 12→13 | upgradeTablesEncoding | 全表转 utf8mb4 | :302-313 |
| 13→14 | upgradeWorldNameLength | world 扩 255 | :294-300 |
| 14→15 | performLogPurchasesIndex | 建 3 索引 | :1036-1051 |
| 16-19→20 | addStateColumn | 加 shop_state + 数据迁移（type=2→state） | :214-239 |

### 双后端约束（关键！）

迁移 SQL 必须同时兼容 **MySQL 与 H2(MODE=MYSQL)**（QuickShop.java:1443-1446）。
- H2 不支持的部分 MySQL 语法（某些 ALTER 细节/全文索引）需分支或改写
- 建表统一 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC`（DataTables.java:209）

### 错误场景

| 错误 | 触发 | 处理 |
|---|---|---|
| 版本 ≤3 | 古库 | 抛异常要求走重生流程（:1085-1087） |
| 列已存在 | 半途失败重跑 | ALTER 前查 INFORMATION_SCHEMA 或用 try-catch 忽略 |
| H2 语法差异 | MODE=MYSQL 不全覆盖 | AI 生成后必须人工双端验证 |

## 4. 依赖清单

| 依赖 | 用途 |
|---|---|
| `DataTables` 枚举 | 建表/新列同步（:25-178） |
| EasySQL builder | createAlterTable 等工厂（:226-294） |
| DatabaseIOUtil | fastBackup（:187-195） |
| config.yml backup-policy | 备份开关 |

## 5. Skill 工作流设计

```markdown
### Step 1: 解析 schema 需求
目标表/列/索引/数据搬迁规则；确认是否破坏性变更

### Step 2: 生成三处修改
① DataTables 枚举：新表项或新列定义（同步 qs_data 等字段清单）
② SimpleDatabaseHelperV2：
   - LATEST_DATABASE_VERSION 递增（:60）
   - 新私有方法 upgradeXxx()（参照 :214-239 数据迁移范例）
   - upgrade() 链中插入 `if (version < N) {...}`（:1138 附近）
③ 代码侧读写字段同步（SimpleDataRecord.generateParams/ResultSet 构造器 :40-92, :103-128）

### Step 3: 强制人工审查点（AI 辅助等级要求）
- [ ] SQL 在 MySQL 8 与 H2 2.x 双端验证语句清单输出
- [ ] fastBackup 位于版本写入之前
- [ ] 数据搬迁幂等（重跑安全）
- [ ] 降级场景说明（不支持降级，文档标注）

### Step 4: 输出验证 SQL 清单
生成可在两个后端手工执行的验证 SELECT，供升级后核对行数
```

**建议 Constraints：**
```markdown
- Always 迁移前 fastBackup() 且版本号最后写入
- Always 输出双后端（MySQL/H2）兼容性说明
- Never 生成 DROP COLUMN/DELETE 不带 WHERE 的破坏性语句，除非用户显式确认
- Never 修改已有历史迁移方法的语义（只追加新版本步骤）
```

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|---|---|---|
| Read | 读 DataTables/HelperV2 | 必需 |
| Edit | 三处同步修改 | 必需 |
| Write | 生成验证 SQL 文档 | 可选 |

**建议 allowed-tools：** `Read Edit Write`

## 7. 使用示例

### ✅ Do This
```
用户: "qs_data 加 stock_notify BIT 默认 0"
输出: ① DataTables.DATA 加列定义 ② LATEST→21 ③ upgradeStockNotify()
      （含 INFORMATION_SCHEMA 存在性检查）④ 链插入 ⑤ 双端验证 SQL ⑥ 提示人工审查备份策略
```

### ❌ Not This
```
错误输出: 直接 ALTER TABLE 不查列存在性、不写版本号（用户重跑服务器时二次迁移崩溃）
```

## 8. 参考材料

- 表定义：`QS/database/DataTables.java:25-220`
- 迁移链：`QS/database/SimpleDatabaseHelperV2.java:73-168, 1053-1163`
- 备份：`QS/database/DatabaseIOUtil.java:37-71`
- Bean 同步：`QS/database/bean/SimpleDataRecord.java:40-128`
