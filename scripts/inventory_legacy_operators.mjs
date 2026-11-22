// Source inventory for deprecated Oreslang expression operators.
// This is an audit, NOT the parser, typechecker, compiler or a semantic CI proof.
// Run: node scripts/inventory_legacy_operators.mjs --json [source paths...]
//      node scripts/inventory_legacy_operators.mjs --fail [source paths...]
import { readFileSync, readdirSync, statSync } from "node:fs";
import { resolve, extname } from "node:path";
import { fileURLToPath } from "node:url";

export function scanLegacyOperators(source) {
  const matches = [];
  // Nested template interpolations are scanned as code, not skipped. Strings
  // and comments remain inert. This is still an inventory, not a parser.
  const stack = [{ kind: "code" }];
  let line = 1, col = 1;
  for (let i = 0; i < source.length;) {
    const c = source[i], next = source[i + 1];
    const frame = stack[stack.length - 1];
    const advance = (n = 1) => {
      for (let j = 0; j < n; j++) {
        if (source[i] === "\n") { line++; col = 1; }
        else col++;
        i++;
      }
    };
    if (frame.kind === "line-comment") {
      if (c === "\n") stack.pop();
      advance(); continue;
    }
    if (frame.kind === "block-comment") {
      if (c === "*" && next === "/") { advance(2); stack.pop(); }
      else advance();
      continue;
    }
    if (frame.kind === "single" || frame.kind === "double") {
      if (c === "\\") { advance(next === undefined ? 1 : 2); continue; }
      if ((frame.kind === "single" && c === "'") ||
          (frame.kind === "double" && c === '"')) stack.pop();
      advance(); continue;
    }
    if (frame.kind === "template") {
      if (c === "\\") { advance(next === undefined ? 1 : 2); continue; }
      if (c === "$" && next === "{") { stack.push({kind: "interpolation", depth: 1}); advance(2); continue; }
      if (c === "`") stack.pop();
      advance(); continue;
    }
    // Normal source or code inside an interpolated template expression.
    if (frame.kind === "interpolation") {
      if (c === "{") { frame.depth++; advance(); continue; }
      if (c === "}") { frame.depth--; if (frame.depth === 0) stack.pop(); advance(); continue; }
    }
    if (c === "/" && next === "/") { stack.push({kind: "line-comment"}); advance(2); continue; }
    if (c === "/" && next === "*") { stack.push({kind: "block-comment"}); advance(2); continue; }
    if (c === "'") { stack.push({kind: "single"}); advance(); continue; }
    if (c === '"') { stack.push({kind: "double"}); advance(); continue; }
    if (c === "`") { stack.push({kind: "template"}); advance(); continue; }
    if (c === "%" || c === "^") {
      const kind = c === "%" ? "percent" : (next === "^" ? "double-caret" : "caret");
      matches.push({kind, line, column: col});
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
