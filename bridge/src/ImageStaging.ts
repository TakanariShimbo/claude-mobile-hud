// docs/03 §6.2.4: Phone base64 画像 → per-pid inbox (`~/.claude/channels/mobile-hud/inbox/<pid>/`)
// → Claude へ meta.image_path で渡す。FR-PH-64 / AD-09。

import { mkdir, readdir, rm, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { randomUUID } from "node:crypto";
import type { Logger } from "./log/StructuredLog.js";

/** docs/03 §6.2.4.3: IANA 公式 4 種に限定。 */
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
    /** docs/03 §6.2.4.5: 生存判定 test seam。 */
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

    static defaultPath(pid: number = process.pid): string {
        return join(homedir(), ".claude", "channels", "mobile-hud", "inbox", String(pid));
    }

    async prepare(): Promise<void> {
        if (this.prepared) return;
        await mkdir(this.inboxDir, { recursive: true });
        this.prepared = true;
        this.logger.info("inbox_ready", { dir: this.inboxDir });
        // docs/03 §6.2.4.2: 起動時 1 回だけ親 inboxRoot を走査して dead pid を掃除 (P2-7)。
        await this.gcOrphanInboxes();
    }

    /** @throws {Error} 未対応 MIME or 書き込み失敗 */
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
            // docs/03 §6.2.4.2: 数字 round-trip しない name は誤消去防止で skip。
            if (!Number.isInteger(pid) || pid <= 0 || String(pid) !== name) continue;
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

// docs/03 §6.2.4.4: signal 0 = 存在/権限 check のみ。EPERM は「存在するが触れない」= true。
function defaultIsPidAlive(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch (err) {
        if ((err as NodeJS.ErrnoException).code === "EPERM") return true;
        return false;
    }
}
