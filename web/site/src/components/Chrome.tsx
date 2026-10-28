/**
 * 每个页面都有的部分：测试环境标识、顶部风险提示条、导航（电脑、手机两套）、页脚、手机底部固定的"登录 | 免费注册"。
 */
import { useEffect, useId, useRef, useState } from 'react';
import { fetchEnvLabel } from '../api/envLabel';
import { Link } from '../app/router';
import { clientLink, PUBLIC_SITE, SIGNUP_KEY, signupLink } from '../config';
import { BRAND_NAME, CONTACT_EMAIL } from '../lib/brand';
import { useI18n, type Lang } from '../i18n';
import { Icon } from './Icon';

export function EnvBanner() {
  const { t } = useI18n();
  const [env, setEnv] = useState('');
  useEffect(() => {
    let alive = true;
    void fetchEnvLabel().then((v) => alive && setEnv(v));
    return () => {
      alive = false;
    };
  }, []);
  if (!env) return null;
  return (
    <div className="env-banner" role="note" data-testid="env-banner">
      {t('common.envTest')}
    </div>
  );
}

export function RiskBar({ short }: { short?: boolean }) {
  const { t } = useI18n();
  return (
    <div className="risk-bar" role="note" data-testid="risk-bar">
      {t(short ? 'risk.barShort' : 'risk.bar')}
      <Link to="risk">{t('risk.link')}</Link>
    </div>
  );
}

/**
 * 文字 Logo：和客户端 web/client/src/components/Icon.tsx 的 Logo / Brand 同一个图形（蓝色渐变圆角方块（方向 A） + 三根柱子，占位图形，品牌定了以后换），
 * 也和 public/favicon.svg 一致；客户端改图形时同步改这里。
 */
export function Logo() {
  const id = `logo-g-${useId().replace(/:/g, '')}`;
  return (
    <Link to="home" className="logo" data-testid="logo">
      <svg className="mark" width={28} height={28} viewBox="0 0 512 512" aria-hidden="true" focusable="false">
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#3b82f6" />
            <stop offset="1" stopColor="#1d4ed8" />
          </linearGradient>
        </defs>
        <rect width="512" height="512" rx="112" fill={`url(#${id})`} />
        <rect x="136" y="236" width="64" height="140" rx="20" fill="#ffffff" />
        <rect x="224" y="156" width="64" height="220" rx="20" fill="#ffffff" />
        <rect x="312" y="196" width="64" height="180" rx="20" fill="#ffffff" />
      </svg>
      <span className="word">{BRAND_NAME}</span>
    </Link>
  );
}

const LANG_OPTIONS: Array<{ value: Lang; label: string; short: string }> = [
  { value: 'zh-CN', label: '简体中文', short: '简体' },
  { value: 'zh-TW', label: '繁體中文', short: '繁體' },
  { value: 'en', label: 'English', short: 'EN' },
];

/**
 * 语言下拉框（体验优化 1021 UX-1，和客户端 web/client/src/components/LangSelect.tsx 一样的交互）：
 * 按钮显示**当前**语言，点开是下拉列表，当前语言打勾并标"当前 / Current"，点另一项切换并记住（lang.ts）。
 * 默认跟随浏览器语言（中文浏览器用中文，其他语言或取不到时用英文）。Esc、点外面关闭。
 */
