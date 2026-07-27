import Link from '@docusaurus/Link';
import Layout from '@theme/Layout';
import type { ReactNode } from 'react';
import styles from './index.module.css';

const repo = 'https://github.com/Buco7854/opentv';
const releaseApk = `${repo}/releases/latest/download/app-release.apk`;
const devApk = `${repo}/releases/download/dev/app-release.apk`;

/* Thin line glyphs (Lucide), same set as the apps. */
const ICONS: Record<string, ReactNode> = {
  playlist: <><path d="M21 5H3" /><path d="M10 12H3" /><path d="M10 19H3" /><path d="M15 12.003a1 1 0 0 1 1.517-.859l4.997 2.997a1 1 0 0 1 0 1.718l-4.997 2.997a1 1 0 0 1-1.517-.86z" /></>,
  calendar: <><path d="M8 2v4" /><path d="M16 2v4" /><rect width="18" height="18" x="3" y="4" rx="2" /><path d="M3 10h18" /></>,
  download: <><path d="M12 15V3" /><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><path d="m7 10 5 5 5-5" /></>,
  play: <path d="M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z" />,
  pip: <><path d="M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4" /><rect width="10" height="7" x="12" y="13" rx="2" /></>,
  lock: <><rect width="18" height="11" x="3" y="11" rx="2" /><path d="M7 11V7a5 5 0 0 1 10 0v4" /></>,
  tv: <><path d="m17 2-5 5-5-5" /><rect width="20" height="15" x="2" y="7" rx="2" /></>,
};

function Glyph({ name }: { name: string }) {
  return (
    <svg viewBox="0 0 24 24" className={styles.glyph} fill="none" stroke="currentColor"
         strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      {ICONS[name]}
    </svg>
  );
}

const FEATURES: [string, string, string][] = [
  ['playlist', 'M3U and Xtream', "Add a playlist by URL or file, or log in to an Xtream panel for server-side Live, Movies and Series categories with no classification guessing."],
  ['calendar', 'EPG and catch-up', 'XMLTV guide with now and next on live rows, a full per-channel guide, and replay of past programmes on channels that keep an archive.'],
  ['download', 'Offline downloads', "Save movies and episodes for offline viewing, with pausable, resumable transfers that respect your provider's connection limit."],
  ['play', 'A player that gets out of the way', 'Hardware decoding, subtitle and audio track selection, playback speed, zoom, double-tap seek and immersive fullscreen.'],
  ['pip', 'Picture-in-Picture', 'Keep watching in a floating window with play and pause controls while you use other apps.'],
  ['lock', 'Private by design', 'No servers, no accounts, no analytics. Your credentials and data stay on your device.'],
];

export default function Home() {
  return (
    <Layout description="Open-source IPTV player for Android and Android TV. M3U and Xtream, EPG, catch-up, downloads and Picture-in-Picture.">
      <main className={styles.main}>
        <section className={styles.hero}>
          <div className={styles.heroArt}><Glyph name="tv" /></div>
          <h1 className={styles.title}>OpenTV</h1>
          <p className={styles.tagline}>
            IPTV player for Android, Android TV and your own server.
            <br />
            M3U and Xtream, EPG, catch-up, downloads and Picture-in-Picture.
            No ads, no tracking, fully open source.
          </p>
          <div className={styles.actions}>
            <Link className={styles.btnPrimary} href={releaseApk}>Download latest release</Link>
            <Link className={styles.btnTonal} href={devApk}>Dev channel</Link>
            <Link className={styles.btnText} to="/guide/installation">Read the guide</Link>
          </div>
        </section>

        <section className={styles.notice}>
          <strong>OpenTV is a player, not a provider.</strong> It does not provide any channels,
          streams or subscriptions, and none are included. You bring your own M3U playlist or
          Xtream login from a provider you already use.
        </section>

        <section className={styles.grid}>
          {FEATURES.map(([icon, title, text]) => (
            <div key={title} className={styles.card}>
              <Glyph name={icon} />
              <h3>{title}</h3>
              <p>{text}</p>
            </div>
          ))}
        </section>

        <section className={styles.channels}>
          <h2>Two download channels</h2>
          <p className={styles.channelsIntro}>
            OpenTV is distributed as an APK you install yourself. It is not on the Play Store.
            Both builds are signed with the same key, so you can switch between them and update
            in place without losing playlists or settings.
          </p>
          <div className={styles.channelCards}>
            <div className={styles.card}>
              <h3>Latest release</h3>
              <p>The most recent tagged, stable version. Use this for everyday viewing.</p>
              <Link className={styles.btnPrimary} href={releaseApk}>Download APK</Link>
            </div>
            <div className={styles.card}>
              <h3>Dev channel</h3>
              <p>Rebuilt from the latest commit on main, so it has the newest changes first.</p>
              <Link className={styles.btnTonal} href={devApk}>Download dev APK</Link>
            </div>
          </div>
          <p className={styles.channelsFoot}>
            <Link to="/guide/installation">Get started in a minute</Link> or{' '}
            <Link to="/download">compare the channels</Link>. Prefer a browser?{' '}
            <Link to="/guide/webclient">Self-host the web client</Link>.
          </p>
        </section>
      </main>
    </Layout>
  );
}
