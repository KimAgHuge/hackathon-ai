import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import eslintConfigPrettier from 'eslint-config-prettier/flat'

export default [
  {
    ignores: [
      'dist/**',
      'coverage/**',
      'node_modules/**',
      'supabase/.temp/**',
      'supabase/functions/**',
    ],
  },

  js.configs.recommended,

  ...pluginVue.configs['flat/essential'],

  {
    files: ['src/**/*.{js,vue}'],

    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',

      globals: {
        ...globals.browser,
      },
    },

    rules: {
      'no-console': [
        'warn',
        {
          allow: ['warn', 'error'],
        },
      ],

      'no-unused-vars': [
        'warn',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
        },
      ],

      'vue/no-unused-vars': 'warn',

      'vue/multi-word-component-names': [
        'error',
        {
          ignores: ['App'],
        },
      ],
    },
  },

  {
    files: ['vite.config.js', 'eslint.config.js'],

    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },

  eslintConfigPrettier,
]
