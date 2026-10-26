/**
 * 内容中心的模拟层（forecast S15；原型预览站、单元测试）：数据结构和后端完全一致（api/fcTypes.ts 的内容中心部分），只是放在内存里。
 * 草稿箱里有每种状态：待审核（检查通过 / 数字对不上标红）、生成失败（禁用词）、已发布的帖子（可以复制）、已过期、回复草稿；
 * 经济日历、焦点战建议、赛程、已过滤；两个题型模板；三种页面；日志和用量。"Gemini 未配置"由 setMockGemini(false) 切换。
 */
import { ApiError, type Page } from '../api/types';
import type {
  ContentCandidates,
  ContentDraft,
  ContentKind,
  ContentLogQuery,
  ContentLogRow,
  ContentPageRow,
  ContentPages,
  ContentStatus,
  ContentWord,
  ContentWordKind,
  DraftQuery,
  EconEvent,
  EconInput,
  EconTemplate,
  EconTemplateInput,
  GenerateInput,
  ToolResult,
} from '../api/fcTypes';
import { mutate, paginate, respond } from './runtime';

const NOW = '2026-10-25T08:00:00Z';
const PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';

let seq = 1000;
let geminiConfigured = true;
let stopped = false;
let drafts: ContentDraft[] = [];
let econ: EconEvent[] = [];
let words: ContentWord[] = [];
let templates: EconTemplate[] = [];
let pages: ContentPageRow[] = [];
let logs: ContentLogRow[] = [];
let settings: Record<string, string> = {};

const refs = (total: string) => [
  { key: 'market.title.zh', label: '题目（中文）', labelEn: 'Question (Chinese)', value: '曼城 vs 利物浦：总进球大于 2.5？', source: '本站奖池', at: NOW },
  { key: 'market.total', label: '奖池合计（USDT）', labelEn: 'Total pool (USDT)', value: total, source: '本站奖池', at: NOW },
  { key: 'market.bettors', label: '下注人数', labelEn: 'Number of bettors', value: '12', source: '本站奖池', at: NOW },
  { key: 'option.1.pct', label: '选项「大 2.5 / Over 2.5」的概率', labelEn: 'Share of the pool on "Over 2.5"', value: '58.3%', source: '本站奖池', at: NOW },
];

function draft(p: Partial<ContentDraft> & Pick<ContentDraft, 'kind' | 'lang' | 'status'>): ContentDraft {
  const no = String(++seq);
  const base: ContentDraft = {
    draftNo: no,
    groupNo: p.groupNo ?? no,
    kind: p.kind,
    lang: p.lang,
    title: p.lang === 'zh' ? '曼城 vs 利物浦：赛前数据' : 'Man City vs Liverpool: pre-match data',
    body:
      p.lang === 'zh'
        ? '本场奖池合计 1,250.00 USDT，12 人下注；押"大 2.5"的占 58.3%。截止下注 2026-10-25 19:55 UTC。数字随奖池变化，最终以截止时为准。'
        : 'The pool totals 1,250.00 USDT from 12 bettors; 58.3% of it is on "Over 2.5". Betting closes 2026-10-25 19:55 UTC. Numbers change with the pool.',
    refs: refs('1250.00'),
    checks: { numbers: [], banned: [], tendency: [], polymarketNoteMissing: false, maxLength: 500, actualLength: 80, missing: [], summary: '' },
    checkOk: true,
    status: p.status,
    failReason: null,
    rejectReason: null,
    marketNo: '100000000000000101',
    pageSlug: null,
    topic: null,
    expiresAt: '2026-10-25T20:00:00Z',
    expired: false,
    aiCalls: 1,
    createdAt: NOW,
    createdBy: null,
    publishedAt: null,
    publishedBy: null,
    version: 1,
    canPublish: false,
    copyOnly: ['BRIEF', 'POST_X', 'POST_TG', 'REPLY'].includes(p.kind),
    canCopy: false,
    link: null,
    siteUrl: null,
  };
  const d = { ...base, ...p, draftNo: no, groupNo: p.groupNo ?? no };
  return derive(d);
}

