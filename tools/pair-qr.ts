// QR コード生成 helper (Phase 4 で実装)。
// `claude-mobile-hud pair lan` 等から呼ばれる想定。Phase 1 FR-PH-08 (QR version 互換) の v=1 payload を出力する。

import { argv } from "node:process";

function main(): void {
    const args = argv.slice(2);
    console.error("[pair-qr] not yet implemented (Phase 4)");
    console.error(`[pair-qr] args: ${JSON.stringify(args)}`);
    process.exit(0);
}

main();
