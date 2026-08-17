# QuickShop-Hikari 性能优化报告·第六轮（收据与通知路径单次取数，2026-08-17）

> 基准程序与方法论同前（34 用例 × 7 套件；同态交替 A/B，基线 commit `414b5eea2` =
> R17 闭合并提交文档后的 HEAD，各 3 fork 中位数）。

## R18：收据/通知单次 getItem（commit a03abce10）

### 问题

`ContainerShop.getItem()` 每次调用克隆商店物品并派发 RETRIEVE 事件。交易成功后的收据与
店主通知路径对其逐行重复调用：`sendPurchaseSuccess` 4 次（数量×2、名称×2）+
`printEnchantment` 2 次；`notifyBought` 4~6 次；`notifySold`/`sendSellSuccess` 各 2~4 次——
同一笔交易读的是同一个不可变物品。

### 改动

四个方法各取一次 `getItem()` 并复用（物品名组件额外提为局部值）；`MsgUtil.printEnchantment`
增加直接传栈重载（旧 `(Shop, printer)` 签名保留委托）。失败分支与第三方路径不动。
每笔成功交易省 4~6 次克隆与 RETRIEVE 事件派发（mock 下克隆打桩为自返回，生产为真实
ItemStack.clone + 事件快路径检查）。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 |
|---|---:|---:|---:|
| trade/actionBuy | 4,505,700 | 4,112,398 | **-8.7%**（fork：base 5.40/4.45/4.51 vs cand 4.04/4.17/4.11——3 个候选 fork 全部快于除一个漂移基线 fork 外的全部基线） |
| trade/actionSell | 4,605,771 | 4,545,953 | -1.3%（卖方向收据仅一行，改动面小，带内） |
| trade/tradeServiceBuy / Sell（未触碰） | — | — | +2.8% / +12.8%（漂移；tradeServiceSell 零改动） |

其余对照组 ±6% 内。结构性事实：买方向收据的 getItem 调用从 6 次降为 1 次、通知从 4~6 次降为
1 次——mock 基准克隆免费（自返回桩），生产收益更大。

### 测试与红线

91 用例全绿（无新增用例——该轮为纯局部取数去重，行为恒等：同一方法内多次读同一物品与读一次
复用在单线程下等价）；`printEnchantment` 旧签名保留；资金路径零改动。

## 五轮总览（2026-08-17 会话，R15–R18）

| 轮 | 主题 | 关键数字 |
|---|---|---|
| R15 | action 层重复扫描消除 + 匹配器类型门 + 卖方向木牌去重 | actionBuy/Sell -73.0%/-66.7% |
| R16 | 匹配器 shopId 身份缓存 + 指标插入批处理 | 扫描 -17.6%/-20.7%；指标每条 -32.6% |
| R17 | qs_external_cache / qs_messages 批处理（按键保末值） | 外部缓存每条 -67.7%，同店连点 N 行→1 行 |
| R18 | 收据/通知单次 getItem | actionBuy 再 -8.7% |

玩家交易全链（第二轮基线 23.0ms → 本会话末 4.11ms，跨轮连乘供参考）：**买 -82.1%、
卖约 -80%**；每笔成功交易的 DB 写从 3 条语句降为每窗口至多 3 条批语句；主线程新增成本均为
百 ns 级队列入队。测试 67→91 用例，基准 26→34 用例，实机 E2E（Paper 1.21.11 + Vault +
EssentialsX 双假人）验证建店→交易→金额守恒全链。