/** 按状态算出能不能发布、复制（和后端 DraftService 同一口径） */
function derive(d: ContentDraft): ContentDraft {
  const copyable = ['PREVIEW', 'BRIEF', 'POST_X', 'POST_TG', 'REPLY'].includes(d.kind);
  d.canPublish = d.kind !== 'REPLY' && d.status === 'PENDING' && d.checkOk && !d.expired && !stopped;
  d.canCopy = copyable && !stopped && !d.expired && (d.kind === 'REPLY' ? d.status === 'PENDING' && d.checkOk : d.status === 'PUBLISHED');
  d.link = d.marketNo && !['SEO_INTRO', 'REPLY'].includes(d.kind) ? `https://fc-test.quickcome.online/m/${d.marketNo}?src=${d.kind === 'POST_TG' ? 'tg' : 'x'}&cmp=post-${d.kind.toLowerCase().replace('_', '-')}` : null;
  return d;
}

function seed(): void {
  seq = 1000;
  stopped = false;
  geminiConfigured = true;
  drafts = [];
  const g1 = String(seq + 1);
  drafts.push(draft({ kind: 'PREVIEW', lang: 'zh', status: 'PENDING', groupNo: g1 }));
  drafts.push(draft({ kind: 'PREVIEW', lang: 'en', status: 'PENDING', groupNo: g1 }));
  const g2 = String(seq + 1);
  const red = draft({ kind: 'POST_X', lang: 'zh', status: 'PENDING', groupNo: g2 });
  red.body = '奖池 1,250.00 USDT，押"大 2.5"的占 85%。';
  red.checkOk = false;
  red.checks = { numbers: [{ field: 'body', text: '85', start: red.body.indexOf('85'), end: red.body.indexOf('85') + 2 }], banned: [], tendency: [], polymarketNoteMissing: false, maxLength: 140, actualLength: red.body.length, missing: [], summary: '有 1 个数字在引用的数据里找不到：85' };
  drafts.push(derive(red));
  drafts.push(draft({ kind: 'POST_X', lang: 'en', status: 'PENDING', groupNo: g2 }));
  const g3 = String(seq + 1);
  const failed = draft({ kind: 'POST_TG', lang: 'zh', status: 'FAILED', groupNo: g3 });
  failed.body = '这场稳赚。';
  failed.checkOk = false;
  failed.failReason = '重新起草后仍不合格：有禁用的说法：稳赚';
  failed.checks = { numbers: [], banned: ['稳赚'], tendency: [], polymarketNoteMissing: false, maxLength: 300, actualLength: 5, missing: [], summary: '有禁用的说法：稳赚' };
  drafts.push(derive(failed));
  const pub = draft({ kind: 'POST_TG', lang: 'en', status: 'PUBLISHED', groupNo: g3, publishedAt: NOW, publishedBy: 1 });
  drafts.push(derive(pub));
  drafts.push(draft({ kind: 'BRIEF', lang: 'zh', status: 'EXPIRED', expired: true }));
  drafts.push(draft({ kind: 'ARTICLE', lang: 'zh', status: 'PENDING', marketNo: null, topic: '彩池玩法怎么算', expiresAt: null }));
  drafts.push(draft({ kind: 'REPLY', lang: 'zh', status: 'PENDING', topic: '某大V：这场 90% 出大球' }));
  econ = [
    { id: '1', kind: 'FOMC', period: '2026-12', releaseAt: '2026-12-09T19:00:00Z', prevValue: '4.50', sourceUrl: 'https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm', status: 'SCHEDULED', marketNo: null, marketStatus: null, note: null, draftDueAt: '2026-12-02T19:00:00Z', closeAt: '2026-12-09T18:00:00Z', version: 0 },
    { id: '2', kind: 'CPI', period: '2026-10', releaseAt: '2026-11-12T13:30:00Z', prevValue: '3.0', sourceUrl: null, status: 'DRAFTED', marketNo: '100000000000000201', marketStatus: 'draft', note: null, draftDueAt: '2026-11-05T13:30:00Z', closeAt: '2026-11-12T12:30:00Z', version: 2 },
  ];
  words = [
    { id: '1', kind: 'BANNED', word: '稳赚', builtIn: true },
    { id: '2', kind: 'BANNED', word: 'risk-free', builtIn: true },
    { id: '3', kind: 'TENDENCY', word: '建议押', builtIn: true },
    { id: '4', kind: 'SENSITIVE', word: '选举', builtIn: true },
    { id: '5', kind: 'SENSITIVE', word: 'U19', builtIn: true },
  ];
  const i3 = (en: string, zhCN: string, zhTW: string) => ({ en, zhCN, zhTW });
  templates = [
    {
      code: 'FOMC',
      title: i3('Fed {periodEn} meeting: will the federal funds target range be cut, held or raised?', '美联储 {periodZh} 议息：联邦基金利率目标区间会降、维持还是加？', '聯準會 {periodZh} 議息：聯邦基金利率目標區間會降、維持還是升？'),
      rules: i3('Resolved by the upper bound of the target range …', '以美联储官网首次发布的声明里的目标区间上限为准……', '以聯準會官網首次發布的聲明裡的目標區間上限為準……'),
      options: [
        { label: i3('Cut', '降息', '降息'), winCondition: i3('Lower', '上限比会议前低', '上限比會議前低') },
        { label: i3('Hold', '维持', '維持'), winCondition: i3('Unchanged', '上限不变', '上限不變') },
        { label: i3('Raise', '加息', '升息'), winCondition: i3('Higher', '上限比会议前高', '上限比會議前高') },
      ],
      sourcePrimary: 'https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm',
      sourceBackup: 'https://www.federalreserve.gov/monetarypolicy/openmarket.htm',
      version: 1,
      updatedBy: null,
      updatedAt: NOW,
      variables: ['periodEn', 'periodZh', 'releaseAt', 'prevEn', 'prevZh', 'prevTw'],
    },
    {
      code: 'CPI',
      title: i3('US {periodEn} CPI year-over-year: above, equal to or below {x}%?', '美国 {periodZh} CPI 同比：高于、等于还是低于 {x}%？', '美國 {periodZh} CPI 年增率：高於、等於還是低於 {x}%？'),
      rules: i3('Resolved by the CPI-U year-over-year change …', '以美国劳工统计局首次公布的 CPI-U 同比为准……', '以美國勞工統計局首次公布的 CPI-U 年增率為準……'),
      options: [
        { label: i3('Above {x}%', '高于 {x}%', '高於 {x}%'), winCondition: i3('Above', '高于', '高於') },
        { label: i3('Equal to {x}%', '等于 {x}%', '等於 {x}%'), winCondition: i3('Equal', '等于', '等於') },
        { label: i3('Below {x}%', '低于 {x}%', '低於 {x}%'), winCondition: i3('Below', '低于', '低於') },
      ],
      sourcePrimary: 'https://www.bls.gov/cpi/',
      sourceBackup: 'https://www.bls.gov/schedule/news_release/cpi.htm',
      version: 1,
      updatedBy: null,
      updatedAt: NOW,
      variables: ['periodEn', 'periodZh', 'releaseAt', 'x'],
    },
  ];
  pages = [
    { slug: 'btc-hourly', kind: 'BTC_STATS', marketNo: null, titleZhCN: 'BTC 每小时涨跌统计', titleEn: 'BTC hourly up/down statistics', introLangs: ['zh'], bodyLangs: [], dataAt: NOW, createdDay: '2026-10-24', status: 'VISIBLE', path: '/insights/btc-hourly' },
    { slug: 'focus-100000000000000101', kind: 'FOCUS', marketNo: '100000000000000101', titleZhCN: '曼城 vs 利物浦：总进球大于 2.5？', titleEn: 'Man City vs Liverpool: over 2.5 goals?', introLangs: [], bodyLangs: [], dataAt: null, createdDay: '2026-10-25', status: 'VISIBLE', path: '/insights/focus-100000000000000101' },
    { slug: 'article-k1', kind: 'ARTICLE', marketNo: null, titleZhCN: '彩池玩法怎么算', titleEn: 'How pari-mutuel pools work', introLangs: [], bodyLangs: ['zh', 'en'], dataAt: null, createdDay: '2026-10-23', status: 'VISIBLE', path: '/insights/article-k1' },
  ];
  logs = Array.from({ length: 28 }, (_, i) => ({
    id: String(500 - i),
    action: ['GENERATE', 'PUBLISH', 'REJECT', 'COPY', 'ECON_DRAFT', 'FILTER', 'BLOCKED'][i % 7],
    kind: ['PREVIEW', 'POST_X', 'POST_TG', 'POST_X', 'FOMC', 'SPORTS', 'ARTICLE'][i % 7],
    ref: String(1001 + i),
    lang: i % 2 ? 'en' : 'zh',
    outcome: (['OK', 'OK', 'OK', 'OK', 'OK', 'BLOCKED', 'BLOCKED'] as const)[i % 7],
    detail: ['待审核，检查通过', '网站页面说明 /insights/btc-hourly', '驳回：语气太硬', '复制文字和链接', '按模板生成市场草稿（CPI 2026-10）', '命中敏感词：U19', '提示里有不能发给 Gemini 的内容：email（NF-16），没有发送'][i % 7],
    aiCalls: i % 7 === 0 ? 1 : 0,
    adminId: i % 3 ? 1 : null,
    createdAt: new Date(Date.parse(NOW) - i * 3_600_000).toISOString(),
  }));
  settings = { 'ai.dailyLimit': '300', 'ai.contentSharePct': '80', 'ai.costAlertUsd': '10', 'ai.drafts': '1', 'pack.enabled': '1', 'pages.dailyNew': '5', 'articles.weekly': '2', 'econ.leadDays': '7', 'publish.stopped': '0' };
}
seed();

