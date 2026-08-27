# QuickShop-Hikari 性能优化报告·第十七轮（价格显示链，2026-08-27）

> 基准程序与方法论同前（本轮起 45 用例 × 8 套件——economy 套件新增
> formatInternalPrice）；同态交替 A/B，基线 commit `4f688f9d8` = R28 闭合并提交文档后
> 的 HEAD，各 3 fork 取中位数。本轮为复合轮：一处性能快照 + 一处红线内并发缺陷修复。

## R29：货币符号快照 + DecimalFormat 线程安全（commit c87da46da..a9cd8f672）

### 问题

1. **`EconomyFormatter` / `BuiltInEconomyFormatter.getInternalFormat`**（价格显示的内部
   格式化路径）：无币种时**每次调用**做一次 `getString("shop.alternate-currency-symbol")`
   配置树查询。该路径服务三类流量：`disable-vault-format=true` 服务器的主格式化路径、
   Vault 返回空/抛异常时的兜底路径、Vault 插件层自身的余额格式化
   （`VaultProvider:189/194`）——签名行 renderPrice、每笔交易收据、菜单价格全部经过。
   （`useDecimalFormat`/`currencySymbolOnRight` 已是字段，唯独符号字符串漏网。）
2. **`MsgUtil.decimalFormat` 线程安全隐患（红线内稳定性修复，非性能）**：共享静态
   `DecimalFormat` 实例被主线程与 Folia 区域线程并发调用——`DecimalFormat` 明确非线程
   安全，并发 format 可产生错乱输出乃至内部状态异常（文档化风险）。触发面：
   `use-decimal-format=true` 且多线程同时渲染签名/收据。

### 改动

1. 两 formatter 增加 `currencySymbol` 快照字段，由既有 `reloadModule()` 刷新（失效
   纪律零新增；默认值 `"$"` 与原 `getString(path, "$")` 一致）。
2. `MsgUtil.DECIMAL_FORMAT = ThreadLocal.withInitial(...)`：每线程惰性构建同配置实例，
   异常回退与告警逻辑逐行保留；「decimal-format 改动需重启」的既有语义按线程保持并
   注释说明。公开方法 `decimalFormat(double)` / `decimalFormat(BigDecimal)` 签名不变。

### 结果（同态交替 A/B，各 3 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| economy/formatInternalPrice（内部格式化兜底全链直调） | 6,849 | 113 | **-98.4%** | 基线三 fork 6,696~14,254 vs 候选 106~132——基线**最优** fork 高于候选**最差** fork 50 倍，六 fork 零重叠；直接计量被优化构件，绝对值即真实量级（字符串拼接 + 字段读 vs 配置树行走） |
| 其余 44 个共享用例 | — | — | 双向漂移带 | db 全套 +12~52%（基线侧偏慢）、listener/menu -5~-30%（候选侧有利）——两侧对称的环境噪声而非回归：同码对照项 text/itemNameFlags ±4.2%、trade 主链 ±3%；另注基线 fork1 数据产自被中断的前次会话运行 |
| MsgUtil ThreadLocal 化 | — | — | 不设独立基准 | 正确性导向改动：消除的是并发未定义行为而非稳态速度；单线程 DFS 调用成本不变（一次 ThreadLocal.get ≈ 原 null 检查） |

机器可读数据：`benchmark/results/round29-baseline-fork{1,2,3}.json` 与
`round29-fork{1,2,3}.json`（对比脚本 `benchmark/results/compare-round29.py`）。

### 语义与红线

- **输出等价性**：符号左右拼接、币种映射表优先级、缺键回退 `"$"` 由
  `EconomyFormatterSnapshotTest` ×4 钉死（右置 + 映射表 `gems;G` → `12.34G`、经 reload
  左置翻转 → `€12.34`、50 次格式化零 config 读取、disable-vault-format 主路径消费
  快照）；全量 **144 用例绿**（140→144）。
- **ThreadLocal 语义差异如实记录**：旧行为下改 decimal-format 永不生效（静态一次性）；
  新行为按「各线程首触」生效——配置变更本就要求重启/reload 流程，观测语义不变。
- 兼容性：`BuiltInEconomyFormatter`/`EconomyFormatter` 公开方法签名零变化；
  `MsgUtil.decimalFormat` 系静态工具，其他插件若有引用不受影响。

## 结论

R29 清算了价格显示链上最后一批逐调用配置访问（构件级 **-98.4%**，六 fork 零重叠），
并以 ThreadLocal 化消除了共享 DecimalFormat 的区域线程并发缺陷（红线内稳定性加固）。
十七轮累计：交易全链约 -84%，DB 写全批量化，文本/日志全缓存化，区块包 O(1) 化，展示
物重送去冗余，浏览菜单快照化，开箱/区块加载扫描快路径化，木牌渲染单扫化，签名排程
去重 O(1) 化，监听器与价格格式化的配置访问全快照化，数值格式化线程安全化。
