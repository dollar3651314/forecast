/**
 * 热钱包余额告警（2026-10-17）：六个告警下限在"系统配置"页设置，默认 0 = 不告警（Amos 2026-10-17）；对账页上方显示三个热钱包的余额、下限、最后查询时间。
 * 模拟层（和后端口径一致）、真实接口的请求、页面中英文渲染。
 */
import { screen, waitFor, within } from '@testing-library/react';
import { listAuditLogs, listHotWallets, listSystemConfigs, switchIdentity, updateSystemConfig } from './api';
import * as real from './api/real';
import { fakeFetch } from './test/fakeFetch';
import { renderAs } from './test/render';
import { ReconPage } from './pages/funds/ReconPage';
import { ConfigPage } from './pages/system/ConfigPage';

const KEYS = ['hotWallet.minUsdt.ERC20', 'hotWallet.minUsdt.BSC', 'hotWallet.minUsdt.TRC20', 'hotWallet.minGas.ERC20', 'hotWallet.minGas.BSC', 'hotWallet.minGas.TRC20'];

afterEach(async () => {
  vi.unstubAllGlobals();
  await switchIdentity('super');
  for (const k of KEYS) await updateSystemConfig(k, '0', '测试复原');
});

describe('热钱包余额告警：模拟层', () => {
  it('六个下限默认 0，单位 usdt / eth / bnb / trx，分组 hotWallet', async () => {
    const list = (await listSystemConfigs()).filter((c) => c.group === 'hotWallet');
    expect(list.map((c) => c.key)).toEqual(KEYS);
    expect(list.every((c) => c.value === '0')).toBe(true);
    expect(list.map((c) => c.unit)).toEqual(['usdt', 'usdt', 'usdt', 'eth', 'bnb', 'trx']);
  });

  it('默认 0 不告警；改成大于 0 后低于下限显示"低于下限"；改回 0 又不告警；修改写审计', async () => {
    let rows = await listHotWallets();
    expect(rows).toHaveLength(6);
    expect(rows.every((r) => r.status === 'off')).toBe(true);
    expect(rows[0].balance).toBe('812.5');

    await updateSystemConfig('hotWallet.minUsdt.ERC20', '1000', '开始告警');
    await updateSystemConfig('hotWallet.minGas.ERC20', '0.02', '开始告警');
    await updateSystemConfig('hotWallet.minGas.TRC20', '1000', '开始告警');
    rows = await listHotWallets();
    expect(rows.find((r) => r.chain === 'ERC20' && !r.gas)).toMatchObject({ status: 'low', threshold: '1000' });
    expect(rows.find((r) => r.chain === 'ERC20' && r.gas)).toMatchObject({ status: 'ok', threshold: '0.02' });
    expect(rows.find((r) => r.chain === 'TRC20' && r.gas)).toMatchObject({ status: 'unknown', balance: null });
    expect(rows.find((r) => r.chain === 'BSC' && !r.gas)?.status).toBe('off');

    const log = (await listAuditLogs({ page: 1, pageSize: 5, action: 'config.update' })).items.find((l) => l.target === 'hotWallet.minUsdt.ERC20');
    expect(log).toMatchObject({ highRisk: true, before: { value: '0' }, after: { value: '1000' } });

    await updateSystemConfig('hotWallet.minUsdt.ERC20', '0', '先不告警');
    rows = await listHotWallets();
    expect(rows.find((r) => r.chain === 'ERC20' && !r.gas)?.status).toBe('off');
  });
});

describe('热钱包余额告警：真实接口', () => {
  it('GET /api/admin/hot-wallets', async () => {
    const { calls } = fakeFetch({ 'GET /api/admin/hot-wallets': { body: [] } });
    expect(await real.listHotWallets()).toEqual([]);
    expect(calls[0].url).toBe('/api/admin/hot-wallets');
  });
});

describe('热钱包余额告警：页面', () => {
  it('对账页上方显示热钱包余额（中文）', async () => {
    await updateSystemConfig('hotWallet.minUsdt.ERC20', '1000', '开始告警');
    renderAs(<ReconPage />, { path: '/funds/recon' });
    expect(await screen.findByText('热钱包余额')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('812.5')).toBeInTheDocument());
    expect(screen.getByText('低于下限')).toBeInTheDocument();
    expect(screen.getAllByText('未启用告警').length).toBeGreaterThanOrEqual(4);
    expect(screen.getByText('最近一次查询失败')).toBeInTheDocument();
  });

  it('对账页英文', async () => {
    renderAs(<ReconPage />, { path: '/funds/recon', lang: 'en' });
    expect(await screen.findByText('Hot wallet balances')).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByText('Alert off').length).toBe(6));
  });

  it('系统配置页：下限为 0 显示"0（不告警）"，分组名"热钱包余额告警"', async () => {
    renderAs(<ConfigPage />, { path: '/system/config' });
    const row = (await screen.findByText('ERC20 热钱包 ETH（手续费）下限')).closest('tr')!;
    expect(within(row as HTMLElement).getByText('0（不告警）')).toBeInTheDocument();
    expect(within(row as HTMLElement).getByText('热钱包余额告警')).toBeInTheDocument();
  });
});