export function resetContentMocks(): void {
  seed();
}

/** 测试、原型：切换"Gemini 已配置 / 未配置" */
export function setMockGemini(configured: boolean): void {
  geminiConfigured = configured;
}

const notFound = () => new ApiError('errors.notFound', undefined, { status: 404, code: 'NOT_FOUND' });
const fcErr = (code: string, detail: string, status = 409) => new ApiError(`errors.fc.${code}`, { detail }, { status, code });

function find(no: string): ContentDraft {
  const d = drafts.find((x) => x.draftNo === no);
  if (!d) throw notFound();
  return d;
}

function log(action: string, kind: string | null, ref: string | null, detail: string): void {
  logs.unshift({ id: String(Date.now()), action, kind, ref, lang: null, outcome: 'OK', detail, aiCalls: 0, adminId: 1, createdAt: new Date().toISOString() });
}

// ---- 草稿箱 ----
export function listDrafts(q: DraftQuery): Promise<Page<ContentDraft>> {
  return respond(
    () => {
      const items = drafts
        .map(derive)
        .filter((d) => (!q.status || d.status === q.status) && (!q.kind || d.kind === q.kind) && (!q.lang || d.lang === q.lang))
        .sort((a, b) => Number(b.status === 'PENDING') - Number(a.status === 'PENDING') || Number(b.draftNo) - Number(a.draftNo));
      return paginate(items, q.page, q.pageSize);
    },
    { empty: () => ({ items: [], total: 0, page: q.page, pageSize: q.pageSize }) },
  );
}

