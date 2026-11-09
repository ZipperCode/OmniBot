import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import type { ChildProcess } from "node:child_process";
import type { ServerResponse } from "node:http";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable, Writable } from "node:stream";
import { fileURLToPath } from "node:url";
import { client, methods, ndJsonStream } from "@agentclientprotocol/sdk/unstable";
import type { ClientConnection, SessionNotification } from "@agentclientprotocol/sdk/unstable";
import { z } from "zod";
import { configFor, httpFixture } from "./fixtures.js";

const image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==";
const signature = Buffer.from("原生协议工具续轮签名").toString("base64");
const fileContent = "原生工具实际写入并读回";
type Api = "google-generative-ai" | "anthropic-messages";
type Tool = { id: string; name: string; args: Record<string, unknown> };

const inlineImage = z.object({ mimeType: z.string(), data: z.string() });
const googleFunctionResponse = z.object({
  id: z.string().optional(),
  response: z.object({ output: z.string().optional(), error: z.string().optional() }),
  parts: z.array(z.object({ inlineData: inlineImage })).optional(),
});
const googleMessage = z.object({ contents: z.array(z.object({ parts: z.array(z.object({
  functionCall: z.object({ id: z.string().optional() }).optional(),
  functionResponse: googleFunctionResponse.optional(),
  thoughtSignature: z.string().optional(),
})) })) });
const anthropicResult = z.object({ type: z.string(), text: z.string().optional(),
  source: z.object({ data: z.string() }).optional() });
const anthropicMessage = z.object({ messages: z.array(z.object({ content: z.union([z.string(), z.array(z.object({
  type: z.string(), tool_use_id: z.string().optional(), is_error: z.boolean().optional(),
  signature: z.string().optional(), content: z.union([z.string(), z.array(anthropicResult)]).optional(),
}))]) })) });

