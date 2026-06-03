#!/usr/bin/env node

'use strict';

const { execSync, spawn } = require('child_process');
const { existsSync, mkdirSync, createWriteStream, unlinkSync } = require('fs');
const path = require('path');
const os = require('os');
const https = require('https');

const { version } = require('../package.json');
const REPO = 'hoeongj/ssuMCP';
const JAR_NAME = `ssuMCP-${version}.jar`;
const JAR_URL = `https://github.com/${REPO}/releases/download/v${version}/${JAR_NAME}`;
const CACHE_DIR = path.join(os.homedir(), '.ssumcp');
const JAR_PATH = path.join(CACHE_DIR, JAR_NAME);

function checkJava() {
  try {
    const output = execSync('java -version 2>&1', { encoding: 'utf8' });
    const match = output.match(/version "(\d+)/);
    const major = match ? parseInt(match[1], 10) : 0;
    if (major < 21) {
      console.error(`[ssumcp] Java 21 이상이 필요합니다. 현재: Java ${major}`);
      console.error('[ssumcp] 설치: https://adoptium.net');
      process.exit(1);
    }
  } catch {
    console.error('[ssumcp] Java가 설치되어 있지 않습니다.');
    console.error('[ssumcp] Java 21 설치: https://adoptium.net');
    process.exit(1);
  }
}

function downloadJar() {
  return new Promise((resolve, reject) => {
    if (existsSync(JAR_PATH)) {
      resolve();
      return;
    }

    if (!existsSync(CACHE_DIR)) {
      mkdirSync(CACHE_DIR, { recursive: true });
    }

    console.log(`[ssumcp] ssuMCP v${version} 다운로드 중...`);

    const file = createWriteStream(JAR_PATH);

    function get(url, hops) {
      if (hops > 5) { reject(new Error('리다이렉트 초과')); return; }
      https.get(url, (res) => {
        if (res.statusCode === 301 || res.statusCode === 302) {
          get(res.headers.location, hops + 1);
          return;
        }
        if (res.statusCode !== 200) {
          file.close();
          try { unlinkSync(JAR_PATH); } catch { /* ignore */ }
          reject(new Error(
            `다운로드 실패: HTTP ${res.statusCode}\n` +
            `릴리즈 확인: https://github.com/${REPO}/releases`
          ));
          return;
        }
        const total = parseInt(res.headers['content-length'] || '0', 10);
        let done = 0;
        res.on('data', (chunk) => {
          done += chunk.length;
          if (total > 0) {
            process.stdout.write(`\r[ssumcp] ${Math.round((done / total) * 100)}%`);
          }
        });
        res.pipe(file);
        file.on('finish', () => { file.close(); process.stdout.write('\n'); resolve(); });
      }).on('error', (err) => {
        file.close();
        try { unlinkSync(JAR_PATH); } catch { /* ignore */ }
        reject(err);
      });
    }

    get(JAR_URL, 0);
  });
}

async function main() {
  checkJava();
  await downloadJar();

  const port = process.env.PORT || '8080';
  const extra = process.argv.slice(2);

  console.log(`[ssumcp] 서버 시작 중 (포트 ${port})...`);
  console.log(`[ssumcp] MCP 엔드포인트 → http://localhost:${port}/mcp`);
  console.log('');
  console.log('Claude Desktop 설정 (claude_desktop_config.json):');
  console.log(JSON.stringify(
    { mcpServers: { ssuMCP: { url: `http://localhost:${port}/mcp` } } },
    null, 2
  ));
  console.log('');

  const child = spawn(
    'java',
    [`-Dserver.port=${port}`, '-jar', JAR_PATH, ...extra],
    { stdio: 'inherit' }
  );

  process.on('SIGINT', () => child.kill('SIGINT'));
  process.on('SIGTERM', () => child.kill('SIGTERM'));
  child.on('exit', (code) => process.exit(code ?? 0));
}

main().catch((err) => {
  console.error('[ssumcp] 오류:', err.message);
  process.exit(1);
});
