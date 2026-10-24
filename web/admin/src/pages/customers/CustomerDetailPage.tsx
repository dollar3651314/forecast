/**
 * 客户详情：资料、KYC、账户余额、最近资金流水；冻结/解冻（高危）。forecast S1 删除了复制源的持仓、已平仓记录和"调整余额"
 * （调余额改为结算补偿流程，S7）；我的下注、派彩等在 S4 以后加。
 * v2 E1：编辑资料（高危，必须填原因）：姓名（名、中间名、姓）、出生日期、国籍；邮箱不能改，状态只能用冻结 / 解冻改。
 *   校验和客户端注册一致（utils/validators.ts）；KYC 已通过的客户允许改，但弹窗里提示"修改会和证件不一致"，审计里也会标出。
 * v2 E6：资料里显示最近登录 IP 和时间。
 */
import { useCallback } from 'react';
import { Alert, Button, Descriptions, Form, Input, Skeleton, Space } from 'antd';
import { ArrowLeftOutlined, LockOutlined, UnlockOutlined, EditOutlined } from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { getCustomer, setCustomerFrozen, updateCustomerProfile } from '../../api';
import type { LedgerEntry } from '../../api/types';
import { useApi } from '../../hooks/useApi';
import { useHighRisk } from '../../hooks/useConfirmDialogs';
import { PageHeader } from '../../components/PageHeader';
import { ErrorState } from '../../components/ErrorState';
import { DataTable } from '../../components/DataTable';
import { PermButton } from '../../components/PermButton';
import { Pill } from '../../components/Pill';
import { Amount, Num } from '../../components/Num';
import { customerTone, kycTone } from '../../utils/status';
import { formatAmount } from '../../utils/money';
import { DateFields } from '../../components/DateFields';
import { birthDateProblems, nameProblems, nationalityProblems } from '../../utils/validators';
import { fullTime } from '../../utils/display';

