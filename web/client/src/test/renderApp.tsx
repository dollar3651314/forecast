import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { AccountProvider } from '../app/AccountProvider';
import { AuthProvider } from '../app/AuthProvider';
import { ThemeProvider } from '../app/ThemeProvider';
import { ToastProvider } from '../app/ToastProvider';

/** 用和正式应用相同的 Provider 渲染一个页面 */
export function renderPage(ui: ReactElement, path = '/') {
  return render(
    <ThemeProvider>
      <AuthProvider>
        <ToastProvider>
          <MemoryRouter initialEntries={[path]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
            <AccountProvider>{ui}</AccountProvider>
          </MemoryRouter>
        </ToastProvider>
      </AuthProvider>
    </ThemeProvider>,
  );
}
