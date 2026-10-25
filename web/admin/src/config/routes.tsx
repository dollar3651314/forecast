/**
 * 路由表（basename = /admin）。menuKey 表示该页面从哪个菜单进入：详情页归到列表菜单（单元测试检查）。
 * forecast S1：复制源的交易、风控、品种行情、交易参数页面删除；S9 加市场管理、待上架、自动生成、体育、裁决台、公示与争议、结算记录、补偿、
 * 彩池对账、报表、参数设置（需求说明书第 6 节）。
 */
import type { ReactNode } from 'react';
import { DashboardPage } from '../pages/DashboardPage';
import { CustomerListPage } from '../pages/customers/CustomerListPage';
import { CustomerDetailPage } from '../pages/customers/CustomerDetailPage';
import { KycListPage } from '../pages/kyc/KycListPage';
import { KycDetailPage } from '../pages/kyc/KycDetailPage';
import { DepositsPage } from '../pages/funds/DepositsPage';
import { WithdrawalsPage } from '../pages/funds/WithdrawalsPage';
import { AutoApprovePage } from '../pages/funds/AutoApprovePage';
import { ReconPage } from '../pages/funds/ReconPage';
import { SweepRecordsPage } from '../pages/funds/sweep/SweepRecordsPage';
import { SweepNewPage } from '../pages/funds/sweep/SweepNewPage';
import { SweepBatchPage } from '../pages/funds/sweep/SweepBatchPage';
import { SweepSettingsPage } from '../pages/funds/sweep/SweepSettingsPage';
import { TasksPage } from '../pages/funds/TasksPage';
import { TemplatesPage } from '../pages/notify/TemplatesPage';
import { SendPage } from '../pages/notify/SendPage';
import { RecordsPage } from '../pages/notify/RecordsPage';
import { AdminsPage } from '../pages/system/AdminsPage';
import { RolesPage } from '../pages/system/RolesPage';
import { MenusPage } from '../pages/system/MenusPage';
import { PermissionsPage } from '../pages/system/PermissionsPage';
import { AuditPage } from '../pages/system/AuditPage';
import { ConfigPage } from '../pages/system/ConfigPage';
import { MarketListPage } from '../pages/markets/MarketListPage';
import { MarketEditPage } from '../pages/markets/MarketEditPage';
import { MarketDetailPage } from '../pages/markets/MarketDetailPage';
import { AutogenPage } from '../pages/markets/AutogenPage';
import { SportsPage } from '../pages/markets/SportsPage';
import { ResolveDeskPage } from '../pages/resolve/ResolveDeskPage';
import { DisputesPage } from '../pages/resolve/DisputesPage';
import { SettleRunsPage } from '../pages/settle/SettleRunsPage';
import { CompensationsPage } from '../pages/settle/CompensationsPage';
import { PoolReconPage } from '../pages/funds/PoolReconPage';
import { ReportsPage } from '../pages/reports/ReportsPage';
import { GrowthPage } from '../pages/growth/GrowthPage';
import { ParamsPage } from '../pages/params/ParamsPage';
import { RiskAlertsPage } from '../pages/risk/RiskAlertsPage';

export interface RouteDef {
  path: string;
  menuKey: string;
  element: ReactNode;
}

export const APP_ROUTES: RouteDef[] = [
  { path: '/dashboard', menuKey: 'dashboard', element: <DashboardPage /> },
  { path: '/markets', menuKey: 'markets', element: <MarketListPage /> },
  { path: '/markets/review', menuKey: 'market-review', element: <MarketListPage review /> },
  { path: '/markets/new', menuKey: 'markets', element: <MarketEditPage /> },
  { path: '/markets/autogen', menuKey: 'autogen', element: <AutogenPage /> },
  { path: '/markets/sports', menuKey: 'sports', element: <SportsPage /> },
  { path: '/markets/:no', menuKey: 'markets', element: <MarketDetailPage /> },
  { path: '/markets/:no/edit', menuKey: 'markets', element: <MarketEditPage /> },
  { path: '/resolve', menuKey: 'resolve', element: <ResolveDeskPage /> },
  { path: '/resolve/disputes', menuKey: 'disputes', element: <DisputesPage /> },
  { path: '/settle/runs', menuKey: 'settle-runs', element: <SettleRunsPage /> },
  { path: '/settle/compensations', menuKey: 'compensations', element: <CompensationsPage /> },
  { path: '/reports', menuKey: 'reports', element: <ReportsPage /> },
  { path: '/growth', menuKey: 'growth', element: <GrowthPage /> },
  { path: '/params', menuKey: 'params', element: <ParamsPage /> },
  { path: '/customers', menuKey: 'customers', element: <CustomerListPage /> },
  { path: '/customers/:id', menuKey: 'customers', element: <CustomerDetailPage /> },
  { path: '/kyc', menuKey: 'kyc', element: <KycListPage /> },
  { path: '/kyc/:id', menuKey: 'kyc', element: <KycDetailPage /> },
  { path: '/funds/deposits', menuKey: 'deposits', element: <DepositsPage /> },
  { path: '/funds/withdrawals', menuKey: 'withdrawals', element: <WithdrawalsPage /> },
  // v2 自动审批规则（原型；真实接口模式下显示"未上线"），从出金审核页进入，归在出金审核菜单下
  { path: '/funds/withdrawals/auto-rules', menuKey: 'withdrawals', element: <AutoApprovePage /> },
  { path: '/funds/reconciliation', menuKey: 'reconciliation', element: <ReconPage /> },
  // v2 资金归集（原型；真实接口模式下显示"未上线"），从对账页进入，归在入金对账菜单下
  { path: '/funds/reconciliation/sweep', menuKey: 'reconciliation', element: <SweepRecordsPage /> },
  { path: '/funds/reconciliation/sweep/new', menuKey: 'reconciliation', element: <SweepNewPage /> },
  { path: '/funds/reconciliation/sweep/batches/:no', menuKey: 'reconciliation', element: <SweepBatchPage /> },
  { path: '/funds/reconciliation/sweep/settings', menuKey: 'reconciliation', element: <SweepSettingsPage /> },
  { path: '/funds/pool-recon', menuKey: 'pool-recon', element: <PoolReconPage /> },
  { path: '/funds/tasks', menuKey: 'tasks', element: <TasksPage /> },
  { path: '/risk/alerts', menuKey: 'risk-alerts', element: <RiskAlertsPage /> },
  { path: '/notify/templates', menuKey: 'templates', element: <TemplatesPage /> },
  { path: '/notify/send', menuKey: 'send', element: <SendPage /> },
  { path: '/notify/records', menuKey: 'records', element: <RecordsPage /> },
  { path: '/system/admins', menuKey: 'admins', element: <AdminsPage /> },
  { path: '/system/roles', menuKey: 'roles', element: <RolesPage /> },
  { path: '/system/menus', menuKey: 'menus', element: <MenusPage /> },
  { path: '/system/permissions', menuKey: 'permissions', element: <PermissionsPage /> },
  { path: '/system/audit', menuKey: 'audit', element: <AuditPage /> },
  { path: '/system/config', menuKey: 'config', element: <ConfigPage /> },
];
