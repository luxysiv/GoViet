# Kế hoạch: gõ không gạch chân (spanless preedit) — GoViet IME

## 1. Mục tiêu
- Không tạo composing span/underline từ IME (`setComposingText`, `setComposingRegion`, `finishComposingText`) trong luồng preedit.
- Preedit vẫn cho phép engine Telex biến đổi, sửa, backspace, adopt, macro như composing thật.
- Hoạt động ổn định trên nhiều loại editor: app native, WebView/Chrome, terminal/field transient, custom InputConnection.

## 2. Cơ chế hiện tại (baseline)

### 2.1 Mô hình spanless
- IME ghi text bằng `commitText`/`replaceText`/`deleteSurroundingText` (hoặc key-event khi cần), tự theo dõi:
  - `lastSetComposingText`: display đã giao cho editor.
  - `composingStartInEditor`: điểm đầu managed range (-1 nếu chưa/không quản lý).
  - `composingCursorIndex` (raw) + `displayCursorIndex()`: caret logic trong preedit.
  - `expectedEditorCaret`: cache caret kỳ vọng sau ghi để nhận diện echo.
- `writePreedit(...)` là đường ghi trung tâm:
  1. **Fast-path**: mở rộng thuần ở cuối → `commitText(tail)` (an toàn cho cả editor giữ text và transient).
  2. **Rewrite có range**: `replaceText` nguyên tử + xác minh delivery bằng text thực (`checkDelivery`).
  3. **Fallback**: `diffRewrite` (xóa suffix thay đổi + chèn tail) với cơ chế xóa theo `DeliveryMode`.

### 2.2 DeliveryMode
- `UNKNOWN | RETAINS | TRANSIENT`, suy ra từ quan sát text (không tin return value).
- Quyết định cơ chế xóa (`deleteSurroundingText` vs DEL key events), hành vi scan/adopt/selection trên field transient.
- Reset mỗi field (`resetDeliveryMode()`), sống qua commit trong cùng field.

### 2.3 Selection reconciliation
- `reconcileSelection(...) -> SelectionVerdict` (NO_INFO/KEEP_PREEDIT/USER_MOVE/USER_SELECTION) dựa trên hình học range + trạng thái composing.
- Echo fast-path: bỏ qua read/adoption khi callback khớp `expectedEditorCaret` (tối ưu IPC, đánh đổi phụ thuộc giả định).

## 3. Vấn đề đã xác định

| Nhóm | Mô tả | Mức độ |
|---|---|---|
| Verify delivery mơ hồ | `getTextBeforeCursor` null/unreadable từng bị coi như “missing” → có thể replay rewrite lên text đã đúng (corrupt/nhân/lệch) | Cao (đã vá phần chính) |
| Echo-cache rò rỉ giữa field | `expectedEditorCaret` không reset khi đổi field → skip adoption/scan sai | Cao (đã vá) |
| Editor capability không đồng nhất | replaceText ignore-but-success, caret/text không đọc được, transient clear, callback trễ/lặp | Cao (cần hardening liên tục) |
| Mất range tracking | `composingStartInEditor == -1` làm giảm khả năng nhận diện echo/range, tăng fallback | Trung bình |
| Heuristic echo | Skip adoption khi khớp expected có thể giữ sai nếu giả định lệch | Trung bình |
| Thiếu quan sát/test ma trận | Khó chẩn đoán theo môi trường cụ thể (WebView, terminal web, app chat) | Trung bình |

## 4. Cải tiến đã thực hiện

- [x] **Reset caret echo-cache khi đổi field**: `resetDeliveryMode()` đặt `expectedEditorCaret = -1`.
- [x] **DeliveryCheck 3 trạng thái** `LANDED / MISSING / UNREADABLE` (`checkDelivery`).
- [x] **Không diffRewrite khi replaceText success nhưng unreadable** trong `writePreedit` (coi như landed, tránh corrupt) và trong macro commit; giữ mode có thể re-probe khi chưa đủ bằng chứng.
- [x] **Suffix delete UNKNOWN + UNREADABLE an toàn, không khóa TRANSIENT**: dùng fallback key-event tương thích nhưng không “kết án” mode để lần ghi đọc được sau có thể xác lập đúng.
- [x] **Docs kế hoạch** (file này) và bổ sung test robustness spanless.

## 5. Cải tiến tiếp theo (backlog có ưu tiên)

### 5.1 Độ đúng delivery & tự phục hồi
- [ ] Re-probe chủ động trong nhiều ngữ cảnh hơn (khi tích lũy tín hiệu đọc được ổn định) để hội tụ nhanh về `RETAINS/TRANSIENT` đúng.
- [ ] Mở rộng chiến lược an toàn cho các nhánh còn phụ thuộc verify/read khó (giữ đúng text, tránh khóa sai).

### 5.2 Selection/echo robustness
- [ ] Echo fast-path thích nghi: hạ cấp live-read/adoption khi phát hiện tín hiệu caret/callback không ổn định hoặc thiếu độ tin cậy.
- [ ] Rà soát toàn bộ điểm chuyển session/field/mode để reset state nguyên tử (range, expected caret, composing snapshot) khi cần.

### 5.3 Khả năng quan sát & kiểm thử
- [ ] Bổ sung test ma trận editor: replaceText ignored + unreadable kết hợp, callback trễ/lặp/mất thứ tự, IC giới hạn API, tap/adopt phức tạp.
- [ ] Thêm logging/debug có điều kiện (mode/check/verdict/nhánh ghi) để chẩn đoán môi trường thực tế (WebView, terminal web, app chat).

### 5.4 Cải tiến kiến trúc (gia tăng)
- [ ] Trích xuất “editor session/capability profile” per-field (khả năng đọc caret/text, độ tin cậy verify, hành vi transient) để tham số hóa chiến lược spanless.
- [ ] Tiếp tục gom primitive ghi/transaction để dễ bảo toàn bất biến.

## 6. Nguyên tắc thiết kế
- **Đúng text > atomic/mượt** khi không đủ thông tin.
- **Không tin return value**, chỉ tin trạng thái đọc được; phân biệt rõ “không biết” và “biết là sai”.
- **Spanless by default**: mọi cải tiến giữ mục tiêu không gạch chân.
- Thay đổi phải có test bảo vệ regression (đặc biệt terminal transient và WebView-like behaviors).

## 7. Cách xác minh
- Unit tests: `./gradlew testDebugUnitTest`
- Tập trung: `TransientEditorDeliveryTest`, `EditorCaretCacheTest`, `SelectionCallbackTest`, `SelectionProtocolTest`, và các test mới bổ sung.
- Smoke thủ công: gõ TV (dấu/fold/backspace), tap giữa từ, terminal web/ô nhập WebView, chuyển field/app.
