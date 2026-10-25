/**
 * v2 资金归集的总开关：SWEEP_UI = false 时对账页不显示"资金归集"入口，四个归集页面只显示"未上线"、不调用接口。
 * （第一阶段上线后默认是 true；TRON 等下一阶段的显示见 sweepNextPhase.test.tsx）
 */
import { screen } from '@testing-library/react';
import { renderAs } from './test/render';
import { ReconPage } from './pages/funds/ReconPage';
import { SweepRecordsPage } from './pages/funds/sweep/SweepRecordsPage';
import { SweepNewPage } from './pages/funds/sweep/SweepNewPage';
import { SweepSettingsPage } from './pages/funds/sweep/SweepSettingsPage';

vi.mock('./config/sweep', async (orig) => ({ ...(await orig<typeof import('./config/sweep')>()), SWEEP_UI: false }));

describe('资金归集总开关关闭时', () => {
  it('对账页没有入口', async () => {
    renderAs(<ReconPage />);
    expect(await screen.findByRole('heading', { name: '对账' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '资金归集' })).toBeNull();
  });

  it.each([
    ['归集记录', <SweepRecordsPage key="r" />],
    ['生成 / 执行', <SweepNewPage key="n" />],
    ['归集设置', <SweepSettingsPage key="s" />],
  ])('%s 页显示"未上线"', (_name, ui) => {
    renderAs(ui);
    expect(screen.getByText('未上线')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /执行归集|生成待签名清单|保存/ })).toBeNull();
  });
});
