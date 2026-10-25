/**
 * 后台整体布局：原型横幅 + 顶栏 + 侧边菜单（手机上收进抽屉）+ 内容区 + 原型场景面板。
 * 菜单按当前身份的权限显示或隐藏：真实模式用 /api/admin/me 返回的菜单（顺序、是否显示由后台"菜单管理"决定）。
 * 原型横幅、场景面板、"切换原型身份"只在 mock 模式下出现。
 */
import { useEffect, useMemo, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Avatar, Button, Drawer, Dropdown, Grid, Layout, Menu, Tooltip, type MenuProps } from 'antd';
import { MenuOutlined, TranslationOutlined, UserSwitchOutlined, LogoutOutlined, KeyOutlined, DownOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { findMenuByPath, visibleMenu, type MenuItemDef } from '../config/menu';
import { useAuth } from '../auth/AuthContext';
import { IS_MOCK, getMenuBadges, type ProtoIdentity } from '../api';
import type { MenuBadges } from '../api/types';
import { setLanguage } from '../i18n';
import { EnvBanner } from './EnvBanner';
import { ProtoBanner } from './ProtoBanner';
import { ScenarioPanel } from './ScenarioPanel';
import { Logo } from './Logo';
import { ThemeSwitch } from './ThemeSwitch';

const IDENTITIES: ProtoIdentity[] = ['super', 'ops', 'risk'];

export function AppLayout() {
  const { t, i18n } = useTranslation();
  const screens = Grid.useBreakpoint();
  const isDesktop = screens.lg ?? true;
  const [drawerOpen, setDrawerOpen] = useState(false);
  const { me, can, identity, switchIdentity, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [badges, setBadges] = useState<MenuBadges>({ kyc: 0, withdrawals: 0 });

  // 每次切换页面刷新菜单角标（待审核 KYC、待审核出金）
  useEffect(() => {
    let alive = true;
    getMenuBadges().then((b) => alive && setBadges(b), () => undefined);
    return () => {
      alive = false;
    };
  }, [location.pathname]);

  useEffect(() => setDrawerOpen(false), [location.pathname]);

  const current = findMenuByPath(location.pathname);

  const items = useMemo<MenuProps['items']>(() => {
    const toItem = (m: MenuItemDef): NonNullable<MenuProps['items']>[number] | null => {
      if (m.children) {
        const children = m.children.map(toItem).filter(Boolean) as NonNullable<MenuProps['items']>;
        if (!children.length) return null;
        return { key: m.key, icon: m.icon, label: t(m.labelKey), children };
      }
      if (!can(m.permission)) return null;
      const count = m.badge ? badges[m.badge] : 0;
      return {
        key: m.key,
        icon: m.icon,
        label: (
          <Link to={m.path!} className="menu-link">
            <span>{t(m.labelKey)}</span>
            {count > 0 ? <span className="menu-badge" aria-label={t('menu.badgeAria', { count })}>{count}</span> : null}
          </Link>
        ),
      };
    };
    return visibleMenu(me?.menus, can).map(toItem).filter(Boolean) as MenuProps['items'];
  }, [t, can, badges, me?.menus]);

  const [openKeys, setOpenKeys] = useState<string[]>(current?.parent ? [current.parent.key] : []);
  useEffect(() => {
    if (current?.parent) setOpenKeys((keys) => (keys.includes(current.parent!.key) ? keys : [...keys, current.parent!.key]));
  }, [current?.parent]);

  const menu = (
    <Menu
      mode="inline"
      items={items}
      selectedKeys={current ? [current.key] : []}
      openKeys={openKeys}
      onOpenChange={(keys) => setOpenKeys(keys as string[])}
      className="side-menu"
    />
  );

  const identityMenu: MenuProps = {
    selectedKeys: [identity],
    items: [
      { key: 'title', type: 'group', label: t('proto.identityHint') },
      ...IDENTITIES.map((id) => ({ key: id, label: t(`proto.identity.${id}`) })),
    ],
    onClick: ({ key }) => {
      void switchIdentity(key as ProtoIdentity);
    },
  };

  const userMenu: MenuProps = {
    items: [
      { key: 'pwd', icon: <KeyOutlined />, label: t('auth.changePassword') },
      { key: 'logout', icon: <LogoutOutlined />, label: t('auth.logout') },
    ],
    onClick: ({ key }) => {
      if (key === 'logout') {
        logout();
        navigate('/login');
      } else navigate('/change-password');
    },
  };

  const crumbs = current ? [current.parent ? t(current.parent.labelKey) : null, t(current.labelKey)].filter(Boolean) : [];
  const name = me?.admin.displayName ?? '';

  return (
    <Layout className="app-shell">
      <ProtoBanner />
      <EnvBanner />
      <Layout.Header className="app-header">
        {!isDesktop ? (
          <Button type="text" icon={<MenuOutlined />} aria-label={t('menu.open')} onClick={() => setDrawerOpen(true)} className="hamburger" />
        ) : null}
        <Logo compact={!isDesktop} />
        {isDesktop && crumbs.length ? (
          <nav className="crumb" aria-label={t('menu.breadcrumb')}>
            {crumbs.map((c, i) => (i === crumbs.length - 1 ? <b key={i}>{c}</b> : <span key={i}>{c} / </span>))}
          </nav>
        ) : null}
        <div className="header-right">
          {IS_MOCK ? (
            <Dropdown menu={identityMenu} trigger={['click']}>
              <Button className="identity-btn" icon={<UserSwitchOutlined />} aria-label={t('proto.switchIdentity')}>
                {isDesktop ? <span>{t('proto.switchIdentity')}：</span> : null}
                <b>{t(`proto.identity.${identity}`)}</b>
                <DownOutlined style={{ fontSize: 10 }} />
              </Button>
            </Dropdown>
          ) : null}
          <ThemeSwitch />
          <Tooltip title={t('common.switchLanguage')}>
            <Button
              type="text"
              icon={<TranslationOutlined />}
              aria-label={t('common.switchLanguage')}
              onClick={() => setLanguage(i18n.language.startsWith('zh') ? 'en' : 'zh-CN')}
            >
              {isDesktop ? (i18n.language.startsWith('zh') ? 'EN' : '中文') : null}
            </Button>
          </Tooltip>
          <Dropdown menu={userMenu} trigger={['click']}>
            <Button type="text" className="who" aria-label={t('auth.account')}>
              <Avatar size={28} className="who-avatar">
                {name.slice(0, 1)}
              </Avatar>
              {isDesktop ? (
                <span className="who-name">
                  {name} · {me?.roles.map((r) => (i18n.language.startsWith('zh') ? r.nameZh : r.nameEn)).join(' / ')}
                </span>
              ) : null}
            </Button>
          </Dropdown>
        </div>
      </Layout.Header>
      <Layout className={`app-body${isDesktop ? ' with-sider' : ''}`} hasSider={isDesktop}>
        {isDesktop ? (
          <Layout.Sider width={200} className="app-sider" theme="light">
            {menu}
          </Layout.Sider>
        ) : (
          <Drawer
            open={drawerOpen}
            onClose={() => setDrawerOpen(false)}
            placement="left"
            width={260}
            title={<Logo />}
            className="menu-drawer"
            rootClassName="menu-drawer-root"
            styles={{ body: { padding: '8px 0' } }}
          >
            {menu}
          </Drawer>
        )}
        <Layout.Content className="app-content" id="main">
          <Outlet />
        </Layout.Content>
      </Layout>
      <ScenarioPanel />
    </Layout>
  );
}
