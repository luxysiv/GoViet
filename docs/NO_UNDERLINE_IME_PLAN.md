# Preedit: composing span chuẩn Android (thay cho spanless)

## Trạng thái hiện tại

Đường spanless đã bị gỡ bỏ. Với mọi field text thường, preedit được ghi bằng hợp đồng
chuẩn của Android:

- `setComposingText(display, 1)` cho mỗi lần engine đổi display.
- `setComposingRegion(start, end)` khi adopt một từ đã có trong editor mà không ghi lại chữ.
- `setSelection(start + caretInDisplay, ...)` khi con trỏ nằm giữa preedit.
- `finishComposingText()` khi commit/xoá/kết thúc session; span được đóng trước khi ghi dấu cách.

TYPE_NULL vẫn đi đường commit key-by-key (`isImmediateCommitMode()`); đây là quyết định theo
loại field, không phải fallback spanless.

## Vì sao bỏ spanless

Suy luận `DeliveryMode` bằng cách đọc lại text trong cùng `beginBatchEdit` không phân biệt
được ba trường hợp: write landed, write bị ignore, và editor publish state trễ (AI Studio,
Gemini, Google Docs, CodeMirror...). Một lần đọc trễ bị hiểu thành `MISSING` sẽ gán sai
`TRANSIENT`, sau đó mọi sửa dấu đi DEL key-event — đường mà các editor web này không hỗ trợ.
Với composing span, editor tự diff và tự quản lý range; IME không đọc lại, không đoán mode,
không có fallback xoá chồng.

## Bất biến cần giữ

- Engine vẫn là nguồn truth của raw buffer, caret logic (`composingCursorIndex`,
  `displayCursorIndex`, `rawIndexOfDisplay`) và Telex — không đổi.
- `lastSetComposingText` vẫn là display đã giao cho editor.
- `composingStartInEditor` là range đang thuộc về IME. Khi editor báo candidates range,
  range đó được ưu tiên trong `onUpdateSelection`.
- Echo của chính write (`newSelStart == expectedEditorCaret`) không được adopt lại.
- Tap vào giữ từ / dấu / backspace vẫn đi chung một đường xử lý như trước; chỉ primitive ghi
  xuống editor thay đổi.

## Kiểm thử

- `ComposingSpanDeliveryTest`: gõ `theem → thêm`, commit space, caret giữa từ, candidates
  echo, backspace trong preedit.
- `EditorCaretCacheTest`, `SelectionCallbackTest`, `SelectionProtocolTest`,
  `LiteralTailSplitTest`: giữ nguyên các bất biến con trỏ/selection/tail.

Chạy: `./gradlew testDebugUnitTest`.