export function getDraftGroup(draftNo: string): Promise<ContentDraft[]> {
  return respond(() => {
    const d = find(draftNo);
    return drafts.filter((x) => x.groupNo === d.groupNo).map(derive).sort((a, b) => (a.lang === 'zh' ? -1 : 1) - (b.lang === 'zh' ? -1 : 1));
  });
}

export function generateContentDrafts(input: GenerateInput): Promise<ContentDraft[]> {
  return mutate(() => {
    if (!geminiConfigured) throw fcErr('AI_UNAVAILABLE', 'Gemini 未配置（FC_GEMINI_API_KEY）');
    const group = String(seq + 1);
    const made = (['zh', 'en'] as const).map((lang) =>
      draft({ kind: input.kind, lang, status: 'PENDING', groupNo: group, marketNo: input.marketNo ?? null, pageSlug: input.pageSlug ?? null, topic: input.topic ?? null }),
    );
    drafts.unshift(...made);
    log('GENERATE', input.kind, group, '开始起草（中文、英文）');
    return made;
  });
}

const BANNED_DEMO = ['稳赚', 'risk-free', '建议押'];

export function editDraft(draftNo: string, title: string, body: string, version: number): Promise<ContentDraft> {
  return mutate(() => {
    const d = find(draftNo);
    if (!['PENDING', 'FAILED'].includes(d.status)) throw fcErr('DRAFT_STATE_CHANGED', '这条草稿现在不能改');
    if (d.version !== version) throw fcErr('DRAFT_STATE_CHANGED', '草稿刚被别人改过，请刷新');
    const bad = BANNED_DEMO.filter((w) => `${title}\n${body}`.toLowerCase().includes(w.toLowerCase()));
    if (bad.length) throw fcErr('CONTENT_BANNED_WORDS', `有禁用的说法或倾向，没有保存：${bad.join('、')}`, 400);
    d.title = title;
    d.body = body;
    d.version += 1;
    d.status = 'PENDING';
    d.failReason = null;
    const wrong = /\b85\b/.test(body);
    d.checkOk = !wrong;
    d.checks = wrong
      ? { ...(d.checks ?? { banned: [], tendency: [], polymarketNoteMissing: false, maxLength: 500, missing: [] }), numbers: [{ field: 'body', text: '85', start: body.indexOf('85'), end: body.indexOf('85') + 2 }], actualLength: body.length, summary: '有 1 个数字在引用的数据里找不到：85', banned: [], tendency: [], polymarketNoteMissing: false, maxLength: 500, missing: [] }
      : { numbers: [], banned: [], tendency: [], polymarketNoteMissing: false, maxLength: 500, actualLength: body.length, missing: [], summary: '' };
    return derive(d);
  });
}

