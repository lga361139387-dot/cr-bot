/**
 * Cursor stdio MCP 桥。把本地 MCP 帧转发到 CRBOT 的 /api/v1/ide-mcp。
 * 环境变量：CRBOT_BASE_URL、CRBOT_MCP_TOKEN。
 */
import { Buffer } from "node:buffer";

const base = (process.env.CRBOT_BASE_URL || "http://127.0.0.1:8080").replace(/\/$/, "");
const token = process.env.CRBOT_MCP_TOKEN || "";
const endpoint = `${base}/api/v1/ide-mcp`;

let pending = Buffer.alloc(0);
let chain = Promise.resolve();

process.stdin.on("data", (chunk) => {
  pending = Buffer.concat([pending, chunk]);
  while (true) {
    const headerEnd = pending.indexOf("\r\n\r\n");
    if (headerEnd < 0) {
      return;
    }
    const header = pending.slice(0, headerEnd).toString("utf8");
    const matched = /Content-Length:\s*(\d+)/i.exec(header);
    if (!matched) {
      process.stderr.write("crbot mcp: 缺少 Content-Length\n");
      process.exit(1);
    }
    const length = Number(matched[1]);
    const start = headerEnd + 4;
    if (pending.length < start + length) {
      return;
    }
    const body = pending.slice(start, start + length).toString("utf8");
    pending = pending.slice(start + length);
    chain = chain.then(() => forward(body)).catch((error) => {
      process.stderr.write(`crbot mcp: ${error.message}\n`);
    });
  }
});

async function forward(body) {
  if (!token) {
    const request = JSON.parse(body);
    writeMessage({
      jsonrpc: "2.0",
      id: request.id ?? null,
      error: { code: -32000, message: "未配置 CRBOT_MCP_TOKEN" }
    });
    return;
  }
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      Authorization: `Bearer ${token}`
    },
    body
  });
  const text = await response.text();
  if (!text) {
    return;
  }
  writeMessage(JSON.parse(text));
}

function writeMessage(payload) {
  const json = JSON.stringify(payload);
  const bytes = Buffer.from(json, "utf8");
  process.stdout.write(`Content-Length: ${bytes.length}\r\n\r\n`);
  process.stdout.write(bytes);
}
