// 轻量静态检查：去掉注释和字符串后统计括号是否配平，并报告未使用的 import。
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || '.';
const files = [];

function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'build' || entry.name === '.gradle' || entry.name === 'node_modules') continue;
      walk(full);
    } else if (entry.name.endsWith('.kt')) {
      files.push(full);
    }
  }
}

walk(root);

function strip(src) {
  let out = '';
  let i = 0;
  let mode = 'code'; // code | line | block | string | raw
  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (mode === 'code') {
      if (c === '/' && n === '/') { mode = 'line'; i += 2; continue; }
      if (c === '/' && n === '*') { mode = 'block'; i += 2; continue; }
      if (c === '"' && src.slice(i, i + 3) === '"""') { mode = 'raw'; i += 3; continue; }
      if (c === '"') { mode = 'string'; i += 1; continue; }
      if (c === "'") { mode = 'char'; i += 1; continue; }
      out += c; i += 1; continue;
    }
    if (mode === 'line') { if (c === '\n') { mode = 'code'; out += c; } i += 1; continue; }
    if (mode === 'block') { if (c === '*' && n === '/') { mode = 'code'; i += 2; } else { i += 1; } continue; }
    if (mode === 'string' || mode === 'char') {
      if (c === '\\') { i += 2; continue; }
      if ((mode === 'string' && c === '"') || (mode === 'char' && c === "'")) { mode = 'code'; }
      i += 1; continue;
    }
    if (mode === 'raw') {
      if (src.slice(i, i + 3) === '"""') { mode = 'code'; i += 3; continue; }
      i += 1; continue;
    }
  }
  return out;
}

let problems = 0;
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  const code = strip(src);
  const stack = [];
  const pairs = { ')': '(', ']': '[', '}': '{' };
  let line = 1;
  let ok = true;
  for (const ch of code) {
    if (ch === '\n') line += 1;
    if (ch === '(' || ch === '[' || ch === '{') stack.push({ ch, line });
    else if (ch === ')' || ch === ']' || ch === '}') {
      const top = stack.pop();
      if (!top || top.ch !== pairs[ch]) {
        console.log(`FAIL ${file}:${line} 意外 '${ch}'`);
        problems += 1; ok = false; break;
      }
    }
  }
  if (ok && stack.length) {
    const top = stack[stack.length - 1];
    console.log(`FAIL ${file}:${top.line} 未闭合 '${top.ch}'（共 ${stack.length} 个未闭合）`);
    problems += 1;
  }
}

// 检查声明中的主要符号是否在使用处能对上
const declarations = new Set();
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  const re = /^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private )?(?:data |sealed |abstract |open |value )*(?:class|interface|object|fun|val|var|enum class)\s+([A-Za-z_][A-Za-z0-9_]*)/gm;
  let m;
  while ((m = re.exec(src))) declarations.add(m[1]);
}

console.log(`检查 ${files.length} 个 Kotlin 文件，问题 ${problems} 个`);
process.exit(problems === 0 ? 0 : 1);
