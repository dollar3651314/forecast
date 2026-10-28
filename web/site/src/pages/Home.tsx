/**
 * 官网首页（M1 原型）：首屏（标语 + 静态示例市场）、能押什么、三步看懂彩池、费率（只对盈利抽成，用示例市场算一遍）、资金和结果、常见问题。
 * 电脑版和手机版是两套结构（C64）：电脑版首屏左右两栏、各区块多列；手机版首屏只有标语、两个大按钮和示例卡片，各区块单列。
 * 示例市场是静态的，不滚动、不闪动（C84，营造氛围的元素放在官网也不做跑马灯）。示例数字是需求第 3.5 节的例子（162.29 = 100 + 65.57 − 3.28）。
 * 文案在 src/i18n/*.json（三种语言），内容只写系统已经做到或已定的规则。
 */
import { clientLink, PUBLIC_SITE, SIGNUP_KEY, signupLink } from '../config';
import { Icon, type IconName } from '../components/Icon';
import { useI18n } from '../i18n';

const CATS: [string, IconName][] = [
  ['football', 'ball'],
  ['nba', 'hoop'],
  ['crypto', 'btc'],
  ['macro', 'bank'],
];
const SAFE: [string, IconName][] = [
  ['s1', 'download'],
  ['s2', 'list'],
  ['s3', 'scale'],
  ['s4', 'id'],
];
const FAQ = ['1', '2', '3', '4'];
/** 示例市场（需求第 3.5 节）：奖池 6,000 / 1,500 / 2,500，概率 60% / 15% / 25%，扣费后 1.63x / 6.38x / 3.85x */
const EX: Array<[string, number, string]> = [
  ['ex.o1', 60, '1.63x'],
  ['ex.o2', 15, '6.38x'],
  ['ex.o3', 25, '3.85x'],
];

function Title({ title, sub }: { title: string; sub?: string }) {
  return (
    <>
      <h2 className="sec-title">{title}</h2>
      {sub && <p className="sec-sub">{sub}</p>}
    </>
  );
}

function Faq() {
  const { t } = useI18n();
  return (
    <div className="faq">
      {FAQ.map((n, i) => (
        <details key={n} className="faq-item" open={i === 0} data-testid="faq-item">
          <summary>{t(`faq.q${n}`)}</summary>
          <p>{t(`faq.a${n}`)}</p>
        </details>
      ))}
    </div>
  );
}

/** 静态示例市场卡片 */
function ExampleCard() {
  const { t } = useI18n();
  return (
    <div className="card ex-card" data-testid="hero-note">
      <div className="ex-meta">
        <span className="ex-tag">{t('ex.tag')}</span>
        <span>{t('ex.league')}</span>
      </div>
      <b className="ex-title">{t('ex.title')}</b>
      <div className="ex-rows">
        {EX.map(([k, p, m]) => (
          <div key={k} className="ex-row">
            <span className="ex-bar" style={{ width: `${p}%` }} />
            <span className="ex-name">{t(k)}</span>
            <b className="num">{p}%</b>
            <small className="num">{m}</small>
          </div>
        ))}
      </div>
      <div className="ex-foot">
        <span>{t('ex.pool')}</span>
      </div>
      <p className="ex-calc num">{t('ex.calc')}</p>
      <p className="ex-note">{t('ex.note')}</p>
    </div>
  );
}

/** 费率：用示例市场算一遍 */
function FeeTable() {
  const { t } = useI18n();
  const rows: Array<[string, string, string?]> = [
    ['fee.r1', '100.00'],
    ['fee.r2', '+ 65.57'],
    ['fee.r3', '− 3.28'],
    ['fee.r4', '162.29', 'strong'],
  ];
  return (
    <dl className="fee-table">
      {rows.map(([k, v, c]) => (
        <div key={k} className={c}>
          <dt>{t(k)}</dt>
          <dd className="num">{v} USDT</dd>
        </div>
      ))}
    </dl>
  );
}

function Cats({ desktop }: { desktop: boolean }) {
  const { t } = useI18n();
  return (
    <div className={desktop ? 'grid4' : 'list'}>
      {CATS.map(([k, icon]) => (
        <div key={k} className="card">
          <div className="ico">
            <Icon name={icon} />
          </div>
          <div>
            <h3>{t(`cats.${k}`)}</h3>
            <p>{t(`cats.${k}D`)}</p>
          </div>
        </div>
      ))}
    </div>
  );
}

function Safe({ desktop }: { desktop: boolean }) {
  const { t } = useI18n();
  return (
    <div className={desktop ? 'grid-safe' : 'list'}>
      {SAFE.map(([k, icon]) => (
        <div key={k} className="card">
          <div className="ico">
            <Icon name={icon} />
          </div>
          <div>
            <h3>{t(`safe.${k}t`)}</h3>
            <p>{t(`safe.${k}d`)}</p>
          </div>
        </div>
      ))}
    </div>
  );
}

