/**
 * 客户详情：资料、KYC、账户余额、最近资金流水；冻结/解冻（高危）。forecast S1 删除了复制源的持仓、已平仓记录和"调整余额"
 * （调余额改为结算补偿流程，S7）；我的下注、派彩等在 S4 以后加。
 * v2 E1：编辑资料（高危，必须填原因）：姓名（名、中间名、姓）、出生日期、国籍；邮箱不能改，状态只能用冻结 / 解冻改。
 *   校验和客户端注册一致（utils/validators.ts）；KYC 已通过的客户允许改，但弹窗里提示"修改会和证件不一致"，审计里也会标出。
 * v2 E6：资料里显示最近登录 IP 和时间。
 * forecast S10：员工账户登记 / 取消登记（customer:edit，高危，原因必填；登记后不能下注，已有下注按 E15 当场退回，弹窗提示退回的笔数和金额）；
 *   关联账户（同设备、同 IP、同充值来源地址、同提现地址，后端 riskalert 扫描生成，需求第 8.1 节）。
 * forecast S17A：测试入金（balance:test_credit，高危）：只在测试环境显示——后端详情的 testCredit 不为空才显示按钮（生产为 null，接口也是 404）；
 *   金额、原因必填、勾选确认；每次打开确认框生成一个请求号，重复点击只入一次。
 * forecast S17B：人工加款 / 人工扣款（balance:adjust，高危，生产可用；需求说明书第 21 节）：金额、类别、原因必填，参考号选填；
 *   扣款不能超过可用余额；超过单笔上限（详情的 adjustMax）时要再勾"我确认超过单笔上限"；请求号防重复点击。
 */
