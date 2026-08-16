# Skill Blueprint: quickshop-localization-manager

> 自动生成自 codebase-analyzer｜分析时间：2026-08-16
> 源模块：`quickshop-bukkit/.../localization/`（SimpleTextManager）+ resources/messages.yml（44+ 语言）

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| **推荐 Skill 名称** | `quickshop-localization-manager` |
| **用途** | 管理 messages.yml 翻译键生命周期：新键补全、死键清理、代码↔键一致性审计、翻译草稿 |
| **AI 替代等级** | 🤖 完全 AI 化（评分 24/30） |
| **实施优先级** | 🥉 Incremental |
| **源文件** | SimpleTextManager.java（约 1100 行）+ lang/*.yml |

## 2. 触发场景与关键词

- "检查哪些翻译键没用到/缺失"
- "给新功能补全 44 种语言的 messages.yml"
- "翻译键清理"
- "localization audit"

**推荐 description 触发词：**
```yaml
description: >-
  Audit and maintain QuickShop-Hikari localization keys (usage detection, missing
  key fill, dead key cleanup, translation drafts). Triggered by: "翻译键",
  "本地化", "localization", "messages.yml".
```

## 3. 输入输出契约

### 键消费契约（SimpleTextManager.java:919, :976-1010）

| API | 行为 |
|---|---|
| `text().of(sender, path, args...)` | 按 sender locale → findRelativeLanguages 回退链取键（:417-461） |
| `TextList` | 多行键（:762） |
| fallback | en_us 兜底（:253-269）+ fillMissing 补缺（:153） |
| 覆盖层 | overrides/<locale>/messages.yml 用户覆盖（:154-171） |

### 加载层级（load() :115-204）

```
① 内置 en_us → ② jar 内 lang/<locale>/ → ③ Crowdin OTA（:99-108）
→ ④ fillMissing 兜底补齐 → ⑤ overrides 覆盖 → ⑥ enabled-languages 过滤
```

### 审计输出契约

| 报告 | 判定规则 |
|---|---|
| 死键 | messages.yml 存在但代码无 `of(..., "key"` 引用（Grep 全源码） |
| 缺键 | 代码引用但 en_us 缺失（运行时会显示原始键路径） |
| 参数失配 | 键含 {0}{1} 占位但调用传参数量不符 |
| 未翻译 | 非 en_us 语言中值 == en_us 值 |

## 4. 依赖清单

| 依赖 | 用途 |
|---|---|
| `resources/messages.yml` | en_us 主键源 |
| `Grep` 全源码 | `text().of(` / `guiMessage` / `lang:` 前缀引用扫描（QuickShopPage.java:146-187） |
| MiniMessage | 键值语法（<red> 等标签合法性检查） |
| color-scheme.yml | `<color_scheme:xxx>` 自定义标签（SimpleTextManager.java:206-239） |

## 5. Skill 工作流设计

```markdown
### Step 1: 建立键集合
解析 messages.yml 为键树；Grep 源码提取全部消费点

### Step 2: 三向 diff
代码引用集 vs en_us 键集 vs 各语言键集 → 死键/缺键/参数失配三表

### Step 3: 处理
- 新键：写入 en_us + 翻译草稿（其余语言标记 todo，正式翻译走 Crowdin）
- 死键：列出待删清单（Never 直接删，等 Crowdin 同步确认）

### Step 4: MiniMessage 语法校验
所有新值过标签合法性（<color:red>、<bold> 等）防客户端解析错误

### Step 5: 验证提示
提示 /qs reload 后生效；overrides 层的用户自定义不会被覆盖（:154-171 语义）
```

**建议 Constraints：**
```markdown
- Always 以 en_us 为唯一权威键源
- Never 直接修改 overrides/ 下用户文件
- Never 翻译含 {0}{1} 参数段落的语序（保持占位符位置语义）
- 翻译草稿仅写入 Crowdin 待译或标注 TODO，正式语言文件由 Crowdin 管线回填
```

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|---|---|---|
| Read | 读 messages.yml/源码 | 必需 |
| Grep | 引用扫描 | 必需 |
| Edit | 补键 | 必需 |
| Write | 审计报告 | 可选 |

**建议 allowed-tools：** `Read Grep Edit Write`

## 7. 使用示例

### ✅ Do This
```
用户: "审计翻译键"
输出: 死键 12 个（列表+引用数 0 证据）/ 缺键 3 个（代码位置）/ 参数失配 1 处
      + en_us 补缺 + 其他语言 TODO 标注
```

### ❌ Not This
```
错误输出: 直接删除"看似没用"的键（可能被 addon/compat 模块以字符串拼接引用）
```

## 8. 参考材料

- 加载器：`QS/localization/text/SimpleTextManager.java:75-204`
- 后处理链：`QS/localization/text/postprocessing/impl/`（4 个处理器）
- 键消费基类：`QS/menu/shared/QuickShopPage.java:44-201`
- Crowdin 闭环：`.github/workflows/mc-publish.yml:80-99` + `sync-crowdinota.yml`
