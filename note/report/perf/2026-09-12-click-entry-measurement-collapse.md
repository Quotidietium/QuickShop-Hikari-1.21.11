# R51：点击-交易入口链重复度量收敛

> 轮次：R51（第五十二轮性能优化）
> 日期：2026-09-12
> 基线：`214f9795f`（R50 闭合并入册点）｜候选：本轮工作树
> 基准：`benchmark/` 独立模块全 59 用例双轴（B/op 分配轴 + ns/op 时序轴），3 fork/侧严格交替
> 产物：`benchmark/results/round51-baseline-fork{1,2,3}.json` / `round51-fork{1,2,3}.json`，
> 驱动脚本 `benchmark/results/run-round51.sh`，对比脚本 `benchmark/results/compare-round51.py`

## 背景与定位

R50 收尾后按惯例做 JFR 归因复查（trade+listener+menu 三套件，`settings=profile`，
12,488 个 ObjectAllocationSample、46.7 GB 权重）。mock 机制噪声坐实为历史已知面
（Mockito LocationImpl 栈捕获 ~16 GB、stub 解析 ~3.3 GB——stubOnly 桩外的普通 mock
拦截机制，R38 起留档）；业务帧侧剩余大头均已在历轮触底（matcher 定位链/定位家族/
事务操作构造）。转向**调用面审查**后发现本轮主题：默认 `interaction.yml` 把站定左键
shopblock 映射到 `TRADE_INTERACTION`（R48 已取证），即**全服每次商店点击**都运行
`ShopUtil.sellToShop/buyFromShop`，而这条入口链在同一交互内对同一不变量重复计量：

| 重复度量 | 原调用次数/每击 | 生产成本构成 |
| --- | --- | --- |
| `sendShopInfo` 的 `shop.getItem()` | 至多 8 次（preview 分支 double-clone） | 每次 NBT 深拷贝（10-50μs 级，R48 留档） |
| `sendShopInfo` 库存/空间行 | 2 次全库存扫描（分支测试+显示值） | 每次 symbol-link 定位 + 54 槽匹配扫描 |
| 显式 `setSignText` + `onClick` 内渲染 | 2 次完整签名渲染 | 每次定位+扫描+4 行布局渲染+木牌写 |
| 出空门 + 上限计算的库存测量 | 2 次全库存扫描 | 同上 |
| 交易链防御克隆 | getItem→事务 ctor→操作 ctor→working 四重 | 生产为 NBT 深拷贝 ×4（必要 2 重） |

## 改动

1. **`SimpleShopManager.sendShopInfo` 单快照+单扫**：面板顶部一次 `getItem()` 快照贯穿
   全部行（preview 的 `items.clone()`、meta 读、附魔行、价格行的三次 getItem 与
   `MsgUtil.printEnchantment(shop,…)`）；库存/空间行的分支测试与显示值共用一次扫描。
2. **`ShopUtil.sellToShop/buyFromShop` 渲染去重**：onClick 紧随显式 setSignText 之后以
   同一 locale 渲染同一批木牌——无监听器时两次渲染逐位等价（共享 HandlerList 零监听器
   无观察面），显式渲染在 `!hasListeners()` 下跳过；有监听器时保持历史双渲染。
3. **门/上限计算扫描共享**：出空门（`getRemainingSpace()==0` / `getRemainingStock()==0`）
   的测量值经新参数透传 `getPlayerCanSell/getPlayerCanBuy`，上限计算不再重扫。
4. **交易链克隆采纳**：`SimpleInventoryTransaction.adopting(...)`（包内）与
   `Remove/AddItemOperation.adopting(...)`（`@ApiStatus.Internal`）让交易服务的新鲜
   `getItem()` 克隆逐层采纳——事务 item 字段或为 ctor 私有克隆或为可证无别名采纳，
   操作类从不直接突变 item（working 克隆承担全部突变），四重防御拷贝收敛为两重
   （getItem + working）；公共 builder/ctor 保持防御克隆语义供第三方调用方。

### 等价性论证

- **渲染去重（无监听器）**：onClick 无监听器路径的渲染与被跳过的显式渲染输入完全相同
  （同一 shop、同一 `findRelativeLanguages(p)` locale、中间无任何状态写入），共享
  HandlerList 零监听器下渲染次数无观察面——R50 构造门控同一论证结构。