import { useCallback } from 'react';
import { Alert, App, Button, Checkbox, Descriptions, Form, Input, Radio, Skeleton, Space } from 'antd';
import { ArrowLeftOutlined, LockOutlined, UnlockOutlined, EditOutlined, SafetyOutlined, ExperimentOutlined, PlusCircleOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { adjustBalance, getCustomer, getCustomerLinks, setCustomerFrozen, setCustomerStaff, testCredit, updateCustomerProfile } from '../../api';
import { ADJUST_CATEGORIES, type AdjustCategory, type AdjustDirection } from '../../api/fcTypes';
import { compareAmounts } from '../../utils/money';
import type { CustomerLink } from '../../api/fcTypes';
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
import { amountProblem, newRequestId } from '../../utils/adjust';

export function CustomerDetailPage() {
  const { t, i18n } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const customerId = Number(id);
  const fetcher = useCallback(() => getCustomer(customerId), [customerId]);
  const { data: c, loading, error, reload } = useApi(fetcher);
  const links = useApi(useCallback(() => getCustomerLinks(customerId), [customerId]));
  const hr = useHighRisk();
  const { message } = App.useApp();

  /** forecast S10：登记 / 取消登记员工账户 */
  const askStaff = () => {
    if (!c) return;
    const registering = !c.staff;
    hr.ask({
      title: registering ? t('customers.staffOn') : t('customers.staffOff'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: c.email })}</div>
          <div>{registering ? t('customers.staffOnImpact') : t('customers.staffOffImpact')}</div>
        </>
      ),
      confirmText: registering ? t('customers.confirmStaffOn') : t('customers.confirmStaffOff'),
      onConfirm: async (v) => {
        const r = await setCustomerStaff(c.id, registering, v.reason);
        if (registering) void message.info(r.bets > 0 ? t('customers.staffRefunded', { bets: r.bets, markets: r.markets, amount: formatAmount(r.amount) }) : t('customers.staffNoRefund'));
      },
      onDone: reload,
    });
  };

  /** forecast S17A：测试入金（只在测试环境；c.testCredit 为空时没有按钮） */
  const askTestCredit = () => {
    if (!c?.testCredit) return;
    const max = c.testCredit.maxAmount;
    const requestId = newRequestId();
    hr.ask({
      title: t('customers.testCredit'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: c.email })}</div>
          <div>{t('customers.testCreditImpact')}</div>
        </>
      ),
      extra: (
        <Form.Item
          name="amount"
          label={t('customers.testCreditAmount')}
          extra={t('customers.testCreditMax', { max: formatAmount(max) })}
          required
          rules={[
            {
              validator: (_r: unknown, value: string | undefined) => {
                const p = amountProblem(value, max);
                if (!p) return Promise.resolve();
                const key = p === 'required' ? 'customers.testCreditAmountRequired' : p === 'invalid' ? 'customers.testCreditAmountInvalid' : 'customers.testCreditOverLimit';
                return Promise.reject(new Error(t(key, { max: formatAmount(max) })));
              },
            },
          ]}
        >
          <Input inputMode="decimal" autoComplete="off" suffix="USDT" style={{ width: 240 }} />
        </Form.Item>
      ),
      confirmText: t('customers.confirmTestCredit'),
      successText: '',
      onConfirm: async (v) => {
        const r = await testCredit(c.id, String(v.amount ?? '').trim(), v.reason, requestId);
        const cr = r.credit;
        void message.success(
          cr.duplicate
            ? t('customers.testCreditDuplicate')
            : t('customers.testCreditDone', { amount: formatAmount(cr.amount), before: formatAmount(cr.availableBefore), after: formatAmount(cr.availableAfter) }),
        );
      },
      onDone: reload,
    });
  };

  /** forecast S17B：人工加款 / 扣款 */
  const askAdjust = (direction: AdjustDirection) => {
    if (!c) return;
    const max = c.adjustMax ?? '10000';
    const available = c.account.available;
    const requestId = newRequestId();
    const credit = direction === 'credit';
    const cats = ADJUST_CATEGORIES[direction];
    hr.ask({
      title: credit ? t('customers.adjustCredit') : t('customers.adjustDebit'),
      description: (
        <>
          <div>{t('customers.targetLine', { name: c.name, id: c.id, email: c.email })}</div>
          <div>{credit ? t('customers.adjustCreditImpact') : t('customers.adjustDebitImpact', { available: formatAmount(available) })}</div>
        </>
      ),
      initialValues: { category: cats[0], overLimitConfirmed: false },
      extra: (
        <>
          <Form.Item
            name="amount"
            label={t('customers.adjustAmount')}
            extra={t('customers.adjustMax', { max: formatAmount(max) })}
            required
            rules={[
              {
                validator: (_r: unknown, value: string | undefined) => {
                  const p = amountProblem(value);
                  if (p === 'required') return Promise.reject(new Error(t('customers.adjustAmountRequired')));
                  if (p) return Promise.reject(new Error(t('customers.adjustAmountInvalid')));
                  if (!credit && compareAmounts(String(value).trim(), available, 8) > 0) {
                    return Promise.reject(new Error(t('customers.adjustOverAvailable', { available: formatAmount(available) })));
                  }
                  return Promise.resolve();
                },
              },
            ]}
          >
            <Input inputMode="decimal" autoComplete="off" suffix="USDT" style={{ width: 240 }} />
          </Form.Item>
          <Form.Item name="category" label={t('customers.adjustCategory')} required rules={[{ required: true, message: t('customers.adjustCategoryRequired') }]}>
            <Radio.Group options={cats.map((k: AdjustCategory) => ({ value: k, label: t(`adjust.cat.${k}`) }))} />
          </Form.Item>
          <Form.Item name="refNo" label={t('customers.adjustRefNo')} extra={t('customers.adjustRefNoHint')} rules={[{ max: 128 }]}>
            <Input autoComplete="off" maxLength={128} />
          </Form.Item>
          <Form.Item noStyle shouldUpdate={(a, b) => a.amount !== b.amount}>
            {({ getFieldValue }) =>
              amountProblem(getFieldValue('amount')) === null && compareAmounts(String(getFieldValue('amount')).trim(), max, 8) > 0 ? (
                <Form.Item
                  name="overLimitConfirmed"
                  valuePropName="checked"
                  extra={t('customers.adjustOverLimitHint')}
                  rules={[{ validator: (_r, v) => (v ? Promise.resolve() : Promise.reject(new Error(t('customers.adjustOverLimitRequired')))) }]}
                >
                  <Checkbox data-testid="adjust-over-limit">{t('customers.adjustOverLimit', { max: formatAmount(max) })}</Checkbox>
                </Form.Item>
              ) : null
            }
          </Form.Item>
        </>
      ),
      confirmText: credit ? t('customers.confirmAdjustCredit') : t('customers.confirmAdjustDebit'),
      successText: '',
      onConfirm: async (v) => {
        const amount = String(v.amount ?? '').trim();
        const r = await adjustBalance(c.id, {
          direction,
          category: v.category as AdjustCategory,
          amount,
          refNo: String(v.refNo ?? ''),
          reason: v.reason,
          requestId,
          overLimitConfirmed: compareAmounts(amount, max, 8) > 0 && v.overLimitConfirmed === true,
        });
        const a = r.adjustment;
        void message.success(
          r.duplicate
            ? t('customers.adjustDuplicate')
            : t('customers.adjustDone', { dir: t(`adjust.dir.${a.direction}`), amount: formatAmount(a.amount), before: formatAmount(a.availableBefore), after: formatAmount(a.availableAfter) }),
        );
      },
      onDone: reload,
    });
  };

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
            {c?.staff ? <Pill tone="admin">{t('customers.staffTag')}</Pill> : null}
          </Space>
        }
        extra={
          c ? (
            <>
              <PermButton perm="customer:edit" icon={<EditOutlined />} onClick={askEdit}>
                {t('customers.editProfile')}
              </PermButton>
              <PermButton perm="customer:edit" icon={<SafetyOutlined />} onClick={askStaff}>
                {c.staff ? t('customers.staffOff') : t('customers.staffOn')}
              </PermButton>
              <PermButton perm="balance:adjust" icon={<PlusCircleOutlined />} onClick={() => askAdjust('credit')} data-testid="adjust-credit">
                {t('customers.adjustCredit')}
              </PermButton>
              <PermButton perm="balance:adjust" icon={<MinusCircleOutlined />} onClick={() => askAdjust('debit')} data-testid="adjust-debit">
                {t('customers.adjustDebit')}
              </PermButton>
              {c.testCredit ? (
                <PermButton perm="balance:test_credit" icon={<ExperimentOutlined />} onClick={askTestCredit} data-testid="test-credit">
                  {t('customers.testCredit')}
                </PermButton>
              ) : null}
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
          {c.staff ? <Alert type="warning" showIcon message={t('customers.staffBanner')} /> : null}
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
              <div className="k">{t('customers.betLocked')}</div>
              <div className="v"><Num>{formatAmount(c.account.betLocked)}</Num></div>
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
            <h2 className="card-title">{t('customers.linksTitle')}</h2>
            <DataTable<CustomerLink>
              bare
              rowKey={(r) => `${r.userId}-${r.reason}`}
              loading={links.loading}
              error={links.error}
              onRetry={links.reload}
              dataSource={links.data ?? undefined}
              emptyText={t('customers.noLinks')}
              columns={[
                { title: t('customers.linkedCustomer'), dataIndex: 'userId', render: (v: string, r) => <Link to={`/customers/${v}`}>{r.customer ?? v} <span className="muted">#{v}</span></Link> },
                { title: t('customers.linkReason'), dataIndex: 'reason', render: (v: string) => t(`customers.linkReasons.${v}`, { defaultValue: v }) },
                { title: t('customers.linkEvidence'), dataIndex: 'evidence', render: (v: string) => <Num className="ellipsis">{v}</Num> },
                { title: t('customers.linkSince'), dataIndex: 'firstSeenAt', render: (v: string) => <Num>{fullTime(v)}</Num> },
              ]}
            />
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
