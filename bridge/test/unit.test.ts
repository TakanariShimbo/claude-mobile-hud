import { mkdtemp, readdir, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ImageStaging, isSupportedMime } from "../src/ImageStaging.js";
import {
    parseSessionIdFromCmdline,
    SessionDetector,
} from "../src/SessionDetector.js";
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
});

describe("SessionDetector", () => {
    it("parseSessionIdFromCmdline picks --session-id <uuid>", () => {
        const cmdline = "node\0/path/claude\0--session-id\0abc-123\0--verbose\0\0";
        expect(parseSessionIdFromCmdline(cmdline)).toBe("abc-123");
    });

    it("parseSessionIdFromCmdline handles --session-id=<uuid>", () => {
        const cmdline = "node\0/path/claude\0--session-id=zzz\0";
        expect(parseSessionIdFromCmdline(cmdline)).toBe("zzz");
    });

    it("parseSessionIdFromCmdline returns null when absent", () => {
        const cmdline = "node\0/path/claude\0--something\0else\0";
        expect(parseSessionIdFromCmdline(cmdline)).toBeNull();
    });

    it("detect prefers cmdline over env over random", async () => {
        const det = new SessionDetector({
            parentPid: 1,
            envValue: "from-env",
            readCmdline: async () => "node\0--session-id\0from-cmd\0",
            logger: silentLogger,
        });
        expect(await det.detect()).toBe("from-cmd");
    });

    it("detect falls back to env when cmdline missing", async () => {
        const det = new SessionDetector({
            parentPid: 1,
            envValue: "from-env",
            readCmdline: async () => "node\0--no-id\0",
            logger: silentLogger,
        });
        expect(await det.detect()).toBe("from-env");
    });

    it("detect throws when both cmdline and env are missing (fail-fast, P2-4)", async () => {
        const det = new SessionDetector({
            parentPid: 1,
            envValue: undefined,
            readCmdline: async () => "node\0",
            logger: silentLogger,
        });
        await expect(det.detect()).rejects.toThrow(/session_id not found/);
    });

    it("detect handles cmdline read failure gracefully", async () => {
        const det = new SessionDetector({
            parentPid: 1,
            envValue: "from-env",
            readCmdline: async () => { throw new Error("EACCES"); },
            logger: silentLogger,
        });
        expect(await det.detect()).toBe("from-env");
    });
});