- **渲染去重（有监听器）**：双渲染全保留，计数可观察面不变；用例
  `listenerKeepsTheExplicitSignRender` 钉住。
- **扫描共享与面板单快照**：同一方法/同一交互内对同一不变量的重复度量消除——R25 已入册
  的等价类（「监听器不能依赖同一渲染内两次相同事件」）。病态边界如实留档：若监听器在
  ItemPreviewComponentPrePopulateEvent/ShopClickEvent 内**变更**商店物品或库存，原代码
  的后一次读取会看到变更后的值、快照/透传读到调用时的值——任何依赖「同一交互内两次
  相同测量结果不同」的插件行为不在保持范围内（历轮一致立场）。
- **克隆采纳**：事务公共 ctor 克隆语义不变（`publicBuilderStillClonesDefensively` 钉住）；
  采纳路径可证无别名（fresh getItem 克隆仅局部持有→事务立即 failSafeCommit，单线程区域
  线程无窗口）；操作类的 working 克隆承担全部突变（`adoptedReferenceIsNeverMutated…`
  钉住零突变）；提交后内部无任何 getItem/operation.item 读取。

### 新增用例（9，合计 271 全绿）

- `ShopUtilEntryCollapseTest`（6）：无监听器零显式渲染+onClick 仍渲染（买卖两侧）、
  有监听器保持双渲染、买/卖入口恰两次扫描计量（面板 1+门 1）、拒止路径扫描数不变、
  自由店可卖上限受预计算空间约束（提示值 min(4,3)=3 逐值断言）、信息面板单 getItem
  单扫。
- `SimpleInventoryTransactionTest`（3）：采纳引用直用+提交成功（assertSame）、公共
  builder 保持防御克隆、采纳引用经操作循环零突变（verify never setAmount）。

## 基准结果

新用例 `trade/clickTradeEntry`：真实 `ShopUtil.sellToShop`（direct=false）对真实
ContainerShop+真实 chest wrapper 驱动整链；面板发送经空组件桩空转（隔离计量与渲染
成本、不含聊天流量），扫描/定位/渲染为真实对象。买方向 `actionBuy/actionSell` 含
交易链克隆消减；`inventoryTxCommit` 直接计量操作克隆消减（每 op 少 2 次 stateful
mock 创建）。两侧同体（R24 先例）。

### B/op 轴（主验收轴，近确定性）——六用例 fork 完全分离

| 用例 | 基线 B/op（3 fork） | R51 B/op（3 fork） | Δ | 分离度 |
| --- | --- | --- | --- | --- |
| `trade/clickTradeEntry` | 2,557,128 [2,556,552 / 2,557,128 / 2,592,544] | 1,446,463 [1,446,289 / 1,446,463 / 1,466,560] | **-43.4%** | 三 fork 零重叠，逐位分离 |
| `trade/inventoryTxCommit` | 408,392 [407,746 / 408,392 / 408,512] | 329,888 [329,602 / 329,888 / 329,901] | **-19.2%** | 完全分离 |
| `trade/tradeServiceBuy` | 928,176 [927,600 / 928,176 / 928,320] | 810,568 [810,400 / 810,568 / 810,616] | **-12.7%** | 完全分离 |
| `trade/tradeServiceSell` | 967,152 [966,378 / 967,152 / 967,388] | 849,384 [849,180 / 849,384 / 849,507] | **-12.2%** | 完全分离 |
| `trade/actionBuy` | 1,172,520 [1,171,752 / 1,172,520 / 1,187,272] | 1,054,913 [1,054,864 / 1,054,913 / 1,068,344] | **-10.0%** | 完全分离 |
| `trade/actionSell` | 1,197,072 [1,196,279 / 1,197,072 / 1,212,176] | 1,079,451 [1,079,416 / 1,079,451 / 1,093,237] | **-9.8%** | 完全分离 |

其余 52 共享用例 B/op 全部 ±1.0% 区间内零回归（多组逐位 ±0：signRender 633,000、
displayResend 34,344、rateLimitGate 24、countItemsScan54 277,528、locateSymbolLink
11,312 等完全同值）。留档：`text/fallbackYamlParse` +4.4% 为历轮双峰带既有形态
（基线三 fork 同落低峰 4,402,128，候选跨 4,402,128/4,594,224/4,644,048 双峰，与
R45/R46/R50 各轮的双向翻转同性质，代码路径未触碰）；`db/insertShopChain` +0.3%、
`startup/shopLoadChain` +0.1% 为粒度级带内漂移。

