// Shrink the framed PNGs with palette quantization.
import sharp from 'sharp';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'static', 'img', 'web');
let before = 0, after = 0;
for (const f of fs.readdirSync(DIR).filter((x) => x.endsWith('.png'))) {
  const p = path.join(DIR, f);
  before += fs.statSync(p).size;
  const buf = await sharp(p).png({ palette: true, quality: 88, effort: 9 }).toBuffer();
  fs.writeFileSync(p, buf);
  after += buf.length;
}
console.log(`before ${(before / 1048576).toFixed(1)}MB -> after ${(after / 1048576).toFixed(1)}MB`);
