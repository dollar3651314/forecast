/** 官网用到的几个线条图标（内联 SVG，不加载外部图标字体） */
const PATHS: Record<string, string> = {
  coin: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm-3.5 5h7M12 8v8m-3 0h6',
  eye: 'M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6S2 12 2 12Zm10-3a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z',
  phone: 'M8 2.5h8a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5Zm3 16h2',
  shield: 'M12 2.5 4.5 5.5v6c0 4.6 3.2 8.4 7.5 10 4.3-1.6 7.5-5.4 7.5-10v-6L12 2.5Zm-3 9.5 2.2 2.2L15.5 10',
  download: 'M12 3v12m0 0-4.5-4.5M12 15l4.5-4.5M4 19.5h16',
  list: 'M9 6h11M9 12h11M9 18h11M4.5 6l.01 0M4.5 12l.01 0M4.5 18l.01 0',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18Zm0 4.5V12l3 2',
  id: 'M3.5 5.5h17v13h-17Zm4.5 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm-2.5 7c.5-1.5 1.5-2.2 2.5-2.2s2 .7 2.5 2.2M13.5 10h4.5M13.5 13.5h3',
  menu: 'M4 6.5h16M4 12h16M4 17.5h16',
  close: 'M6 6l12 12M18 6 6 18',
  globe: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM3.5 9h17M3.5 15h17M12 3c-2.5 2.6-3.5 5.6-3.5 9s1 6.4 3.5 9c2.5-2.6 3.5-5.6 3.5-9s-1-6.4-3.5-9Z',
  chevronDown: 'M6 9l6 6 6-6',
  check: 'M5 12.5l4.5 4.5L19 7.5',
};

export type IconName = keyof typeof PATHS;

export function Icon({ name }: { name: IconName }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name]} />
    </svg>
  );
}
