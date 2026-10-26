/**
 * 内容中心各页共用的小部件（forecast S15）：Gemini 状态条、草稿种类和状态标签、正文里对不上的数字标红、复制到剪贴板。
 */
import { useCallback, type ReactNode } from 'react';
import { Alert, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { getContentStatus } from '../../api';
import type { ContentDraft, ContentKind, DraftStatus, NumberHit } from '../../api/fcTypes';
import { Pill, type PillTone } from '../../components/Pill';
import { useApi } from '../../hooks/useApi';

export const STATUS_TONE: Record<DraftStatus, PillTone> = {
  GENERATING: 'info',
  PENDING: 'pend',
  PUBLISHED: 'ok',
  REJECTED: 'muted',
  EXPIRED: 'muted',
  FAILED: 'danger',
};

export function KindTag({ kind }: { kind: ContentKind }) {
  const { t } = useTranslation();
  return <Tag color={kind === 'REPLY' ? 'default' : kind.startsWith('POST') ? 'purple' : 'blue'}>{t(`ct.kind.${kind}`)}</Tag>;
}

export function DraftStatusPill({ status }: { status: DraftStatus }) {
  const { t } = useTranslation();
  return <Pill tone={STATUS_TONE[status]}>{t(`ct.status.${status}`)}</Pill>;
}

/** 顶部的 Gemini 状态（未配置、暂停、额度用完、紧急停止时提示；正常时不显示） */
export function GeminiBanner({ extra }: { extra?: ReactNode }) {
  const { t } = useTranslation();
  const { data } = useApi(useCallback(() => getContentStatus(), []));
  if (!data) return null;
  const items: ReactNode[] = [];
  if (data.stopped) items.push(<Alert key="stop" type="error" showIcon message={t('ct.banner.stopped')} />);
  if (data.geminiState !== 'ok') {
    items.push(
      <Alert
        key="g"
        type={data.geminiState === 'unconfigured' ? 'warning' : 'info'}
        showIcon
        data-testid="gemini-banner"
        message={t(`ct.banner.${data.geminiState}`)}
        description={t('ct.banner.still')}
      />,
    );
  }
  if (!items.length && !extra) return null;
  return (
    <div className="ct-banners">
      {items}
      {extra}
    </div>
  );
}

/** 正文里标红对不上的数字（后端给的位置） */
export function Highlighted({ text, hits }: { text: string; hits: NumberHit[] }) {
  if (!hits.length) return <span className="ct-text">{text}</span>;
  const parts: ReactNode[] = [];
  let at = 0;
  [...hits].sort((a, b) => a.start - b.start).forEach((h, i) => {
    if (h.start < at || h.end > text.length) return;
    parts.push(text.slice(at, h.start));
    parts.push(
      <mark key={i} className="ct-bad-number" title={h.text}>
        {text.slice(h.start, h.end)}
      </mark>,
    );
    at = h.end;
  });
  parts.push(text.slice(at));
  return <span className="ct-text">{parts}</span>;
}

/** 复制的内容：标题不复制（给审核人看的），正文 + 链接 */
export function copyContent(d: ContentDraft): string {
  return d.link ? `${d.body}\n${d.link}` : d.body;
}

export async function copyToClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  document.body.appendChild(ta);
  ta.select();
  document.execCommand('copy');
  ta.remove();
}
