# QuickShop-Hikari 性能优化报告·第三十一轮（文本渲染管线收敛，2026-09-08）

> 承 R44。基线 `8f9693e1b`（R44 完成点），候选 `40b1f4ca0`，3 fork/侧严格交替，
> 全 53 用例双轴计量（基准文件两侧同源，无同步需求——本轮零基准改动，既有
> text 套件已覆盖该路径；signRender 顺带计量了同管线的生产签名渲染路径）。

## 背景：每条消息渲染都在重走可冻结的决策

R45 选点经基准剖析模式（`-Dbenchmark.profile`）：text/forLocaleWithArgs 的
quickshop 顶帧中 `MsgUtil.fillArgs` 独占 1,361/2,297 采样（≈48%），次席
`PreParsedTemplate.walk`+`splitTextNode` 400+180（≈20%）、`findRelativeLanguages`
双调 147。代码走查定位三层结构性浪费——

1. **渲染成功后的冗余后处理遍历**：R19 的预解析渲染（哨兵孔直插参数）成功后，
   postProcess 链的 `FillerProcessor` 仍对成品树执行 `MsgUtil.fillArgs`——每参
   一个 `TextReplacementConfig.builder().matchLiteral(...).build()` 分配（builder
   + config + 字面匹配器，adventure 库真实对象，非 mock 面）加一次全树
   `replaceText` 遍历，而占位符已被哨兵拆分消费、匹配可证零命中；
2. **每渲染重推导拆分骨架**：`walk` 对每个内部节点先分配 `new ArrayList` 再判断
   是否变更（未变路径也付分配），`splitTextNode` 每次重新 indexOf/parseInt/
   substring 重建字面片段——而拆分位置、片段内容、参数槽索引全部只依赖模板，
   与参数无关；
3. **杂项**：`TextList.forLocale` 每次 `findRelativeLanguages` 调两遍（index 探测
   与渲染键各一）；`renderPreParsed` 每次 `locale + ' ' + path` 拼接新键串探测
   缓存。

## 改动（commit `40b1f4ca0`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| `MsgUtil.fillArgs(Component, Component...)` | 逐参 replaceText（N 次 config 分配+全树遍历）+compact | 先做全树 `'{'` 预扫（域为 replaceText 递归域的超集：文本内容+可译组件的 Component 参数+hover 事件值+children；已对 adventure 4.16 `TextReplacementRenderer` 反编译核实其递归域）；无 brace 时逐参匹配可证零命中，跳过全部 config 分配与遍历，仅保留恒有的 compact；有 brace 走原顺序循环 | 无 brace ⇒ 每个 `matchLiteral("{N}")` 不可能命中 ⇒ 旧路径返回值即 `origin.compact()`，两路逐位同值；参数值携带 `{N}`（嵌套再填）或未填充模板 ⇒ 预扫命中 brace ⇒ 走原路径，嵌套语义不变（`nestedPlaceholderInsideArgumentIsStillRefilled` 用例钉住） |
| `PreParsedTemplate` | 每渲染 walk 全树+splitTextNode 重建片段+containsSentinel 全树复扫 | 解析期编译：哨兵拆分位置/字面片段/参数槽冻结为骨架（sealed `PermNode`：`Leaf` 原树共享 / `Split` 片段计划 / `Branch` 路径重建）；parse 以 maxIndex+1 个哑元参数跑一次完整 render 并要求 containsSentinel 通过（参数无关的回退裁决一次冻结为 BROKEN）；render 只做参数验证（样式/哨兵/缺参/null 槽）+装配 | 编译即历版算法以哑元执行一遍——MiniMessage 嵌套样式树下深处哨兵不可达拆分、tag 参数内哨兵等情形，历版每渲染重复回退，现冻结为 BROKEN，观测行为不变（输出或回退逐位同判）；字面片段与未触及子树跨渲染共享，组件不可变故无漂移；282 模板语料等价测试全过 |
| `TextList.forLocale` | `findRelativeLanguages` 双调 | 单次解析复用 | 同一 locale 两次调用同值，纯去重 |
| `preParsedTemplateCache` | 扁平键，每渲染拼接 `locale+' '+path` 新串 | 嵌套 locale→path 两级 CHM，零分配查找；register 失效整 locale 移除 | 键语义不变；register 原按前缀删除本 locale 全部条目，整 map 移除等价 |

**开发中抓获并修复的等价陷阱**：首版编译漏跑解析期 containsSentinel 验证——
MiniMessage 把连续样式段产为**嵌套**树（每段是前段文本节点的 child），深处的
哨兵孔骑在前一个 Split 节点的「原始子树」上而拆分不可达，历版靠 render 末尾的
containsSentinel 兜底每次回退 legacy；漏跑则语料测试立刻暴露哨兵字漏进输出
（2 用例红），补上后全绿——语料等价网在本轮起到了它设计的作用。