for (const api of ["google-generative-ai", "anthropic-messages"] as const) {
  test(`${api} 的真实 ACP 进程执行多工具、错误与图片回传并恢复会话`, { timeout: 30000 }, async () => {
    const root = mkdtempSync(join(tmpdir(), "native-protocol-tools-"));
    const model = api === "google-generative-ai" ? "gemini-3-flash-preview" : "claude-sonnet-4-5";
    const mcp = await httpFixture((body, response) => {
      if (!body.id) { response.writeHead(202); response.end(); return; }
      const result = body.method === "initialize"
        ? { protocolVersion: body.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: "device", version: "1" } }
        : body.method === "tools/list"
          ? { tools: [{ name: "snapshot", description: "读取测试截图", inputSchema: { type: "object", properties: {} } }] }
          : { content: [{ type: "text", text: "测试截图" }, { type: "image", mimeType: "image/png", data: image }] };
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ jsonrpc: "2.0", id: body.id, result }));
    });
    let round = 0;
    const provider = await httpFixture((_body, response) => {
      round++;
      const tools: Tool[] = round === 1
        ? [{ id: "call_write", name: "write", args: { path: "proof.txt", content: fileContent } },
          { id: "call_missing", name: "read", args: { path: "missing.txt" } }]
        : round === 2
          ? [{ id: "call_read", name: "read", args: { path: "proof.txt" } },
            { id: "call_image", name: "mcp__device__snapshot", args: {} }]
          : [];
      respond(response, api, model, tools);
    });
    let child: ChildProcess | undefined;
    let connection: ClientConnection | undefined;
    try {
      const config = configFor(root, provider.url);
      config.modelId = model;
      config.provider.api = api;
      config.provider.baseUrl = api === "google-generative-ai" ? `${provider.url}/v1beta` : provider.url;
      config.provider.models = [{ id: model, name: model, reasoning: true, input: ["text", "image"],
        contextWindow: 128000, maxTokens: 4096, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } }];
      mkdirSync(config.agentDir, { recursive: true });
      const modelsPath = join(config.agentDir, "models.json");
      writeFileSync(modelsPath, JSON.stringify({ providers: { omnibot: config.provider } }));
      child = spawn(process.execPath, [fileURLToPath(new URL("../dist/index.js", import.meta.url))], {
        stdio: ["pipe", "pipe", "pipe"],
        env: { ...process.env, PI_CODING_AGENT_DIR: config.agentDir, OMNIBOT_PI_MODELS_PATH: modelsPath,
          OMNIBOT_PI_MODEL: model, OMNIBOT_PI_API_KEY: config.apiKey, OMNIBOT_PI_HEADERS: JSON.stringify({ "X-Native-Protocol": "local" }) },
      });
      const exited = once(child, "exit");
      let stderr = "";
      child.stderr!.on("data", (chunk) => { stderr += chunk; });
      const updates: SessionNotification[] = [];
      let approvals = 0;
      connection = client({ name: "native-protocol-tools" })
        .onNotification(methods.client.session.update, ({ params }) => { updates.push(params); })
        .onRequest(methods.client.session.requestPermission, () => {
          approvals++;
          return { outcome: { outcome: "selected", optionId: "allow_once" } };
        })
        .connect(ndJsonStream(Writable.toWeb(child.stdin!), Readable.toWeb(child.stdout!)));
      const remote = connection.agent;
      await remote.request(methods.agent.initialize, { protocolVersion: 1, clientCapabilities: {} });
      const servers = [{ type: "http" as const, name: "device", url: `${mcp.url}/mcp`, headers: [] }];
      const session = await remote.request(methods.agent.session.new, { cwd: root, mcpServers: servers });
      const result = await remote.request(methods.agent.session.prompt, {
        sessionId: session.sessionId, prompt: [{ type: "text", text: "实际写入文件，读回内容并检查工具错误与截图。" }],
      });
      assert.equal(result.stopReason, "end_turn");
      assert.equal(readFileSync(join(root, "proof.txt"), "utf8"), fileContent);
      assert.equal(provider.requests.length, 3);
      assert.equal(approvals, 4);
      const toolEvents = updates.map(({ update }) => update).filter((update) =>
        update.sessionUpdate === "tool_call" || update.sessionUpdate === "tool_call_update");
      assert.equal(new Set(toolEvents.map((update) => update.toolCallId)).size, 4);
      assert.ok(toolEvents.some((update) => update.status === "failed"));
      assert.ok(updates.some(({ update }) => update.sessionUpdate === "agent_message_chunk" &&
        update.content.type === "text" && update.content.text.includes("原生多工具完成")));
      assert.ok(provider.requests.every(({ headers }) => headers["x-native-protocol"] === "local"));
      const second = provider.requests[1].body;
      const third = provider.requests[2].body;
      if (api === "google-generative-ai") {
        assert.ok(provider.requests.every(({ url }) => new URL(url, provider.url).pathname === `/v1beta/models/${model}:streamGenerateContent`));
        assert.equal(provider.requests[0].headers["x-goog-api-key"], config.apiKey);
        const secondRequest = googleMessage.parse(second);
        const thirdRequest = googleMessage.parse(third);
        const responses = secondRequest.contents.flatMap((content) => content.parts)
          .flatMap((part) => part.functionResponse ? [part.functionResponse] : []);
        assert.deepEqual(responses.map((item) => item.id), ["call_write", "call_missing"]);
        assert.equal(typeof responses[0].response.output, "string");
        assert.match(responses[1].response.error ?? "", /missing\.txt|ENOENT/);
        const replay = secondRequest.contents.flatMap((content) => content.parts).find((part) => part.functionCall?.id === "call_write");
        assert.equal(replay?.thoughtSignature, signature);
        const next = thirdRequest.contents.flatMap((content) => content.parts)
          .flatMap((part) => part.functionResponse ? [part.functionResponse] : []);
        assert.match(next.find((item) => item.id === "call_read")?.response.output ?? "", new RegExp(fileContent));
        assert.equal(next.find((item) => item.id === "call_image")?.parts?.[0].inlineData.data, image);
      } else {
        assert.ok(provider.requests.every(({ url }) => new URL(url, provider.url).pathname === "/v1/messages"));
        assert.equal(provider.requests[0].headers["x-api-key"], config.apiKey);
        const secondRequest = anthropicMessage.parse(second);
        const thirdRequest = anthropicMessage.parse(third);
        const results = secondRequest.messages.flatMap((message) => typeof message.content === "string" ? [] : message.content)
          .filter((block) => block.type === "tool_result");
        assert.deepEqual(results.map((block) => block.tool_use_id), ["call_write", "call_missing"]);
        assert.equal(results[1].is_error, true);
        const thinking = secondRequest.messages.flatMap((message) => typeof message.content === "string" ? [] : message.content)
          .find((block) => block.type === "thinking");
        assert.equal(thinking?.signature, signature);
        const resultsWithImages = thirdRequest.messages.flatMap((message) => typeof message.content === "string" ? [] : message.content)
          .filter((block) => block.type === "tool_result");
        assert.match(JSON.stringify(resultsWithImages.find((block) => block.tool_use_id === "call_read")?.content), new RegExp(fileContent));
        const resultContent = resultsWithImages.find((block) => block.tool_use_id === "call_image")?.content;
        assert.ok(Array.isArray(resultContent));
        assert.equal(resultContent.find((block) => block.type === "image")?.source?.data, image);
      }
      await remote.request(methods.agent.session.close, { sessionId: session.sessionId });
      await remote.request(methods.agent.session.load, { sessionId: session.sessionId, cwd: root, mcpServers: servers });
      assert.equal((await remote.request(methods.agent.session.prompt, {
        sessionId: session.sessionId, prompt: [{ type: "text", text: "恢复后继续" }],
      })).stopReason, "end_turn");
      assert.equal(provider.requests.length, 4);
      assert.equal(approvals, 4);
      child.stdin!.end();
      assert.equal((await exited)[0], 0, stderr);
    } finally {
      connection?.close();
      if (child && child.exitCode == null) {
        const exited = once(child, "exit");
        child.kill("SIGTERM");
        await exited;
      }
      await provider.close();
      await mcp.close();
      rmSync(root, { recursive: true, force: true });
    }
  });
}

