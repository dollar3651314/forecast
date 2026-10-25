import { fireEvent, screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { renderAs } from '../test/render';
import { AppLayout } from './AppLayout';

/** v1 功能对照核对"已实现没有测试"的 C-23（v2 E12）：后台手机布局用抽屉菜单，电脑上是侧边菜单 */
function page() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<div>首页内容</div>} />
      </Route>
    </Routes>
  );
}

function setWidth(mobile: boolean) {
  window.matchMedia = ((query: string) => ({
    matches: mobile ? false : query.includes('min-width'),
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

const original = window.matchMedia;
afterEach(() => {
  window.matchMedia = original;
});

describe('后台布局（C-23）', () => {
  it('手机宽度：没有侧边菜单，点"打开菜单"后在抽屉里显示菜单', async () => {
    setWidth(true);
    renderAs(page());
    expect(await screen.findByText('首页内容')).toBeInTheDocument();
    const open = await screen.findByRole('button', { name: '打开菜单' });
    expect(document.querySelector('.ant-drawer-open')).toBeNull();
    fireEvent.click(open);
    const drawer = await screen.findByRole('dialog');
    expect(drawer.querySelector('.side-menu')).not.toBeNull();
    expect(drawer.querySelectorAll('.ant-menu-item, .ant-menu-submenu').length).toBeGreaterThan(3);
  });

  it('电脑宽度：侧边菜单常驻，没有"打开菜单"按钮', async () => {
    setWidth(false);
    renderAs(page());
    expect(await screen.findByText('首页内容')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '打开菜单' })).toBeNull();
    expect(document.querySelector('.side-menu')).not.toBeNull();
  });
});
