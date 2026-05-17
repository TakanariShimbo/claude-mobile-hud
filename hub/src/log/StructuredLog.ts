// docs/03 §8.3 / §8.5: 構造化ログ key=value 形式。stderr 専有 (§8.5.1)。

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
        // docs/03 §8.5.2: sink seam (テスト用 capture)。
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

    /** docs/03 §8.5.3: dot 区切り階層タグの cascade。 */
    withTag(suffix: string): StructuredLog {
        return new StructuredLog(`${this.tag}.${suffix}`, this.minLevel, this.sink);
    }
}

// docs/03 §8.5.4: 空白 / `"` / `=` / `\` / 制御文字を含むときだけ quote。
const NEEDS_QUOTE = /[\s"=\\\x00-\x1f\x7f]/;

// docs/03 §8.5.5: null / undefined は文字列として区別 (空文字に潰さない)。
function formatValue(v: unknown): string {
    if (v === null) return "null";
    if (v === undefined) return "undefined";
    if (typeof v === "number" || typeof v === "boolean") return String(v);
    const s = typeof v === "string" ? v : JSON.stringify(v);
    return NEEDS_QUOTE.test(s) ? JSON.stringify(s) : s;
}
