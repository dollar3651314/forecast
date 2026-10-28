/**
 * 等候页上的台湾入金教程（forecast S19；S18 的 P2、SQ-63）：页面在繁中、上线前调 GET /api/public/pages/how-to-play?lang=zh-TW，
 * 只用里面 id = deposit-tw 的段落。Cloudflare 上没有后端，这里直接用后端同一份文字 backend/src/main/resources/content/public-pages.json 的 local
 * （构建时打包进 Worker，后端改了文字、重新部署就跟着变）：
 * - 只给这种语言 local 里的段落；标了 draft（待市场定稿）的不给，和后端一样页面不显示；
 * - 只认识 {brand}（上线前沿用 Quick Come，项目地图约定 17）；段落里有别的变量（参数设置里的数字，Cloudflare 上没有）就不给这一段。
 */
import pages from '../../backend/src/main/resources/content/public-pages.json';
import { json } from './http';
import { normLang } from './util';

export const BRAND = 'Quick Come';

interface Section {
  id: string;
  heading: string;
  items: string[];
  draft?: string;
}

type Local = Record<string, { howto?: { sections?: Section[] } } | undefined>;

export function localSections(lang: string, local: Local = (pages as unknown as { local?: Local }).local ?? {}): Section[] {
  const out: Section[] = [];
  for (const s of local[lang]?.howto?.sections ?? []) {
    if (s.draft) continue;
    const texts = [s.heading, ...s.items].map((t) => t.replace(/\{brand\}/g, BRAND));
    if (texts.some((t) => /\{\w+\}/.test(t))) continue;
    out.push({ id: s.id, heading: texts[0], items: texts.slice(1) });
  }
  return out;
}

export function howTo(url: URL): Response {
  const lang = normLang(url.searchParams.get('lang'));
  return json({ lang, sections: localSections(lang) }, 200, { 'Cache-Control': 'public, max-age=300' });
}
