import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  guide: [
    {
      type: 'category',
      label: 'Getting started',
      collapsed: false,
      items: ['guide/installation', 'guide/playlists', 'guide/updating'],
    },
    {
      type: 'category',
      label: 'Using OpenTV',
      collapsed: false,
      items: [
        'guide/browsing',
        'guide/player',
        'guide/epg-catchup',
        'guide/downloads',
        'guide/settings',
        'guide/account',
      ],
    },
    {
      type: 'category',
      label: 'Web client',
      collapsed: false,
      link: { type: 'doc', id: 'guide/webclient' },
      items: [
        'guide/webclient-tour',
        'guide/webclient-now-watching',
        'guide/webclient-hosting',
      ],
    },
    { type: 'doc', id: 'download', label: 'Download the app' },
    { type: 'doc', id: 'faq', label: 'FAQ' },
  ],
};

export default sidebars;
