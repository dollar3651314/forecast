/** 服务条款、隐私政策、风险披露（v2 需求 4.3）：顶部标"需法务审核"，正文在 src/content/legal.ts。 */
import { Link } from '../app/router';
import { LEGAL, LEGAL_UPDATED } from '../content/legal';
import { useI18n } from '../i18n';
import type { LegalPage as Which } from '../lib/route';

export function LegalPage({ which }: { which: Which }) {
  const { t, lang } = useI18n();
  const doc = LEGAL[which][lang];
  return (
    <main className="legal" data-testid={`legal-${which}`}>
      <div className="wrap">
        <div className="review" role="note" data-testid="legal-review">
          <b>{t('legal.review')}</b>
          <span>{t('legal.reviewNote')}</span>
        </div>
        <h1>{doc.title}</h1>
        <p className="updated">
          {t('legal.updated')}: {LEGAL_UPDATED}
        </p>
        <div className="intro">
          {doc.intro.map((p, i) => (
            <p key={i}>{p}</p>
          ))}
        </div>
        <nav className="card toc" aria-label={t('legal.contents')}>
          <h2>{t('legal.contents')}</h2>
          <ol>
            {doc.sections.map((s, i) => (
              <li key={i}>
                <a href={`#s${i + 1}`}>{s.h}</a>
              </li>
            ))}
          </ol>
        </nav>
        {doc.sections.map((s, i) => (
          <section key={i}>
            <h2 id={`s${i + 1}`}>{s.h}</h2>
            {s.p.map((p, j) => (
              <p key={j}>{p}</p>
            ))}
          </section>
        ))}
        <Link to="home" className="back">
          ← {t('common.backHome')}
        </Link>
      </div>
    </main>
  );
}
