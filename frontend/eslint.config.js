import eslint from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import vue from "eslint-plugin-vue";

export default tseslint.config(
  { ignores: ["dist/**", "node_modules/**", "test-results/**"] },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  ...vue.configs["flat/recommended"],
  {
    files: ["**/*.{js,ts,vue}"],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: { parser: tseslint.parser, extraFileExtensions: [".vue"] },
    },
    rules: {
      "vue/max-attributes-per-line": "off",
      "vue/html-closing-bracket-newline": "off",
      "vue/html-indent": "off",
      "vue/html-self-closing": "off",
      "vue/multi-word-component-names": "off",
      "vue/singleline-html-element-content-newline": "off",
    },
  },
  {
    files: [
      "src/components/resource/cards/DocumentResourceCard.vue",
      "src/components/resource/cards/VideoResourceCard.vue",
    ],
    rules: {
      // Both renderers sanitize content immediately before v-html.
      "vue/no-v-html": "off",
    },
  },
  {
    files: ["tests/unit/**/*.{js,ts}"],
    languageOptions: { globals: globals.vitest },
  },
);
