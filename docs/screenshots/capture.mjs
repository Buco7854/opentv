// Drive the web client (dev server on :5173, proxying /api to the mock on :8080)
// and save a raw screenshot of each screen, dark and light, into ./raw.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const RAW = path.join(DIR, 'raw');
fs.mkdirSync(RAW, { recursive: true });

const BASE = 'http://127.0.0.1:5173';
const DESKTOP = { width: 1240, height: 800 };
const PHONE = { width: 402, height: 860 };
const G = encodeURIComponent('United Kingdom');

const openDetails = async (p) => {
  await p.locator('.session-card .actions button[aria-label*="detail" i]').first().click();
  await p.waitForSelector('.dialog.stream-dialog', { timeout: 5000 });
  await p.waitForTimeout(400);
};

// name, url, viewport, optional action(page)
const shots = [
  ['browse-live', `/browse/1?t=0&g=${G}`, DESKTOP],
  ['browse-movies', '/browse/1?t=1&g=Action', DESKTOP],
  ['browse-categories', '/browse/1?t=1', DESKTOP],
  ['series', '/browse/1?t=2&g=Netflix', DESKTOP],
  ['movie-detail', '/movie/1001', DESKTOP],
  ['series-detail', '/xseries/1/5001', DESKTOP],
  ['search', '/search/1', DESKTOP, async (p) => { await p.fill('.search-wrap input', 'inter'); await p.waitForTimeout(700); }],
  ['favorites', '/favorites/1', DESKTOP],
  ['downloads', '/downloads', DESKTOP],
  ['now-watching', '/sessions', DESKTOP],
  ['now-watching-details', '/sessions', DESKTOP, openDetails],
  ['settings', '/settings', DESKTOP],
  ['account', '/account/1', DESKTOP, async (p) => { await p.waitForTimeout(800); }],
  ['guide-sheet', `/browse/1?t=0&g=${G}`, DESKTOP, async (p) => {
    await p.locator('button[aria-label*="Guide" i], button[aria-label*="catch" i]').first().click();
    await p.waitForSelector('.sheet .guide-row', { timeout: 5000 }).catch(() => {});
    await p.waitForTimeout(500);
  }],
  ['playlists-panel', `/browse/1?t=0&g=${G}`, DESKTOP, async (p) => {
    await p.locator('button[aria-label="Playlists"]').first().click();
    await p.waitForSelector('.dock-panel', { timeout: 5000 }).catch(() => {});
    await p.waitForTimeout(400);
  }],
  ['phone-live', `/browse/1?t=0&g=${G}`, PHONE],
  ['phone-now-watching', '/sessions', PHONE],
];

const browser = await chromium.launch({ executablePath: process.env.PW_EXECUTABLE || undefined });
for (const theme of ['dark', 'light']) {
  for (const [name, url, viewport, action] of shots) {
    const ctx = await browser.newContext({ viewport, deviceScaleFactor: 2, baseURL: BASE });
    await ctx.addInitScript((t) => {
      localStorage.setItem('theme', t);
      localStorage.setItem('activePlaylist', '1');
      localStorage.setItem('gridBrowse', '1');
      localStorage.setItem('language', 'en');
    }, theme);
    const page = await ctx.newPage();
    try {
      await page.goto(url, { waitUntil: 'networkidle', timeout: 20000 });
      await page.waitForTimeout(700);
      if (action) await action(page);
      await page.screenshot({ path: path.join(RAW, `${name}-${theme}.png`) });
      console.log(`ok ${name}-${theme}`);
    } catch (e) {
      console.log(`FAIL ${name}-${theme}: ${e.message.split('\n')[0]}`);
    }
    await ctx.close();
  }
}
await browser.close();