export function publishDraft(draftNo: string, _reason: string): Promise<ContentDraft> {
  void _reason;
  return mutate(() => {
    const d = derive(find(draftNo));
    if (stopped) throw fcErr('PUBLISH_STOPPED', '已按下紧急停止');
    if (d.kind === 'REPLY') throw fcErr('DRAFT_COPY_ONLY', '回复草稿只能复制');
    if (!d.checkOk) throw fcErr('DRAFT_CHECK_FAILED', `检查没有通过，不能发布：${d.checks?.summary ?? ''}`);
    if (d.status !== 'PENDING') throw fcErr('DRAFT_STATE_CHANGED', '只有待审核的草稿能发布');
    d.status = 'PUBLISHED';
    d.publishedAt = new Date().toISOString();
    d.publishedBy = 1;
    d.siteUrl = d.kind === 'ARTICLE' ? '/insights/article-k1' : d.kind === 'SEO_INTRO' ? `/insights/${d.pageSlug}` : d.kind === 'PREVIEW' ? `/insights/focus-${d.marketNo}` : null;
    log('PUBLISH', d.kind, d.draftNo, '发布');
    return derive(d);
  });
}

export function rejectDraft(draftNo: string, reason: string): Promise<ContentDraft> {
  return mutate(() => {
    const d = find(draftNo);
    if (!['PENDING', 'FAILED'].includes(d.status)) throw fcErr('DRAFT_STATE_CHANGED', '只有待审核、生成失败的草稿能驳回');
    d.status = 'REJECTED';
    d.rejectReason = reason;
    return derive(d);
  });
}

export function retryDraft(draftNo: string): Promise<ContentDraft> {
  return mutate(() => {
    const d = find(draftNo);
    if (!geminiConfigured) throw fcErr('AI_UNAVAILABLE', 'Gemini 未配置（FC_GEMINI_API_KEY）');
    if (!['FAILED', 'REJECTED'].includes(d.status)) throw fcErr('DRAFT_STATE_CHANGED', '只有生成失败、驳回的草稿能重新起草');
    d.status = 'PENDING';
    d.checkOk = true;
    d.failReason = null;
    d.body = d.lang === 'zh' ? '本场奖池合计 1,250.00 USDT，12 人下注。' : 'The pool totals 1,250.00 USDT from 12 bettors.';
    d.checks = { numbers: [], banned: [], tendency: [], polymarketNoteMissing: false, maxLength: 300, actualLength: d.body.length, missing: [], summary: '' };
    return derive(d);
  });
}

