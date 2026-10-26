/**
 * v2 提现自动审批（技术方案/v2-提现自动审批.md 第 9 节）：出金列表的"规则判定"列和筛选、详情的规则判定卡片、规则设置页（权限 withdrawal:auto_rule_edit、
 * 版本号冲突）。页面跑模拟数据（mock）模式，模拟层和真实接口同一口径；真实接口的路径和字段在 api/real.test.ts。
 */
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import i18n from './i18n';
import { getWithdrawal, listAutoApproveChanges, listAuditLogs, listWithdrawals, updateAutoApproveSettings, getAutoApproveSettings } from './api';
import type { AutoRuleResult } from './api/types';
import { renderAs } from './test/render';
import { crossRuleProblems, ruleParamProblem, AUTO_RULES } from './config/autoApprove';
import { WithdrawalsPage } from './pages/funds/WithdrawalsPage';
import { AutoApprovePage } from './pages/funds/AutoApprovePage';
import { AutoReviewCard, ruleLine } from './pages/funds/AutoReview';

const t = i18n.getFixedT('zh-CN');

describe('规则判定的模拟数据', () => {
  it('R4、R5 和出金详情一致：此前已完成提现的笔数 = 这个客户之前已完成的出金，"最近出金"里能看到', async () => {
    const all = (await listWithdrawals({ page: 1, pageSize: 100, status: 'pending' })).items.filter((w) => w.autoReview?.phase === 'final');
    expect(all.length).toBeGreaterThan(5);
    for (const w of all) {
      const d = await getWithdrawal(w.id);
      const r4 = d.autoReviewDetail!.rules.find((x) => x.code === 'R4')!;
      expect(r4.values.count).toBe(String(d.recentWithdrawals.length));
      expect(r4.status).toBe(d.recentWithdrawals.length ? 'passed' : 'failed');
      // firstUse（地址第一次用）和 R5 一致
      expect(d.autoReviewDetail!.rules.find((x) => x.code === 'R5')!.status).toBe(d.firstUse ? 'failed' : 'passed');
    }
  });

  it('覆盖：自动通过、触发 1 条、触发多条、总开关关闭、冷静期预判、没有判定记录', async () => {
    const all = (await listWithdrawals({ page: 1, pageSize: 100 })).items;
    const outcomes = all.map((w) => w.autoReview?.outcome ?? 'none');
    expect(outcomes).toContain('auto_approved');
    expect(outcomes).toContain('master_off');
    expect(outcomes).toContain('none');
    expect(all.some((w) => w.autoReview?.outcome === 'manual' && w.autoReview.failed.length === 1)).toBe(true);
    expect(all.some((w) => w.autoReview?.outcome === 'manual' && w.autoReview.failed.length >= 3)).toBe(true);
    expect(all.some((w) => w.status === 'cooling' && w.autoReview?.phase === 'precheck')).toBe(true);
    // 自动通过的没有审核人
    all.filter((w) => w.autoReview?.outcome === 'auto_approved' && w.autoReview.phase === 'final').forEach((w) => expect(w.reviewer).toBeNull());
  });

  it('按"触发了某条规则"和判定结果筛选（包括 R13）', async () => {
    const r6 = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'R6' })).items;
    expect(r6.length).toBeGreaterThan(0);
    r6.forEach((w) => expect(w.autoReview?.failed).toContain('R6'));
    const auto = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'auto_approved' })).items;
    expect(auto.length).toBeGreaterThan(0);
    auto.forEach((w) => expect(w.autoReview?.outcome).toBe('auto_approved'));
    const none = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'none' })).items;
    none.forEach((w) => expect(w.autoReview).toBeNull());
  });

  it('判定记录带判定时的阈值快照：第 1 版时单笔上限是 1,000', async () => {
    const all = (await listWithdrawals({ page: 1, pageSize: 100 })).items;
    const old = all.find((w) => w.status === 'failed' && w.autoReview?.outcome === 'auto_approved')!;
    const d = (await getWithdrawal(old.id)).autoReviewDetail!;
    expect(d.configVersion).toBe(1);
    expect(d.rules.find((r) => r.code === 'R1')!.values.limit).toBe('1000');
  });
});

