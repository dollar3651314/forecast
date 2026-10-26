/**
 * 内容中心的真实接口（forecast S15；后端 admin/ops/ContentAdminController，路径、字段以后端为准）。
 * 发布、设置、词表、紧急停止、页面显示 / 隐藏是高危（content:publish）：reason、checked 原样传给后端，后端检查并写审计。
 * 没有任何"发到 X / Telegram"的接口：帖子审核后由人复制手动发（自动发布等 SQ-22 和账号）。
 */
import { ApiError, type Page } from './types';
import { API_BASE, request, toApiError } from './http';
import type {
  ContentCandidates,
  ContentDraft,
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
} from './fcTypes';

const enc = encodeURIComponent;

// ---- 草稿箱 ----
export const listDrafts = (q: DraftQuery): Promise<Page<ContentDraft>> =>
  request('/content/drafts', { query: { status: q.status, kind: q.kind, lang: q.lang, page: q.page, pageSize: q.pageSize } });
export const getDraftGroup = (draftNo: string): Promise<ContentDraft[]> => request(`/content/drafts/${enc(draftNo)}`);
export const generateContentDrafts = (input: GenerateInput): Promise<ContentDraft[]> => request('/content/drafts', { method: 'POST', body: input });
export const editDraft = (draftNo: string, title: string, body: string, version: number): Promise<ContentDraft> =>
  request(`/content/drafts/${enc(draftNo)}`, { method: 'PUT', body: { title, body, version } });
export const publishDraft = (draftNo: string, reason: string): Promise<ContentDraft> =>
  request(`/content/drafts/${enc(draftNo)}/publish`, { method: 'POST', body: { reason, checked: true } });
export const rejectDraft = (draftNo: string, reason: string): Promise<ContentDraft> => request(`/content/drafts/${enc(draftNo)}/reject`, { method: 'POST', body: { reason } });
export const retryDraft = (draftNo: string): Promise<ContentDraft> => request(`/content/drafts/${enc(draftNo)}/retry`, { method: 'POST' });
export const copiedDraft = (draftNo: string): Promise<ContentDraft> => request(`/content/drafts/${enc(draftNo)}/copied`, { method: 'POST' });

// ---- 用量和设置 ----
export const getContentStatus = (): Promise<ContentStatus> => request('/content/status');
export const updateContentSettings = (values: Record<string, string>, reason: string): Promise<ContentStatus> =>
  request('/content/settings', { method: 'PUT', body: { values, reason, checked: true } });
export const setContentStopped = (stopped: boolean, reason: string): Promise<ContentStatus> =>
  request('/content/stop', { method: 'POST', body: { stopped, reason, checked: true } });
export const listContentWords = (kind?: ContentWordKind): Promise<ContentWord[]> => request('/content/words', { query: { kind } });
export const addContentWord = (kind: ContentWordKind, word: string, reason: string): Promise<ContentWord> =>
  request('/content/words', { method: 'POST', body: { kind, word, reason, checked: true } });
export const deleteContentWord = (id: string, reason: string): Promise<ContentWord> =>
  request(`/content/words/${enc(id)}/delete`, { method: 'POST', body: { reason, checked: true } });

// ---- 事件候选 ----
export const getCandidates = (): Promise<ContentCandidates> => request('/content/candidates');
export const listEcon = (): Promise<EconEvent[]> => request('/content/econ');
export const addEcon = (input: EconInput): Promise<EconEvent> => request('/content/econ', { method: 'POST', body: input });
export const updateEcon = (id: string, input: EconInput): Promise<EconEvent> => request(`/content/econ/${enc(id)}`, { method: 'PUT', body: input });
export const cancelEcon = (id: string): Promise<EconEvent> => request(`/content/econ/${enc(id)}/cancel`, { method: 'POST' });
export const draftEcon = (id: string): Promise<EconEvent> => request(`/content/econ/${enc(id)}/draft`, { method: 'POST' });

// ---- 模板 ----
export const listEconTemplates = (): Promise<EconTemplate[]> => request('/content/templates');
export const updateEconTemplate = (code: string, input: EconTemplateInput): Promise<EconTemplate> =>
  request(`/content/templates/${enc(code)}`, { method: 'PUT', body: input });

// ---- 页面 ----
export const listContentPages = (): Promise<ContentPages> => request('/content/pages');
export const refreshBtcPage = (): Promise<ContentPageRow> => request('/content/pages/btc-hourly/refresh', { method: 'POST' });
export const setPageVisible = (slug: string, visible: boolean, reason: string): Promise<ContentPageRow> =>
  request(`/content/pages/${enc(slug)}/visibility`, { method: 'POST', body: { visible, reason, checked: true } });

// ---- 工具 ----
export const makeDataCard = (kind: 'market' | 'btc', marketNo: string | undefined, lang: string): Promise<ToolResult> =>
  request('/content/tools/data-card', { method: 'POST', body: { kind, marketNo, lang } });
export const makeCompare = (marketNo: string, viewpoint: string, polymarketSlug: string | undefined, lang: string): Promise<ToolResult> =>
  request('/content/tools/compare', { method: 'POST', body: { marketNo, viewpoint, polymarketSlug, lang } });

// ---- 日志 ----
export const listContentLogs = (q: ContentLogQuery): Promise<Page<ContentLogRow>> =>
  request('/content/logs', { query: { from: q.from, to: q.to, action: q.action, page: q.page, pageSize: q.pageSize } });
/** CSV 导出（服务器生成并写审计） */
export async function exportContentLogs(q: Omit<ContentLogQuery, 'page' | 'pageSize'>): Promise<void> {
  const p = new URLSearchParams();
  if (q.from) p.set('from', q.from);
  if (q.to) p.set('to', q.to);
  if (q.action) p.set('action', q.action);
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/content/logs/export?${p.toString()}`, { credentials: 'same-origin', headers: { Accept: 'text/csv, application/json' } });
  } catch {
    throw new ApiError('errors.network');
  }
  if (!res.ok) {
    let body = null;
    try {
      body = await res.json();
    } catch {
      body = null;
    }
    throw toApiError(res.status, body);
  }
  const blob = await res.blob();
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'content-logs.csv';
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
