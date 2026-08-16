# Skill Blueprint 索引

> 自动生成自 codebase-analyzer｜QuickShop-Hikari｜2026-08-16

| # | Blueprint | 组件 | AI 等级 | 优先级 | 文件 |
|---|-----------|------|---------|--------|------|
| 1 | quickshop-compat-generator | 第三方插件兼容模块（30 个样板） | 🤖 完全 AI 化（28/30） | 🥇 Quick Win | [01-compat-module-generator.md](01-compat-module-generator.md) |
| 2 | quickshop-subcommand-scaffold | /qs 子命令脚手架（60 个既有） | 🤖 完全 AI 化（27/30） | 🥇 Quick Win | [02-subcommand-scaffold.md](02-subcommand-scaffold.md) |
| 3 | quickshop-db-migration-author | 数据库版本迁移（v20 链） | 🧑‍💻 AI 辅助（18/30） | 🥈 Strategic | [03-database-migration-author.md](03-database-migration-author.md) |
| 4 | quickshop-localization-manager | 翻译键生命周期管理（44+ 语言） | 🤖 完全 AI 化（24/30） | 🥉 Incremental | [04-localization-manager.md](04-localization-manager.md) |

## 实施路线图

### 立即实施（Quick Win）
1. **兼容模块生成器** — 样板最稳定（全部继承 CompatibilityModule 单基类），联网核实目标 API 后可全自动生成
2. **子命令脚手架** — CommandHandler 约定 60 次重复，注册点单一（SimpleCommandManager 构造器）

### 规划实施（Strategic）
3. **数据库迁移编写** — 模式固定但毁库风险高，Blueprint 内置三处强制人工审查点 + 双后端（MySQL/H2）验证清单

### 逐步推进（Incremental）
4. **本地化管理** — 审计类任务（死键/缺键 diff）全自动；翻译产出走 Crowdin 管线

---

> 每个 Blueprint 文件包含创建对应 Skill 所需的完整设计规格（触发词、接口契约、依赖清单、Workflow、Constraints、示例）。
> 使用 `skill-for-skills` 加载对应 Blueprint 文件即可生成标准 SKILL.md。
