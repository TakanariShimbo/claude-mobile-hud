// Bridge の session_id を確定する。
//
// 設計判断 (AD-12 / Phase 3 §6.2.3): session_id の **single source of truth は wrapper**
// (`claude-mobile-hud run`)。wrapper は uuidgen で生成した UUID を:
//   1. claude には `--session-id <uuid>` で渡す (claude 履歴 slug と一致させる)
//   2. Bridge には `.mcp.runtime.json` の `env.BRIDGE_SESSION_ID` で直接渡す
// この双方向 inject によって Bridge は env を 1 つ読むだけで claude と同じ session_id を
// 得られる。POC では bridge 起動時に `/proc/<ppid>/cmdline` を 10 hop 親方向に登って
// claude 祖先を探していたが、wrapper 側で env templating ができる構成なら不要 (Linux 限定,
// claude の spawn topology 変更で壊れる brittle さ, etc を全部回避)。
//
// 失敗時は **fail-fast** で throw。random UUID で起動して claude の履歴 file と
// Hub の session_active が不一致になる AD-12 違反を絶対に許さない。

import type { Logger } from "./log/StructuredLog.js";

const ENV_VAR = "BRIDGE_SESSION_ID";
const SESSION_ID_RE =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface SessionDetectorOptions {
    /** env 検索対象 (default: `process.env`)。テスト用 seam。 */
    processEnv?: NodeJS.ProcessEnv;
    logger?: Logger;
}

export class SessionDetector {
    constructor(private readonly opts: SessionDetectorOptions = {}) {}

    async detect(): Promise<string> {
        const env = this.opts.processEnv ?? process.env;
        const raw = env[ENV_VAR];
        if (!raw || raw.length === 0) {
            this.opts.logger?.error("session_id_env_missing", { env_var: ENV_VAR });
            throw new Error(
                `${ENV_VAR} env var not set. Bridge must be launched via ` +
                    "`claude-mobile-hud run safe|yolo` (the wrapper injects this env via " +
                    "`.mcp.runtime.json`).",
            );
        }
        if (!SESSION_ID_RE.test(raw)) {
            this.opts.logger?.error("session_id_invalid", { env_var: ENV_VAR, value: raw });
            throw new Error(
                `${ENV_VAR}=${raw} is not a valid UUID. wrapper bug — refusing to ` +
                    "register a malformed session_id with Hub (would break AD-12 correlation).",
            );
        }
        this.opts.logger?.info("session_detected", {
            source: "env",
            env_var: ENV_VAR,
            session_id: raw,
        });
        return raw;
    }
}