export function CustomerDetailPage() {
  const { t, i18n } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const customerId = Number(id);
  const fetcher = useCallback(() => getCustomer(customerId), [customerId]);
  const { data: c, loading, error, reload } = useApi(fetcher);
  const hr = useHighRisk();

  const askFreeze = () => {
    if (!c) return;
    const freezing = c.status === 'active';
    hr.ask({
      title: freezing ? t('customers.freeze') : t('customers.unfreeze'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: c.email })}</div>
          <div>{freezing ? t('customers.freezeImpact') : t('customers.unfreezeImpact')}</div>
        </>
      ),
      confirmText: freezing ? t('customers.confirmFreeze') : t('customers.confirmUnfreeze'),
      onConfirm: (v) => setCustomerFrozen(c.id, freezing, v.reason),
      onDone: reload,
    });
  };

  /** 表单校验：把校验函数返回的翻译键拼成一条提示（一次显示这个字段的全部问题） */
  const rule = (problems: (v: string) => string[]) => ({
    validator: (_r: unknown, value: string | undefined) => {
      const p = problems(value ?? '');
      return p.length ? Promise.reject(new Error(p.map((k) => t(k)).join('；'))) : Promise.resolve();
    },
  });

  const askEdit = () => {
    if (!c) return;
    const kycApproved = c.kyc.status === 'approved';
    hr.ask({
      title: t('customers.editProfile'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: c.email })}</div>
          <div>{t('customers.editProfileImpact')}</div>
        </>
      ),
      initialValues: {
        firstName: c.firstName ?? '',
        middleName: c.middleName ?? '',
        lastName: c.lastName ?? '',
        birthDate: c.birthDate ?? '',
        nationality: c.nationality ?? '',
      },
      extra: (
        <div className="profile-form">
          {kycApproved ? <Alert type="warning" showIcon style={{ marginBottom: 12 }} message={t('customers.kycApprovedEditWarning')} /> : null}
          <Form.Item label={t('customers.email')} extra={t('customers.emailReadOnly')}>
            <Input value={c.email} disabled />
          </Form.Item>
          <div className="name-row">
            <Form.Item name="firstName" label={t('customers.firstName')} required rules={[rule((v) => nameProblems(v, true))]}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item name="middleName" label={t('customers.middleName')} rules={[rule((v) => nameProblems(v, false))]}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item name="lastName" label={t('customers.lastName')} required rules={[rule((v) => nameProblems(v, true))]}>
              <Input autoComplete="off" />
            </Form.Item>
          </div>
          <Form.Item name="birthDate" label={t('customers.birthDate')} required extra={t('customers.birthDateHint')} rules={[rule((v) => birthDateProblems(v))]}>
            <DateFields groupLabel={t('customers.birthDate')} />
          </Form.Item>
          <Form.Item
            name="nationality"
            label={t('customers.nationality')}
            required
            extra={t('customers.nationalityHint')}
            normalize={(v: string) => (v ?? '').toUpperCase()}
            rules={[rule(nationalityProblems)]}
          >
            <Input maxLength={2} style={{ width: 120 }} autoComplete="off" />
          </Form.Item>
        </div>
      ),
      confirmText: t('customers.confirmEditProfile'),
      onConfirm: (v) =>
        updateCustomerProfile(
          c.id,
          {
            firstName: String(v.firstName ?? '').trim(),
            middleName: String(v.middleName ?? '').trim(),
            lastName: String(v.lastName ?? '').trim(),
            birthDate: String(v.birthDate ?? '').trim(),
            nationality: String(v.nationality ?? '').trim(),
          },
          v.reason,
        ),
      onDone: reload,
    });
  };

  return (
    <>
      <PageHeader
        title={
          <Space>
            <Button type="text" icon={<ArrowLeftOutlined />} aria-label={t('common.back')} onClick={() => navigate('/customers')} />
            {c ? <span className="ellipsis" style={{ maxWidth: 360 }} title={c.name}>{c.name}</span> : t('customers.detail')}
            {c ? <Pill tone={customerTone[c.status]}>{t(`customerStatus.${c.status}`)}</Pill> : null}
          </Space>
        }
        extra={
          c ? (
            <>
              <PermButton perm="customer:edit" icon={<EditOutlined />} onClick={askEdit}>
                {t('customers.editProfile')}
              </PermButton>
              <PermButton perm="customer:freeze" danger icon={c.status === 'active' ? <LockOutlined /> : <UnlockOutlined />} onClick={askFreeze}>
                {c.status === 'active' ? t('customers.freeze') : t('customers.unfreeze')}
              </PermButton>
            </>
          ) : null
        }
      />
      {error ? (
        <div className="card">
          <ErrorState error={error} onRetry={reload} />
        </div>
      ) : !c || loading ? (
        <div className="card card-pad">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      ) : (
        <>
          {c.status === 'frozen' ? <Alert type="error" showIcon message={t('customers.frozenBanner')} description={c.frozenReason ? t('customers.frozenReasonLine', { reason: c.frozenReason }) : undefined} /> : null}
          <section className="detail-grid" aria-label={t('customers.account')}>
            <div className="card stat">
              <div className="k">{t('customers.balance')}</div>
              <div className="v"><Amount value={c.account.balance} /></div>
            </div>
            <div className="card stat">
              <div className="k">{t('customers.available')}</div>
              <div className="v"><Amount value={c.account.available} /></div>
            </div>
            <div className="card stat">
              <div className="k">{t('customers.frozenWithdraw')}</div>
              <div className="v"><Num>{formatAmount(c.account.frozenWithdraw)}</Num></div>
            </div>
          </section>

          <div className="card card-pad">
            <h2 className="card-title">{t('customers.profile')}</h2>
            <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
              <Descriptions.Item label={t('customers.id')}><Num>{c.id}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.email')}>{c.email}</Descriptions.Item>
              <Descriptions.Item label={t('customers.nationality')}>{c.nationality}</Descriptions.Item>
              <Descriptions.Item label={t('customers.firstName')}>{c.firstName || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('customers.middleName')}>{c.middleName || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('customers.lastName')}>{c.lastName || '—'}</Descriptions.Item>
              <Descriptions.Item label={t('customers.birthDate')}><Num>{c.birthDate ?? '—'}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.registeredAt')}><Num>{fullTime(c.registeredAt)}</Num></Descriptions.Item>
              <Descriptions.Item label={t('customers.lastLogin')}>{c.lastLoginAt ? <Num>{fullTime(c.lastLoginAt)}</Num> : t('customers.neverLogin')}</Descriptions.Item>
              <Descriptions.Item label={t('customers.lastLoginIp')}>
                {c.lastLoginIp ? (
                  <span>
                    <Num>{c.lastLoginIp}</Num>
                    {c.lastLoginIpAt ? <span className="muted"> · <Num>{fullTime(c.lastLoginIpAt)}</Num></span> : null}
                  </span>
                ) : (
                  '—'
                )}
              </Descriptions.Item>
            </Descriptions>
          </div>

          <div className="card card-pad">
            <h2 className="card-title">
              <span>{t('customers.kycTitle')}</span>
              {c.kyc.status === 'pending' ? <Link to="/kyc">{t('customers.goKyc')}</Link> : null}
            </h2>
            <Descriptions size="small" column={{ xs: 1, sm: 2, lg: 3 }}>
              <Descriptions.Item label={t('customers.kyc')}>
                <Pill tone={kycTone[c.kyc.status]}>{t(`kycStatus.${c.kyc.status}`)}</Pill>
              </Descriptions.Item>
              <Descriptions.Item label={t('kyc.docType')}>{c.kyc.docType ? t(`kyc.doc.${c.kyc.docType}`) : '—'}</Descriptions.Item>
              <Descriptions.Item label={t('kyc.docNumber')}>{c.kyc.docNumber ? <Num>{c.kyc.docNumber}</Num> : '—'}</Descriptions.Item>
              <Descriptions.Item label={t('kyc.reviewedAt')}>{c.kyc.reviewedAt ? <Num>{fullTime(c.kyc.reviewedAt)}</Num> : '—'}</Descriptions.Item>
              {c.kyc.rejectReason ? <Descriptions.Item label={t('kyc.rejectReason')}>{c.kyc.rejectReason}</Descriptions.Item> : null}
            </Descriptions>
          </div>

          <div className="card card-pad">
            <h2 className="card-title">{t('customers.ledgerTitle')}</h2>
            <DataTable<LedgerEntry>
              bare
              rowKey="id"
              dataSource={c.ledger}
              emptyText={t('customers.noLedger')}
              columns={[
                { title: t('customers.time'), dataIndex: 'time', render: (v: string) => <Num>{fullTime(v)}</Num> },
                { title: t('customers.ledgerType'), dataIndex: 'type', render: (v: string) => (i18n.exists(`ledger.${v}`) ? t(`ledger.${v}`) : v) },
                { title: t('customers.amount'), dataIndex: 'amount', align: 'right', render: (v: string) => <Amount value={v} signed /> },
                { title: t('customers.balanceAfter'), dataIndex: 'balanceAfter', align: 'right', render: (v: string) => <Amount value={v} /> },
                { title: t('customers.ref'), dataIndex: 'ref', render: (v: string) => <Num>{v}</Num> },
              ]}
            />
          </div>
        </>
      )}
      {hr.element}
    </>
  );
}
