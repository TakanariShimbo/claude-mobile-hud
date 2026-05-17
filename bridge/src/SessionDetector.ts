// docs/03 §6.2.3: env `BRIDGE_SESSION_ID` から session_id を確定 (AD-12)。
// docs/03 §6.2.3.1: fail-fast — random UUID fallback は AD-12 を黙って壊すため禁止。

import type { Logger } from "./log/StructuredLog.js";

const ENV_VAR = "BRIDGE_SESSION_ID";
const SESSION_ID_RE =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface SessionDetectorOptions {
    /** docs/03 §6.2.3.2: env 源の test seam (default `process.env`)。 */
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
