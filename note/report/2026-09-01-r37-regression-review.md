# R37 裁剪后回归审查（2026-09-01）

> 本轮对 R37 功能裁剪（31cfed2a7..2d5698e20）做三层验证：diff 语义审查（代理逐文件）→ 全量测试 → 实机 E2E（Paper 1.21.11 启动）。
> 收尾 `4b63d96d4`（预期）。结论：裁剪后功能链完整，发现并修复 5 个问题（1 个既有崩溃级缺陷 + 4 个裁剪残留）。

## 审查发现与修复

| # | 严重度 | 问题 | 位置 | 处置 |
|---|---|---|---|---|
| 1 | **高** | **Unirest 启动崩溃（既有缺陷，非裁剪回归）**：`kong.unirest.Unirest.<clinit>` 在 onLoad 触发 `ClassNotFoundException: org.apache.http.nio.protocol.HttpAsyncResponseConsumer`，插件无法启用。该缺陷自上游即存在——旧 fat-jar 与 libby 清单均不含 http 运行时栈，本机 8 月靠 Paper remapped 目录残留的历史缓存侥幸启动，全新环境必崩。本项目此前从未做过真实全新环境启动验证。 | libraries.maven | 按 unirest 3.14.5 的官方 pom 补全运行时依赖六行（httpclient 4.5.13 / httpcore 4.4.13 / httpcore-nio / httpasyncclient 4.1.5 / httpmime / commons-codec 1.15）。修复后实机启动 15 库全装载通过。非裁剪范围的功能恢复性修复。 |
| 2 | **中** | `/qs about` 渠道标签显示原始语言键路径（`updatenotify.label.lts/stable/unstable` 三键在批次 3 删 updatenotify 段时被误伤，该段仍被 SubCommand_About 引用） | SubCommand_About.java | 精确补回 en/zh_cn 双语的三键（updatenotify.list 通知消息体与 buttontitle 等更新器专用键不恢复） |
| 3 | 低 | EconomyTransaction 接口删 currency() 时遗留未闭合 javadoc 片段（与下一注释合并解析错乱） | EconomyTransaction.java | 清残块 |
| 4 | 低 | ContainerShop.updateShopData() 删 extra.currency 迁移段后成空方法（遗留死调用与死局部变量） | ContainerShop.java | 方法与调用点整体删除 |
| 5 | 低 | builder 链批量删 `.currency(...)` 后遗留缩进空行 | ShopUtil/OngoingFeeWatcher/SubCommand_Name | 清理 |

## 语义审查结论（逐文件，代理复核 + 主审对照）

- 资金安全路径（QSEconomyTransaction commit/rollback/tax、EconomyDeposit/WithdrawOperation、VaultProvider balance/deposit/withdraw）逐行等价，仅去透传死参数。
- ContainerShop 构造器 17 参处全部调用点（ShopLoader:182、SimpleShopManager:576、benchmark 3 处、测试 6 处）实参对齐无串位；createDataRecord 第 7 参 null 恰落 currency 列（有意保 schema）。
- SimpleTextManager.load() 删 OTA 后顺序完整；zh_cn 经 loadBundled 命中；47 个代码引用语言键在双语均存在。
- MsgUtil 删 Bungee 段未误伤离线消息持久化（batcher.offerOfflineMessage 分支完整）。
- SimplePriceLimiter 删币种维度后与旧默认币种路径（currency=null 时 isApplicableCurrency 恒 true、默认 currency:'*' 匹配一切）行为等价；权限组按组名字符串存储，SET_CURRENCY 删除不影响旧库。
- QuickShop 五处挂载点（errorReporter/updater/bungeeListener/metricManager/通信频道）成对删除无断裂。

## 验证证据

- 全量测试：**177 用例全绿**（`-Dtest='!TradeLoadSmokeTest'`）。
- 实机 E2E（Paper 1.21.11 + Vault + EssentialsX + packetevents + H2）：插件 onLoad→onEnable→自检→1 商店从旧 H2 库加载→`Selected economy bridge: BuiltIn-Vault`→`Bootstrap -> All Complete (558ms)`，全程零异常。
- 主 jar 语言文件验证：仅 en_us 兜底 + zh_cn 两个 messages.yml。
- 全仓库打包通过（排除 matcherplus 既有外部依赖问题）。

## 已知非回归项

- **TradeLoadSmokeTest.soakMixedLoadStaysConsistent 本机超时**：1g 堆 12×OOM、3g 堆纯 300s 超时；R36 基线 31cfed2a7 同条件复测同样超时，确认为本机吞吐劣化的环境问题（非本轮回归），其断言逻辑由核心回归覆盖。
- matcherplus 本地无法解析 ExcellentCrates 6.5.0（改动前既有的仓库构件缺失，CI 可构建）。
