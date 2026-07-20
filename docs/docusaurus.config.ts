import type * as Preset from '@docusaurus/preset-classic';
import type { Config } from '@docusaurus/types';
import { themes as prismThemes } from 'prism-react-renderer';

const repo = 'https://github.com/Buco7854/opentv';
const releaseApk = `${repo}/releases/latest/download/app-release.apk`;
const devApk = `${repo}/releases/download/dev/app-release.apk`;

const config: Config = {
  title: 'OpenTV',
  tagline: 'Open-source IPTV player for Android and Android TV.',
  favicon: 'logo.svg',

  url: 'https://opentv.grimbert.net',
  baseUrl: '/',
  trailingSlash: false,

  onBrokenLinks: 'warn',
  markdown: { hooks: { onBrokenMarkdownLinks: 'warn' } },

  i18n: { defaultLocale: 'en', locales: ['en'] },

  presets: [
    [
      'classic',
      {
        docs: {
          path: 'docs',
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: `${repo}/edit/main/docs/`,
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    image: 'banner.svg',
    colorMode: {
      defaultMode: 'dark',
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'OpenTV',
      logo: { alt: 'OpenTV', src: 'logo.svg' },
      items: [
        { to: '/guide/installation', label: 'Guide', position: 'left', activeBasePath: '/guide' },
        { to: '/download', label: 'Download', position: 'left' },
        { to: '/faq', label: 'FAQ', position: 'left' },
        {
          type: 'dropdown',
          label: 'Get the app',
          position: 'right',
          items: [
            { label: 'Latest release', href: releaseApk },
            { label: 'Dev channel', href: devApk },
            { label: 'All releases', href: `${repo}/releases` },
          ],
        },
        { href: repo, label: 'GitHub', position: 'right' },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Use OpenTV',
          items: [
            { label: 'Installation', to: '/guide/installation' },
            { label: 'Add a playlist', to: '/guide/playlists' },
            { label: 'Web client', to: '/guide/webclient' },
          ],
        },
        {
          title: 'Get the app',
          items: [
            { label: 'Latest release', href: releaseApk },
            { label: 'Dev channel', href: devApk },
            { label: 'All releases', href: `${repo}/releases` },
          ],
        },
        {
          title: 'Project',
          items: [
            { label: 'GitHub', href: repo },
            { label: 'FAQ', to: '/faq' },
          ],
        },
      ],
      copyright: 'OpenTV is a player, not a provider: it ships with no channels, streams or subscriptions.',
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.vsDark,
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