export function HomeDesktop() {
  const { t, lang } = useI18n();
  const reg = signupLink(lang);
  return (
    <main>
      <div className="hero" data-testid="hero">
        <div className="wrap">
          <div>
            <h1>
              {t('hero.titleA')}
              <br />
              <em>{t('hero.titleB')}</em>
            </h1>
            <p className="sub">{t('hero.sub')}</p>
            <div className="cta">
              <a className="btn pri lg" href={reg} data-testid="hero-register">
                {t(SIGNUP_KEY)}
              </a>
              {!PUBLIC_SITE && (
                <a className="btn sec lg" href={clientLink('login', lang)} data-testid="hero-login">
                  {t('common.login')}
                </a>
              )}
            </div>
            <div className="points">
              <span>{t('hero.p1')}</span>
              <span>{t('hero.p2')}</span>
              <span>{t('hero.p3')}</span>
            </div>
          </div>
          <ExampleCard />
        </div>
      </div>

      <section className="blk" id="how" data-testid="sec-platform">
        <div className="wrap">
          <Title title={t('cats.title')} sub={t('cats.sub')} />
          <Cats desktop />
          <div className="steps" data-testid="sec-steps">
            {['s1', 's2', 's3'].map((s, i) => (
              <div key={s} className="card step">
                <span className="n">{i + 1}</span>
                <h3>{t(`steps.${s}t`)}</h3>
                <p>{t(`steps.${s}d`)}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="blk" id="fee" data-testid="sec-fee">
        <div className="wrap fee-wrap">
          <div>
            <Title title={t('fee.title')} sub={t('fee.sub')} />
            <p className="fee-note">{t('fee.note')}</p>
          </div>
          <FeeTable />
        </div>
      </section>

      <section className="blk" id="safety" data-testid="sec-safety">
        <div className="wrap">
          <Title title={t('safe.title')} />
          <Safe desktop />
          <div className="tbd license">{t('safe.license')}</div>
        </div>
      </section>

      <section className="blk" id="faq" data-testid="sec-faq">
        <div className="wrap">
          <Title title={t('faq.title')} />
          <Faq />
        </div>
      </section>

      <section className="blk final">
        <div className="wrap">
          <Title title={t(PUBLIC_SITE ? 'final.titleWaitlist' : 'final.title')} sub={t(PUBLIC_SITE ? 'final.subWaitlist' : 'final.sub')} />
          <div style={{ marginTop: 22 }}>
            <a className="btn pri lg" href={reg}>
              {t(SIGNUP_KEY)}
            </a>
          </div>
        </div>
      </section>
    </main>
  );
}

export function HomeMobile() {
  const { t, lang } = useI18n();
  const reg = signupLink(lang);
  return (
    <main>
      <div className="hero" data-testid="hero">
        <h1>
          {t('hero.titleA')}
          <br />
          <em>{t('hero.titleB')}</em>
        </h1>
        <p className="sub">{t('hero.subMobile')}</p>
        <div className="cta">
          <a className="btn pri" href={reg} data-testid="hero-register">
            {t(SIGNUP_KEY)}
          </a>
          {!PUBLIC_SITE && (
            <a className="btn sec" href={clientLink('login', lang)} data-testid="hero-login">
              {t('common.login')}
            </a>
          )}
        </div>
        <ExampleCard />
      </div>

      <section className="blk" id="how" data-testid="sec-platform">
        <Title title={t('cats.title')} />
        <Cats desktop={false} />
      </section>

      <section className="blk" data-testid="sec-steps">
        <Title title={t('steps.title')} />
        <div className="steps">
          {['s1', 's2', 's3'].map((s, i) => (
            <div key={s} className="step">
              <span className="n">{i + 1}</span>
              <h3>{t(`steps.${s}t`)}</h3>
              <p>{t(`steps.${s}d`)}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="blk" id="fee" data-testid="sec-fee">
        <Title title={t('fee.title')} sub={t('fee.sub')} />
        <FeeTable />
        <p className="fee-note">{t('fee.note')}</p>
      </section>

      <section className="blk" id="safety" data-testid="sec-safety">
        <Title title={t('safe.title')} />
        <Safe desktop={false} />
        <div className="tbd license">{t('safe.license')}</div>
      </section>

      <section className="blk" id="faq" data-testid="sec-faq">
        <Title title={t('faq.title')} />
        <Faq />
      </section>

      <section className="blk final">
        <Title title={t(PUBLIC_SITE ? 'final.titleWaitlist' : 'final.title')} sub={t(PUBLIC_SITE ? 'final.subWaitlist' : 'final.sub')} />
      </section>
    </main>
  );
}