### ns/op 轴（本机参考）

`clickTradeEntry` **-38.0%**（3.94ms→2.44ms）、`inventoryTxCommit` **-23.2%**、
`tradeServiceBuy` -11.0%、`actionBuy/actionSell` -2.9%/-1.9% 同向。未触碰路径出现
+10~48% 的正向漂移（`startup/shopLoadChain` +47.9%、`fallbackYamlParse` +24.6%、
`serialize/generateLookupParams` +33.0%——后者 B/op 三 fork 逐位同值 1,832），为
R49 留档的本机双向噪声带形态：候选侧时段外部负载/预热差异对时序轴的系统性抬升，
以「B/op 逐位一致的未触碰路径同样漂移」佐证与代码无因果。

### 消减量模型自洽

- `inventoryTxCommit` -78,504 B/op：基准走公共 builder，基线每 op 5 次 stateful 克隆
  （ctor+两操作 ctor+两 working），候选 3 次（ctor+两 working——操作 ctor 的两次被
  采纳路径消除），差 2 次。
- `tradeServiceBuy/Sell` -117,608/-117,768 B/op：基线每笔 6 次（getItem+事务 ctor+两
  操作 ctor+两 working），候选 3 次（getItem+两 working），差 3 次。
- **交叉自洽**：两用例独立推导同一单克隆成本——78,504/2 = 39,252 B、
  117,608/3 = 39,203 B，每次 stateful mock 创建（含 Mockito 拦截面分配）≈39.2 KB，
  两轴严格一致。
- `actionBuy/actionSell` 消减 -117,607/-117,621 与 tradeService 侧 -117,608 逐位贴合
  （action 面的交易腿即 tradeService 链，action 面其余部分零变化）。
- `clickTradeEntry` -1,110,665 B/op ≈ 面板 7 次多余 stateful 克隆（7×39.2K≈275K）+
  3 次多余全库存扫描（3×~280K≈840K，同 countItemsScan54/countSpaceScan41 量级）——
  各构件量级与既有用例单测值同阶，总量 1,115K 与实测 1,110,665 拟合。生产面每击
  克隆次数 8→1、全库存扫描 6→3（面板 2+双渲染 2+门 1+上限 1 → 面板 1+onClick 渲染
  1+门 1）、签名渲染 2→1，生产克隆为 NBT 深拷贝（10-50μs/次，R48 留档），mock 面
  如实低估生产收益。

## 累计视图（R38–R51，分配轴，对 R37 后基线 fb785e159；括注为该用例首计轮）

| 用例 | R37 后基线 | R51 后 | 累计 |
|---|---|---|---|
| trade/actionBuy | 1,508,192 | 1,054,913 | **-30.1%** |
| trade/actionSell | 1,505,232 | 1,079,451 | **-28.3%** |
| trade/tradeServiceBuy | 1,121,977 | 810,568 | **-27.7%** |
| trade/tradeServiceSell | 1,133,787 | 849,384 | **-25.1%** |
| trade/inventoryTxCommit | —（R51 前 408,392） | 329,888 | **-19.2%** |
| trade/clickTradeEntry（R51 计量面） | —（R51 前 2,557,128） | 1,446,463 | **-43.4%** |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 6,008 | **-82.3%** |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 6,504 | **-86.4%** |
| listener/displayResend（R50 计量面） | —（R50 前 100,360） | 34,344 | -65.8% |
| trade/shopClickDispatch（R50 计量面） | —（R50 前 21,104） | 10,832 | -48.7% |
| listener/quickCreateGate（R48 计量面） | —（R48 前 20,224） | 10,144 | -49.8% |
| listener/rateLimitGate（R49 计量面） | —（R49 前 40） | 24 | -40.0% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,520 | **-63.4%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,620 | **-60.0%** |
| db/insertShopChain | —（R42 前 42,208） | 20,168 | **-52.2%** |
| db/listShops（R47 计量面） | —（R47 前 1,010,088） | 249,081 | -75.3% |
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,870,832 | -52.3% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,688 | -42.7% |

## 复现

```bash
bash benchmark/results/run-round51.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物逐 fork 自动拷回；跨轮检出前自动丢弃上一轮同步的基准文件
python benchmark/results/compare-round51.py
