import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync, copyFileSync } from "node:fs";
import { gzipSync, gunzipSync } from "node:zlib";
import { matchesAndroidInstaller } from "../scripts/installer-validation.mjs";
import { execFileSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";

const files = {
  "package-lock.json": '{"lockfileVersion":3}\n',
  "LICENSE": "Repository license\n",
  "dist/index.js": 'console.log("Pi ACP");\n',
};
const archive = JSON.stringify(files);
const packagePayload = (text: string, level: number = 9, os: number = 19) => {
  const gzip = gzipSync(text, { level });
  gzip[9] = os;
  return gzip.toString("base64");
};
const installer = (payload: string) => `set -eu
PI_ACP_PACKAGE_DIR=/runtime/versions/content-digest
node <<'EXTRACT'
const files = JSON.parse(require('node:zlib').gunzipSync(Buffer.from('${payload}', 'base64')).toString());
EXTRACT
node "$PI_ACP_PACKAGE_DIR/dist/index.js" --health
`;
const expected = installer(packagePayload(archive));

test("packaging accepts identical files across gzip platforms and compression levels", () => {
  for (const level of [0, 1, 9]) {
    for (const os of [3, 10, 19, 255]) {
      const candidate = installer(packagePayload(archive, level, os));
      assert.equal(matchesAndroidInstaller(candidate, expected, archive), true);
    }
  }
  assert.notEqual(installer(packagePayload(archive, 9, 3)), expected);
});

test("packaging rejects changed source, dependencies and license bytes", () => {
  for (const file of Object.keys(files)) {
    const modified = { ...files, [file]: "changed\n" };
    assert.equal(matchesAndroidInstaller(installer(packagePayload(JSON.stringify(modified))), expected, archive), false, file);
  }
  assert.equal(matchesAndroidInstaller(installer(packagePayload(JSON.stringify({ ...files, "unexpected.js": "extra" }))), expected, archive), false);
});

test("packaging still verifies installer commands and content revision", () => {
  for (const candidate of [
    expected.replace("--health", "--version"),
    expected.replace("content-digest", "old-digest"),
    expected.replace("gunzipSync", "inflateSync"),
    `${expected}\nnode unexpected.js\n`,
  ]) assert.equal(matchesAndroidInstaller(candidate, expected, archive), false);
});

test("packaging rejects absent, ambiguous or damaged payloads", () => {
  for (const candidate of [
    installer(""),
    installer(Buffer.from("not gzip").toString("base64")),
    installer(packagePayload(archive).slice(0, -12)),
    `${expected}\nBuffer.from('AA==', 'base64')\n`,
  ]) assert.equal(matchesAndroidInstaller(candidate, expected, archive), false);
});

test("the shipped Android installer verifies after Linux-style recompression", () => {
  const shipped = readFileSync(new URL("../../../app/src/main/assets/acp/install/pi.sh", import.meta.url), "utf8");
  const payload = shipped.match(/Buffer\.from\('([A-Za-z0-9+/=]+)', 'base64'\)/)![1];
  const contents = gunzipSync(Buffer.from(payload, "base64")).toString();
  const linux = shipped.replace(payload, packagePayload(contents, 1, 3));
  assert.notEqual(linux, shipped);
  assert.equal(matchesAndroidInstaller(shipped, linux, contents), true);
  assert.equal(matchesAndroidInstaller(linux, shipped, contents), true);
});

test("打包在 LF 与 CRLF 检出下产生相同安装身份并可检查", () => {
  const temporary = mkdtempSync(join(tmpdir(), "omnibot-pi-newlines-"));
  try {
    const results: Array<{revision: string; archive: string}> = [];
    for (const newline of ["\n", "\r\n"]) {
      const repo = join(temporary, newline.length === 1 ? "lf" : "crlf");
      const tool = join(repo, "tools/pi-acp");
      mkdirSync(join(tool, "scripts"), {recursive: true});
      mkdirSync(join(tool, "dist"));
      mkdirSync(join(repo, "app/src/main/assets/acp/install"), {recursive: true});
      for (const filename of ["package.json", "package-lock.json", "LICENSE"]) {
        writeFileSync(join(tool, filename), JSON.stringify({name: "fixture", license: "MIT"}) + newline);
      }
      writeFileSync(join(tool, "dist/index.js"), "console.log('fixture');" + newline);
      const generator = readFileSync(new URL("../scripts/package-android.mjs", import.meta.url), "utf8").replaceAll("\r\n", "\n").replaceAll("\n", newline);
      writeFileSync(join(tool, "scripts/package-android.mjs"), generator);
      copyFileSync(new URL("../scripts/installer-validation.mjs", import.meta.url), join(tool, "scripts/installer-validation.mjs"));
      const catalog = join(repo, "app/src/main/assets/acp/agents.json");
      writeFileSync(catalog, JSON.stringify({agents: [{id: "pi-acp", runtime: {preparationRevision: "old"}}]}));
      execFileSync(process.execPath, [join(tool, "scripts/package-android.mjs")]);
      execFileSync(process.execPath, [join(tool, "scripts/package-android.mjs"), "--check"]);
      const shipped = readFileSync(join(repo, "app/src/main/assets/acp/install/pi.sh"), "utf8");
      const payload = shipped.match(/Buffer\.from\('([A-Za-z0-9+/=]+)', 'base64'\)/)![1];
      results.push({revision: JSON.parse(readFileSync(catalog, "utf8")).agents[0].runtime.preparationRevision, archive: gunzipSync(Buffer.from(payload, "base64")).toString()});
    }
    assert.equal(results[0].revision, results[1].revision);
    assert.equal(results[0].archive, results[1].archive);
    assert.equal(JSON.parse(results[0].archive)["dist/index.js"], "console.log('fixture');\n");
  } finally {
    rmSync(temporary, {recursive: true, force: true});
  }
});
