#!/usr/bin/env node
/**
 * 界面用图标生成器:把 `SPECS` 里定义的几何写成 res/drawable 下的矢量 XML。
 *
 * 每个图标都在 24x24 的视口里用直线/圆/弧现算出来,路径数据由脚本拼接,
 * 不引用任何外部图形资源。改图标只改这里的坐标,重新跑一次即可:
 *
 *   node tools/make_ui_icons.js
 */
'use strict';
const fs = require('fs');
const path = require('path');

const OUT = path.resolve(__dirname, '..', 'app/src/main/res/drawable');

const fmt = (n) => {
  const v = Math.round(n * 100) / 100;
  return Number.isInteger(v) ? String(v) : String(v);
};

/** 圆:两个半圆的弧拼成闭合圆,可用于描边或填充。 */
function circle(cx, cy, r) {
  return `M${fmt(cx - r)},${fmt(cy)}a${fmt(r)},${fmt(r)} 0 1,0 ${fmt(r * 2)},0a${fmt(r)},${fmt(r)} 0 1,0 ${fmt(-r * 2)},0Z`;
}

/** 正多边形顶点串,给需要棱角的图形用。 */
function points(pts) {
  return pts.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${fmt(x)},${fmt(y)}`).join('') + 'Z';
}

/** 圆弧:角度按屏幕坐标给(0°=右,顺时针递增),always 顺时针画。 */
function arc(cx, cy, r, fromDeg, toDeg) {
  const at = (deg) => {
    const a = (deg * Math.PI) / 180;
    return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  };
  const [sx, sy] = at(fromDeg);
  const [ex, ey] = at(toDeg);
  // 顺时针从 fromDeg 扫到 toDeg,跨过 360° 时也要算对弧度
  const sweep = (((toDeg - fromDeg) % 360) + 360) % 360;
  const large = sweep > 180 ? 1 : 0;
  return `M${fmt(sx)},${fmt(sy)}A${fmt(r)},${fmt(r)} 0 ${large},1 ${fmt(ex)},${fmt(ey)}`;
}

/**
 * 罗盘星:4 个尖角 + 4 个对角内凹点,每个臂拆成浅/深两个三角面。
 * 返回两组同色子路径(浅面 / 深面),拼出旋转的金属块面感。
 */
function compassStar(cx, cy, rx, ry, rin) {
  const d = rin * Math.SQRT1_2;
  const p = [
    [cx + rx, cy], [cx + d, cy - d], [cx, cy - ry], [cx - d, cy - d],
    [cx - rx, cy], [cx - d, cy + d], [cx, cy + ry], [cx + d, cy + d],
  ];
  const facet = (i) => {
    const a = p[i % 8], b = p[(i + 1) % 8];
    return `M${fmt(cx)},${fmt(cy)}L${fmt(a[0])},${fmt(a[1])}L${fmt(b[0])},${fmt(b[1])}Z`;
  };
  return {
    light: [1, 3, 5, 7].map(facet).join(''),
    dark: [0, 2, 4, 6].map(facet).join(''),
  };
}

/** 径向排布的一组短线(太阳光芒、刻度等)。 */
function rays(cx, cy, r1, r2, count, offsetDeg) {
  return Array.from({ length: count }, (_, i) => {
    const a = ((offsetDeg + (360 / count) * i) * Math.PI) / 180;
    const sx = cx + r1 * Math.cos(a), sy = cy - r1 * Math.sin(a);
    const ex = cx + r2 * Math.cos(a), ey = cy - r2 * Math.sin(a);
    return `M${fmt(sx)},${fmt(sy)}L${fmt(ex)},${fmt(ey)}`;
  }).join('');
}

// ---- 复用图形 ----
const BUBBLE = [
  [5.4, 3.8], [18.6, 3.8],
  // 右侧与底部圆角,左下留出尾巴
].join('');

const head = (cx, cy, r) => circle(cx, cy, r);

/** 肩膀:半圆开弧,从左上到右上。 */
const shoulders = (cx, cy, w, depth) =>
  `M${fmt(cx - w)},${fmt(cy + depth)}C${fmt(cx - w)},${fmt(cy - depth * 0.35)} ` +
  `${fmt(cx - w * 0.45)},${fmt(cy - depth * 0.85)} ${fmt(cx)},${fmt(cy - depth * 0.85)}` +
  `C${fmt(cx + w * 0.45)},${fmt(cy - depth * 0.85)} ${fmt(cx + w)},${fmt(cy - depth * 0.35)} ${fmt(cx + w)},${fmt(cy + depth)}`;

/** 对话气泡轮廓:圆角矩形 + 左下角折出的尾巴。 */
const bubblePath =
  'M5.6,3.8H18.4A2.8,2.8 0 0 1 21.2,6.6V14.6A2.8,2.8 0 0 1 18.4,17.4H11.2L6.2,21V17.4H5.6A2.8,2.8 0 0 1 2.8,14.6V6.6A2.8,2.8 0 0 1 5.6,3.8Z';

// ---- 图标定义 ----
/** 内置助手 intersect 的默认头像:罗盘星,在 108 视口里画才有足够细节。 */
const COMPASS = compassStar(54, 54, 41, 41, 10);
/** 两个轨道点坐在外圈上,落在圆环的缺口里。 */
const orbitDot = (deg) =>
  circle(54 + 36.5 * Math.cos((deg * Math.PI) / 180), 54 + 36.5 * Math.sin((deg * Math.PI) / 180), 3);

// 每项:文件名 -> 若干图层。layers 里 s=描边 / f=填充。
const SPECS = {
  'ui_plus_frame': [
    { s: circle(12, 12, 9.2), w: 1.9 },
    { s: 'M12,7.4V16.6M7.4,12H16.6', w: 1.9, cap: 'round' },
  ],
  'ui_chevron_right': [
    { s: 'M9.4,4.6L16.8,12L9.4,19.4', w: 2.2, cap: 'round', join: 'round' },
  ],
  'ui_chevron_left': [
    { s: 'M14.6,4.6L7.2,12L14.6,19.4', w: 2.2, cap: 'round', join: 'round' },
  ],
  'ui_tab_chat_on': [{ f: bubblePath }],
  'ui_tab_chat_off': [{ s: bubblePath, w: 1.8, join: 'round' }],
  'ui_tab_people_on': [
    { f: head(9.2, 8.4, 3.5) },
    { f: `M2.6,20C2.6,15.9 5.5,13.4 9.2,13.4C12.9,13.4 15.8,15.9 15.8,20Z` },
    { f: head(17.4, 9.4, 2.7) },
    { f: 'M16.2,13.6C19.4,13.8 21.4,16 21.4,19.4V20H17.2C17.2,17.3 16.8,15.2 16.2,13.6Z' },
  ],
  'ui_tab_people_off': [
    { s: head(9.2, 8.4, 3.3), w: 1.8 },
    { s: 'M3.2,20C3.2,16.1 5.9,13.8 9.2,13.8C12.5,13.8 15.2,16.1 15.2,20', w: 1.8, cap: 'round' },
    { s: head(17.6, 9.6, 2.5), w: 1.8 },
    { s: 'M16.4,14C19.4,14.3 21,16.4 21,19.4', w: 1.8, cap: 'round' },
  ],
  'ui_tab_me_on': [
    { f: head(12, 7.4, 3.9) },
    { f: 'M4,20.4C4,15.7 7.6,13 12,13C16.4,13 20,15.7 20,20.4Z' },
  ],
  'ui_tab_me_off': [
    { s: head(12, 7.4, 3.7), w: 1.8 },
    { s: 'M4.8,20.4C4.8,16 8.1,13.6 12,13.6C15.9,13.6 19.2,16 19.2,20.4', w: 1.8, cap: 'round' },
  ],
  'ui_menu_bars': [
    { s: 'M4.2,7.4H19.8M4.2,12H19.8M4.2,16.6H13.4', w: 1.9, cap: 'round' },
  ],
  'ui_qr_frame': [
    { s: 'M3.2,3.2h5.6v5.6h-5.6z', w: 1.7, join: 'round' },
    { s: 'M15.2,3.2h5.6v5.6h-5.6z', w: 1.7, join: 'round' },
    { s: 'M3.2,15.2h5.6v5.6h-5.6z', w: 1.7, join: 'round' },
    { f: points([[16, 16], [20.4, 16], [20.4, 20.4], [16, 20.4]]) },
    { f: points([[11.6, 3.6], [13.6, 3.6], [13.6, 5.6], [11.6, 5.6]]) },
    { f: points([[11.6, 11.6], [13.6, 11.6], [13.6, 13.6], [11.6, 13.6]]) },
    { f: points([[3.6, 11.6], [5.6, 11.6], [5.6, 13.6], [3.6, 13.6]]) },
  ],
  'ui_download_tray': [
    { s: 'M12,3.4V14.6M7.2,10L12,14.8L16.8,10', w: 1.9, cap: 'round', join: 'round' },
    { s: 'M4.2,16.4V19.2A1.6,1.6 0 0 0 5.8,20.8H18.2A1.6,1.6 0 0 0 19.8,19.2V16.4', w: 1.9, cap: 'round' },
  ],
  'ui_send_arrow': [
    { f: 'M21,3.2L3.2,10.6L10.4,13.4Z' },
    { f: 'M21,3.2L13.4,20.8L10.4,13.4Z' },
  ],
  // 开发者控制台附件菜单用的三个入口图标(请求体 / 角色 / 模式)
  'ui_dev_request': [
    { s: 'M9.2,5.6C7.6,5.6 6.8,6.8 6.8,8.2V8.8C6.8,10.4 5.4,11.4 5.2,12C5.4,12.6 6.8,13.6 6.8,15.2V15.8C6.8,17.2 7.6,18.4 9.2,18.4', w: 1.8, cap: 'round', join: 'round' },
    { s: 'M14.8,5.6C16.4,5.6 17.2,6.8 17.2,8.2V8.8C17.2,10.4 18.6,11.4 18.8,12C18.6,12.6 17.2,13.6 17.2,15.2V15.8C17.2,17.2 16.4,18.4 14.8,18.4', w: 1.8, cap: 'round', join: 'round' },
  ],
  'ui_dev_character': [
    { s: head(12, 8.2, 3.2), w: 1.8 },
    { s: 'M4.6,19.8C4.6,16.2 7.9,13.8 12,13.8C16.1,13.8 19.4,16.2 19.4,19.8', w: 1.8, cap: 'round' },
  ],
  'ui_dev_mode': [
    { s: 'M4.4,12H19.6', w: 1.8, cap: 'round' },
    { f: circle(8.2, 12, 3) },
  ],
  'ui_mic': [
    { s: 'M12,3.4A3.2,3.2 0 0 1 15.2,6.6V11.6A3.2,3.2 0 0 1 8.8,11.6V6.6A3.2,3.2 0 0 1 12,3.4Z', w: 1.8 },
    { s: 'M5.8,11.2V12A6.2,6.2 0 0 0 18.2,12V11.2', w: 1.8, cap: 'round' },
    { s: 'M12,18.2V20.8M8.8,20.8H15.2', w: 1.8, cap: 'round' },
  ],
  'ui_mode_light': [
    { s: circle(12, 12, 4.2), w: 1.9 },
    { s: rays(12, 12, 6.8, 9, 8, 0), w: 1.8, cap: 'round' },
  ],
  'ui_mode_dark': [
    { f: 'M20.6,14.8A8.8,8.8 0 0 1 9.2,3.4A8.8,8.8 0 1 0 20.6,14.8Z' },
  ],
  // 语言切换用的经纬网地球:外圈 + 赤道 + 两条纬线 + 一道经线,缩到 20dp 也能认出是地球
  'ui_lang_globe': [
    { s: circle(12, 12, 8.6), w: 1.7 },
    { s: 'M3.4,12H20.6', w: 1.4, cap: 'round' },
    { s: 'M4.8,7.4H19.2M4.8,16.6H19.2', w: 1.2, cap: 'round' },
    { s: 'M12,3.4C15.4,6.2 15.4,17.8 12,20.6C8.6,17.8 8.6,6.2 12,3.4Z', w: 1.4 },
  ],
  'ui_avatar_blank': [
    { s: circle(12, 12, 10.4), w: 1.7 },
    { s: head(12, 9.2, 3), w: 1.7 },
    { s: 'M5.6,19.6C6.2,16.4 8.8,14.6 12,14.6C15.2,14.6 17.8,16.4 18.4,19.6', w: 1.7, cap: 'round' },
  ],
  // ui_avatar_me 不再是矢量图:默认用户头像是 drawable-nodpi 里的位图,由用户给的原图裁出
  'ui_avatar_helper': [
    // 白底:头像按圆角方块裁切,底色要撑满整个视口
    { f: points([[0, 0], [108, 0], [108, 108], [0, 108]]), color: '#FFFFFFFF' },
    // 内圈:两段细弧,与星臂错开
    { s: `${arc(54, 54, 21, 275, 25)}${arc(54, 54, 21, 95, 205)}`, w: 1.8, cap: 'round', color: '#FFC2CDD6' },
    // 外圈:两段弧,缺口正好落在两个轨道点处
    { s: `${arc(54, 54, 36.5, 185, 295)}${arc(54, 54, 36.5, 5, 115)}`, w: 2, cap: 'round', color: '#FF93A7B6' },
    { f: `${orbitDot(-50)}${orbitDot(130)}`, color: '#FF8297A7' },
    // 四角星:浅面在下、深面在上,块面交错形成旋转感
    { f: COMPASS.light, color: '#FFE9EFF3' },
    { f: COMPASS.dark, color: '#FF6F8598' },
  ],

  // 状态图标:都画成「细圆环 + 中间一个图形」,15dp 下也能认出来
  'ui_state_active': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M7.8,12.2L10.6,15L16.4,8.8', w: 1.8, cap: 'round', join: 'round' },
  ],
  'ui_state_away': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M12,6.8V12.4L16,14.6', w: 1.8, cap: 'round', join: 'round' },
  ],
  'ui_state_busy': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M8.6,8.6L15.4,15.4M15.4,8.6L8.6,15.4', w: 1.8, cap: 'round' },
  ],
  'ui_state_silent': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M7.8,12H16.2', w: 1.9, cap: 'round' },
  ],
  'ui_state_talk': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { f: 'M7.6,9.4h8.8a1.4,1.4 0 0 1 1.4,1.4v3.4a1.4,1.4 0 0 1 -1.4,1.4h-4.6l-2.6,2v-2h-1.6a1.4,1.4 0 0 1 -1.4,-1.4v-3.4a1.4,1.4 0 0 1 1.4,-1.4z' },
  ],
  'ui_state_commute': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M7.4,8.4h9.2v6.2h-9.2z', w: 1.6, join: 'round' },
    { s: 'M7.4,11.4h9.2M9.4,16.4V17.8M14.6,16.4V17.8', w: 1.6, cap: 'round' },
  ],
  'ui_state_music': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M14.8,7.6V14.4', w: 1.8, cap: 'round' },
    { f: circle(13.2, 14.6, 2) },
    { s: 'M14.8,7.6C16.6,8 17.4,9 17.2,10.4', w: 1.6, cap: 'round' },
  ],
  'ui_state_rest': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { f: 'M15.6,7.6A5,5 0 0 1 9.6,14.6A5.4,5.4 0 1 0 15.6,7.6Z' },
  ],
  'ui_state_sport': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { f: 'M13.4,7.2L8.8,13.2H11.8L10.6,16.8L15.2,10.8H12.2Z' },
  ],
  'ui_state_study': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M7.2,8.4h4.4a1.2,1.2 0 0 1 1.2,1.2v6.6a1.6,1.6 0 0 0 -1.6,-1.4H7.2Z', w: 1.6, join: 'round' },
    { s: 'M16.8,8.4h-4.4a1.2,1.2 0 0 0 -1.2,1.2', w: 1.6, cap: 'round' },
    { s: 'M16.8,8.4v6.4', w: 1.6, cap: 'round' },
  ],
  'ui_state_work': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M7.4,10.2h9.2a1.2,1.2 0 0 1 1.2,1.2v4a1.2,1.2 0 0 1 -1.2,1.2h-9.2a1.2,1.2 0 0 1 -1.2,-1.2v-4a1.2,1.2 0 0 1 1.2,-1.2z', w: 1.6, join: 'round' },
    { s: 'M10,10.2V8.6a1,1 0 0 1 1,-1h2a1,1 0 0 1 1,1v1.6', w: 1.6, cap: 'round' },
  ],
  'ui_state_idle': [
    { s: circle(12, 12, 8.6), w: 1.8 },
    { s: 'M7.2,13.6C8.6,11 10.2,15.4 12,13.2C13.8,11 15.2,14.4 16.8,12.4', w: 1.8, cap: 'round' },
  ],

  // 启动图标背景层:白色圆角方块,自适应图标缩放时靠它撑住底
  'ui_launcher_plate': [
    { f: points([[0, 0], [108, 0], [108, 108], [0, 108]]), color: '#FFFFFFFF' },
  ],
};

const VIEWPORT = (name) => (name === 'ui_launcher_plate' || name === 'ui_avatar_helper' ? 108 : 24);
/** 资源里声明的 dp 尺寸:头像按 48dp 出图(实际由 UI 缩放),其余与视口同尺寸。 */
const DP_SIZE = (name) => (name === 'ui_avatar_helper' ? 48 : VIEWPORT(name));

function toXml(name, layers) {
  const size = VIEWPORT(name);
  const dp = DP_SIZE(name);
  const body = layers.map((l) => {
    const color = l.color || '#FF000000';
    const attrs = [`android:pathData="${l.f || l.s}"`];
    if (l.f) {
      attrs.push(`android:fillColor="${color}"`);
      if (l.w) attrs.push(`android:strokeColor="#00000000"`, `android:strokeWidth="${fmt(l.w)}"`);
    } else {
      attrs.push('android:fillColor="#00000000"');
      attrs.push(`android:strokeColor="${color}"`);
      attrs.push(`android:strokeWidth="${fmt(l.w || 1.8)}"`);
      if (l.cap) attrs.push(`android:strokeLineCap="${l.cap}"`);
      if (l.join) attrs.push(`android:strokeLineJoin="${l.join}"`);
    }
    return `  <path\n      ${attrs.join('\n      ')} />`;
  }).join('\n');

  return `<?xml version="1.0" encoding="utf-8"?>
<!-- 由 tools/make_ui_icons.js 生成,坐标即在脚本里定义 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${dp}dp"
    android:height="${dp}dp"
    android:viewportWidth="${size}"
    android:viewportHeight="${size}">
${body}
</vector>
`;
}

fs.mkdirSync(OUT, { recursive: true });
let count = 0;
for (const [name, layers] of Object.entries(SPECS)) {
  fs.writeFileSync(path.join(OUT, `${name}.xml`), toXml(name, layers));
  count++;
}
console.log(`已生成 ${count} 个图标 -> ${path.relative(process.cwd(), OUT)}`);
