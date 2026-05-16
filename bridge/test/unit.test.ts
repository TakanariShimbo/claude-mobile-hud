import { mkdtemp, readdir, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ImageStaging, isSupportedMime } from "../src/ImageStaging.js";
import { SessionDetector } from "../src/SessionDetector.js";
import { StructuredLog } from "../src/log/StructuredLog.js";

const silentLogger = new StructuredLog("test", "ERROR", () => undefined);

describe("ImageStaging", () => {
    it("supported mimes", () => {
        expect(isSupportedMime("image/jpeg")).toBe(true);
        expect(isSupportedMime("image/png")).toBe(true);
        expect(isSupportedMime("image/webp")).toBe(true);
        expect(isSupportedMime("application/pdf")).toBe(false);
    });

    it("save writes file with extension matching mime", async () => {
        const dir = await mkdtemp(join(tmpdir(), "bridge-inbox-"));
        const stage = new ImageStaging(dir, silentLogger);
        const data = Buffer.from([0xff, 0xd8, 0xff]).toString("base64"); // JPEG header
        const path = await stage.save(data, "image/jpeg");
        expect(path.endsWith(".jpg")).toBe(true);
        const bytes = await readFile(path);
        expect(bytes[0]).toBe(0xff);
        expect(bytes[1]).toBe(0xd8);
        expect(bytes[2]).toBe(0xff);
        await stage.cleanup();
    });

    it("save rejects unsupported mime", async () => {
        const dir = await mkdtemp(join(tmpdir(), "bridge-inbox-"));
        const stage = new ImageStaging(dir, silentLogger);
        await expect(stage.save("aGVsbG8=", "application/pdf")).rejects.toThrow(/unsupported/);
        await stage.cleanup();
    });

    it("cleanup removes inbox directory", async () => {
        const dir = await mkdtemp(join(tmpdir(), "bridge-inbox-"));
        const stage = new ImageStaging(dir, silentLogger);
        await stage.save(Buffer.from("x").toString("base64"), "image/png");
        await stage.cleanup();
        // ディレクトリは消えているので readdir は失敗するはず
        await expect(readdir(dir)).rejects.toThrow();
    });

    it("prepare gc-cleans orphan pid inboxes (P2-7)", async () => {
        const { mkdir, writeFile } = await import("node:fs/promises");
        const root = await mkdtemp(join(tmpdir(), "bridge-inbox-root-"));
        // 3 つの pid ディレクトリを用意: 自分 / 生きている他 / 死んでいる
        const selfPid = 99999;
        const aliveOther = 88888;
        const deadOther = 77777;
        const notNumeric = "abc";
        await mkdir(join(root, String(selfPid)), { recursive: true });
        await mkdir(join(root, String(aliveOther)), { recursive: true });
        await mkdir(join(root, String(deadOther)), { recursive: true });
        await mkdir(join(root, notNumeric), { recursive: true });
        await writeFile(join(root, String(deadOther), "stuff.jpg"), "x");
        await writeFile(join(root, notNumeric, "stuff"), "y");

        const stage = new ImageStaging(join(root, String(selfPid)), silentLogger, {
            isPidAlive: (pid) => pid === aliveOther,
        });
        await stage.prepare();

        const remaining = await readdir(root);
        expect(remaining.sort()).toEqual(
            [String(selfPid), String(aliveOther), notNumeric].sort(),
        );
    });
});

describe("SessionDetector", () => {
    const UUID = "11111111-2222-3333-4444-555555555555";

    it("returns BRIDGE_SESSION_ID when set and valid", async () => {
        const det = new SessionDetector({
            processEnv: { BRIDGE_SESSION_ID: UUID },
            logger: silentLogger,
        });
        await expect(det.detect()).resolves.toBe(UUID);
    });

    it("throws fail-fast when BRIDGE_SESSION_ID is unset (AD-12)", async () => {
        const det = new SessionDetector({ processEnv: {}, logger: silentLogger });
        await expect(det.detect()).rejects.toThrow(/BRIDGE_SESSION_ID env var not set/);
    });

    it("throws fail-fast when BRIDGE_SESSION_ID is empty string", async () => {
        const det = new SessionDetector({
            processEnv: { BRIDGE_SESSION_ID: "" },
            logger: silentLogger,
        });
        await expect(det.detect()).rejects.toThrow(/BRIDGE_SESSION_ID env var not set/);
    });

    it("throws when BRIDGE_SESSION_ID is not a valid UUID (refuse to corrupt AD-12)", async () => {
        const det = new SessionDetector({
            processEnv: { BRIDGE_SESSION_ID: "not-a-uuid" },
            logger: silentLogger,
        });
        await expect(det.detect()).rejects.toThrow(/not a valid UUID/);
    });

    it("accepts upper-case UUID characters", async () => {
        const upper = UUID.toUpperCase();
        const det = new SessionDetector({
            processEnv: { BRIDGE_SESSION_ID: upper },
            logger: silentLogger,
        });
        await expect(det.detect()).resolves.toBe(upper);
    });
});
