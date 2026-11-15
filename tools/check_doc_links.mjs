// 校验仓库内 Markdown 的相对链接是否可达（docs/ 与根目录文档）。
// 用法：node tools/check_doc_links.mjs   （仓库根目录执行；退出码 1 表示有坏链）
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";

const ROOTS = ["docs", "README.md", "CHANGELOG.md", "NOTICE.md", "tools"];
const files = [];
function walk(p) {
  const st = statSync(p);
  if (st.isDirectory()) for (const e of readdirSync(p)) walk(`${p}/${e}`);
  else if (p.endsWith(".md")) files.push(p);
}
for (const r of ROOTS) walk(r);

let bad = 0;
for (const f of files) {
  const text = readFileSync(f, "utf8");
  for (const m of text.matchAll(/\[([^\]]*)\]\(([^)\s]+)\)/g)) {
    const target = m[2];
    if (/^(https?:|mailto:|#)/.test(target)) continue;
    const abs = resolve(dirname(f), decodeURI(target.split("#")[0]));
    if (!existsSync(abs)) {
      bad++;
      console.log(`坏链 ${f} -> ${target}`);
    }
  }
}
console.log(bad === 0 ? `链接检查通过（${files.length} 个 md）` : `${bad} 条坏链`);
process.exit(bad === 0 ? 0 : 1);
