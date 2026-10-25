/**
 * 路由表（basename = /admin）。menuKey 表示该页面从哪个菜单进入：详情页归到列表菜单（单元测试检查）。
 * forecast S1：复制源的交易、风控、品种行情、交易参数页面删除；市场管理、裁决台等新页面随 S3 以后各步加（需求说明书第 6 节）。
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

export interface RouteDef {
  path: string;
  menuKey: string;
  element: ReactNode;
}

export const APP_ROUTES: RouteDef[] = [
  { path: '/dashboard', menuKey: 'dashboard', element: <DashboardPage /> },
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
  { path: '/funds/tasks', menuKey: 'tasks', element: <TasksPage /> },
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
