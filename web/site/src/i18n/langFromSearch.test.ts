import { langFromSearch } from './lang';

describe('地址栏 ?lang=（客户端"我的"里的服务条款等链接带上客户端的语言，S11）', () => {
  it('认识的三种语言直接用，其他忽略', () => {
    expect(langFromSearch('?lang=zh-TW')).toBe('zh-TW');
    expect(langFromSearch('?x=1&lang=en')).toBe('en');
    expect(langFromSearch('?lang=zh-CN')).toBe('zh-CN');
    expect(langFromSearch('?lang=ja')).toBeNull();
    expect(langFromSearch('')).toBeNull();
    expect(langFromSearch(undefined)).toBeNull();
  });
});
