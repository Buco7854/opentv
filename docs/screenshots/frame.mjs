// Frame each raw screenshot in a window (dot buttons, rounded corners, shadow)
// on a soft wallpaper backdrop. Dark and light variants.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const RAW = path.join(DIR, 'raw');
const OUT = path.join(DIR, '..', 'static', 'img', 'web');

const THEME = {
  dark: {
    bg: [
      'radial-gradient(50% 45% at 18% 22%, rgba(120,110,255,0.34), transparent 62%)',
      'radial-gradient(48% 42% at 84% 16%, rgba(226,80,220,0.22), transparent 62%)',
      'radial-gradient(55% 55% at 82% 88%, rgba(40,190,190,0.24), transparent 62%)',
      'radial-gradient(50% 50% at 22% 92%, rgba(70,150,250,0.22), transparent 62%)',
      'linear-gradient(135deg,#15171f,#0d0f15)',
    ].join(','),
    ring: 'rgba(255,255,255,0.12)',
    shadow: '0 22px 55px rgba(0,0,0,0.5), 0 6px 16px rgba(0,0,0,0.4)',
    bar: '#2a2b30', barLine: 'rgba(255,255,255,0.07)',
  },
  light: {
    bg: [
      'radial-gradient(50% 45% at 18% 22%, rgba(120,110,255,0.22), transparent 62%)',
      'radial-gradient(48% 42% at 84% 16%, rgba(236,90,180,0.16), transparent 62%)',
      'radial-gradient(55% 55% at 82% 88%, rgba(30,180,180,0.18), transparent 62%)',
      'radial-gradient(50% 50% at 22% 92%, rgba(70,140,240,0.16), transparent 62%)',
      'linear-gradient(135deg,#eef1f8,#e6ebf4)',
    ].join(','),
    ring: 'rgba(0,0,0,0.10)',
    shadow: '0 22px 50px rgba(20,30,60,0.26), 0 6px 16px rgba(20,30,60,0.14)',
    bar: '#edeef1', barLine: 'rgba(0,0,0,0.07)',
  },
};

const html = (dataUri, w, pad, th) => `<!doctype html><html><head><meta charset="utf-8"><style>
  *{margin:0;box-sizing:border-box}
  .bg{display:inline-block;padding:${pad}px;background:${th.bg}}
  .win{width:${w}px;border-radius:12px;overflow:hidden;background:${th.bar};
       border:1px solid ${th.ring};box-shadow:${th.shadow}}
  .bar{height:34px;display:flex;align-items:center;gap:9px;padding:0 15px;
       background:${th.bar};border-bottom:1px solid ${th.barLine}}
  .dot{width:12px;height:12px;border-radius:50%}
  img{display:block;width:100%}
</style></head><body><div class="bg"><div class="win"><div class="bar">
  <span class="dot" style="background:#ff5f57"></span>
  <span class="dot" style="background:#febc2e"></span>
  <span class="dot" style="background:#28c840"></span>
</div><img src="${dataUri}"></div></div></body></html>`;

const files = fs.readdirSync(RAW).filter((f) => f.endsWith('.png'));
const browser = await chromium.launch({ executablePath: process.env.PW_EXECUTABLE || undefined });
const page = await browser.newPage({ viewport: { width: 1600, height: 1500 }, deviceScaleFactor: 2 });

for (const file of files) {
  const base = file.replace(/\.png$/, '');
  const theme = base.endsWith('-dark') ? 'dark' : 'light';
  const isPhone = base.startsWith('phone-');
  const dataUri = `data:image/png;base64,${fs.readFileSync(path.join(RAW, file)).toString('base64')}`;
  await page.setContent(html(dataUri, isPhone ? 402 : 1240, isPhone ? 40 : 60, THEME[theme]), { waitUntil: 'load' });
  await page.waitForTimeout(120);
  await (await page.$('.bg')).screenshot({ path: path.join(OUT, file) });
  console.log(`framed ${file}`);
}
await browser.close();
