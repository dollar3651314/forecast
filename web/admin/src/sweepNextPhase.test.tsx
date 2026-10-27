/**
 * v2 资金归集第一阶段（2026-10-20）：真实接口模式下（SWEEP_NEXT_PHASE = true）以太坊、BSC 可以用，TRON 和自动归集显示"下一阶段"。
 * 这里用模拟数据渲染页面，只把 SWEEP_NEXT_PHASE 打开；真实接口的请求和字段转换见 sweepReal.test.ts。
 */
import { screen, within } from '@testing-library/react';
import { renderAs } from './test/render';
import { ReconPage } from './pages/funds/ReconPage';
import { SweepRecordsPage } from './pages/funds/sweep/SweepRecordsPage';
import { SweepNewPage } from './pages/funds/sweep/SweepNewPage';
import { SweepSettingsPage } from './pages/funds/sweep/SweepSettingsPage';
import { SWEEP_PERM } from './config/sweep';
import { ALL_PERMISSION_CODES, PERMISSIONS } from './config/permissions';

vi.mock('./config/sweep', async (orig) => {
  const m = await orig<typeof import('./config/sweep')>();
  return { ...m, SWEEP_NEXT_PHASE: true, sweepChainLive: (c: string) => c !== 'TRC20' };
});

describe('资金归集第一阶段（真实接口模式的显示）', () => {
  it('权限点换成 sweep:view / sweep:operate / sweep:config，后两个是高危', () => {
    expect(SWEEP_PERM).toEqual({ view: 'sweep:view', operate: 'sweep:operate', config: 'sweep:config' });
    // forecast S1 删除差价合约的 20 个权限点后剩 32 个（和后端 Permission 一致）
    expect(ALL_PERMISSION_CODES).toHaveLength(58); // forecast S3 按需求第 2.2 节加了 21 个；S13 加 growth:view；S15 加 content:view / edit / publish（S16 的同名两个整合 4 去重）；S12b 加 alert_params:edit
    expect(PERMISSIONS.filter((p) => p.code.startsWith('sweep:')).map((p) => [p.code, p.highRisk])).toEqual([
      ['sweep:view', false],
      ['sweep:operate', true],
      ['sweep:config', true],
    ]);
  });

  it('对账页有"资金归集"入口；超管能看到', async () => {
    renderAs(<ReconPage />);
    expect(await screen.findByRole('button', { name: '资金归集' })).toBeInTheDocument();
  });

  it('归集记录：TRON 卡片显示下一阶段，以太坊、BSC 照常', async () => {
    renderAs(<SweepRecordsPage />);
    const tron = await screen.findByRole('region', { name: 'TRC20' });
    expect(within(tron).getByText('下一阶段')).toBeInTheDocument();
    expect(within(tron).queryByRole('button', { name: /预览并/ })).toBeNull();
    const erc = screen.getByRole('region', { name: 'ERC20' });
    expect(within(erc).queryByText('下一阶段')).toBeNull();
  });

  it('生成 / 执行：默认以太坊；选 TRON 只显示下一阶段、不调用预览', async () => {
    renderAs(<SweepNewPage />, { path: '/funds/reconciliation/sweep/new' });
    expect(await screen.findByRole('button', { name: '执行归集' })).toBeInTheDocument();
    renderAs(<SweepNewPage />, { path: '/funds/reconciliation/sweep/new?chain=TRC20' });
    expect(await screen.findByText(/TRON 归集（离线签名 \+ 租能量）是下一阶段/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '生成待签名清单' })).toBeNull();
  });

  it('归集设置：只显示第一阶段的项，TRON 冷钱包和能量不显示', async () => {
    renderAs(<SweepSettingsPage />);
    expect(await screen.findByRole('region', { name: '合约和冷钱包（从链上读取）' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'TRON 冷钱包地址' })).toBeNull();
    expect(screen.getByText(/是下一阶段，这里先不显示/)).toBeInTheDocument();
    expect(screen.getByLabelText('归集门槛 ERC20')).toBeInTheDocument();
    expect(screen.getByLabelText('每个地址上限')).toBeInTheDocument();
    expect(screen.queryByLabelText('TRON 能量')).toBeNull();
    expect(screen.queryByLabelText('自动归集（以太坊、BSC）')).toBeNull();
  });
});
