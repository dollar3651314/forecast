import { langFromSearch } from './urlLang';

describe('langFromSearch：官网跳转带的语言参数', () => {
  it('认识 zh、zh-CN、en，不分大小写', () => {
    expect(langFromSearch('?lang=en')).toBe('en');
    expect(langFromSearch('?lang=EN')).toBe('en');
    expect(langFromSearch('?lang=zh-CN')).toBe('zh-CN');
    expect(langFromSearch('?lang=zh')).toBe('zh-CN');
    expect(langFromSearch('?x=1&lang=zh-cn')).toBe('zh-CN');
  });

  it('没有参数或不认识的值返回 null', () => {
    expect(langFromSearch('')).toBeNull();
    expect(langFromSearch('?lang=')).toBeNull();
    expect(langFromSearch('?lang=fr')).toBeNull();
  });
});
