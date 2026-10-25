/**
 * 右上角的主题切换（v2 E2）：跟随系统 / 浅色 / 深色。按钮图标显示当前生效的主题。
 */
import { Button, Dropdown, type MenuProps } from 'antd';
import { DesktopOutlined, MoonOutlined, SunOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useThemeMode, type ThemePref } from '../hooks/useThemeMode';

const PREFS: ThemePref[] = ['system', 'light', 'dark'];
const ICONS: Record<ThemePref, JSX.Element> = { system: <DesktopOutlined />, light: <SunOutlined />, dark: <MoonOutlined /> };

export function ThemeSwitch() {
  const { t } = useTranslation();
  const { pref, theme, setPref } = useThemeMode();
  const menu: MenuProps = {
    selectable: true,
    selectedKeys: [pref],
    items: PREFS.map((p) => ({ key: p, icon: ICONS[p], label: t(`theme.${p}`) })),
    onClick: ({ key }) => setPref(key as ThemePref),
  };
  const label = t('theme.switch', { current: t(`theme.${pref}`) });
  return (
    <Dropdown menu={menu} trigger={['click']}>
      <Button type="text" icon={theme === 'dark' ? <MoonOutlined /> : <SunOutlined />} aria-label={label} title={label} data-theme-pref={pref} className="theme-btn" />
    </Dropdown>
  );
}
