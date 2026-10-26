/**
 * 内容中心 → 模板（forecast S15；自动化需求第 3.2 节、EX-7、C6）：经济日历的题型模板（FOMC、CPI），三种语言，后台能改（content:edit）。
 * 保存时检查：三种语言都有、选项数不变、只能用认识的变量、没有禁用词和敏感词；只影响之后生成的草稿。
 * 五种卡片的文字模板在 S14 的 content/cards.json（自动发布和模板审定随 MF-7 一起做）。
 */
import { useCallback, useEffect, useState } from 'react';
import { Alert, Card, Form, Input, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { listEconTemplates, updateEconTemplate } from '../../api';
import type { EconTemplate, EconTemplateOption, I18nText } from '../../api/fcTypes';
import { ErrorState } from '../../components/ErrorState';
import { I18nInputs } from '../../components/I18nInputs';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { useApi } from '../../hooks/useApi';
import { useAction } from '../../hooks/useConfirmDialogs';
import { utc } from '../../utils/market';

export function ContentTemplatesPage() {
  const { t } = useTranslation();
  const { data, loading, error, reload } = useApi(useCallback(() => listEconTemplates(), []));
  if (error) return <ErrorState error={error} onRetry={reload} />;
  return (
    <>
      <PageHeader title={t('ct.tpl.title')} sub={t('ct.tpl.sub')} />
      <Alert type="info" showIcon className="ct-note" message={t('ct.tpl.cards')} />
      {loading && !data ? null : (data ?? []).map((tpl) => <TemplateCard key={`${tpl.code}-${tpl.version}`} tpl={tpl} onSaved={reload} />)}
    </>
  );
}

function TemplateCard({ tpl, onSaved }: { tpl: EconTemplate; onSaved: () => void }) {
  const { t } = useTranslation();
  const [title, setTitle] = useState<I18nText>(tpl.title);
  const [rules, setRules] = useState<I18nText>(tpl.rules);
  const [options, setOptions] = useState<EconTemplateOption[]>(tpl.options);
  const [primary, setPrimary] = useState(tpl.sourcePrimary);
  const [backup, setBackup] = useState(tpl.sourceBackup);
  const act = useAction();
  useEffect(() => {
    setTitle(tpl.title);
    setRules(tpl.rules);
    setOptions(tpl.options);
  }, [tpl]);
  return (
    <Card
      size="small"
      className="ct-card"
      data-testid={`tpl-${tpl.code}`}
      title={
        <Space>
          <b>{t(`ct.econKind.${tpl.code}`)}</b>
          <Tag>v{tpl.version}</Tag>
          <span className="muted">{utc(tpl.updatedAt)}</span>
        </Space>
      }
      extra={
        <PermButton
          perm="content:edit"
          type="primary"
          onClick={() =>
            void act(() => updateEconTemplate(tpl.code, { title, rules, options, sourcePrimary: primary, sourceBackup: backup, version: tpl.version }), t('common.saved'), onSaved)
          }
          data-testid={`tpl-save-${tpl.code}`}
        >
          {t('common.save')}
        </PermButton>
      }
    >
      <p className="muted">{t('ct.tpl.vars', { vars: tpl.variables.map((v) => `{${v}}`).join(' ') })}</p>
      <Form layout="vertical">
        <Form.Item label={t('ct.tpl.question')}>
          <I18nInputs value={title} onChange={setTitle} testId={`tpl-title-${tpl.code}`} />
        </Form.Item>
        <Form.Item label={t('ct.tpl.rules')}>
          <I18nInputs value={rules} onChange={setRules} multiline rows={4} />
        </Form.Item>
        {options.map((o, i) => (
          <Form.Item key={i} label={t('ct.tpl.option', { n: i + 1 })}>
            <I18nInputs value={o.label} onChange={(v) => setOptions(options.map((x, j) => (j === i ? { ...x, label: v } : x)))} />
            <I18nInputs value={o.winCondition} onChange={(v) => setOptions(options.map((x, j) => (j === i ? { ...x, winCondition: v } : x)))} placeholder={t('ct.tpl.win')} />
          </Form.Item>
        ))}
        <Form.Item label={t('ct.tpl.primary')}>
          <Input value={primary} onChange={(e) => setPrimary(e.target.value)} />
        </Form.Item>
        <Form.Item label={t('ct.tpl.backup')}>
          <Input value={backup} onChange={(e) => setBackup(e.target.value)} />
        </Form.Item>
      </Form>
    </Card>
  );
}