describe('判定说明的文字', () => {
  const r = (code: AutoRuleResult['code'], status: AutoRuleResult['status'], values: Record<string, string> = {}): AutoRuleResult => ({ code, status, values });
  it('带具体数值，金额有千分位、去掉 .00', () => {
    expect(ruleLine(t, r('R1', 'failed', { amount: '800.00', limit: '500' }))).toBe('单笔 800 > 上限 500');
    expect(ruleLine(t, r('R1', 'passed', { amount: '500.00', limit: '500' }))).toBe('单笔 500 ≤ 上限 500');
    expect(ruleLine(t, r('R12', 'failed', { issues: 'hotWallet', balance: '820', need: '1099' }))).toBe('热钱包余额 820 < 需要 1,099');
    expect(ruleLine(t, r('R6', 'failed', { event: 'password_change', ago: '1.5', hours: '48' }))).toBe('1.5 小时前改了密码（窗口 48 小时）');
    expect(ruleLine(t, r('R8', 'disabled'))).toBe('未启用（不参与判定）');
    expect(ruleLine(t, r('R8', 'passed', { volume: '0', deposit: '0', ratio: '—', limit: '1', note: 'noDeposit' }))).toBe('近 30 天没有充值，不检查');
    expect(ruleLine(t, r('R12', 'failed', { issues: 'hotWalletUnknown', balance: '—', need: '99' }))).toBe('查不到热钱包余额（超过 10 分钟没查成功），按不通过处理');
    // forecast S5（AC-38）：彩池对账有未处理的差异；和热钱包一起不通过时都列出
    expect(ruleLine(t, r('R12', 'failed', { issues: 'poolRecon', poolIssues: '2', balance: '9,999', need: '99' }))).toBe('彩池对账有 2 个未处理的差异');
    expect(ruleLine(t, r('R12', 'failed', { issues: 'poolRecon,hotWallet', poolIssues: '1', balance: '1', need: '99' }))).toMatch(/^彩池对账有 1 个未处理的差异.+热钱包余额 1 < 需要 99$/);
    expect(ruleLine(t, r('R7', 'failed', { days: '3', limit: '7', kyc: 'pending' }))).toBe('注册 3 天（要求 ≥ 7 天）· KYC 待审核');
    expect(ruleLine(t, r('R3', 'unknown', { why: '数据读取失败' }))).toBe('无法判定：数据读取失败（按未通过处理）');
  });
});

