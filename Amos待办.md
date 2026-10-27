# Amos 待办（forecast）
- 只放**要你处理或确认的事**。做完、答复过的直接删掉。历史记录看 git 提交历史，结论写在各开发记录里。
- 答复方式：同意建议就回复"按建议"；不同意就写编号和你的意见。
- 密钥只由你自己填进服务器，不要发给我。截图前遮住密码、key、`.env` 的内容。
- 版本：v1.34（2026-10-27）

## 🟠 要你做的事
| # | 做什么 | 怎么确认做好了 |
|---|---|---|
| T17 | 后台 https://fc-test-admin.quickcome.online →"加密自动生成"→ 打开 BTC 1 小时 → 检查后点"启用"。场次是 UTC，要写两位格式，例如北京 09:00 写 `01:00`（`1:00` 报错的问题正在修） | 几分钟内客户端首页出现 BTC 市场 |
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
