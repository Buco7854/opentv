import { defineConfig } from 'vitepress'

const repo = 'https://github.com/Buco7854/opentv'
const releaseApk = `${repo}/releases/latest/download/app-release.apk`
const devApk = `${repo}/releases/download/dev/app-release.apk`

export default defineConfig({
  lang: 'en-US',
  title: 'OpenTV',
  description: 'Open-source IPTV player for Android and Android TV.',
  // Served from the custom domain opentv.grimbert.net at the root.
  base: '/',
  lastUpdated: true,
  ignoreDeadLinks: true,

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#5BE3B5' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'OpenTV' }],
    ['meta', { property: 'og:description', content: 'Open-source IPTV player for Android and Android TV.' }],
  ],

  themeConfig: {
    logo: '/logo.svg',

    nav: [
      { text: 'Guide', link: '/guide/installation', activeMatch: '/guide/' },
      { text: 'Download', link: '/download' },
      { text: 'FAQ', link: '/faq' },
      {
        text: 'Get the app',
        items: [
          { text: 'Latest release', link: releaseApk },
          { text: 'Dev channel', link: devApk },
          { text: 'All releases', link: `${repo}/releases` },
        ],
      },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Getting started',
          items: [
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Add a playlist', link: '/guide/playlists' },
            { text: 'Updating the app', link: '/guide/updating' },
          ],
        },
        {
          text: 'Using OpenTV',
          items: [
            { text: 'Browsing and search', link: '/guide/browsing' },
            { text: 'The player', link: '/guide/player' },
            { text: 'EPG and catch-up', link: '/guide/epg-catchup' },
            { text: 'Downloads', link: '/guide/downloads' },
            { text: 'Settings', link: '/guide/settings' },
            { text: 'Account and privacy', link: '/guide/account' },
          ],
        },
      ],
    },

    socialLinks: [{ icon: 'github', link: repo }],

    search: { provider: 'local' },

    editLink: {
      pattern: 'https://github.com/Buco7854/opentv/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },

    footer: {
      message: 'Open-source IPTV player. Not affiliated with any content provider.',
      copyright: 'Released under the GPL v3.0 License.',
    },
  },
})