export function copiedDraft(draftNo: string): Promise<ContentDraft> {
  return mutate(() => {
    const d = derive(find(draftNo));
    if (!d.canCopy) throw fcErr('DRAFT_STATE_CHANGED', stopped ? '已按下紧急停止' : '这条草稿现在不能复制（要先审核通过）');
    log('COPY', d.kind, d.draftNo, '复制文字和链接');
    return d;
  });
}

// ---- 用量和设置 ----
function status(): ContentStatus {
  const limit = Number(settings['ai.dailyLimit']);
  return {
    contentEnabled: true,
    geminiConfigured,
    geminiState: geminiConfigured ? (settings['ai.drafts'] === '1' ? 'ok' : 'disabled') : 'unconfigured',
    model: 'gemini-2.5-flash-lite',
    usedToday: geminiConfigured ? 37 : 0,
    limit,
    contentCap: Math.floor((limit * Number(settings['ai.contentSharePct'])) / 100),
    contentUsedToday: geminiConfigured ? 37 : 0,
    scoreUsedToday: 0,
    failuresToday: 2,
    monthCalls: 812,
    monthCostUsd: '0.42',
    costAlertUsd: settings['ai.costAlertUsd'],
    costAlert: false,
    stopped,
    pagesToday: 1,
    pagesDaily: Number(settings['pages.dailyNew']),
    articlesThisWeek: 1,
    articlesWeekly: Number(settings['articles.weekly']),
    polymarketEnabled: false,
    queue: 0,
    pausedUntil: null,
    settings: { ...settings, 'publish.stopped': stopped ? '1' : '0' },
  };
}

export function getContentStatus(): Promise<ContentStatus> {
  return respond(status);
}

export function updateContentSettings(values: Record<string, string>, _reason: string): Promise<ContentStatus> {
  void _reason;
  return mutate(() => {
    Object.assign(settings, values);
    return status();
  });
}

export function setContentStopped(next: boolean, _reason: string): Promise<ContentStatus> {
  void _reason;
  return mutate(() => {
    stopped = next;
    log(next ? 'STOP' : 'RESUME', null, null, next ? '紧急停止' : '恢复发布');
    return status();
  });
}

export function listContentWords(kind?: ContentWordKind): Promise<ContentWord[]> {
  return respond(() => words.filter((w) => !kind || w.kind === kind));
}

export function addContentWord(kind: ContentWordKind, word: string, _reason: string): Promise<ContentWord> {
  void _reason;
  return mutate(() => {
    const w = { id: String(words.length + 100), kind, word, builtIn: false };
    words.push(w);
    return w;
  });
}

export function deleteContentWord(id: string, _reason: string): Promise<ContentWord> {
  void _reason;
  return mutate(() => {
    const w = words.find((x) => x.id === id);
    if (!w) throw notFound();
    if (w.builtIn) throw fcErr('WORD_BUILT_IN', '内置的词不能删');
    words = words.filter((x) => x.id !== id);
    return w;
  });
}

