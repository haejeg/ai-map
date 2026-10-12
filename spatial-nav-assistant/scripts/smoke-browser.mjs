// Serve the app locally and run tests/browser-smoke.html in headless Chrome/Edge.
// The harness POSTs its PASS/FAIL report to /__result.
// Usage: node scripts/smoke-browser.mjs [path-to-browser]
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { extname, join, normalize, resolve } from 'node:path';

const ROOT = resolve('.');
const TIMEOUT_MS = 60000;
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css' };

const CANDIDATES = [
  process.argv[2],
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].filter(Boolean);
const browserPath = CANDIDATES.find((p) => existsSync(p));
if (!browserPath) {
  console.error('No Chromium-based browser found; pass its path as an argument.');
  process.exit(2);
}

const profileDir = mkdtempSync(join(tmpdir(), 'smoke-'));
let browser;

function finish(text, code) {
  console.log(text);
  browser?.kill();
  server.close();
  setTimeout(() => {
    try { rmSync(profileDir, { recursive: true, force: true }); } catch { /* browser may still hold files */ }
    process.exit(code);
  }, 500);
}

const server = createServer((req, res) => {
  const pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (req.method === 'POST' && pathname === '/__result') {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      res.writeHead(204).end();
      finish(body, body.startsWith('PASS') ? 0 : 1);
    });
    return;
  }
  const path = normalize(join(ROOT, pathname));
  if (!path.startsWith(ROOT) || !existsSync(path)) {
    res.writeHead(404).end();
    return;
  }
  res.writeHead(200, { 'Content-Type': TYPES[extname(path)] ?? 'application/octet-stream' });
  res.end(readFileSync(path));
});

server.listen(0, '127.0.0.1', () => {
  const url = `http://127.0.0.1:${server.address().port}/tests/browser-smoke.html`;
  browser = spawn(browserPath, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--mute-audio', `--user-data-dir=${profileDir}`, url,
  ], { stdio: 'ignore' });
  setTimeout(() => finish('FAIL\ntimed out waiting for the harness to report', 1), TIMEOUT_MS).unref();
});
