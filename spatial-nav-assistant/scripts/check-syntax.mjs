// Syntax-check every source file (including browser-only modules that the
// test runner can't execute) with `node --check`.
import { execFileSync } from 'node:child_process';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';

const dirs = ['js', 'tests', 'scripts'];
let failed = 0;
for (const dir of dirs) {
  for (const file of readdirSync(dir).filter((f) => /\.m?js$/.test(f))) {
    const path = join(dir, file);
    try {
      execFileSync(process.execPath, ['--check', path], { stdio: 'pipe' });
      console.log(`ok   ${path}`);
    } catch (err) {
      failed++;
      console.error(`FAIL ${path}\n${err.stderr}`);
    }
  }
}
process.exit(failed ? 1 : 0);
