/**
 * Worker 的绑定和变量（forecast S19）。变量写在 wrangler.toml（不含密钥）；密钥由 Amos 在 Cloudflare 控制台填（K2），不经过 AI。
 * 控制台填的值不会被部署冲掉：wrangler.toml 里 `keep_vars = true`，密钥本来就不受部署影响。
 */
export interface Env {
  /** D1：订阅名单、限次、行情缓存、来源访问（cf/migrations/） */
  DB: D1Database;
  /** 静态资源（cf/dist）：公开版的等候页、官网 */
  ASSETS: Fetcher;
  /** production / staging / local（健康检查里显示；staging 另外返回 env 让页面显示测试标识条） */
  ENVIRONMENT?: string;
  /** 部署时 CI 用 --var VERSION:<提交号> 传进来 */
  VERSION?: string;
  /** 邮件里确认、退订链接的站点地址，例如 https://www.quickcome.online；没填时用请求自己的地址（staging 的 workers.dev） */
  PUBLIC_BASE_URL?: string;
  /** 发信地址，必须在 Resend 已验证的发信域名下 */
  MAIL_FROM?: string;
  /** 等候页是否还收邮箱（默认 true）；开放交易、名单迁走以后改成 false（P15） */
  WAITLIST_OPEN?: string;
  /** 官方 Telegram 链接（空 = 不显示） */
  TG_CHANNEL_ZH?: string;
  TG_CHANNEL_EN?: string;
  TG_BOT?: string;
  // ---------- 下面是密钥 / 控制台填的值 ----------
  /** Resend 的 API key（Sending access，只授权发信域名 fc-email.quickcome.online，C104）；没填 = 不发信，健康检查"未开通" */
  RESEND_API_KEY?: string;
  /** Cloudflare Access 的团队域名，例如 https://<团队名>.cloudflareaccess.com；和 ACCESS_AUD 都填了 /api/admin/ 才打开 */
  ACCESS_TEAM_DOMAIN?: string;
  /** Access 应用的 Application Audience (AUD) Tag */
  ACCESS_AUD?: string;
}