export function LangSwitch() {
  const { lang, setLang, t } = useI18n();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const listId = useId();
  const cur = LANG_OPTIONS.find((o) => o.value === lang) ?? LANG_OPTIONS[0];

  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('pointerdown', close);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);

  return (
    <div className="lang" ref={ref} data-testid="lang-switch">
      <button
        type="button"
        className="lang-btn"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        aria-label={`${t('common.langLabel')}: ${cur.label}`}
        onClick={() => setOpen((o) => !o)}
        lang={cur.value}
      >
        <Icon name="globe" />
        <span>{cur.short}</span>
        <Icon name="chevronDown" />
      </button>
      {open && (
        <ul className="lang-list" role="listbox" id={listId} aria-label={t('common.langLabel')}>
          {LANG_OPTIONS.map((o) => {
            const on = o.value === lang;
            return (
              <li key={o.value} role="option" aria-selected={on} lang={o.value}>
                <button
                  type="button"
                  className={on ? 'on' : ''}
                  onClick={() => {
                    setOpen(false);
                    if (!on) setLang(o.value);
                  }}
                >
                  <span>{o.label}</span>
                  {on && (
                    <span className="lang-current">
                      <Icon name="check" />
                      {t('common.langCurrent')}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

/** 首页的几个区块（导航锚点） */
export const SECTIONS = [
  ['how', 'nav.how'],
  ['fee', 'nav.fee'],
  ['safety', 'nav.safety'],
  ['faq', 'nav.faq'],
] as const;

export function HeaderDesktop() {
  const { t, lang } = useI18n();
  return (
    <header className="top">
      <div className="wrap">
        <Logo />
        <nav aria-label="main">
          {SECTIONS.map(([id, key]) => (
            <Link key={id} to="home" hash={id}>
              {t(key)}
            </Link>
          ))}
        </nav>
        <div className="right">
          <LangSwitch />
          {!PUBLIC_SITE && (
            <a className="btn sec sm" href={clientLink('login', lang)} data-testid="nav-login">
              {t('common.login')}
            </a>
          )}
          <a className="btn pri sm" href={signupLink(lang)} data-testid="nav-register">
            {t(SIGNUP_KEY)}
          </a>
        </div>
      </div>
    </header>
  );
}

export function HeaderMobile() {
  const { t, lang } = useI18n();
  const [open, setOpen] = useState(false);
  return (
    <>
      <header className="top">
        <Logo />
        <div className="right">
          <LangSwitch />
          <button type="button" className="menu-btn" aria-label={t(open ? 'common.close' : 'common.menu')} aria-expanded={open} onClick={() => setOpen((v) => !v)} data-testid="menu-btn">
            <Icon name={open ? 'close' : 'menu'} />
          </button>
        </div>
      </header>
      {open && (
        <div className="drawer" onClick={() => setOpen(false)} data-testid="menu">
          <div className="drawer-panel" onClick={(e) => e.stopPropagation()}>
            {SECTIONS.map(([id, key]) => (
              <Link key={id} to="home" hash={id} className="nav" onClick={() => setOpen(false)}>
                {t(key)}
              </Link>
            ))}
            {PUBLIC_SITE ? (
              <a className="btn pri" href={signupLink(lang)} data-testid="menu-waitlist">
                {t(SIGNUP_KEY)}
              </a>
            ) : (
              <a className="btn sec" href={clientLink('login', lang)}>
                {t('common.login')}
              </a>
            )}
          </div>
        </div>
      )}
    </>
  );
}

export function Footer() {
  const { t } = useI18n();
  return (
    <footer data-testid="footer">
      <div className="wrap">
        <Logo />
        <div className="links">
          <Link to="terms">{t('footer.terms')}</Link>
          <Link to="privacy">{t('footer.privacy')}</Link>
          <Link to="risk">{t('footer.risk')}</Link>
          {CONTACT_EMAIL ? (
            <a href={`mailto:${CONTACT_EMAIL}`} data-testid="contact">
              {t('footer.contact', { email: CONTACT_EMAIL })}
            </a>
          ) : (
            <span className="tbd" data-testid="contact">
              {t('footer.contactTbd')}
            </span>
          )}
        </div>
        <p className="risk-full" data-testid="footer-risk">
          {t('risk.full')}
        </p>
        <div className="meta">
          <span className="tbd">
            {t('footer.company')} · {t('safe.license')}
          </span>
          <span>{t('footer.copyright')}</span>
        </div>
      </div>
    </footer>
  );
}

export function StickyCta() {
  const { t, lang } = useI18n();
  return (
    <div className="sticky-cta" data-testid="sticky-cta">
      {!PUBLIC_SITE && (
        <a className="btn sec" href={clientLink('login', lang)} data-testid="sticky-login">
          {t('common.login')}
        </a>
      )}
      <a className="btn pri" href={signupLink(lang)} data-testid="sticky-register">
        {t(SIGNUP_KEY)}
      </a>
    </div>
  );
}