describe('R13 盈利客户（forecast S10：派彩 + 补偿 − 已结算本金 − 抽成）', () => {
  const r = (status: AutoRuleResult['status'], values: Record<string, string> = {}): AutoRuleResult => ({ code: 'R13', status, values });
  it('判定说明写明累计净盈利 = 派彩 + 补偿 − 本金 − 抽成，以及门槛', () => {
    expect(ruleLine(t, r('failed', { net: '1600', payout: '1860', compensation: '0', stake: '212.4', fee: '47.6', limit: '0' }))).toBe(
      '累计净盈利 1,600 = 派彩 1,860 + 补偿 0 − 本金 212.40 − 抽成 47.60，门槛 0（超过，转人工）',
    );
    expect(ruleLine(t, r('passed', { net: '-12.25', payout: '310', compensation: '0', stake: '236.8', fee: '85.45', limit: '0' }))).toBe(
      '累计净盈利 -12.25 = 派彩 310 + 补偿 0 − 本金 236.80 − 抽成 85.45，门槛 0（未超过）',
    );
    expect(ruleLine(t, r('unknown', { why: '数据读取失败' }))).toBe('无法判定：数据读取失败（按未通过处理）');
    expect(t('autoRule.R13.name')).toBe('盈利客户');
  });

  it('门槛：单位 USDT、默认 0、可以填 0，不能是负数、最多 2 位小数', () => {
    const def = AUTO_RULES.find((x) => x.code === 'R13')!;
    expect(def.params).toEqual([{ key: 'maxNetProfit', unit: 'usdt', defaultValue: '0', min: 0, max: 1_000_000, decimals: 2 }]);
    expect(ruleParamProblem(def.params[0], '0')).toBeNull();
    expect(ruleParamProblem(def.params[0], '1000.5')).toBeNull();
    expect(ruleParamProblem(def.params[0], '-1')?.key).toBe('autoApprove.errDecimals');
    expect(ruleParamProblem(def.params[0], '0.001')?.key).toBe('autoApprove.errDecimals');
    expect(ruleParamProblem(def.params[0], '1000000.01')?.key).toBe('autoApprove.errRange');
  });

  it('模拟数据：默认开启、门槛 0；有只触发 R13 的待审核出金；R13 的数值自洽（净盈利 = 派彩 + 补偿 − 本金 − 抽成，R9 的盈亏 = 净盈利）', async () => {
    const s = await getAutoApproveSettings();
    expect(s.rules.find((x) => x.code === 'R13')).toEqual({ code: 'R13', enabled: true, params: { maxNetProfit: '0' } });
    const hit = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'R13' })).items;
    expect(hit.length).toBeGreaterThanOrEqual(2);
    hit.forEach((w) => expect(w.autoReview?.failed).toContain('R13'));
    const only = hit.find((w) => w.status === 'pending' && w.autoReview?.failed.join(',') === 'R13');
    expect(only).toBeDefined();
    const all = (await listWithdrawals({ page: 1, pageSize: 200 })).items.filter((w) => w.autoReview);
    for (const w of all) {
      const d = (await getWithdrawal(w.id)).autoReviewDetail!;
      const r13 = d.rules.find((x) => x.code === 'R13')!;
      expect(r13).toBeDefined();
      if (r13.status === 'disabled') continue;
      const v = r13.values;
      const net = Number(v.payout) + Number(v.compensation) - Number(v.stake) - Number(v.fee);
      expect(Number(v.net)).toBeCloseTo(net, 2);
      expect(r13.status).toBe(Number(v.net) > Number(v.limit) ? 'failed' : 'passed');
      expect(d.failed.includes('R13')).toBe(r13.status === 'failed');
      const r9 = d.rules.find((x) => x.code === 'R9')!;
      if (r9.status !== 'disabled' && Number(r9.values.deposit) > 0) expect(r9.values.pnl).toBe(v.net);
    }
  });

  it('详情卡片：只触发 R13 时标红排在最上面；设置页有 R13 一行（默认开、默认 0 USDT）', async () => {
    const only = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'R13' })).items.find((w) => w.autoReview?.failed.join(',') === 'R13')!;
    const d = await getWithdrawal(only.id);
    renderAs(<AutoReviewCard detail={d.autoReviewDetail} />);
    expect(screen.getByText('触发 1 条规则，转人工审核')).toBeInTheDocument();
    const first = document.querySelector('li.ar-row')!;
    expect(first).toHaveClass('failed');
    expect(first.querySelector('.ar-code')?.textContent).toBe('R13');
    expect(within(first as HTMLElement).getByText(/累计净盈利 1,600 = 派彩 1,860 \+ 补偿 0 − 本金 212.40 − 抽成 47.60，门槛 0/)).toBeInTheDocument();
  });

  it('设置页 R13 一行：开关默认开、门槛 0 USDT；改成负数标红不能保存', async () => {
    renderAs(<AutoApprovePage />);
    expect(await screen.findByRole('switch', { name: '启用 R13 盈利客户' })).toBeChecked();
    const row = document.querySelector('.aa-row[data-rule="R13"]') as HTMLElement;
    expect(within(row).getByText('默认 0 USDT')).toBeInTheDocument();
    const input = within(row).getByLabelText('门槛');
    expect(input).toHaveValue('0');
    fireEvent.change(input, { target: { value: '-5' } });
    expect(await within(row).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保存修改/ })).toBeDisabled();
  });
});

