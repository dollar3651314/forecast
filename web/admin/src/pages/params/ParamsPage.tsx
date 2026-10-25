/**
 * 参数设置（需求第 6.2 节）：品类默认费率（fee:edit）；下注限额、加密市场的开放和截止提前量、争议窗口、KYC 阈值、单笔最小提现（betting_params:edit）。
 * 每项修改都是高危操作：显示"前 → 后"、原因必填、勾选确认、写审计；带版本（expected），别人刚改过时提示冲突（409 CONFIG_CHANGED）。
 * 单笔最小提现必须大于每条链的提现手续费（后端校验，不满足时提示是哪条链，RQ-16）。
 * 接口是 S3 已有的 GET/PUT /api/admin/params/{fee|betting}（真实接口模式也能用）；M1 只加了页面。
 */
import { useCallback } from 'react';
import { Alert, Card, Col, Form, Input, Row } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { listParams, updateParam } from '../../api';
import type { ParamItem, ParamSection } from '../../api/types';
import { ErrorState } from '../../components/ErrorState';
import { Num } from '../../components/Num';
import { PageHeader } from '../../components/PageHeader';
import { PermButton } from '../../components/PermButton';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { shortTime } from '../../utils/display';
import { formatAmount } from '../../utils/money';

const PERM: Record<ParamSection, string> = { fee: 'fee:edit', betting: 'betting_params:edit' };
/** 卡片分组和顺序 */
const GROUPS: Array<{ key: string; section: ParamSection }> = [
  { key: 'fee', section: 'fee' },
  { key: 'bet', section: 'betting' },
  { key: 'withdraw', section: 'betting' },
  { key: 'kyc', section: 'betting' },
  { key: 'crypto', section: 'betting' },
  { key: 'dispute', section: 'betting' },
];

function nameKey(key: string): string {
  return `pm.key.${key.replace(/\./g, '_')}`;
}

function show(p: ParamItem, t: (k: string, o?: Record<string, unknown>) => string): string {
  switch (p.unit) {
    case 'percent':
      return `${p.value}%`;
    case 'usdt':
      return `${formatAmount(p.value)} USDT`;
    case 'minutes':
      return Number(p.value) % 60 === 0 ? t('pm.hours', { n: Number(p.value) / 60 }) : t('pm.minutes', { n: p.value });
    case 'seconds':
      return t('pm.seconds', { n: p.value });
    default:
      return p.value;
  }
}

export function ParamsPage() {
  const { t } = useTranslation();
  const fee = useApi(useCallback(() => listParams('fee'), []));
  const betting = useApi(useCallback(() => listParams('betting'), []));
  const hr = useHighRisk();
  const all = [...(fee.data ?? []), ...(betting.data ?? [])];
  const error = fee.error || betting.error;

  const edit = (p: ParamItem) =>
    hr.ask({
      title: t('pm.editTitle', { name: t(nameKey(p.key)) }),
      description: t('pm.editDesc', { value: show(p, t) }),
      initialValues: { value: p.value },
      extra: (
        <Form.Item
          name="value"
          label={t('pm.newValue')}
          extra={p.key === 'withdraw.minAmount' ? t('pm.minWithdrawHint') : p.section === 'fee' ? t('pm.feeHint') : undefined}
          rules={[
            { required: true, whitespace: true, message: t('validation.required') },
            { pattern: p.unit === 'usdt' || p.unit === 'percent' ? /^\d+(\.\d{1,2})?$/ : /^\d+$/, message: t('validation.positiveNumber') },
          ]}
        >
          <Input suffix={p.unit === 'percent' ? '%' : p.unit === 'usdt' ? 'USDT' : p.unit === 'seconds' ? t('pm.secUnit') : t('pm.minUnit')} />
        </Form.Item>
      ),
      onConfirm: (v) => updateParam(p.section, p.key, String(v.value).trim(), v.reason, p.value),
      onDone: () => (p.section === 'fee' ? fee.reload() : betting.reload()),
    });

  return (
    <>
      <PageHeader title={t('pm.title')} sub={t('pm.sub')} />
      <Alert type="info" showIcon message={t('pm.hint')} />
      {error ? (
        <ErrorState error={error} onRetry={() => (fee.reload(), betting.reload())} />
      ) : (
        <Row gutter={[12, 12]} className="pm-grid">
          {GROUPS.map((g) => {
            const items = all.filter((p) => p.section === g.section && p.group === g.key);
            return (
              <Col xs={24} lg={12} xxl={8} key={g.key}>
                <Card size="small" title={t(`pm.group.${g.key}`)} loading={fee.loading && betting.loading && !all.length} extra={<span className="muted">{PERM[g.section]}</span>}>
                  <ul className="pm-list">
                    {items.map((p) => (
                      <li key={p.key}>
                        <div className="pm-name">
                          <b>{t(nameKey(p.key))}</b>
                          <span className="muted">{p.updatedAt ? t('pm.updated', { t: shortTime(p.updatedAt) }) : t('pm.default')}</span>
                        </div>
                        <Num strong className="pm-val">
                          {show(p, t)}
                        </Num>
                        <PermButton perm={PERM[p.section]} size="small" type="text" icon={<EditOutlined />} onClick={() => edit(p)} aria-label={t('common.edit')} />
                      </li>
                    ))}
                  </ul>
                  {g.key === 'withdraw' && <p className="pm-note">{t('pm.withdrawNote')}</p>}
                  {g.key === 'fee' && <p className="pm-note">{t('pm.feeNote')}</p>}
                </Card>
              </Col>
            );
          })}
        </Row>
      )}
      {hr.element}
    </>
  );
}
