import { tseslint, hooks, globals } from './tools/quality/runtime.mjs';

export default [
  {
    ignores: [
      '**/node_modules/**',
      '**/target/**',
      '**/dist/**',
      '**/coverage/**',
      '**/test-results/**',
      '**/playwright-report/**',
      'artifacts/**',
      'docs/**',
    ],
  },
  {
    files: ['**/*.{js,mjs,mts,ts,tsx}'],
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    plugins: { '@typescript-eslint': tseslint.plugin, 'react-hooks': hooks },
    rules: {
      'no-undef': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
        },
      ],
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
    },
  },
  {
    files: [
      '**/*.mjs',
      '**/*.mts',
      'frontend/*config.ts',
      'frontend/e2e/**/*.ts',
      'frontend/tests/**/*.ts',
    ],
    languageOptions: { globals: globals.node },
  },
  {
    files: [
      'frontend/{src,e2e,tests,scripts}/**/*.{ts,tsx,mts}',
      'frontend/{vite,playwright}.config.ts',
    ],
    // The frontend tsconfig includes TS/MTS scripts too; its compiler checks names and types.
    rules: { 'no-undef': 'off' },
  },
  {
    files: ['frontend/src/**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser } },
  },
];