describe('规则数值校验', () => {
  it('整数、小数位、范围；单笔 ≤ 客户 24 小时 ≤ 全平台 24 小时', () => {
    const r1 = AUTO_RULES[0].params[0];
    const r11 = AUTO_RULES.find((x) => x.code === 'R11')!.params[0];
    expect(ruleParamProblem(r1, '500')).toBeNull();
    expect(ruleParamProblem(r1, '500.123')?.key).toBe('autoApprove.errDecimals');
    expect(ruleParamProblem(r1, '')?.key).toBe('autoApprove.errRequired');
    expect(ruleParamProblem(r11, '2.5')?.key).toBe('autoApprove.errInteger');
    expect(ruleParamProblem(r11, '0')?.key).toBe('autoApprove.errRange');
    expect(crossRuleProblems({ R1: { maxAmount: '3000' }, R2: { maxUser24h: '2000' }, R3: { maxPlatform24h: '20000' } })).toEqual([
      { code: 'R2', key: 'maxUser24h', msg: 'autoApprove.errR2LtR1' },
    ]);
  });
});

describe('出金审核页：规则判定列和卡片', () => {
  it('列表显示"触发 N 条"和规则编号，详情未通过的标红排在最上面', async () => {
    renderAs(<WithdrawalsPage />);
    expect(await screen.findByRole('columnheader', { name: '规则判定' })).toBeInTheDocument();
    expect((await screen.findAllByText('触发 3 条')).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /自动审批规则/ })).toBeInTheDocument();
    const card = await screen.findByRole('region', { name: '规则判定' });
    expect(within(card).getByText('触发 3 条规则，转人工审核')).toBeInTheDocument();
    const rows = card.querySelectorAll('li.ar-row');
    expect(rows).toHaveLength(AUTO_RULES.length);
    expect([...rows].slice(0, 3).map((li) => li.querySelector('.ar-code')?.textContent)).toEqual(['R1', 'R2', 'R5']);
    expect(rows[0]).toHaveClass('failed');
    expect(within(card).getByText('单笔 5,000 > 上限 500')).toBeInTheDocument();
    expect(within(card).getByText('未启用（不参与判定）')).toBeInTheDocument();
  });

  it('总开关关闭时：显示"总开关关闭，全部人工审核"和如果打开的结果', async () => {
    const all = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'master_off' })).items;
    const pass = all.find((w) => w.autoReview!.failed.length === 0)!;
    const d = await getWithdrawal(pass.id);
    renderAs(<AutoReviewCard detail={d.autoReviewDetail} />);
    expect(screen.getByText('总开关关闭，全部人工审核')).toBeInTheDocument();
    expect(screen.getByText('规则照常判定供参考：如果打开，本笔会自动通过。')).toBeInTheDocument();
  });

  it('自动通过：已启用的规则全部通过', async () => {
    const auto = (await listWithdrawals({ page: 1, pageSize: 100, autoReview: 'auto_approved' })).items.find((w) => w.autoReview!.phase === 'final')!;
    const d = await getWithdrawal(auto.id);
    renderAs(<AutoReviewCard detail={d.autoReviewDetail} />);
    expect(screen.getByText(/条规则全部通过，系统自动通过/)).toBeInTheDocument();
  });

  it('没有判定记录时说明原因', () => {
    renderAs(<AutoReviewCard detail={null} />);
    expect(screen.getByText('没有判定记录（自动审批上线前提交的提现）。')).toBeInTheDocument();
  });
});

