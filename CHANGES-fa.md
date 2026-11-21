# تغییرات SalviaBrowxer

این فایل تاریخچهٔ تغییرات است و باید با کدِ درون مخزن بخواند. نسخه‌های قبلی این سند به ساختاری
اشاره می‌کردند که دیگر وجود ندارد (Room، ماژول‌های `:core:model` / `:media:resolver` /
`:media:detector`، اسکریپت تست `scripts/test-media-sniffer.cjs`، بیلد TypeScript/Vite و «۱۹ تست
واحد»). آن ادعاها در همین بازبینی اصلاح شدند؛ اعداد زیر از بیلد واقعی همان درخت کد آمده‌اند.

---

## نسخهٔ ۶ — گیت‌های CI برای تست واحد و بیلد R8، هم‌ترازی مستندات

### بیلد
- **خطای کامپایل رفع شد:** الگوی `catch (_: Exception)` که استثنا را به `SalviaLog.w(...)` پاس
  می‌داد در ۱۹ نقطه با `catch (e: Exception)` جایگزین شد؛ `SalviaLog` هم `buffer` واقعی
  (`ArrayDeque<Entry>`) گرفت تا ۸ خطای `Unresolved reference 'buffer'` از بین برود. ۱۸ خط لاگ هم
  داخل بلوک‌های `catch` بازتورفتگی شد.
- `gradle.properties` روی `-Xmx2g -XX:MaxMetaspaceSize=768m` تنظیم شد تا بیلد روی ماشین ۲ هسته‌ای
  با ۳ گیگ رم OOM نشود؛ `gradlew` بیت اجرایی گرفت؛ `app/debug.keystore` مشترک اضافه شد تا APKهای
  پشت‌سرهم CI روی هم نصب شوند.

### نتایج تأییدشده (JDK 17 + Android SDK 35)
- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**، ۲۶ تست در ۵ کلاس، ۰ failure / ۰ error / ۰ skipped.
- `:app:assembleDebug` → موفق، `app-debug.apk` = ۲۲٬۶۱۶٬۴۳۲ بایت.
- `:app:assembleRelease` با R8 و shrink منابع → موفق، `app-release.apk` = **۴٬۲۸۲٬۸۲۶ بایت**، یک
  `classes.dex`.
- `apksigner verify --print-certs` روی خروجی release: `CN=Android Debug, O=Android, C=US`،
  SHA-256 = `2ed50ed0691cee1eab86ce7e4922a7a3c383c32fb44469ed7cbbb810ec6f3136`.
- `mapping.txt` تأیید می‌کند کلاس `BrowserHost$DetectorBridge` و متد `report(java.lang.String,
  java.lang.String)` با نام اصلی حفظ شده‌اند (پل JS پس از کوچک‌سازی نمی‌شکند).

### CI (`.github/workflows/build-apk.yml`)
- چهار job: **تست واحد** → **release با R8** و **debug** (هر دو وابسته به تست) → **انتشار GitHub
  Release** فقط برای تگ‌های `v*`.
- release دیگر «advisory» نیست: نبود APK، خالی بودن `mapping.txt`، حذف‌شدن
  `BrowserHost$DetectorBridge` یا `report(` از mapping، یا رد شدن `apksigner verify` باعث **شکست**
  job می‌شود.
- `mapping.txt` همراه APK به‌عنوان آرتیفکت ذخیره می‌شود تا استک‌تریس نسخهٔ release قابل خواندن باشد؛
  گزارش تست (XML + HTML) و لاگ Gradle هم آپلود و خطاهای مهم به annotation تبدیل می‌شوند.

### مستندات
- `docs/agent-handoff.md` از صفر بر اساس کد واقعی بازنویسی شد: یک ماژول `:app`، نقشهٔ پکیج‌ها،
  مسیر واقعی یک دانلود، دستورهای بیلد، گیت‌های CI، و «چه چیزی تأیید نشده».
- همین فایل (`CHANGES-fa.md`) اصلاح شد: ادعاهای مربوط به دورهٔ وب حذف و اعداد بیلد با خروجی واقعی
  جایگزین شدند.
- `CONTRIBUTING.md` دیگر به اسکریپت تست Node که وجود ندارد ارجاع نمی‌دهد.

---

## نسخهٔ ۵ — خودآپدیتر، ثبت کرش بومی و دریافت لینک از اپ‌های دیگر

- **خودآپدیتر:** بررسی روزانه (حداکثر یک‌بار در ۲۴ ساعت، بی‌صدا) و بررسی دستی از تنظیمات، با مقایسهٔ
  عددی نسخهٔ سه‌بخشی، نادیده‌گرفتن draft/prerelease و آدرس `SalviaConstants.RELEASES_API_URL`.
  دانلود APK با همان موتور دانلود خود اپ انجام می‌شود و با زدن روی فایل در «دانلودها» نصب‌کنندهٔ
  اندروید باز می‌شود (مجوز `REQUEST_INSTALL_PACKAGES`).
- **`CrashReporter`:** هر کرش غیرگیرا (استک کامل + مدل دستگاه + نسخهٔ اندروید + نسخهٔ اپ) در
  `files/crash/` ذخیره می‌شود (حداکثر ۱۰ فایل) و از تنظیمات ← درباره با share sheet ارسال می‌شود.
  همه‌چیز روی دستگاه می‌ماند.
