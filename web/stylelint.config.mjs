// Tokens only: outside src/tokens.css every colour, type size, weight, line height, spacing, radius, shadow,
// z-index and motion value must be a var(--token) (or a keyword). A value that is missing becomes a token.
// `npm run lint:css`. Zero violations, zero disables.
const keywords = [
  'inherit', 'initial', 'unset', 'revert', 'currentColor', 'currentcolor', 'transparent', 'none', 'auto', '0',
  '100%', '50%', 'normal', '1px', '-1px', 'Canvas', 'CanvasText', 'GrayText', 'Highlight', 'ButtonText',
];

export default {
  plugins: ['stylelint-declaration-strict-value'],
  ignoreFiles: ['src/tokens.css', 'src/fonts.css', 'src/pages/configurations/kioskFonts.css', 'dist/**', 'node_modules/**'],
  rules: {
    'scale-unlimited/declaration-strict-value': [
      [
        '/color$/', 'fill', 'stroke', 'font-size', 'font-weight', 'font-family', 'line-height', 'letter-spacing',
        'border-radius', 'box-shadow', 'z-index', 'transition-duration', 'transition-timing-function',
        'animation-duration', 'animation-timing-function', '/^(margin|padding)/', 'gap', 'row-gap', 'column-gap',
      ],
      {
        ignoreValues: keywords,
        expandShorthand: true,
        disableFix: true,
        message: 'Use a token from tokens.css (or add one there) instead of the raw "${value}" for ${property}.',
      },
    ],
    'declaration-property-value-disallowed-list': { transition: ['/\\ball\\b/'], 'transition-property': ['all'] },
    'color-no-hex': true,
    'color-named': 'never',
    'function-disallowed-list': ['rgb', 'rgba', 'hsl', 'hsla'],
  },
};
