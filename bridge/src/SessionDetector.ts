// Bridge は Claude Code から子プロセスとして起動される。
// Claude Code は `claude --session-id <uuid> ...` で起動しているので、
// 親プロセスの cmdline から session-id を抽出する。Phase 3 §6.2.3 / AD-12。
//
// Linux 限定 (`/proc` を使う)。失敗時は env `CLAUDE_SESSION_ID` を fallback。
// 両方無い場合は **fail-fast** で throw (random UUID で起動すると Hub 側の
// session_active が Phone に出るのに Claude 側の履歴 file と一致せず、相関 ID 伝播
// = AD-12 が破綻するため。Bridge は Claude Code の子としてしか正規起動されない)。

import { readFile } from "node:fs/promises";
import type { Logger } from "./log/StructuredLog.js";

export interface SessionDetectorOptions {
    /** デフォルト: process.ppid */
    parentPid?: number;
    /**
     * 明示的 env 値の上書き。指定された場合は `processEnv` を見ない。テストで env を
     * 直接固定したいときに使う。
     */
    envValue?: string | undefined;
    /**
     * env 検索対象 (default: `process.env`)。テストで env 全体を差し替えるための seam。
     * 検索順は `CLAUDE_CODE_SESSION_ID` (Claude Code 2.x 経路) → `CLAUDE_SESSION_ID`
     * (旧 POC / 互換)。
     */
    processEnv?: NodeJS.ProcessEnv;
    /** デフォルト: /proc/<pid>/cmdline を読む */
    readCmdline?: (pid: number) => Promise<string>;
    logger?: Logger;
}

const SESSION_ID_FLAG = "--session-id";

export class SessionDetector {
    constructor(private readonly opts: SessionDetectorOptions = {}) {}

    /**
     * 優先順位:
     *  1. /proc/<ppid>/cmdline の `--session-id <uuid>` (wrapper script / 明示起動)
     *  2. env `CLAUDE_CODE_SESSION_ID` (Claude Code 2.x の default 経路)
     *  3. env `CLAUDE_SESSION_ID` (旧 POC / 古い Claude CLI 互換)
     *  4. throw (Bridge が誤って単独起動された徴候)
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

        // Claude Code 2.x 経路: claude が child の env に CLAUDE_CODE_SESSION_ID を入れる。
        // 旧 CLAUDE_SESSION_ID は POC 互換のため fallback で見る。
        const env = this.opts.processEnv ?? process.env;
        const envVal =
            this.opts.envValue ??
            env.CLAUDE_CODE_SESSION_ID ??
            env.CLAUDE_SESSION_ID;
        if (envVal && envVal.length > 0) {
            this.opts.logger?.info("session_detected", { source: "env", session_id: envVal });
            return envVal;
        }

        this.opts.logger?.error("session_id_unavailable", { ppid });
        throw new Error(
            `session_id not found: neither --session-id in /proc/${ppid}/cmdline nor ` +
                "CLAUDE_CODE_SESSION_ID / CLAUDE_SESSION_ID env. " +
                "Bridge must be launched as a Claude Code child process.",
        );
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
