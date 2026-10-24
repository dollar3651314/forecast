import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

// 和 web/client 同一套规则
export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: { ecmaVersion: 2020, globals: { ...globals.browser, ...globals.node } },
    plugins: { 'react-hooks': reactHooks, 'react-refresh': reactRefresh },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      // 页面和组件不能直接引用模拟层，必须走 src/api（真实构建不含模拟层，约定 20）
      'no-restricted-imports': [
        'error',
        { patterns: [{ group: ['**/mocks/**', '**/mocks'], message: '页面和组件只能通过 src/api 获取数据，不能直接引用 mocks' }] },
      ],
    },
  },
  {
    files: ['src/api/**', 'src/mocks/**', 'src/test/**', '**/*.test.{ts,tsx}'],
    rules: { 'no-restricted-imports': 'off', 'react-refresh/only-export-components': 'off' },
  },
);