## 测试面

新增 **12 用例**（合计 **217 全绿**）：`TextRenderPipelineR45Test`——编译骨架跨
参数复现与 legacy 逐位等价、哨兵携带参数回退、maxIndex 缺参回退、null 槽回退、
嵌套哨兵/tag 哨兵解析期冻结为 BROKEN、fillArgs 无 brace 与历史慢路径逐位等价、
嵌套占位符再填保持、hover 值/可译参数的扫描域命中、参数 brace 不拼接、空内容
容器 compact 等价。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| text/forLocaleWithArgs | 2,944 | 1,648 | **-44.0%** | [2,944, 2,944, 3,048] \| [1,648, 1,648, 1,688]——完全分离；ns 轴 -22.1% 同向 |
| listener/signRender | 636,312 | 632,704 | **-0.6%** | [636,312, 636,312, 637,406] \| [632,704, 632,704, 632,782]——完全分离；ns 轴 -3.1% 同向。签名价格行带参渲染走同管线，-3.6KB/op ≈ 2-3 行带参渲染的管线节省，与单价模型自洽 |
| 其余 51 共享用例 | — | — | 噪声带内 | 详见下 |

**mock 放大如实披露**：本轮被消除的分配主体是 **adventure 库对象与 JDK 集合**
（TextReplacementConfig builder/config/字面匹配器、walk 的逐节点 ArrayList、
splitTextNode 的 substring 与 Component.text 片段、键拼接 String）——它们在
生产上逐字节同样发生，-44% 基本是生产等量消除，非 Mockito 计量面；唯一的
mock 面是渲染链上的少量插件桩调用（platform().miniMessage() 等），两侧同测。

如实入册五点：

- **lookup/getShopByLocation-hit +2.9%/miss +2.8%（56B 粒度）**：本轮未触碰
  查找路径；跨会话佐证——R44 候选（与 R45 基线**同码**）在 round44 测得
  hit 1,992，本轮基线测 [1,936, 1,936, 1,968]、本轮候选 [1,968, 1,992,
  1,992]：同码跨会话在两个相邻分配态间摆动，与代码版本无关，区间在 1,968
  处相接（R43/R44 共享 JVM 预热扰动带先例）。
- **lookup/getShopByRuntimeUuid ±+1.2%（120B）**：基线 [9,624, 9,624, 9,784]
  与候选 [9,624, 9,744, 9,904] 区间重叠（9,624 共有），同带。
- **economy/safeCommitWithTax +1.0%（85B）**：[8,496, 8,496, 8,560] 与
  [8,568, 8,581, 8,616] 相邻相接，无因果路径（经济提交不涉文本），粒度级偏移。
- **text/fallbackYamlParse -5.2%**：两侧同码用例；基线 fork1 落低峰 4,402,128、
  fork2/3 落高峰，候选三 fork 全钉低峰——R40 起历轮留档的双峰翻转，无因果。
- 时序轴 ±20~60% 抖动（chunkPacketPeek +50%/inventoryTxCommit +60% 等）为本机
  时序噪声，B/op 轴为准；log/append 系 ns -21~-42% 无 B/op 变化，为 JIT 时序
  摆动不计入。

## 生产语义收益（B/op 轴之外）

- **每条带参消息**（交易收据/店主通知/菜单行/签名价格行/控制面板）省 N 次
  replaceText 全树遍历与 N 组 config 分配、每渲染一次的全树 walk/split 重推导
  与未变路径的 ArrayList 分配、每消息一次键拼接分配；
- **TextList 渲染**（多行消息）再省一次 findRelativeLanguages 解析链；
- 预解析模板的解析期验证把「每渲染重复裁决回退」冻结为一次性，少数病态模板
  不再每条消息重跑整棵判定。

## 累计（R38–R45，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R45 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,846,680 | -52.5% |
| text/forLocaleWithArgs（本轮重计量） | —（R45 前 2,944） | 1,648 | **-44.0%** |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 8,064 | -76.2% |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 8,581 | -82.0% |
| serialize/createDataRecord | —（R42 前 34,200） | 29,488 | -13.8% |
| db/insertShopChain | —（R42 前 42,208） | 37,224 | -11.8% |
| db/updateShop-unchangedData | —（R42 前 36,540） | 31,596 | -13.8% |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,104 | -9.9% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,208 | -8.9% |
| listener/signRender（本轮计入管线收益） | —（R45 前 636,312） | 632,704 | -0.6% |
| trade/countItemsScan54 | 391,576 | 277,528 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 932,296 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 971,194 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,274,066 | **-15.5%** |
| trade/actionSell | 1,505,232 | 1,298,614 | **-13.8%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round45.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物落基线 worktree 的 benchmark/results，需拷回主仓（round44 先例）
python benchmark/results/compare-round45.py
```
