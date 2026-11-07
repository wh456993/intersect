#!/usr/bin/env node
/**
 * intersect 启动图标生成器。
 *
 * 图形:四角罗盘星(每一角剖成明暗两面,各自带渐变) + 两层各断两处的轨道环 + 两颗卫星点。
 * 完全用 Node 内置模块完成扫描线光栅化与 PNG 编码,无第三方依赖。
 *
 * 用法:
 *   node tools/render_launcher_icons.js            只生成资源
 *   node tools/render_launcher_icons.js --preview p.png   另外输出 432px 预览图
 *
 * 产物:
 *   app/src/main/res/drawable-nodpi/ui_launcher_artwork.png   自适应图标前景(透明底)
 *   app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png      旧版方形图标
 *   app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png 旧版圆形图标
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const RES = path.resolve(__dirname, '..', 'app/src/main/res');
const VIEW = 108;      // 视口边长(dp)
const SIDE = 864;      // 主画布像素边长(= VIEW * 8,超采样后再降采样)
const K = SIDE / VIEW; // 视口单位 -> 画布像素

// ---------------- 调色板 ----------------
const PAPER = [255, 255, 255];
const RING_OUTER = [0x77, 0x93, 0xA6];
const RING_INNER = [0x8B, 0xA3, 0xB2];
const SATELLITE = [0x82, 0x9C, 0xB0];
const FACE_TIP_LIGHT = [255, 255, 255];
const FACE_BASE_LIGHT = [0xC2, 0xD1, 0xDC];
const FACE_TIP_DARK = [0x4E, 0x64, 0x76];
const FACE_BASE_DARK = [0x94, 0xA8, 0xB7];

// ---------------- 画布 ----------------
const buf = new Float64Array(SIDE * SIDE * 4); // 预乘直存,alpha 用 0..1

function reset(color, alpha) {
  for (let i = 0; i < SIDE * SIDE; i++) {
    buf[i * 4] = color ? color[0] : 0;
    buf[i * 4 + 1] = color ? color[1] : 0;
    buf[i * 4 + 2] = color ? color[2] : 0;
    buf[i * 4 + 3] = alpha;
  }
}

function put(x, y, c, cover) {
  if (cover <= 0 || x < 0 || y < 0 || x >= SIDE || y >= SIDE) return;
  const a = cover > 1 ? 1 : cover;
  const i = (y * SIDE + x) * 4;
  const dstA = buf[i + 3];
  const outA = a + dstA * (1 - a);
  if (outA <= 0) return;
  buf[i] = (c[0] * a + buf[i] * dstA * (1 - a)) / outA;
  buf[i + 1] = (c[1] * a + buf[i + 1] * dstA * (1 - a)) / outA;
  buf[i + 2] = (c[2] * a + buf[i + 2] * dstA * (1 - a)) / outA;
  buf[i + 3] = outA;
}

// ---------------- 图元 ----------------
// 三角面渐变填充:颜色沿 from->to 的投影在 tip 与 base 两色之间插值
function facet(tri, tipColor, baseColor, from, to) {
  const p = tri.map(([x, y]) => [x * K, y * K]);
  const fx = from[0] * K, fy = from[1] * K;
  const tx = to[0] * K, ty = to[1] * K;
  const dx = tx - fx, dy = ty - fy;
  const len2 = dx * dx + dy * dy || 1;
  let lo = Infinity, hi = -Infinity;
  for (const [, y] of p) { if (y < lo) lo = y; if (y > hi) hi = y; }
  const c = [0, 0, 0];
  for (let y = Math.max(0, Math.floor(lo)); y <= Math.min(SIDE - 1, Math.ceil(hi)); y++) {
    const yc = y + 0.5;
    const xs = [];
    for (let i = 0; i < 3; i++) {
      const [x1, y1] = p[i], [x2, y2] = p[(i + 1) % 3];
      if ((y1 <= yc && y2 > yc) || (y2 <= yc && y1 > yc)) xs.push(x1 + ((yc - y1) / (y2 - y1)) * (x2 - x1));
    }
    if (xs.length < 2) continue;
    xs.sort((a, b) => a - b);
    const xa = Math.max(0, Math.round(xs[0])), xb = Math.min(SIDE - 1, Math.round(xs[xs.length - 1]) - 1);
    for (let x = xa; x <= xb; x++) {
      const t = Math.max(0, Math.min(1, (((x + 0.5 - fx) * dx + (yc - fy) * dy) / len2)));
      c[0] = tipColor[0] + (baseColor[0] - tipColor[0]) * t;
      c[1] = tipColor[1] + (baseColor[1] - tipColor[1]) * t;
      c[2] = tipColor[2] + (baseColor[2] - tipColor[2]) * t;
      put(x, y, c, 1);
    }
  }
}

function dot(cx, cy, r, color) {
  const X = cx * K, Y = cy * K, R = r * K;
  for (let y = Math.floor(Y - R - 1); y <= Math.ceil(Y + R + 1); y++)
    for (let x = Math.floor(X - R - 1); x <= Math.ceil(X + R + 1); x++)
      put(x, y, color, R - Math.hypot(x + 0.5 - X, y + 0.5 - Y) + 0.5);
}

// 圆弧:角度按数学习惯(0=正右,逆时针为正),逐段铺圆形笔头
function ring(cx, cy, r, from, to, width, color) {
  const R = r * K, hw = (width * K) / 2;
  const steps = Math.max(96, Math.round(Math.abs(to - from) * R / 90));
  let prev = null;
  for (let s = 0; s <= steps; s++) {
    const deg = from + ((to - from) * s) / steps;
    const t = (deg * Math.PI) / 180;
    const cur = [cx * K + R * Math.cos(t), cy * K - R * Math.sin(t)];
    if (prev) {
      const n = Math.max(1, Math.ceil(Math.hypot(cur[0] - prev[0], cur[1] - prev[1])));
      for (let i = 0; i <= n; i++) {
        const x = prev[0] + ((cur[0] - prev[0]) * i) / n;
        const y = prev[1] + ((cur[1] - prev[1]) * i) / n;
        const reach = Math.ceil(hw);
        for (let oy = -reach; oy <= reach; oy++)
          for (let ox = -reach; ox <= reach; ox++)
            put(Math.round(x + ox), Math.round(y + oy), color, hw - Math.hypot(ox, oy) + 0.5);
      }
    }
    prev = cur;
  }
}

function discMask(radius, color) {
  const C = SIDE / 2, R = radius * K;
  for (let y = 0; y < SIDE; y++)
    for (let x = 0; x < SIDE; x++)
      put(x, y, color, R - Math.hypot(x + 0.5 - C, y + 0.5 - C) + 0.5);
}

// ---------------- 图形 ----------------
const C = [54, 54];
const N = [54, 22];
const E = [86, 54];
const S = [54, 86];
const W = [22, 54];
const NE = [60.8, 47.2];
const NW = [47.2, 47.2];
const SW = [47.2, 60.8];
const SE = [60.8, 60.8];

function drawLogo() {
  // 轨道环:外层两段(顶底留口),内层两段(断口错开)
  ring(54, 54, 23, 104, 256, 1.0, RING_OUTER);
  ring(54, 54, 23, 284, 436, 1.0, RING_OUTER);
  ring(54, 54, 17.4, 64, 216, 0.95, RING_INNER);
  ring(54, 54, 17.4, 244, 396, 0.95, RING_INNER);

  // 卫星点:一颗在外环缺口外侧,一颗在内环缺口外侧
  dot(76.6, 38.8, 1.9, SATELLITE);
  dot(31.4, 69.2, 1.6, SATELLITE);

  // 十字星:四角各剖成两面,亮面朝外侧(左/上),暗面顺时针一侧
  facet([C, NW, N], FACE_TIP_LIGHT, FACE_BASE_LIGHT, N, C);
  facet([C, N, NE], FACE_TIP_DARK, FACE_BASE_DARK, N, C);
  facet([C, NE, E], FACE_TIP_LIGHT, FACE_BASE_LIGHT, E, C);
  facet([C, E, SE], FACE_TIP_DARK, FACE_BASE_DARK, E, C);
  facet([C, SE, S], FACE_TIP_LIGHT, FACE_BASE_LIGHT, S, C);
  facet([C, S, SW], FACE_TIP_DARK, FACE_BASE_DARK, S, C);
  facet([C, SW, W], FACE_TIP_LIGHT, FACE_BASE_LIGHT, W, C);
  facet([C, W, NW], FACE_TIP_DARK, FACE_BASE_DARK, W, C);
}

// ---------------- 降采样 + PNG ----------------
function flatten(size) {
  const out = new Uint8Array(size * size * 4);
  const k = SIDE / size;
  for (let ty = 0; ty < size; ty++) {
    for (let tx = 0; tx < size; tx++) {
      let r = 0, g = 0, b = 0, a = 0, wsum = 0;
      const y0 = ty * k, y1 = y0 + k, x0 = tx * k, x1 = x0 + k;
      for (let sy = Math.floor(y0); sy < Math.ceil(y1); sy++) {
        const wy = Math.min(y1, sy + 1) - Math.max(y0, sy);
        if (wy <= 0) continue;
        for (let sx = Math.floor(x0); sx < Math.ceil(x1); sx++) {
          const wx = Math.min(x1, sx + 1) - Math.max(x0, sx);
          if (wx <= 0) continue;
          const w = wx * wy, i = (sy * SIDE + sx) * 4;
          const al = buf[i + 3];
          r += buf[i] * al * w; g += buf[i + 1] * al * w; b += buf[i + 2] * al * w;
          a += al * w; wsum += w;
        }
      }
      const o = (ty * size + tx) * 4;
      const al = a / wsum;
      if (al > 0) {
        out[o] = Math.round(Math.max(0, Math.min(255, r / a)));
        out[o + 1] = Math.round(Math.max(0, Math.min(255, g / a)));
        out[o + 2] = Math.round(Math.max(0, Math.min(255, b / a)));
      }
      out[o + 3] = Math.round(al * 255);
    }
  }
  return out;
}

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(bytes) {
  let c = -1;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 255] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function encodePng(size, rgba) {
  const stride = size * 4;
  const raw = Buffer.alloc((stride + 1) * size);
  for (let y = 0; y < size; y++) {
    const base = y * (stride + 1);
    raw[base] = 0; // None 滤波:图标含渐变,Sub 滤波反而变大
    Buffer.from(rgba.buffer, y * stride, stride).copy(raw, base + 1);
  }
  const chunk = (type, body) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(body.length, 0);
    const ty = Buffer.from(type, 'ascii');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([ty, body])), 0);
    return Buffer.concat([len, ty, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;  // 位深
  ihdr[9] = 6;  // RGBA
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------- 主流程 ----------------
const densityDirs = [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]];
const previewFlag = process.argv.indexOf('--preview');
const previewTarget = previewFlag > -1 ? process.argv[previewFlag + 1] : null;

function emit(label, file, size, background, roundMask) {
  reset(background, background ? 1 : 0);
  if (roundMask) discMask(54, PAPER);
  drawLogo();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const png = encodePng(size, flatten(size));
  fs.writeFileSync(file, png);
  console.log(`${label.padEnd(40)} ${size}x${size}  ${png.length} 字节`);
}

// 自适应图标前景:透明底、图形居中在安全区内
emit('drawable-nodpi/ui_launcher_artwork.png',
  path.join(RES, 'drawable-nodpi/ui_launcher_artwork.png'), 432, null, false);

// 旧版图标(API 21-25):方形白底 / 圆形白底
for (const [density, size] of densityDirs) {
  emit(`mipmap-${density}/ic_launcher.png`,
    path.join(RES, `mipmap-${density}/ic_launcher.png`), size, PAPER, false);
  emit(`mipmap-${density}/ic_launcher_round.png`,
    path.join(RES, `mipmap-${density}/ic_launcher_round.png`), size, null, true);
}

if (previewTarget) {
  reset(PAPER, 1);
  drawLogo();
  fs.writeFileSync(previewTarget, encodePng(432, flatten(432)));
  console.log(`预览图 -> ${previewTarget}`);
}
