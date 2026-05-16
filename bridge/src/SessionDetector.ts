// Bridge は Claude Code から子プロセスとして起動される。
// Claude Code は `claude --session-id <uuid> ...` で起動しているので、
// 親プロセスの cmdline から session-id を抽出する。Phase 3 §6.2.3。
//
// Linux 限定 (`/proc` を使う)。失敗時は env `CLAUDE_SESSION_ID` を fallback、
// それも無ければ random UUID を生成 (Hub 側で意味が無くなるが、Bridge を起動
// しないわけにはいかないので動かす。)

import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import type { Logger } from "./log/StructuredLog.js";

export interface SessionDetectorOptions {
    /** デフォルト: process.ppid */
    parentPid?: number;
    /** デフォルト: process.env.CLAUDE_SESSION_ID */
    envValue?: string | undefined;
    /** デフォルト: /proc/<pid>/cmdline を読む */
    readCmdline?: (pid: number) => Promise<string>;
    logger?: Logger;
}

const SESSION_ID_FLAG = "--session-id";

export class SessionDetector {
    constructor(private readonly opts: SessionDetectorOptions = {}) {}

    /**
     * 優先順位:
     *  1. /proc/<ppid>/cmdline の `--session-id <uuid>`
     *  2. env CLAUDE_SESSION_ID
     *  3. randomUUID() を生成 (warn log)
     */
    async detect(): Promise<string> {
        const ppid = this.opts.parentPid ?? process.ppid;
        const reader = this.opts.readCmdline ?? defaultReadCmdline;

        try {
            const cmdline = await reader(ppid);
            const fromCmd = parseSessionIdFromCmdline(cmdline);
            if (fromCmd) {
                this.opts.logger?.info("session_detected", { source: "cmdline", session_id: fromCmd });
                return fromCmd;
            }
        } catch (err) {
            this.opts.logger?.warn("cmdline_read_failed", { error: (err as Error).message });
        }

        const envVal = this.opts.envValue ?? process.env.CLAUDE_SESSION_ID;
        if (envVal && envVal.length > 0) {
            this.opts.logger?.info("session_detected", { source: "env", session_id: envVal });
            return envVal;
        }

        const generated = randomUUID();
        this.opts.logger?.warn("session_fallback_random", { session_id: generated });
        return generated;
    }
}

/**
 * `/proc/<pid>/cmdline` のフォーマットは NUL 区切り。
 * `node\0/path/to/claude\0--session-id\0<uuid>\0--foo\0bar\0` のような形。
 */
export function parseSessionIdFromCmdline(cmdline: string): string | null {
    const args = cmdline.split("\0").filter((s) => s.length > 0);
    for (let i = 0; i < args.length; i++) {
        const arg = args[i]!;
        if (arg === SESSION_ID_FLAG && i + 1 < args.length) {
            return args[i + 1] ?? null;
        }
        if (arg.startsWith(SESSION_ID_FLAG + "=")) {
            return arg.slice(SESSION_ID_FLAG.length + 1);
        }
    }
    return null;
}

async function defaultReadCmdline(pid: number): Promise<string> {
    return await readFile(`/proc/${pid}/cmdline`, "utf8");
}
