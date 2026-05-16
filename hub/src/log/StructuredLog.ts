// 構造化ログ。Phase 3 §8.3 の key=value 形式。
// stderr に書く (stdout は将来の IPC 拡張のため空けておく)。

export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR";

export interface Logger {
    debug(event: string, fields?: Record<string, unknown>): void;
    info(event: string, fields?: Record<string, unknown>): void;
    warn(event: string, fields?: Record<string, unknown>): void;
    error(event: string, fields?: Record<string, unknown>): void;
}

const LEVELS: Record<LogLevel, number> = { DEBUG: 10, INFO: 20, WARN: 30, ERROR: 40 };

export class StructuredLog implements Logger {
    constructor(
        private readonly tag: string,
        private readonly minLevel: LogLevel = "INFO",
        private readonly sink: (line: string) => void = (line) => process.stderr.write(line + "\n"),
    ) {}

    private emit(level: LogLevel, event: string, fields?: Record<string, unknown>): void {
        if (LEVELS[level] < LEVELS[this.minLevel]) return;
        const ts = new Date().toISOString();
        const parts = [`ts=${ts}`, `level=${level}`, `tag=${this.tag}`, `event=${event}`];
        if (fields) {
            for (const [k, v] of Object.entries(fields)) {
                parts.push(`${k}=${formatValue(v)}`);
            }
        }
        this.sink(parts.join(" "));
    }

    debug(event: string, fields?: Record<string, unknown>): void {
        this.emit("DEBUG", event, fields);
    }
    info(event: string, fields?: Record<string, unknown>): void {
        this.emit("INFO", event, fields);
    }
    warn(event: string, fields?: Record<string, unknown>): void {
        this.emit("WARN", event, fields);
    }
    error(event: string, fields?: Record<string, unknown>): void {
        this.emit("ERROR", event, fields);
    }

    withTag(suffix: string): StructuredLog {
        return new StructuredLog(`${this.tag}.${suffix}`, this.minLevel, this.sink);
    }
}

// 空白 / quote / `=` / `\` / 制御文字 (0x00-0x1F, 0x7F) のいずれかを含むなら quote する。
const NEEDS_QUOTE = /[\s"=\\\x00-\x1f\x7f]/;

/**
 * key=value のための値整形。
 * - null / undefined はそれぞれ "null" / "undefined" (空文字と区別する。Phase 3 §8.3 の例参照)。
 * - 数値・boolean はそのまま。
 * - string は空白・`=`・`\`・改行・制御文字・引用符を含む場合 `JSON.stringify` で完全 escape。
 *   1 行 1 イベント (Phase 3 §8.3) を壊さない。
 */
function formatValue(v: unknown): string {
    if (v === null) return "null";
    if (v === undefined) return "undefined";
    if (typeof v === "number" || typeof v === "boolean") return String(v);
    const s = typeof v === "string" ? v : JSON.stringify(v);
    return NEEDS_QUOTE.test(s) ? JSON.stringify(s) : s;
}
