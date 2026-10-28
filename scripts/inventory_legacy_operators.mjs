// Source inventory for deprecated Oreslang expression operators.
// This is an audit, NOT the parser, typechecker, compiler or a semantic CI proof.
// Run: node scripts/inventory_legacy_operators.mjs --json [source paths...]
//      node scripts/inventory_legacy_operators.mjs --fail [source paths...]
import { readFileSync, readdirSync, statSync } from "node:fs";
import { resolve, extname } from "node:path";
import { fileURLToPath } from "node:url";

export function scanLegacyOperators(source) {
  const matches = [];
  let state = "code", line = 1, col = 1;
  for (let i = 0; i < source.length;) {
    const c = source[i], next = source[i + 1];
    const advance = (n = 1) => {
      for (let j = 0; j < n; j++) {
        if (source[i] === "\n") { line++; col = 1; }
        else col++;
        i++;
      }
    };
    if (state === "line-comment") {
      if (c === "\n") state = "code";
      advance(); continue;
    }
    if (state === "block-comment") {
      if (c === "*" && next === "/") { advance(2); state = "code"; }
      else advance();
      continue;
    }
    if (state === "single" || state === "double" || state === "template") {
      if (c === "\\") { advance(next === undefined ? 1 : 2); continue; }
      if ((state === "single" && c === "'") ||
          (state === "double" && c === '"') ||
          (state === "template" && c === "`")) state = "code";
      // Template interpolation requires full parser treatment; inventory errs
      // on the side of not claiming this scan is comprehensive.
      advance(); continue;
    }
    if (c === "/" && next === "/") { state = "line-comment"; advance(2); continue; }
    if (c === "/" && next === "*") { state = "block-comment"; advance(2); continue; }
    if (c === "'") { state = "single"; advance(); continue; }
    if (c === '"') { state = "double"; advance(); continue; }
    if (c === "`") { state = "template"; advance(); continue; }
    if (c === "%" || c === "^") {
      const kind = c === "%" ? "percent" : (next === "^" ? "double-caret" : "caret");
      matches.push({ kind, line, column: col });
      advance(kind === "double-caret" ? 2 : 1); continue;
    }
    advance();
  }
  return matches;
}

function walk(path) {
  const info = statSync(path);
  if (info.isFile()) return extname(path) === ".ores" ? [path] : [];
  if (!info.isDirectory()) return [];
  return readdirSync(path, { withFileTypes: true }).flatMap(entry => {
    if (entry.name === ".git" || entry.isSymbolicLink()) return [];
    return walk(resolve(path, entry.name));
  });
}

export function inventoryPaths(paths) {
  return paths.flatMap(p => walk(resolve(p))).sort().flatMap(path =>
    scanLegacyOperators(readFileSync(path, "utf8")).map(m => ({ path, ...m }))
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2), fail = args.includes("--fail"), json = args.includes("--json");
  const paths = args.filter(arg => arg !== "--fail" && arg !== "--json");
  const matches = inventoryPaths(paths.length ? paths : ["stdlib", "examples"]);
  if (json) process.stdout.write(JSON.stringify({ status: "inventory-only", matches }, null, 2) + "\n");
  else {
    for (const m of matches) process.stdout.write(`${m.path}:${m.line}:${m.column}: ${m.kind}\n`);
    process.stdout.write(`Inventory (not compiler verification): ${matches.length} legacy operators\n`);
  }
  if (fail && matches.length) process.exitCode = 1;
}
