// 把 Kotlin 里的 Markdown 解析器 + SSE 解析逻辑等价移植到 JS，验证算法本身是否正确
// （只验证逻辑，不能替代编译）

/* ---------------- Markdown ---------------- */
const BULLET = /^([-*+])\s+(.*)$/;
const ORDERED = /^(\d{1,2})[.)]\s+(.*)$/;
const RULE = /^([-*_])\1{2,}$/;

const INLINE = new RegExp(
  '\\*\\*([^*\\n]+?)\\*\\*' +
  '|__([^_\\n]+?)__' +
  '|~~([^~\\n]+?)~~' +
  '|`([^`\\n]+?)`' +
  '|\\[([^\\]\\n]+?)\\]\\(([^)\\s]+?)\\)' +
  '|\\*([^*\\n]+?)\\*' +
  '|(https?://[^\\s<)）]+)',
  'g'
);

function parseMarkdown(source) {
  const text = source.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const lines = text.split('\n');
  const blocks = [];
  let paragraph = '';
  const flush = () => {
    if (paragraph.trim().length) blocks.push({ type: 'p', raw: paragraph.trim() });
    paragraph = '';
  };
  let index = 0;
  while (index < lines.length) {
    const raw = lines[index];
    const line = raw.trim();
    if (line.startsWith('```')) {
      flush();
      const language = line.slice(3).trim();
      let code = '';
      index++;
      while (index < lines.length && !lines[index].trim().startsWith('```')) {
        if (code.length) code += '\n';
        code += lines[index];
        index++;
      }
      blocks.push({ type: 'code', language, code });
    } else if (line === '') {
      flush();
    } else if (RULE.test(line)) {
      flush();
      blocks.push({ type: 'rule' });
    } else if (line.startsWith('#')) {
      flush();
      let level = 0;
      while (level < line.length && level < 6 && line[level] === '#') level++;
      const content = line.slice(level).trim();
      if (!content) paragraph += line;
      else blocks.push({ type: 'h', level, raw: content });
    } else if (line.startsWith('>')) {
      flush();
      blocks.push({ type: 'quote', raw: line.slice(1).trim() });
    } else if (BULLET.test(line)) {
      flush();
      blocks.push({ type: 'li', marker: '•', raw: BULLET.exec(line)[2] });
    } else if (ORDERED.test(line)) {
      flush();
      const m = ORDERED.exec(line);
      blocks.push({ type: 'li', marker: m[1] + '.', raw: m[2] });
    } else {
      if (paragraph.length) paragraph += '\n';
      paragraph += line;
    }
    index++;
  }
  flush();
  return blocks;
}

function inlineSegments(raw) {
  const out = [];
  let cursor = 0;
  for (const m of raw.matchAll(INLINE)) {
    if (m.index > cursor) out.push({ kind: 'text', text: raw.slice(cursor, m.index) });
    const g = m;
    if (g[1]) out.push({ kind: 'bold', text: g[1] });
    else if (g[2]) out.push({ kind: 'bold2', text: g[2] });
    else if (g[3]) out.push({ kind: 'strike', text: g[3] });
    else if (g[4]) out.push({ kind: 'code', text: g[4] });
    else if (g[5]) out.push({ kind: 'link', text: g[5], url: g[6] });
    else if (g[7]) out.push({ kind: 'italic', text: g[7] });
    else if (g[8]) out.push({ kind: 'url', text: g[8] });
    else out.push({ kind: 'text', text: m[0] });
    cursor = m.index + m[0].length;
  }
  if (cursor < raw.length) out.push({ kind: 'text', text: raw.slice(cursor) });
  return out;
}

/* ---------------- SSE ---------------- */
function parseSse(body) {
  const events = [];
  let reasoning = '';
  let content = '';
  let usage = null;
  let finish = null;
  for (const line of body.split('\n')) {
    if (!line.startsWith('data:')) continue;
    const data = line.slice(5).trim();
    if (!data || data === '[DONE]') continue;
    let chunk;
    try { chunk = JSON.parse(data); } catch { continue; }
    if (chunk.usage) usage = chunk.usage;
    const choices = chunk.choices || [];
    if (!choices.length) continue;
    const choice = choices[0];
    if (choice.finish_reason && choice.finish_reason !== 'null') finish = choice.finish_reason;
    const delta = choice.delta || {};
    if (typeof delta.reasoning_content === 'string' && delta.reasoning_content) reasoning += delta.reasoning_content;
    if (typeof delta.content === 'string' && delta.content) content += delta.content;
    events.push(delta);
  }
  return { reasoning, content, usage, finish, count: events.length };
}

