/**
 * 官网首页：导航、首屏、平台优势、三步开始、资金和安全、常见问题、页脚。
 * 电脑版和手机版是两套结构（C64）：电脑版首屏左右两栏，手机版首屏只有标语和两个大按钮，各模块单列。
 * 文案全部在 src/i18n/*.json，内容只写系统已经做到的。
 * forecast S1：删除了复制源的公开行情面板和"热门品种"区块；首屏右栏先放占位卡片，彩池玩法说明由 PM 写（架构方案第 3 节，S11）。
 */
import { clientLink } from '../config';
import { Icon, type IconName } from '../components/Icon';
import { useI18n } from '../i18n';

const ADV: [string, IconName][] = [
  ['a1', 'coin'],
  ['a2', 'phone'],
];
const SAFE: [string, IconName][] = [
  ['s1', 'download'],
  ['s2', 'list'],
  ['s3', 'clock'],
  ['s4', 'id'],
];
const FAQ = ['1', '2', '3', '4'];

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

export function HomeDesktop() {
  const { t, lang } = useI18n();
  const reg = clientLink('register', lang);
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
                {t('common.register')}
              </a>
              <a className="btn sec lg" href={clientLink('login', lang)} data-testid="hero-login">
                {t('common.login')}
              </a>
            </div>
            <div className="points">
              <span>{t('hero.p1')}</span>
              <span>{t('hero.p2')}</span>
              <span>{t('hero.p3')}</span>
            </div>
          </div>
          <div className="card hero-note" data-testid="hero-note">
            {t('hero.note')}
          </div>
        </div>
      </div>

      <section className="blk" id="platform" data-testid="sec-platform">
        <div className="wrap">
          <Title title={t('adv.title')} sub={t('adv.sub')} />
          <div className="grid2">
            {ADV.map(([k, icon]) => (
              <div key={k} className="card">
                <div className="ico">
                  <Icon name={icon} />
                </div>
                <h3>{t(`adv.${k}t`)}</h3>
                <p>{t(`adv.${k}d`)}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      <section className="blk" id="start" data-testid="sec-steps">
        <div className="wrap">
          <Title title={t('steps.title')} />
          <div className="steps">
            {['s1', 's2', 's3'].map((s, i) => (
              <div key={s} className="card step">
                <span className="n">{i + 1}</span>
                <h3>{t(`steps.${s}t`)}</h3>
                <p>{t(`steps.${s}d`)}</p>
              </div>
            ))}
          </div>
          <div style={{ marginTop: 24 }}>
            <a className="btn pri" href={reg}>
              {t('common.registerNow')}
            </a>
          </div>
        </div>
      </section>

      <section className="blk" id="safety" data-testid="sec-safety">
        <div className="wrap">
          <Title title={t('safe.title')} />
          <div className="grid-safe">
            {SAFE.map(([k, icon]) => (
              <div key={k} className="card">
                <div className="ico">
                  <Icon name={icon} />
                </div>
                <h3>{t(`safe.${k}t`)}</h3>
                <p>{t(`safe.${k}d`)}</p>
              </div>
            ))}
          </div>
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
          <Title title={t('final.title')} sub={t('final.sub')} />
          <div style={{ marginTop: 22 }}>
            <a className="btn pri lg" href={reg}>
              {t('common.register')}
            </a>
          </div>
        </div>
      </section>
    </main>
  );
}

export function HomeMobile() {
  const { t, lang } = useI18n();
  const reg = clientLink('register', lang);
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
            {t('common.register')}
          </a>
          <a className="btn sec" href={clientLink('login', lang)} data-testid="hero-login">
            {t('common.login')}
          </a>
        </div>
      </div>

      <section className="blk" id="platform" data-testid="sec-platform">
        <Title title={t('adv.title')} />
        <div className="list">
          {ADV.map(([k, icon]) => (
            <div key={k} className="card">
              <div className="ico">
                <Icon name={icon} />
              </div>
              <div>
                <h3>{t(`adv.${k}t`)}</h3>
                <p>{t(`adv.${k}d`)}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="blk" id="start" data-testid="sec-steps">
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

      <section className="blk" id="safety" data-testid="sec-safety">
        <Title title={t('safe.title')} />
        <div className="list">
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
        <div className="tbd license">{t('safe.license')}</div>
      </section>

      <section className="blk" id="faq" data-testid="sec-faq">
        <Title title={t('faq.title')} />
        <Faq />
      </section>

      <section className="blk final">
        <Title title={t('final.title')} sub={t('final.sub')} />
      </section>
    </main>
  );
}
