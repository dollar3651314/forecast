import { clientLink, resolveClientUrl } from '../config';
import { pageFromPath, pathFor } from './route';

describe('客户端地址和注册、登录链接（4.4）', () => {
  it('构建变量优先，其次是页面 meta；占位符和非法值不用', () => {
    expect(resolveClientUrl('https://test-app.example.com/', 'https://x.example')).toBe('https://test-app.example.com');
    expect(resolveClientUrl('', 'https://test-app.e2e.test')).toBe('https://test-app.e2e.test');
    expect(resolveClientUrl(undefined, '__FC_CLIENT_URL__')).toBe('');
    expect(resolveClientUrl(undefined, 'javascript:alert(1)')).toBe('');
    expect(resolveClientUrl(undefined, null)).toBe('');
  });
  it('带上当前语言', () => {
    expect(clientLink('register', 'en', 'https://test-app.example.com')).toBe('https://test-app.example.com/register?lang=en');
    expect(clientLink('login', 'zh-CN', '')).toBe('/login?lang=zh-CN');
  });
});

describe('页面路径', () => {
  it('部署在 / 时', () => {
    expect(pageFromPath('/', '/')).toBe('home');
    expect(pageFromPath('/index.html', '/')).toBe('home');
    expect(pageFromPath('/terms', '/')).toBe('terms');
    expect(pageFromPath('/privacy/', '/')).toBe('privacy');
    expect(pageFromPath('/risk', '/')).toBe('risk');
    expect(pageFromPath('/abc', '/')).toBe('notFound');
    expect(pathFor('home', '/')).toBe('/');
    expect(pathFor('terms', '/')).toBe('/terms');
  });
  it('原型站放在 /site/ 时', () => {
    expect(pageFromPath('/site', '/site/')).toBe('home');
    expect(pageFromPath('/site/', '/site/')).toBe('home');
    expect(pageFromPath('/site/risk', '/site/')).toBe('risk');
    expect(pageFromPath('/terms', '/site/')).toBe('notFound');
    expect(pathFor('privacy', '/site/')).toBe('/site/privacy');
  });
});
