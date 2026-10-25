/** forecast M1 原型新增页面的路由（只在原型模式下注册，见 protoMenu.tsx 的说明）。 */
import { MarketEditPage } from '../pages/markets/MarketEditPage';
import { MarketListPage } from '../pages/markets/MarketListPage';
import { ParamsPage } from '../pages/params/ParamsPage';
import { ResolveDeskPage } from '../pages/resolve/ResolveDeskPage';
import type { RouteDef } from './routes';

export const PROTO_ROUTES: RouteDef[] = [
  { path: '/markets', menuKey: 'markets', element: <MarketListPage /> },
  { path: '/markets/new', menuKey: 'markets', element: <MarketEditPage /> },
  { path: '/markets/:no/edit', menuKey: 'markets', element: <MarketEditPage /> },
  { path: '/resolve', menuKey: 'resolve', element: <ResolveDeskPage /> },
  { path: '/params', menuKey: 'params', element: <ParamsPage /> },
];