// ---- 事件候选 ----
export function getCandidates(): Promise<ContentCandidates> {
  return respond(
    () => ({
      focus: [
        {
          fixtureNo: '300000000000000001',
          sport: 'FOOTBALL',
          competition: 'PL',
          competitionName: 'Premier League',
          home: 'Manchester City',
          away: 'Liverpool',
          kickoffAt: '2026-10-25T12:30:00Z',
          mappingConfirmed: true,
          markets: 0,
          suggestedPlay: 'FOOTBALL_OU 2.5',
          reasons: ['Premier League（优先联赛第 2 档）', '开赛 10-25 12:30 UTC（北京时间 10-25 20:30，晚上，华语用户方便看）', '两个数据源的比赛对应已确认，可以自动裁决'],
        },
      ],
      fixtures: [
        { fixtureNo: '300000000000000001', sport: 'FOOTBALL', competitionName: 'Premier League', home: 'Manchester City', away: 'Liverpool', kickoffAt: '2026-10-25T12:30:00Z', mappingConfirmed: true, markets: 0, filtered: false, filteredBy: null },
        { fixtureNo: '300000000000000002', sport: 'NBA', competitionName: 'NBA', home: 'Los Angeles Lakers', away: 'Golden State Warriors', kickoffAt: '2026-10-26T02:00:00Z', mappingConfirmed: false, markets: 1, filtered: false, filteredBy: null },
        { fixtureNo: '300000000000000003', sport: 'FOOTBALL', competitionName: 'UEFA Youth League', home: 'Benfica U19', away: 'Ajax U19', kickoffAt: '2026-10-25T14:00:00Z', mappingConfirmed: false, markets: 0, filtered: true, filteredBy: 'U19' },
      ],
      econ: [...econ],
      filtered: [{ source: 'SPORTS', ref: '300000000000000003', title: 'Benfica U19 vs Ajax U19 · UEFA Youth League', reason: 'KEYWORD', matched: 'U19', createdAt: NOW }],
      sportsError: null,
    }),
    { empty: () => ({ focus: [], fixtures: [], econ: [], filtered: [], sportsError: null }) },
  );
}

export function listEcon(): Promise<EconEvent[]> {
  return respond(() => [...econ]);
}

export function addEcon(input: EconInput): Promise<EconEvent> {
  return mutate(() => {
    if (input.kind === 'CPI' && !input.prevValue) {
      throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'prevValue', code: 'cpiOneDecimal' }] });
    }
    const release = new Date(input.releaseAt);
    const e: EconEvent = {
      id: String(econ.length + 10),
      kind: input.kind,
      period: input.period,
      releaseAt: input.releaseAt,
      prevValue: input.prevValue ?? null,
      sourceUrl: input.sourceUrl ?? null,
      status: 'SCHEDULED',
      marketNo: null,
      marketStatus: null,
      note: null,
      draftDueAt: new Date(release.getTime() - 7 * 86_400_000).toISOString(),
      closeAt: new Date(release.getTime() - 3_600_000).toISOString(),
      version: 0,
    };
    econ.push(e);
    return e;
  });
}

export function updateEcon(id: string, input: EconInput): Promise<EconEvent> {
  return mutate(() => {
    const e = econ.find((x) => x.id === id);
    if (!e) throw notFound();
    Object.assign(e, { kind: input.kind, period: input.period, releaseAt: input.releaseAt, prevValue: input.prevValue ?? null, sourceUrl: input.sourceUrl ?? null, version: e.version + 1 });
    return e;
  });
}

export function cancelEcon(id: string): Promise<EconEvent> {
  return mutate(() => {
    const e = econ.find((x) => x.id === id);
    if (!e) throw notFound();
    e.status = 'CANCELLED';
    return e;
  });
}

export function draftEcon(id: string): Promise<EconEvent> {
  return mutate(() => {
    const e = econ.find((x) => x.id === id);
    if (!e) throw notFound();
    if (!['SCHEDULED', 'FAILED'].includes(e.status)) throw fcErr('ECON_STATE_CHANGED', '这一条已经生成过或取消了');
    e.status = 'DRAFTED';
    e.marketNo = `1000000000000003${e.id.padStart(2, '0')}`;
    e.marketStatus = 'draft';
    return e;
  });
}

// ---- 模板 ----
export function listEconTemplates(): Promise<EconTemplate[]> {
  return respond(() => templates);
}