function respond(response: ServerResponse, api: Api, model: string, tools: Tool[]) {
  response.writeHead(200, { "Content-Type": "text/event-stream" });
  if (api === "google-generative-ai") {
    const parts = tools.length ? tools.map((tool, index) => ({
      functionCall: { id: tool.id, name: tool.name, args: tool.args },
      ...(index === 0 ? { thoughtSignature: signature } : {}),
    })) : [{ text: "原生多工具完成" }];
    response.end(`data: ${JSON.stringify({ candidates: [{ content: { role: "model", parts }, finishReason: "STOP" }],
      usageMetadata: { promptTokenCount: 48, candidatesTokenCount: 12, totalTokenCount: 60 } })}\n\n`);
    return;
  }
  const send = (type: string, fields = {}) => response.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...fields })}\n\n`);
  send("message_start", { message: { id: "native-message", type: "message", role: "assistant", model,
    content: [], stop_reason: null, usage: { input_tokens: 48, output_tokens: 0 } } });
  if (tools.length) {
    send("content_block_start", { index: 0, content_block: { type: "thinking", thinking: "", signature: "" } });
    send("content_block_delta", { index: 0, delta: { type: "thinking_delta", thinking: "执行工具" } });
    send("content_block_delta", { index: 0, delta: { type: "signature_delta", signature } });
    send("content_block_stop", { index: 0 });
    for (const [index, tool] of tools.entries()) {
      send("content_block_start", { index: index + 1, content_block: { type: "tool_use", id: tool.id, name: tool.name, input: {} } });
      const argumentsJson = JSON.stringify(tool.args);
      const boundary = Math.floor(argumentsJson.length / 2);
      for (const partial_json of [argumentsJson.slice(0, boundary), argumentsJson.slice(boundary)]) {
        send("content_block_delta", { index: index + 1, delta: { type: "input_json_delta", partial_json } });
      }
      send("content_block_stop", { index: index + 1 });
    }
  } else {
    send("content_block_start", { index: 0, content_block: { type: "text", text: "" } });
    send("content_block_delta", { index: 0, delta: { type: "text_delta", text: "原生多工具完成" } });
    send("content_block_stop", { index: 0 });
  }
  send("message_delta", { delta: { stop_reason: tools.length ? "tool_use" : "end_turn" }, usage: { output_tokens: 12 } });
  send("message_stop");
  response.end();
}
