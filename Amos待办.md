# Amos 待办（forecast）
- 只放**要你处理或确认的事**。做完、答复过的直接删掉。历史记录看 git 提交历史，结论写在各开发记录里。
- 答复方式：同意建议就回复"按建议"；不同意就写编号和你的意见。
- 密钥只由你自己填进服务器，不要发给我。截图前遮住密码、key、`.env` 的内容。
- 版本：v1.38（2026-10-28）

## 🟠 要你做的事
| # | 做什么 | 怎么确认做好了 |
|---|---|---|
| T17 | 后台 https://fc-test-admin.quickcome.online →"加密自动生成"→ 打开 BTC 1 小时 → 检查后点"启用"。场次是 UTC（北京时间减 8 小时），`1:00`、`01:00` 都可以 | 几分钟内客户端首页出现 BTC 市场 |
| T18 | 测试入金、人工调账：后台 → 客户 → 详情 → 右上角"测试入金"给自己的测试账号加钱，然后下注、等结算、试提现；"人工加款 / 人工扣款"也试一次 | 客户端资产和资金流水里看到；后台 → 资金 → 人工调账记录里有记录 |
| T19 | Threads 应用第一部分（约 20 分钟，随时可做）：按 `部署方案/v1-Threads应用申请清单.md` 第 1～4 步注册 Meta 开发者、建应用、加发帖权限、把测试账号登记为测试者并接受邀请。第 5～7 步（拿令牌）等我通知再做 | 清单第 4 步的"怎么确认"都通过 |
| T21 | Cloudflare 公开站测试版（约 40 分钟）：按 `部署方案/v1-公开部分Cloudflare操作清单.md` 第 1～4 步：确认账号是 Free、建部署令牌并填 GitHub Secrets `CLOUDFLARE_API_TOKEN`、`CLOUDFLARE_ACCOUNT_ID` → 在 GitHub Actions 里把 staging 上最近一次 ci 点 **Re-run all jobs** → 测试版加 Access、填三个 Secret（含新建的 Resend key）→ 自己走一遍。截图前遮住令牌和 `re_` key | `/api/health` 里 `mail`、`admin`、`board` 都是 ok；清单 4.3 的 4 步都对；把 `/api/health` 截图发我 |
| T22 | T21 验收通过后对外：清单第 5 步（正式版填密钥、建名单页 Access、**删掉 `www` 的 A 记录**、绑自定义域名）。测试服务器上**不要运行** `sudo fc add-domain` | 打开 `https://www.quickcome.online/api/health` 显示 `"environment":"production"` |
| T2 | 给朋友的情况简介 https://claude.ai/artifact/CYg8Ei1uhynh1YLm6NSeey ：在右上角"分享"里打开访问权限 | 朋友能打开链接 |

## ❓ 等你回答的问题
| 编号 | 问题 | 建议 |
|---|---|---|
| SQ-43 | 等候页现在写"即将上线"，要不要写具体上线日期 | 上线日期定了再告诉我 |
| SQ-50 | 申请证书时要不要留到期提醒邮箱 | 不留（证书自动续期） |
| SQ-52 | 切换命令 `fc` 和系统自带命令同名，必须带 `sudo` | 保持 `fc`，一律写 `sudo fc` |
| SQ-53 | 充值合约地址一个都没填时算"未开通"、不发告警邮件 | 按未开通处理，上线检查单里查"合约已部署" |
| SQ-54 | 后台界面只有中文和英文 | 后台不加繁体 |
| SQ-55 | ① 24 小时提醒间隔要不要放进后台 ② 要不要让重启后还记得已发过的告警 | 都先不做 |
| SQ-56 | 测试入金单笔上限 | 10,000 USDT |
| SQ-57 | 人工调账超过单笔上限时怎么办 | 再勾一次确认就放行 |
| SQ-58 | 人工调账哪些类别算作充值（影响风控和提现规则） | 只有"充值未到账补入" |
| SQ-59 | 24 小时人工加款告警阈值 | 10,000 USDT |
| SQ-60 | 人工调账的三个类别够不够 | 先用着，需要再加 |
| SQ-61 | www 上想进产品的人要不要跳到测试环境 | 不跳 |
| SQ-62 | 台湾入金教程只在繁中显示，还是三种语言都显示 | 只在繁中 |
| SQ-63 | 等候页上显示入金教程吗 | 显示，可以展开 |
| SQ-64 | AI 直接按台湾用语写稿吗 | 网站页面继续"自动转换 + 词表"；X、Threads 繁中帖子直接按台湾用语写 |
| SQ-65 | "充值""提现"要不要改叫"入金/儲值""提領" | 等市场校对时一起定 |
| SQ-66 | 正式上线时 www 怎么办 | 上线前一天迁订阅名单，再把 www 指向正式环境，发上线通知（S19 后按 Cloudflare 版调整） |
| SQ-67 | 公开站打开时的语言：要不要改成"不是简中、英文浏览器一律繁中" | 不改：推广链接都带 `?lang=zh-TW` |
| SQ-68 | S18 的"www 指向测试服务器"代码（`sudo fc add-domain` 等）删不删 | 先留着不启用，开放交易时一起清理 |
| SQ-69 | 测试版也填 Resend key、真的发信吗 | 要（测试版有 Access 保护，只有你能用） |
| SQ-70 | www 定时健康探测：加 GitHub Actions 定时任务（每 30 分钟，失败 GitHub 发邮件）吗 | 加（你同意后再做） |
| SQ-71 | 隐私政策要不要写明邮箱存在 Cloudflare、经 Resend 发信 | 写，定稿法律页时加一句 |
| SQ-72 | 看板多久取一次行情 | 5 分钟 |
| SQ-73 | 内容发布（Threads、X 等）原来预留叫 S19，和本次重名 | 改叫 S20 |

## ⏸ 用到时再做
在服务器上填 key：`sudo fc set-key 变量名`，回车后粘贴，屏幕不显示，会自动生效。

| # | 做什么 | 什么时候做 |
|---|---|---|
| T4 | Gemini：在 Google AI Studio 申请免费 key → `sudo fc set-key FC_GEMINI_API_KEY` | 要用自动化文章、体育抓取解析时 |
| T14a | football-data.org：在 https://www.football-data.org/client/register 注册免费档，token 会发到邮箱 → `sudo fc set-key FC_SRC_FOOTBALL_DATA_TOKEN` | 要测足球市场时 |
| T14b | balldontlie：在 https://app.balldontlie.io 注册免费档 → `sudo fc set-key FC_SRC_BALLDONTLIE_KEY` | 要测 NBA 市场时 |
| T14c | TheSportsDB：上线时买约 $9/月的档 → `sudo fc set-key FC_SRC_THESPORTSDB_KEY` | 上线前 |
| T16 | Telegram 机器人：在 BotFather 建机器人 → `sudo fc set-key FC_TELEGRAM_BOT_TOKEN` → 建频道、把机器人设为管理员 → 后台"Telegram 机器人"页填频道并打开 → 确认开奖卡模板（细节见 `开发记录/S16-等候页和机器人.md` 第 11 节） | 要做频道推广时 |
| T5～T10 | 充值合约：建 Safe 冷钱包和两个新账户 → 部署合约 → 填地址 → 测试网充值和归集验收（步骤见 `部署方案/v1-归集合约部署.md`，到时我再做成一条命令） | 要验收充值、提现时 |
| T23 | 开放交易那天：按 Cloudflare 操作清单第 6 步关等候页 → 导出名单 → 生产后台导入 → 发上线通知 | 开放交易那天 |
