// Phone 由来の base64 画像をローカルファイルに staging。
// Phase 3 §6.2.4 / FR-PH-64 / AD-09。
//
// Bridge プロセスごとに専用 inbox (`<inboxRoot>/<pid>/`) を持ち、Bridge 終了時に
// 自プロセスの inbox を削除する。起動時には親 inboxRoot を走査して、生きていない
// pid のサブディレクトリも掃除する (kill -9 / クラッシュで cleanup を逃した残骸対策)。

import { mkdir, readdir, rm, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
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

export interface ImageStagingOptions {
    /** pid 生存判定を差し替えるための seam (テスト用)。 */
    isPidAlive?: (pid: number) => boolean;
}

export class ImageStaging {
    private prepared = false;
    private readonly isPidAlive: (pid: number) => boolean;

    constructor(
        private readonly inboxDir: string,
        private readonly logger: Logger,
        options: ImageStagingOptions = {},
    ) {
        this.isPidAlive = options.isPidAlive ?? defaultIsPidAlive;
    }

    /** Bridge default の inbox: `~/.claude/channels/mobile-hud/inbox/<pid>/` */
    static defaultPath(pid: number = process.pid): string {
        return join(homedir(), ".claude", "channels", "mobile-hud", "inbox", String(pid));
    }

    async prepare(): Promise<void> {
        if (this.prepared) return;
        await mkdir(this.inboxDir, { recursive: true });
        this.prepared = true;
        this.logger.info("inbox_ready", { dir: this.inboxDir });
        // 親 inboxRoot を走査して、生きていない pid の inbox を掃除 (P2-7)。
        // 失敗してもログのみで継続。
        await this.gcOrphanInboxes();
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

    /** Bridge 終了時に自 pid の inbox を削除。失敗は warn ログのみ。 */
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

    /**
     * `<inboxRoot>/` の直下を走査して、`<pid>` ディレクトリのうち生きていない pid を rm。
     * 自分の pid と数字でないディレクトリ名はスキップする。失敗時は warn ログ。
     */
    private async gcOrphanInboxes(): Promise<void> {
        const root = dirname(this.inboxDir);
        const selfDirName = this.inboxDir.split("/").pop() ?? "";
        let entries: string[];
        try {
            entries = await readdir(root);
        } catch (err) {
            this.logger.warn("inbox_gc_readdir_failed", {
                root,
                error: (err as Error).message,
            });
            return;
        }
        let removed = 0;
        for (const name of entries) {
            if (name === selfDirName) continue;
            const pid = Number.parseInt(name, 10);
            if (!Number.isInteger(pid) || pid <= 0 || String(pid) !== name) {
                // 数字でない / 整数化で形が変わるディレクトリは触らない
                continue;
            }
            if (this.isPidAlive(pid)) continue;
            const path = join(root, name);
            try {
                const st = await stat(path);
                if (!st.isDirectory()) continue;
                await rm(path, { recursive: true, force: true });
                removed += 1;
                this.logger.info("inbox_orphan_removed", { pid, path });
            } catch (err) {
                this.logger.warn("inbox_orphan_rm_failed", {
                    pid,
                    error: (err as Error).message,
                });
            }
        }
        if (removed > 0) {
            this.logger.info("inbox_gc_done", { removed });
        }
    }
}

function defaultIsPidAlive(pid: number): boolean {
    try {
        // signal 0 は実際に送らず、permission/存在チェックのみ。
        // 存在しない pid → ESRCH throw、権限不足 → EPERM (= 存在はする) なので true 扱い。
        process.kill(pid, 0);
        return true;
    } catch (err) {
        if ((err as NodeJS.ErrnoException).code === "EPERM") return true;
        return false;
    }
}