export function updateEconTemplate(code: string, input: EconTemplateInput): Promise<EconTemplate> {
  return mutate(() => {
    const t = templates.find((x) => x.code === code);
    if (!t) throw notFound();
    if (t.version !== input.version) throw fcErr('CONTENT_TEMPLATE_CHANGED', '模板刚被别人改过，请刷新');
    const all = [input.title, input.rules, ...input.options.flatMap((o) => [o.label, o.winCondition])].flatMap((x) => [x.en, x.zhCN, x.zhTW]).join('\n');
    const bad = BANNED_DEMO.filter((w) => all.includes(w));
    if (bad.length) throw fcErr('CONTENT_BANNED_WORDS', `模板里有不能用的词：${bad.join('、')}`, 400);
    Object.assign(t, input, { version: t.version + 1, updatedAt: new Date().toISOString(), updatedBy: 1 });
    return t;
  });
}

// ---- 页面 ----
export function listContentPages(): Promise<ContentPages> {
  return respond(() => ({ items: [...pages], createdToday: 1, dailyLimit: Number(settings['pages.dailyNew']) }), {
    empty: () => ({ items: [], createdToday: 0, dailyLimit: 5 }),
  });
}

export function refreshBtcPage(): Promise<ContentPageRow> {
  return mutate(() => {
    const p = pages.find((x) => x.slug === 'btc-hourly')!;
    p.dataAt = new Date().toISOString();
    return p;
  });
}

export function setPageVisible(slug: string, visible: boolean, _reason: string): Promise<ContentPageRow> {
  void _reason;
  return mutate(() => {
    const p = pages.find((x) => x.slug === slug);
    if (!p) throw notFound();
    p.status = visible ? 'VISIBLE' : 'HIDDEN';
    return p;
  });
}

// ---- 工具 ----
export function makeDataCard(kind: 'market' | 'btc', marketNo: string | undefined, _lang: string): Promise<ToolResult> {
  void _lang;
  return mutate(() => {
    if (kind === 'market' && !marketNo) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'marketNo', code: 'required' }] });
    return {
      image: PNG,
      text: kind === 'btc' ? 'BTC 每小时涨跌统计\n过去 7 天上涨的小时 66.7%' : '曼城 vs 利物浦：总进球大于 2.5？\n· 大 2.5 58.3%\n奖池 1,250.00 USDT · 12 人下注',
      refs: refs('1250.00'),
      replies: [],
      notes: [],
    };
  });
}

export function makeCompare(marketNo: string, viewpoint: string, polymarketSlug: string | undefined, _lang: string): Promise<ToolResult> {
  void _lang;
  return mutate(() => {
    if (!marketNo) throw new ApiError('errors.validationFailed', undefined, { status: 400, code: 'VALIDATION_FAILED', fields: [{ field: 'marketNo', code: 'required' }] });
    const notes: string[] = [];
    if (polymarketSlug) notes.push('Polymarket 公开接口没有打开（使用条款待核实，默认关闭），图上只有本站数据');
    const replies: ContentDraft[] = [];
    if (geminiConfigured) {
      const group = String(seq + 1);
      for (const lang of ['zh', 'en'] as const) {
        const d = draft({ kind: 'REPLY' as ContentKind, lang, status: 'PENDING', groupNo: group, topic: viewpoint, marketNo });
        d.body = lang === 'zh' ? '我们这场真实押注的奖池：押"大 2.5"的占 58.3%，12 人下注。' : 'Real stakes in our pool: 58.3% on "Over 2.5" from 12 bettors.';
        drafts.unshift(derive(d));
        replies.push(derive(d));
      }
    } else {
      notes.push('没有起草回复：Gemini 未配置（FC_GEMINI_API_KEY）');
    }
    return { image: PNG, text: `观点：${viewpoint}\n奖池 1,250.00 USDT · 12 人`, refs: refs('1250.00'), replies, notes };
  });
}

// ---- 日志 ----
export function listContentLogs(q: ContentLogQuery): Promise<Page<ContentLogRow>> {
  return respond(
    () => paginate(logs.filter((l) => !q.action || l.action === q.action), q.page, q.pageSize),
    { empty: () => ({ items: [], total: 0, page: q.page, pageSize: q.pageSize }) },
  );
}

export async function exportContentLogs(_q: Omit<ContentLogQuery, 'page' | 'pageSize'>): Promise<void> {
  void _q;
  await mutate(() => null);
}
