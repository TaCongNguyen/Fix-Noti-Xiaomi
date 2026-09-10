# 🚀 Fix-Noti-Xiaomi

**Fix-Noti-Xiaomi** là ứng dụng Android chuyên dụng giúp khắc phục triệt để tình trạng **chậm / trễ thông báo (Notification Delay)** trên các dòng thiết bị Xiaomi, Redmi và POCO (đặc biệt đối với các bản ROM nội địa Trung Quốc - China ROM, MIUI và HyperOS).

Ứng dụng sử dụng **[Shizuku](https://shizuku.rikka.app/)** để tương tác trực tiếp với các dịch vụ hệ thống Android cấp thấp, giúp tối ưu hóa ứng dụng **KHÔNG CẦN ROOT** thiết bị hay mở khóa Bootloader.

> ### 🙏 Ghi công
>
> Dự án này là bản fork từ **[optimus0701/Fix-Noti-Xiaomi](https://github.com/optimus0701/Fix-Noti-Xiaomi)**.
> Toàn bộ ý tưởng, kiến trúc và bản dựng gốc (tới `v1.0.1`) là công sức của **[@optimus0701](https://github.com/optimus0701)** — xin chân thành cảm ơn tác giả đã tạo ra và chia sẻ công cụ này.
>
> Fork này bổ sung các sửa lỗi và cải tiến sau khi kiểm thử thực tế trên HyperOS 3 / Android 16.

---

## ✨ Tính năng

- ⚡ **Sửa trễ thông báo 1-Click**: Tối ưu hóa hàng loạt nhiều ứng dụng cùng lúc (Zalo, Facebook Messenger, Telegram, WhatsApp, Instagram, các ứng dụng Ngân hàng & Ví điện tử...).
- 🛡️ **Tối ưu hóa đa tầng qua Shizuku**:
  - **DeviceIdle Whitelist**: Đưa ứng dụng vào danh sách bỏ qua cơ chế tiết kiệm pin sâu (Doze Mode).
  - **Standby Bucket**: Đặt trạng thái ứng dụng về `ACTIVE` (mức ưu tiên tài nguyên cao nhất của Android).
  - **AppOps Background**: Bật quyền chạy ngầm `RUN_IN_BACKGROUND` và `RUN_ANY_IN_BACKGROUND`.
  - **Manage if unused**: Tắt cơ chế tự thu hồi quyền khi lâu không dùng.
  - **MIUI/HyperOS System Whitelists**: Thêm ứng dụng vào các bảng danh sách trắng của hệ thống Xiaomi (`millet_white`, `cloud_lowlatency_whitelist`, `MILLET_NO_RESTRICT_APP`) — chỉ với những bảng ROM thực sự có.
- 🔍 **Quản lý & Kiểm tra chi tiết**: Hiển thị trạng thái từng chỉ số tối ưu hóa cho mỗi ứng dụng, hỗ trợ bật/tắt hoặc khôi phục quyền thủ công.
- 🌐 **Cập nhật danh sách ứng dụng đề xuất qua CDN**: Tải tự động danh sách các ứng dụng phổ biến với cơ chế fallback và bộ nhớ đệm.
- 🎨 **Giao diện hiện đại**: Jetpack Compose + Material Design 3, hỗ trợ **Dark Mode** theo hệ thống.

---

## 🆕 Cải tiến trong bản fork này

### Sửa lỗi làm hỏng dữ liệu hệ thống

- **Không còn ghi chuỗi lỗi vào bảng hệ thống.** Trước đây khi một lệnh shell thất bại, thông báo lỗi được coi là dữ liệu hợp lệ, bị tách thành "tên package" rồi ghi ngược vào `settings system` — làm hỏng `millet_white` và `MILLET_NO_RESTRICT_APP`, thậm chí **tạo mới** key mà ROM vốn không có. Nay việc đọc bắt buộc phải `exit 0`, mọi token phải khớp regex tên package, và đọc hỏng thì **không ghi gì cả**.
- **Sửa lỗi `Process.waitFor(timeout, TimeUnit)`**: bản có timeout là cài đặt mặc định của `java.lang.Process`, hoạt động bằng cách hỏi `exitValue()` liên tục — `ShizukuRemoteProcess` không hợp với cách đó nên báo hoàn tất quá sớm rồi ném `IllegalThreadStateException`, khiến **mọi lệnh trả về rỗng**. Phải dùng `waitFor()` thuần.
- **Giữ đúng dấu phân cách của từng ROM**: trên HyperOS 3, `MILLET_NO_RESTRICT_APP` dùng `", "` còn `cloud_lowlatency_whitelist` dùng `","`. Ghi sai có nguy cơ làm hệ thống đọc hỏng cả danh sách.

### Sửa lỗi báo sai trạng thái

- **So khớp whitelist chính xác**: trước đây dò chuỗi con nên `com.foo` bị coi là đã whitelist khi trong danh sách chỉ có `com.foo.bar`.
- **Standby Bucket**: `contains("5")` khiến `RESTRICTED (45)` cũng hiện xanh.
- **Đọc AppOps đúng định dạng thật**: ưu tiên dòng của riêng package thay vì `Default mode:`, cắt đuôi `; time=...`, xử lý `MIUIOP(10008)` và `No operations.`.
- **`UNKNOWN` không còn được tính là đạt** — trước đây app báo xanh cả khi chưa đọc được gì.

### Hiệu năng

Kiểm tra một ứng dụng từng tốn **9 lần spawn process** qua binder; tối ưu 200 app nghĩa là hơn 3000 lần. Nay:

- `dumpsys deviceidle whitelist` và 3 bảng `settings` đọc **một lần** cho cả đợt, tra trong bộ nhớ
- Các lệnh còn lại gộp vào **một** process shell duy nhất
- Bảng MIUI được ghi **một lần** cho toàn danh sách thay vì đọc–sửa–ghi từng app

→ Tối ưu 200 app: từ ~3000 lần spawn xuống còn ~205.

### Minh bạch & trải nghiệm

- **Lệnh thất bại được báo cáo thật**: đọc cả `stderr` lẫn exit code, lệnh lỗi hiện **dòng đỏ** trong nhật ký kèm dòng tổng kết đếm số lệnh thất bại. Bản cũ nuốt mọi lỗi và luôn báo thành công.
- **Tự cấp lại AppOp `WRITE_SETTINGS` cho `com.android.shell`**: HyperOS thu hồi quyền này khiến mọi lệnh `settings put system` fail với `SecurityException`. App tự cấp lại trước khi ghi.
- **Thấy được ứng dụng MIUI giấu**: lấy danh sách từ `pm list packages -3` qua Shizuku thay vì `PackageManager` (thứ MIUI hay chặn), và gắn nhãn cho package không lấy được tên/icon.
- **Chỉ hỏi quyền Shizuku một lần mỗi phiên** thay vì mỗi lần quay lại màn hình.
- **Dark Mode thật**, adaptive launcher icon (có lớp monochrome cho themed icon Android 13+), `--user 0` nhất quán cho mọi lệnh `appops`.

---

## ⚠️ Hai mục app KHÔNG tự bật được

Có hai thứ app chỉ **đọc và nhắc**, bạn phải tự bật trong Cài đặt:

### 1. Quyền thông báo

Nếu quyền thông báo của một app bị tắt thì mọi tối ưu chạy ngầm đều vô nghĩa — app có chạy cũng không được phép hiện thông báo. App hiển thị trạng thái này ở mục 1 trong màn hình chi tiết, kèm nút mở thẳng trang Thông báo của app đó.

Trạng thái được đọc từ `importance` trong `dumpsys notification`, **không** phải từ AppOp `POST_NOTIFICATION`. Đo trên HyperOS 3: app bị chặn (`com.twitter.android`) và app bình thường (`com.shopee.vn`) trả về AppOp **y hệt nhau** (`Uid mode: ignore` + `POST_NOTIFICATION: allow`), nên op đó không phân biệt được. Ngoài ra `appops set POST_NOTIFICATION` cũng không có tác dụng vì op này suy ra từ runtime permission.

> **Cài đặt → Ứng dụng → (tên app) → Thông báo**

### 2. Tự khởi động (Autostart)

**App KHÔNG bật được mục này thay bạn — bắt buộc phải bật thủ công.**

Lệnh `appops set <package> 10008 allow` chạy trót lọt và đọc lại đúng là `allow`, nhưng **MIUI không thực sự áp dụng** — Security Center mới là nơi thực thi Autostart. Việc ghi vào AppOp đó chỉ khiến chỉ báo hiện xanh trong khi tính năng vẫn tắt, che mất đúng thứ bạn cần biết.

Vì vậy app **chỉ đọc** giá trị này làm chỉ báo, và nhắc bạn bật tay tại:

> **Bảo mật → Quyền → Tự khởi động** *(Security → Permissions → Autostart)*

Bấm nút **"Bật tay"** ở mục 6 trong màn hình chi tiết để mở thẳng tới đó.

---

## 📋 Yêu cầu hệ thống

- Thiết bị: Xiaomi / Redmi / POCO chạy MIUI 12+ hoặc HyperOS (Android 7.0 trở lên).
- Ứng dụng **[Shizuku](https://shizuku.rikka.app/)** đã cài và đang **Đang chạy (Running)**.

### Nếu Shizuku báo "Quyền của adb bị hạn chế"

Xiaomi thu hồi bớt quyền của user `shell` (`WRITE_SECURE_SETTINGS`, `INJECT_EVENTS`), khiến Shizuku **từ chối uỷ quyền** cho mọi app. Khắc phục:

> **Tùy chọn nhà phát triển → bật "Gỡ lỗi USB (Cài đặt bảo mật)"**
> *(USB debugging (Security settings) — khác với "Gỡ lỗi USB" thường)*

Mục này yêu cầu đã **đăng nhập tài khoản Mi** và có **SIM** trong máy tại thời điểm bật. Bật xong có thể rút SIM.

---

## 📱 Hướng dẫn sử dụng

1. Cài **Shizuku** từ Google Play hoặc GitHub.
2. Kích hoạt Shizuku qua **Gỡ lỗi không dây** (Android 11+, không cần máy tính) hoặc qua ADB từ máy tính.
3. Mở **Fix-Noti-Xiaomi** và cấp quyền Shizuku khi được hỏi.
4. Tích chọn ứng dụng cần sửa rồi bấm **"FIX THÔNG BÁO"**. Đừng thoát app cho tới khi xong.
5. Đọc nhật ký: 🟢 xanh = thành công, 🟠 cam = đang chạy, 🔴 **đỏ = lệnh thất bại**.
6. Làm nốt hai bước thủ công mà app không tự bật được: **quyền thông báo** và **Tự khởi động** (xem mục cảnh báo ở trên). Nhật ký cuối đợt fix sẽ liệt kê app nào còn thiếu.

> 💡 **Đừng tối ưu tất cả ứng dụng.** Đưa mọi app vào whitelist chống Doze sẽ làm **tụt pin rõ rệt** — Doze tồn tại là có lý do. Chỉ nên chọn những app thật sự cần thông báo tức thì (nhắn tin, ngân hàng, gọi xe), khoảng 10–20 app là hợp lý.

> ℹ️ Shizuku **tự tắt sau mỗi lần khởi động lại máy** — đó là bản chất của nó. Nhưng các tối ưu đã ghi thì **vẫn còn**, vì chúng nằm trong hệ thống Android chứ không phải trong app. Chỉ cần bật lại Shizuku khi muốn sửa thêm app mới.

---

## 🛠️ Nguyên lý hoạt động

Trễ thông báo trên ROM Xiaomi chủ yếu do 3 nguyên nhân:

1. **Doze Mode & Standby Bucket**: Hệ thống hạ cấp ứng dụng xuống nhóm hạn chế (`RARE` / `RESTRICTED`) khiến ứng dụng không nhận được tin nhắn FCM/GCM tức thì.
2. **Hạn chế AppOps**: Xiaomi chặn quyền chạy ngầm của các ứng dụng bên thứ 3.
3. **Millet (Xiaomi Power Management)**: Bộ đóng băng ứng dụng nền của MIUI/HyperOS tự động "đóng băng" ứng dụng sau vài phút tắt màn hình.

Ứng dụng gửi lệnh quản trị trực tiếp qua Shizuku Service để đưa ứng dụng vào danh sách ưu tiên của hệ thống.

---

## 🔨 Build từ mã nguồn

Cần **JDK 17** (Gradle 8.7 không hỗ trợ JDK 22+) và **Android SDK 34**.

```bash
./gradlew assembleRelease
```

Tạo `local.properties` ở thư mục gốc trỏ tới Android SDK (dùng dấu `/`, tránh escape sai):

```
sdk.dir=C:/Users/<ten>/AppData/Local/Android/Sdk
```

Muốn ký bản release bằng key riêng thì copy `keystore.properties.example` thành `keystore.properties` và điền thông tin. Không có file này thì bản release tự ký bằng debug key.

---

## 📄 Giấy phép (License)

Kế thừa giấy phép từ dự án gốc [optimus0701/Fix-Noti-Xiaomi](https://github.com/optimus0701/Fix-Noti-Xiaomi).
