# forecast

预测市场项目：客户用 USDT（ERC20 / BEP20 / TRC20，每人独立充值地址）充值后押注事件结果（彩池模式）。S1～S5 已完成（账户、KYC、账本、钱包、通知、后台权限从复制源复制；市场、彩池下注、结算退款新写，"充值 → 下注 → 结算 → 提现"已打通），下一步是 S6 自动裁决和原型。

先读：[`CLAUDE.md`](CLAUDE.md) → [《项目地图》](项目地图.md) → [《项目索引》](项目索引.md)。调研报告：[`调研/v1-Polymarket调研报告.md`](调研/v1-Polymarket调研报告.md)；需求说明书：[`需求文档/v1-需求说明书.md`](需求文档/v1-需求说明书.md)；架构方案：[`技术方案/v1-架构方案.md`](技术方案/v1-架构方案.md)；市场调研与推广策略：[`市场/v1-市场调研与推广策略.md`](市场/v1-市场调研与推广策略.md)；开发记录：[`开发记录/`](开发记录/)；测试环境：[`部署方案/v1-测试环境与切换.md`](部署方案/v1-测试环境与切换.md)；等 Amos 处理的事：[`Amos待办.md`](Amos待办.md)。

代码：`backend/`（Java 25、Spring Boot 4）、`web/client`、`web/admin`、`web/site`（React 18、Vite）、`contracts/`（Foundry）、`e2e/`（Playwright）、`deploy/`；CI 在 `.github/workflows/ci.yml`。