describe('自动审批规则设置页', () => {
  it('显示总开关（默认关闭的说明）、全部规则（条数按实际，现在 15 条）、最近修改记录', async () => {
    renderAs(<AutoApprovePage />);
    expect(await screen.findByRole('switch', { name: '自动审批总开关' })).toBeChecked();
    expect(screen.getByText('默认：关闭')).toBeInTheDocument();
    expect(AUTO_RULES).toHaveLength(15);
    expect(document.querySelectorAll('.aa-row')).toHaveLength(AUTO_RULES.length);
    expect(screen.getByRole('heading', { name: '15 条规则' })).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: '启用 R8 充值后的下注量' })).not.toBeChecked();
    expect(await screen.findByText('R1 单笔金额 · 单笔上限：1,000 USDT → 500 USDT')).toBeInTheDocument();
  });

  it('数值不合法时标红，保存按钮不可用', async () => {
    renderAs(<AutoApprovePage />);
    const r2 = await screen.findByLabelText('累计上限', { selector: '#aa-R2\\.maxUser24h' });
    fireEvent.change(r2, { target: { value: '100' } });
    expect(await screen.findByText('不能小于 R1 单笔上限')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保存修改/ })).toBeDisabled();
  });

  it('运营身份没有修改权限：开关和输入框不可用', async () => {
    renderAs(<AutoApprovePage />, { adminId: 2 });
    expect(await screen.findByRole('switch', { name: '自动审批总开关' })).toBeDisabled();
  });

  it('保存：高危确认填原因，成功后写修改记录和审计，版本 +1', async () => {
    renderAs(<AutoApprovePage />);
    const r1 = await screen.findByLabelText('单笔上限');
    fireEvent.change(r1, { target: { value: '800' } });
    fireEvent.click(screen.getByRole('switch', { name: '启用 R11 当天提现次数' }));
    expect(screen.getByText('有 2 项修改未保存')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('R1 单笔金额 · 单笔上限：500 USDT → 800 USDT')).toBeInTheDocument();
    // 原因必填、勾选确认由 HighRiskModal.test.tsx 统一测
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '测试放宽单笔' } });
    fireEvent.click(within(dialog).getByRole('checkbox'));
    fireEvent.click(within(dialog).getByRole('button', { name: '确认保存' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const s = await getAutoApproveSettings();
    expect(s.version).toBe(5);
    expect(s.rules.find((r) => r.code === 'R1')!.params.maxAmount).toBe('800');
    expect(s.rules.find((r) => r.code === 'R11')!.enabled).toBe(false);
    expect((await listAutoApproveChanges())[0].reason).toBe('测试放宽单笔');
    const log = (await listAuditLogs({ page: 1, pageSize: 5 })).items[0];
    expect(log.action).toBe('withdrawal.auto_rule_update');
    expect(log.highRisk).toBe(true);
    // 没有修改不能保存；版本号不对和后端一样返回 409
    await expect(updateAutoApproveSettings({ masterEnabled: s.masterEnabled, rules: s.rules, reason: 'x', expectedVersion: s.version })).rejects.toMatchObject({
      reasonKey: 'errors.autoApproveNoChange',
    });
    await expect(updateAutoApproveSettings({ masterEnabled: false, rules: s.rules, reason: 'x', expectedVersion: s.version - 1 })).rejects.toMatchObject({
      status: 409,
      code: 'CONFIG_CHANGED',
    });
  });

  it('别人先保存了（版本变了）：提示 409 原因，页面换成最新的配置', async () => {
    renderAs(<AutoApprovePage />);
    const r1 = await screen.findByLabelText('单笔上限');
    fireEvent.change(r1, { target: { value: '450' } });
    // 另一个人在这期间保存了一次（R11 改成 4 笔）
    const cur = await getAutoApproveSettings();
    await updateAutoApproveSettings({
      masterEnabled: cur.masterEnabled,
      rules: cur.rules.map((r) => (r.code === 'R11' ? { ...r, params: { maxCount: '4' } } : r)),
      reason: '另一个人改的',
      expectedVersion: cur.version,
    });
    fireEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: '收紧单笔' } });
    fireEvent.click(within(dialog).getByRole('checkbox'));
    fireEvent.click(within(dialog).getByRole('button', { name: '确认保存' }));
    expect(await within(dialog).findByText(/规则已被别人修改/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('每天最多')).toHaveValue('4'));
  });

  it('风控身份没有 withdrawal:auto_rule_edit：开关不可用，直接调接口也被拒', async () => {
    renderAs(<AutoApprovePage />, { adminId: 3 });
    expect(await screen.findByRole('switch', { name: '自动审批总开关' })).toBeDisabled();
    await expect(updateAutoApproveSettings({ ...(await getAutoApproveSettings()), reason: 'x', expectedVersion: 5 })).rejects.toMatchObject({ reasonKey: 'errors.forbidden' });
  });
});
