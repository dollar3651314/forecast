/** 测试工具：带路由、主题、i18n、登录状态的渲染 */
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Providers } from '../Providers';
import i18n from '../i18n';
import { setScenario } from '../mocks/runtime';
import * as db from '../mocks/data/db';

export function renderAs(ui: ReactElement, opts: { adminId?: number; path?: string; lang?: 'zh-CN' | 'en' } = {}) {
  const adminId = opts.adminId ?? 1;
  const admin = db.admins.find((a) => a.id === adminId)!;
  localStorage.setItem(
    'fx-admin-session',
    JSON.stringify({ token: 't', adminId, username: admin.username, displayName: admin.displayName, mustChangePassword: false }),
  );
  void i18n.changeLanguage(opts.lang ?? 'zh-CN');
  return render(
    <MemoryRouter initialEntries={[opts.path ?? '/']}>
      <Providers>{ui}</Providers>
    </MemoryRouter>,
  );
}

export { setScenario };