/* ---------------- 断言 ---------------- */
let failed = 0;
function check(name, cond, extra) {
  if (!cond) { failed++; console.log(`FAIL ${name}${extra ? ' → ' + extra : ''}`); }
  else console.log(`ok   ${name}`);
}

// 1. 代码块
{
  const b = parseMarkdown('前言\n\n```kotlin\nval a = 1\nprintln(a)\n```\n\n结尾');
  check('code block 数量', b.length === 3, JSON.stringify(b));
  check('code block 语言', b[1].type === 'code' && b[1].language === 'kotlin', JSON.stringify(b[1]));
  check('code block 内容', b[1].code === 'val a = 1\nprintln(a)', JSON.stringify(b[1].code));
}
// 2. 未闭合代码块
{
  const b = parseMarkdown('```\nabc\ndef');
  check('未闭合代码块被吞掉', b.length === 1 && b[0].type === 'code' && b[0].code === 'abc\ndef', JSON.stringify(b));
}
// 3. 标题/列表/引用/分隔线
{
  const b = parseMarkdown('# 一级\n## 二级\n- 条目 A\n1. 条目 B\n> 引用\n---\n正文');
  check('块序列', b.map((x) => x.type).join(',') === 'h,h,li,li,quote,rule,p', b.map((x) => x.type).join(','));
  check('h1 level', b[0].level === 1 && b[1].level === 2);
  check('有序列表 marker', b[3].marker === '1.' && b[3].raw === '条目 B');
  check('rule', b[5].type === 'rule');
}
// 4. 段落合并
{
  const b = parseMarkdown('第一行\n第二行\n\n新段落');
  check('段落合并', b.length === 2 && b[0].raw === '第一行\n第二行', JSON.stringify(b));
}
// 5. 行内样式
{
  const s = inlineSegments('这是 **粗体** 和 `code`，链接 [文档](https://x.com) 与 https://a.b/c');
  const kinds = s.map((x) => x.kind).join(',');
  check('行内样式切分', kinds === 'text,bold,text,code,text,link,text,url', kinds + ' :: ' + JSON.stringify(s));
  check('链接 URL 组', s.find((x) => x.kind === 'link').url === 'https://x.com');
}
// 6. 行内不误伤（乘号、下划线）
{
  const s = inlineSegments('2 * 3 = 6，snake_case_name');
  check('不误判斜体', s.length === 1 && s[0].kind === 'text', JSON.stringify(s));
}
// 7. 空输入
check('空输入', parseMarkdown('').length === 0);

// 8. SSE
{
  const body = [
    'data: {"choices":[{"delta":{"reasoning_content":"先想"}}]}',
    '',
    'data: {"choices":[{"delta":{"reasoning_content":"一想"}}]}',
    '',
    'data: {"choices":[{"delta":{"content":"你好"}}]}',
    '',
    'data: {"choices":[{"delta":{"content":"世界"},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18,"completion_tokens_details":{"reasoning_tokens":4}}}',
    '',
    'data: [DONE]',
    '',
  ].join('\n');
  const r = parseSse(body);
  check('SSE reasoning 拼接', r.reasoning === '先想一想', r.reasoning);
  check('SSE content 拼接', r.content === '你好世界', r.content);
  check('SSE usage', r.usage && r.usage.total_tokens === 18);
  check('SSE reasoning_tokens', r.usage.completion_tokens_details.reasoning_tokens === 4);
  check('SSE finish', r.finish === 'stop');
}

// 9. 空 choices 的 usage-only chunk 不应中断
{
  const body = 'data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}\ndata: [DONE]';
  const r = parseSse(body);
  check('usage-only chunk', r.usage && r.usage.total_tokens === 3 && r.content === '');
}

console.log(failed === 0 ? '\n全部通过' : `\n失败 ${failed} 项`);
process.exit(failed === 0 ? 0 : 1);
