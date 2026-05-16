// Phone 由来の base64 画像をローカルファイルに staging。
// Phase 3 §6.2.4 / FR-PH-64 / AD-09。
//
// Bridge プロセスごとに専用 inbox を持ち、Bridge 終了時に削除する。

import { mkdir, rm, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import type { Logger } from "./log/StructuredLog.js";

/** 受理する MIME → 拡張子。AD-09。IANA 公式の MIME のみ。 */
const MIME_TO_EXT: Record<string, string> = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
    "image/gif": "gif",
};

export function isSupportedMime(mime: string): boolean {
    return Object.prototype.hasOwnProperty.call(MIME_TO_EXT, mime);
}

export class ImageStaging {
    private prepared = false;

    constructor(
        private readonly inboxDir: string,
        private readonly logger: Logger,
    ) {}

    /** Bridge default の inbox: `~/.claude/channels/mobile-hud/inbox/<pid>/` */
    static defaultPath(pid: number = process.pid): string {
        return join(homedir(), ".claude", "channels", "mobile-hud", "inbox", String(pid));
    }

    async prepare(): Promise<void> {
        if (this.prepared) return;
        await mkdir(this.inboxDir, { recursive: true });
        this.prepared = true;
        this.logger.info("inbox_ready", { dir: this.inboxDir });
    }

    /**
     * base64 を decode してファイルに書き出し、絶対パスを返す。
     * @throws {Error} 未対応 MIME or 書き込み失敗
     */
    async save(base64: string, mime: string): Promise<string> {
        if (!isSupportedMime(mime)) {
            throw new Error(`unsupported mime: ${mime}`);
        }
        await this.prepare();
        const ext = MIME_TO_EXT[mime]!;
        const path = join(this.inboxDir, `${randomUUID()}.${ext}`);
        await writeFile(path, Buffer.from(base64, "base64"));
        this.logger.debug("image_saved", { path, mime, bytes: base64.length });
        return path;
    }

    /** Bridge 終了時に inbox を空にする。失敗は warn ログのみ。 */
    async cleanup(): Promise<void> {
        try {
            await rm(this.inboxDir, { recursive: true, force: true });
            this.logger.info("inbox_cleaned", { dir: this.inboxDir });
        } catch (err) {
            this.logger.warn("inbox_cleanup_failed", {
                dir: this.inboxDir,
                error: (err as Error).message,
            });
        }
    }
}
