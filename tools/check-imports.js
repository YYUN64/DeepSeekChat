// 检查两件最容易写错的事：
// 1) 用了 Icons.Xxx.Yyy 却没 import 对应图标；
// 2) 用了 Modifier.某扩展函数却没 import 对应包。
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || '.';
const files = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.name.endsWith('.kt')) files.push(full);
  }
})(root);

const modifierImports = {
  padding: ['androidx.compose.foundation.layout'],
  size: ['androidx.compose.foundation.layout'],
  width: ['androidx.compose.foundation.layout'],
  height: ['androidx.compose.foundation.layout'],
  fillMaxWidth: ['androidx.compose.foundation.layout'],
  fillMaxSize: ['androidx.compose.foundation.layout'],
  fillMaxHeight: ['androidx.compose.foundation.layout'],
  widthIn: ['androidx.compose.foundation.layout'],
  heightIn: ['androidx.compose.foundation.layout'],
  background: ['androidx.compose.foundation'],
  clickable: ['androidx.compose.foundation'],
  horizontalScroll: ['androidx.compose.foundation'],
  verticalScroll: ['androidx.compose.foundation'],
  clip: ['androidx.compose.ui.draw'],
  alpha: ['androidx.compose.ui.draw'],
  border: ['androidx.compose.foundation']
};

let problems = 0;
for (const file of files) {
  const src = fs.readFileSync(file, 'utf8');
  const imports = new Set();
  for (const m of src.matchAll(/^import\s+([\w.]+)$/gm)) imports.add(m[1]);

  // 1. 图标
  for (const m of src.matchAll(/Icons\.(Filled|Outlined|Rounded|Sharp|TwoTone|AutoMirrored\.\w+)\.(\w+)/g)) {
    const variant = m[1];
    const name = m[2];
    if (variant.startsWith('AutoMirrored.')) continue;
    const variantPkg = variant.toLowerCase();
    const expected = `androidx.compose.material.icons.${variantPkg}.${name}`;
    if (!imports.has(expected)) {
      console.log(`MISSING ICON IMPORT ${file}: ${expected}`);
      problems += 1;
    }
  }

  // 2. Modifier 扩展
  for (const m of src.matchAll(/Modifier\s*\.\s*(\w+)\s*\(/g)) {
    const ext = m[1];
    const pkgs = modifierImports[ext];
    if (!pkgs) continue;
    const ok = [...imports].some((imp) => pkgs.some((pkg) => imp === `${pkg}.${ext}`));
    if (!ok) {
      console.log(`MISSING MODIFIER IMPORT ${file}: Modifier.${ext}`);
      problems += 1;
    }
  }
}

console.log(`import 检查完成：${files.length} 个文件，问题 ${problems} 个`);
process.exit(problems === 0 ? 0 : 1);
