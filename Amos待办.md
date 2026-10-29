# Amos 待办（forecast）
- 只放**要你处理或确认的事**。做完、答复过的直接删掉。历史记录看 git 提交历史，结论写在各开发记录里。
- 答复方式：同意建议就回复"按建议"；不同意就写编号和你的意见。
- 密钥只由你自己填进服务器，不要发给我。截图前遮住密码、key、`.env` 的内容。
- 版本：v1.37（2026-10-28）

## ⏸ 项目已暂停（2026-10-28）
Amos 2026-10-28："现在我已经将所有项目都暂停了"。整体调整的结论见 agents 仓库的《项目版图》（agents PR #30），重启时按新产品的五个阶段处理（C116）。v1.38 里的 T17～T23、SQ-43～73 和"用到时再做"都挂起，重启时从 git 历史里的 v1.38 取回再看。

## 🟠 要你做的事（暂停收尾，Amos 2026-10-29："所有都可以停掉"）
| # | 做什么 | 怎么确认做好了 |
|---|---|---|
| T24 | 停掉测试服务器上的 forecast 测试环境：登录服务器后运行 `sudo bash /opt/forecast/stop.sh`（会先备份数据库，只停不删，以后 `sudo bash /opt/forecast/start.sh` 原样恢复） | 最后打印的容器列表里 `forecast-` 开头的都是 `Exited`；把这段输出截图发我（输出里没有密钥） |
| T25 | 停掉 amos111 在 cron-job.org 的两个每分钟任务（生产 `amos111.vercel.app/api/tick/` 和测试环境那个）：cron-job.org → Cronjobs → 每个任务点开 → 关闭 Enabled 开关（不删除） | 两个任务都显示停用；Vercel 用量页里 amos111 的 CPU 之后不再增长 |
| T26 | （顺手）重新打开 Ubuntu 自带的软件包数据库备份：`sudo systemctl enable --now dpkg-db-backup.timer`（10-26 一键部署时为绕过检查关掉的） | `systemctl is-enabled dpkg-db-backup.timer` 显示 `enabled` |