- **دریافت لینک:** فیلترهای `ACTION_SEND` و `ACTION_PROCESS_TEXT` اضافه شدند؛ اولین لینک متن
  استخراج و در تب باز می‌شود. لینکی که اپ را از حالت بسته باز می‌کند تا آماده‌شدن رابط نگه داشته
  می‌شود.

## نسخهٔ ۴ — اسکریپت ریلیز و مستندات

- `scripts/termux-sign.sh`: ساخت/پوش ریپو، کلید امضای PKCS12/RSA-2048، ثبت Secretها
  (`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
  `SIGNING_KEY_PASSWORD`)، تگ و Release با APK امضاشده.
- `docs/RELEASE-TERMUX-fa.md` (راهنمای فارسی + عیب‌یابی) و `docs/TEST-SITES.md` (چک‌لیست تست روی
  گوشی واقعی).

## نسخهٔ ۳ — مرورگر کامل، ازسرگیری دانلود، DASH و sidx

- **مرورگر بومی:** یک WebView به‌ازای هر تب با سقف ۸ تب زنده (بقیه با LRU آزاد می‌شوند)، تب خصوصی
  واقعی با `ProfileStore`، پاپ‌آپ/`target=_blank`، آپلود فایل، مجوز دوربین/میکروفون/موقعیت با
  حافظهٔ per-site، هشدار SSL با گزینهٔ ادامه، HTTP Basic Auth، دیالوگ‌های JS، چاپ/PDF، PiP و
  edge-to-edge اندروید ۱۵.
- **تشخیص مدیا:** `shouldInterceptRequest` برای همهٔ درخواست‌ها (شامل iframe پخش‌کننده‌ها)،
  تزریق اسکریپت در همهٔ فریم‌ها، هوک `MediaSource`/`addSourceBuffer`/`createObjectURL`، probe با
  Content-Type برای URLهای بدون پسوند (سقف ۱۲ در هر صفحه و یک‌بار برای هر URL)، ذخیرهٔ هدرهای
  درخواست برای CDNهای امضادار.
- **دانلود:** retry با backoff (بدون تکرار خطاهای دائمی 403/404)، ازسرگیری واقعی segmentها با
  tmp+rename، صف ماندگار در `download_queue.json` و ازسرگیری پس از ریبوت، کنترل فضای دیسک، خروجی در
  پوشهٔ انتخابی SAF، انتخاب خودکار کانتینر بر اساس کدک، سرویس foreground از نوع
  `dataSync|specialUse` با نوتیفیکیشن per-job، و توقف خودکار روی دادهٔ همراه.
- **DASH:** مانیفست ایستا و چندپریودی بدون DRM، `SegmentTemplate`/`SegmentList`/`SegmentBase` با
  پارسر `sidx`، و ترکیب ویدیو/صدا با `MediaMuxer`. **HLS:** `EXT-X-BYTERANGE`، `EXT-X-MAP` و
  رمزگشایی AES-128-CBC با کلید/IV. پخش زنده و DRM صریحاً رد می‌شوند.

## نسخهٔ ۲ — لایهٔ UI و منطق دامنه

- رابط Compose (Material 3) با تم روشن/تیره، دو زبان EN/FA با چیدمان RTL، هوم‌هاب با طبقه‌بندی
  لینک، بوکمارک/تاریخچه، تنظیمات کامل، پلیر داخلی Media3 و «مرکز دانلود».
- منطق خالص در `core/AppDomainLogic.kt` جدا شد تا بدون دستگاه هم تست شود: قواعد URL/فرمت، امتیازدهی
  کاندید اصلی (تبلیغ و ویدیوی «پیشنهادی» جریمه می‌شوند)، برنامه‌ریزی نام فایل، قواعد SAF و
  `LockoutPolicy`/`SecurityManager` برای پوشهٔ قفل‌دار (PBKDF2-HMAC-SHA256، ۱۲۰٬۰۰۰ تکرار).

## نسخهٔ ۱ — پیاده‌سازی اولیهٔ اندروید

- اسکلت اپ Kotlin/Compose، `MainActivity` + `SalviaApplication`، نگه‌داری وضعیت در JSON،
  `SalviaLog` برای لاگ درون‌حافظه‌ای، و اولین نسخهٔ تشخیص/دانلود مدیا.

---

## وضعیت فعلی در یک نگاه

| مورد | مقدار |
|---|---|
| ماژول‌ها | فقط `:app` |
| AGP / Kotlin / Gradle | 8.7.3 / 2.0.21 / 8.11.1 |
| کمینهٔ SDK | 26 (target/compile 35) |
| نسخه | `1.0.0` / `versionCode 10000` |
| کد | ۵۱ فایل Kotlin در `main` + ۵ فایل تست، ~۱۸٬۶۰۰ خط |
| تست | ۵ کلاس، ۲۶ مورد (JVM؛ تست ابزارداری وجود ندارد) |
| APK debug / release | ~۲۲٫۶ مگابایت / ~۴٫۲۸ مگابایت |
| امضای release | کلید debug مشترک، مگر اینکه Secretهای امضا تنظیم شوند |

**آنچه تأیید نشده:** هیچ‌کدام از رفتارهای وابسته به دستگاه (لایهٔ لمس/WebView شفاف، پخش
فول‌اسکرین و PiP، خروجی MediaStore/SAF، کدک‌ها، اکشن‌های نوتیفیکیشن) به‌صورت خودکار یا روی گوشی
واقعی آزموده نشده است؛ `docs/TEST-SITES.md` برای همین است. نسخهٔ release با کلید debug هم برای
Play Store مناسب نیست.
