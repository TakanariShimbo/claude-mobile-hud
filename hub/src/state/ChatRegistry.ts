// Phone → Hub の `send` で発行する chat_id を採番。
// Hub 内 in-memory。Hub 再起動でカウンタはリセットされるが、Phone は新しい chat_id を受けるだけ。

export class ChatRegistry {
    private nextSeq = 1;
    private readonly prefix: string;

    constructor(prefix: string = "chat") {
        this.prefix = prefix;
    }

    mint(): string {
        const id = `${this.prefix}-${this.nextSeq.toString().padStart(6, "0")}`;
        this.nextSeq += 1;
        return id;
    }
}
