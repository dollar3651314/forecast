# forecast

预测市场项目：客户用 USDT（ERC20 / BEP20 / TRC20，每人独立充值地址）充值后押注事件结果（彩池模式）。当前处于开发 S1（复制骨架）：账户、KYC、账本、钱包、通知、后台权限从复制源复制，市场、彩池、裁决、结算从 S2 起新写。

先读：[`CLAUDE.md`](CLAUDE.md) → [《项目地图》](项目地图.md) → [《项目索引》](项目索引.md)。调研报告：[`调研/v1-Polymarket调研报告.md`](调研/v1-Polymarket调研报告.md)；需求说明书：[`需求文档/v1-需求说明书.md`](需求文档/v1-需求说明书.md)；架构方案：[`技术方案/v1-架构方案.md`](技术方案/v1-架构方案.md)；开发记录：[`开发记录/S1-复制骨架.md`](开发记录/S1-复制骨架.md)。

代码：`backend/`（Java 25、Spring Boot 4）、`web/client`、`web/admin`、`web/site`（React 18、Vite）、`contracts/`（Foundry）、`e2e/`（Playwright）、`deploy/`；CI 在 `.github/workflows/ci.yml`。
