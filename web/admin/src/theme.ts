/**
 * Ant Design 主题：颜色、圆角、字号取自效果图 M6、M7 的设计 token。
 * 白底上的主色文字和按钮用 cyan-700 #0e7490（满足 WCAG AA 4.5:1）。
 *
 * v2 E2 深色主题：浅色、深色两套 token，和 styles/global.css 里 :root、:root[data-theme='dark'] 的 CSS 变量一一对应（改一边要同时改另一边）。
 * 深色下的文字、链接、涨跌色在所有底色（bg、s1、s2、s3）上的对比度都不低于 4.5:1（WCAG AA，数值见《技术方案/v1-后台方案.md》第 11 节）；
 * 主色按钮仍用 #0e7490 底 + 白字（5.4:1），链接和选中的菜单用更亮的 #5fd4e8。
 */
import { theme as antdThemes, type ThemeConfig } from 'antd';

export type ThemeName = 'light' | 'dark';

const shared = {
  sans: '-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei","Noto Sans CJK SC","Hiragino Sans GB","WenQuanYi Zen Hei",sans-serif',
  mono: '"JetBrains Mono",ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,"Liberation Mono","DejaVu Sans Mono",monospace',
};

export const tokens = {
  ...shared,
  bg: '#f4f7fb',
  s1: '#ffffff',
  s2: '#f8fafc',
  s3: '#eef5f9',
  border: 'rgba(15,23,42,.12)',
  hair: 'rgba(15,23,42,.07)',
  text: '#0f172a',
  muted: '#475569',
  subtle: '#64748b',
  primary: '#0891b2',
  primaryDeep: '#0e7490',
  link: '#0e7490',
  long: '#15803d',
  short: '#dc2626',
  warn: '#b45309',
  risk: '#c2410c',
  danger: '#b91c1c',
};

/** 深色（slate 底）：和客户端、官网同一套深色方向 */
export const darkTokens: typeof tokens = {
  ...shared,
  bg: '#0b1220',
  s1: '#111a2b',
  s2: '#0f1726',
  s3: '#1a2538',
  border: 'rgba(148,163,184,.24)',
  hair: 'rgba(148,163,184,.13)',
  text: '#e2e8f0',
  muted: '#a8b5c8',
  subtle: '#94a3b8',
  primary: '#22d3ee',
  primaryDeep: '#0e7490',
  link: '#5fd4e8',
  long: '#4ade80',
  short: '#f87171',
  warn: '#fbbf24',
  risk: '#fb923c',
  danger: '#fca5a5',
};

export function buildAntdTheme(name: ThemeName): ThemeConfig {
  const dark = name === 'dark';
  const t = dark ? darkTokens : tokens;
  return {
    algorithm: dark ? antdThemes.darkAlgorithm : antdThemes.defaultAlgorithm,
    token: {
      colorPrimary: t.primaryDeep,
      colorInfo: t.primaryDeep,
      colorLink: t.link,
      colorSuccess: t.long,
      colorWarning: t.warn,
      colorError: dark ? '#f87171' : t.danger,
      colorText: t.text,
      colorTextSecondary: t.muted,
      colorTextTertiary: t.subtle,
      colorTextDescription: t.subtle,
      colorTextPlaceholder: t.subtle,
      colorTextHeading: t.text,
      colorBgLayout: t.bg,
      colorBgContainer: t.s1,
      colorBgElevated: dark ? '#162033' : '#ffffff',
      colorInfoBg: dark ? '#0f3649' : '#ecfeff',
      colorInfoBorder: dark ? '#155e75' : '#a5f3fc',
      colorWarningBg: dark ? '#3b2f17' : '#fff7ed',
      colorWarningBorder: dark ? '#92400e' : '#fdba74',
      colorErrorBg: dark ? '#3a1d24' : '#fef2f2',
      colorErrorBorder: dark ? '#7f1d1d' : '#fecaca',
      colorSuccessBg: dark ? '#123326' : '#f0fdf4',
      colorSuccessBorder: dark ? '#166534' : '#bbf7d0',
      colorBorder: dark ? 'rgba(148,163,184,.30)' : 'rgba(15,23,42,.16)',
      colorBorderSecondary: t.hair,
      borderRadius: 6,
      borderRadiusLG: 8,
      fontSize: 13,
      fontFamily: t.sans,
      fontFamilyCode: t.mono,
    },
    components: {
      Layout: { headerBg: t.s1, siderBg: t.s1, bodyBg: t.bg, headerHeight: 52, headerPadding: '0 20px 0 16px' },
      Menu: {
        itemBg: t.s1,
        itemSelectedBg: dark ? 'rgba(34,211,238,.14)' : 'rgba(8,145,178,.10)',
        itemSelectedColor: t.link,
        itemColor: t.muted,
        itemHoverColor: t.text,
        itemHeight: 38,
        subMenuItemBg: t.s1,
        itemMarginInline: 8,
      },
      Table: {
        headerBg: t.s3,
        headerColor: t.muted,
        cellPaddingBlock: 9,
        cellPaddingInline: 10,
        headerSplitColor: 'transparent',
        rowSelectedBg: dark ? 'rgba(34,211,238,.10)' : 'rgba(8,145,178,.07)',
        rowHoverBg: dark ? '#17233a' : '#f3f8fb',
      },
      Card: { paddingLG: 16 },
      Button: { primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none' },
      Tag: { defaultBg: t.s3 },
    },
  };
}

/** 兼容旧引用：浅色主题 */
export const antdTheme: ThemeConfig = buildAntdTheme('light');
