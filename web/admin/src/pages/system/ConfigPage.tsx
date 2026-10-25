/**
 * 系统配置：只列真正生效的配置项（出金限额、冷静期、白名单、确认数、登录锁定、IP 白名单、余额调整上限、追保通知节流、热钱包余额告警下限）。
 * 热钱包余额告警下限（分组 hotWallet）：0 = 不告警（默认，Amos 2026-10-17），显示为"0（不告警）"。
 * 修改是高危操作，二次确认并写审计。
 */
import { useCallback } from 'react';
import { Alert, Form, Input } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { listSystemConfigs, updateSystemConfig } from '../../api';
import type { SystemConfig } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Num } from '../../components/Num';
import { formatAmount } from '../../utils/money';
import { shortTime } from '../../utils/display';

/** 手续费币单位的显示名 */
const COIN_UNITS: Partial<Record<SystemConfig['unit'], string>> = { eth: 'ETH', bnb: 'BNB', trx: 'TRX' };

/** 新值的格式：USDT 最多 2 位小数；手续费币最多 8 位小数（和后端一致）；其余是整数 */
function valuePattern(unit: SystemConfig['unit']): RegExp {
  if (unit === 'usdt') return /^\d+(\.\d{1,2})?$/;
  if (COIN_UNITS[unit]) return /^\d+(\.\d{1,8})?$/;
  return /^\d+$/;
}

function displayValue(c: SystemConfig, t: (k: string, o?: Record<string, unknown>) => string): string {
  if (c.group === 'hotWallet' && Number(c.value) === 0) return t('config.alertOff');
  switch (c.unit) {
    case 'eth':
    case 'bnb':
    case 'trx':
      return `${c.value} ${COIN_UNITS[c.unit]}`;
    case 'usdt':
      return `${formatAmount(c.value)} USDT`;
    case 'hours':
      return t('config.hours', { count: Number(c.value) });
    case 'minutes':
      return t('config.minutes', { count: Number(c.value) });
    case 'count':
      return c.value;
    default:
      return c.value;
  }
}

export function ConfigPage() {
  const { t } = useTranslation();
  const fetcher = useCallback(() => listSystemConfigs(), []);
  const { data, loading, error, reload } = useApi(fetcher);
  const hr = useHighRisk();

  const edit = (c: SystemConfig) =>
    hr.ask({
      title: t('config.editTitle', { name: t(`configKey.${c.key.replace(/\./g, '_')}`) }),
      description: t('config.editDesc', { key: c.key, value: displayValue(c, t) }),
      initialValues: { value: c.value },
      extra: (
        <Form.Item
          name="value"
          label={t('config.newValue')}
          rules={[
            { required: true, whitespace: true, message: t('validation.required') },
            c.unit === 'text' ? { max: 500 } : { pattern: valuePattern(c.unit), message: t('validation.positiveNumber') },
          ]}
          extra={c.group === 'hotWallet' ? t('config.hotWalletHint') : undefined}
        >
          <Input />
        </Form.Item>
      ),
      onConfirm: (v) => updateSystemConfig(c.key, String(v.value).trim(), v.reason),
      onDone: reload,
    });

  return (
    <>
      <PageHeader title={t('config.title')} sub={t('config.sub')} />
      <Alert type="info" showIcon message={t('config.hint')} />
      <DataTable<SystemConfig>
        rowKey="key"
        loading={loading}
        error={error}
        onRetry={reload}
        dataSource={data}
        columns={[
          { title: t('config.group'), dataIndex: 'group', render: (v: string) => t(`configGroup.${v}`) },
          { title: t('config.name'), dataIndex: 'key', key: 'name', render: (v: string) => <b>{t(`configKey.${v.replace(/\./g, '_')}`)}</b> },
          { title: t('config.key'), dataIndex: 'key', render: (v: string) => <Num className="muted">{v}</Num> },
          { title: t('config.value'), key: 'value', render: (_, r) => <Num strong>{displayValue(r, t)}</Num> },
          { title: t('config.updatedAt'), dataIndex: 'updatedAt', render: (v: string) => <Num>{shortTime(v)}</Num> },
          {
            title: t('common.actions'),
            key: 'op',
            fixed: 'right',
            render: (_, r) => (
              <PermButton perm="config:edit" size="small" icon={<EditOutlined />} onClick={() => edit(r)}>
                {t('common.edit')}
              </PermButton>
            ),
          },
        ]}
      />
      {hr.element}
    </>
  );
}
