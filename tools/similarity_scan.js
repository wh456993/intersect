#!/usr/bin/env node
/**
 * 与仓库首个提交(上游 WeCompose)做「非空唯一行」重合度扫描。
 *
 * 判定方式:取当前文件里每一条非空、去掉首尾空白、长度达标且不是纯符号的行,
 * 放进集合后,统计其中有多少条能在参照文件的行集合里找到。比值高说明两边的
 * 代码字面接近。只做字面比对,不解析语法。
 *
 * 用法:
 *   node tools/similarity_scan.js           扫 app/src(默认)
 *   node tools/similarity_scan.js --all     连 build 脚本与根目录文本一起扫
 *
 * 参照版本默认是最早的提交,可用 BASE=<ref> 覆盖。
 */
'use strict';
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const BASE = process.env.BASE || (() => {
  try {
    return execSync('git rev-list --max-parents=0 HEAD', { cwd: ROOT }).toString().trim().split('\n')[0];
  } catch {
    return 'HEAD';
  }
})();

const git = (args) => execSync(`git ${args}`, { cwd: ROOT, maxBuffer: 64 * 1024 * 1024 }).toString();

// 这些行是任何工程都会写的样板(声明、导入、XML 头与固定属性),比对时剔除,
// 否则会把「两边都有 import」算成重合。
const BOILERPLATE = [
  /^package\s/,
  /^import\s/,
  /^<\?xml/,
  /^<vector\b/,
  /^<resources>/,
  /^android:(width|height|viewportWidth|viewportHeight|fillColor|strokeColor|strokeWidth|strokeLineCap|strokeLineJoin|pathData)=/,
  /^android:(label|icon|roundIcon|theme|allowBackup|supportsRtl|exported|name)="[^"]*"\/?>?$/,
  /^\}\]?>?;?$/,
  // Compose 里绕不开的签名与惯用写法,不构成字面抄袭
  /^@Composable$/,
  /^@(JvmStatic|JvmField|OptIn|Suppress)\b/,
  /^modifier: Modifier = Modifier,$/,
  /^\.fillMax(Width|Size|Height)\(\)$/,
  /^\.clickable\(onClick = \{$/,
  /^\.align\(Alignment\.\w+\)$/,
  /^override fun onCreate\(savedInstanceState: Bundle\?\) \{$/,
  /^super\.onCreate\(savedInstanceState\)$/,
  /^setContent \{$/,
  /^drawContent\(\)$/,
  /^content = content$/,
  /^val (id|name): String,$/,
];

/** 行是否值得比较:去掉空白后够长、含有标识符或汉字,且不是样板行。 */
function worthLine(raw) {
  const line = raw.trim();
  if (line.length < 10) return false;
  if (!/[A-Za-z0-9\u4e00-\u9fa5]/.test(line)) return false;
  if (BOILERPLATE.some((re) => re.test(line))) return false;
  return true;
}

const lineSet = (text) => {
  const set = new Set();
  for (const raw of text.split('\n')) if (worthLine(raw)) set.add(raw.trim());
  return set;
};

// ---- 参照侧:上游代码树 ----
const upFiles = git(`ls-tree -r ${BASE} --name-only app/src/main/java app/src/main/res`)
  .split('\n').filter(Boolean);
const upByBase = new Map();
const upAll = new Set();
for (const file of upFiles) {
  let text;
  try {
    text = git(`show ${BASE}:${file}`);
  } catch {
    continue;
  }
  const set = lineSet(text);
  for (const line of set) upAll.add(line);
  const base = path.basename(file);
  upByBase.set(base, set);
}

// ---- 当前侧 ----
const targets = [];
const walk = (dir) => {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (/\.(kt|xml|kts|pro|md)$/.test(entry.name)) targets.push(full);
  }
};
walk(path.join(ROOT, 'app/src'));
if (process.argv.includes('--all')) {
  for (const name of ['build.gradle', 'settings.gradle', 'gradle.properties', 'README.md']) {
    const full = path.join(ROOT, name);
    if (fs.existsSync(full)) targets.push(full);
  }
}

const rows = [];
for (const file of targets) {
  const text = fs.readFileSync(file, 'utf8');
  const set = lineSet(text);
  if (set.size === 0) continue;
  const own = upByBase.get(path.basename(file)) || new Set();
  let ownHit = 0;
  for (const line of set) if (own.has(line)) ownHit++;
  let anyHit = 0;
  for (const line of set) if (upAll.has(line)) anyHit++;
  rows.push({
    file: path.relative(ROOT, file),
    lines: set.size,
    sameName: own.size ? ownHit / set.size : 0,
    anyUpstream: anyHit / set.size,
    hits: anyHit,
  });
}

const detailIdx = process.argv.indexOf('--detail');
if (detailIdx > -1) {
  const target = path.resolve(ROOT, process.argv[detailIdx + 1]);
  const text = fs.readFileSync(target, 'utf8');
  console.log(`${path.relative(ROOT, target)} 与上游重合的行:\n`);
  for (const raw of text.split('\n')) {
    if (!worthLine(raw)) continue;
    const line = raw.trim();
    if (upAll.has(line)) console.log(`  ${line}`);
  }
  process.exit(0);
}

rows.sort((a, b) => b.anyUpstream - a.anyUpstream || b.hits - a.hits);
const pct = (v) => `${(v * 100).toFixed(0)}%`.padStart(4);

console.log(`参照: ${BASE.slice(0, 8)}  (上游 ${upFiles.length} 个文件, ${upAll.size} 条唯一行)\n`);
console.log('重合度  同行名  条数  文件');
for (const r of rows) {
  if (r.anyUpstream === 0 && r.sameName === 0) continue;
  console.log(`${pct(r.anyUpstream)}   ${pct(r.sameName)}  ${String(r.hits).padStart(4)}  ${r.file}`);
}

const clean = rows.filter((r) => r.anyUpstream === 0).length;
const total = rows.length;
const avg = rows.reduce((sum, r) => sum + r.anyUpstream, 0) / total;
console.log(`\n汇总: ${total} 个文件, ${clean} 个零重合, 平均重合度 ${(avg * 100).toFixed(1)}%`);

// 同一行在本仓库内被多处重复写入的情况(重复率自查)
const lineOwners = new Map();
for (const file of targets) {
  const text = fs.readFileSync(file, 'utf8');
  for (const raw of text.split('\n')) {
    if (!worthLine(raw)) continue;
    const key = raw.trim();
    if (!lineOwners.has(key)) lineOwners.set(key, new Set());
    lineOwners.get(key).add(path.relative(ROOT, file));
  }
}
const dup = [...lineOwners.entries()]
  .filter(([, files]) => files.size > 1)
  .sort((a, b) => b[1].size - a[1].size);
console.log(`\n内部重复: ${dup.length} 条非空行出现在多个文件里(前 15 条)`);
for (const [line, files] of dup.slice(0, 15)) {
  console.log(`  ${files.size}份  ${line.length > 70 ? line.slice(0, 70) + '...' : line}  [${[...files].map((f) => path.basename(f)).join(', ')}]`);
}
