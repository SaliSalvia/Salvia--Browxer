# انتشار (Release) SalviaBrowxer از داخل Termux — راهنمای کامل

اسکریپت `salvia-termux-release.sh` کل فرآیند ساخت ریپو، پوش، ساخت کلید امضای شخصی و انتشار APK امضاشده را **یک‌جا و خودکار** انجام می‌دهد.

## اسکریپت چه کاری انجام می‌دهد؟

1. نصب خودکار ابزارهای لازم در Termux (`git`, `gh`, `openjdk-17` برای `keytool`, `unzip`)
2. ورود به GitHub (در صورت نیاز، تعاملی با کد یک‌بارمصرف)
3. ساخت ریپوی جدید به نام `SalviaBrowxer` (عمومی یا خصوصی — به انتخاب شما)
4. استخراج زیپ سورس، تنظیم خودکار `versionCode` / `versionName` بر اساس تگ ریلیز
5. ساخت اولین commit و پوش به `main`
6. **ساخت تعاملی کلید امضای شخصی (Keystore):** رمز، alias و مشخصات گواهی را خودتان در همان ترمینال وارد می‌کنید
7. ثبت کلید امضا به‌صورت **Secret** در ریپو:
   - `SIGNING_KEYSTORE_BASE64`
   - `SIGNING_KEYSTORE_PASSWORD`
   - `SIGNING_KEY_ALIAS`
   - `SIGNING_KEY_PASSWORD`
8. ساخت تگ (مثل `v1.0.0`) و GitHub Release
9. ساخت خودکار APK توسط GitHub Actions (`.github/workflows/build-apk.yml`) و **پیوست APK امضاشده به Release**
10. پایش زنده وضعیت build و در پایان، دانلود و اجرای نصب APK

## پیش‌نیازها

| نیاز | توضیح |
|---|---|
| Termux | فقط از **F-Droid** یا **GitHub Releases** نصب کنید — نسخه Play Store قدیمی است و `pkg` آن خراب است |
| حساب GitHub | با احراز هویت دو مرحله‌ای هم مشکلی نیست |
| زیپ پروژه | `SalviaBrowxer-final-v5.zip` |
| اسکریپت | `salvia-termux-release.sh` |

## اجرا

```bash
# ۱) دسترسی به حافظه گوشی (فقط بار اول، یک دیالوگ تایید می‌آید)
termux-setup-storage

# ۲) برو به جایی که دو فایل را دانلود کرده‌ای (مثلاً Downloads)
cd /sdcard/Download

# ۳) اجرا
bash salvia-termux-release.sh SalviaBrowxer-final-v5.zip
```

سپس به سؤالات اسکریپت جواب بدهید (مقدار پیش‌فرض داخل براکت است؛ با Enter تایید می‌شود):

- نام ریپو ← `SalviaBrowxer`
- عمومی/خصوصی ← `public` (دقیقه‌های Actions رایگان نامحدود) یا `private`
- نسخه ریلیز ← `v1.0.0`
- **alias کلید** ← مثل `salvia`
- **رمز keystore** ← حداقل ۶ کاراکتر؛ هنگام تایپ نمایش داده نمی‌شود (دوبار پرسیده می‌شود)
- مشخصات گواهی (CN/OU/O/City/State/Country) ← Enter برای مقادیر پیش‌فرض
- اعتبار کلید ← `10000` روز

## نتیجه

- ریپو: `https://github.com/<USERNAME>/SalviaBrowxer`
- APK امضاشده: `https://github.com/<USERNAME>/SalviaBrowxer/releases` ← `SalviaBrowxer-release.apk`
- فایل `SalviaBrowxer-debug.apk` هم به Release پیوست می‌شود (امضای debug).

## ریلیزهای بعدی (نسخه جدید)

```bash
bash salvia-termux-release.sh SalviaBrowxer-final-v5.zip
# نسخه را v1.0.1 (یا بالاتر) بده
```

اسکریپت همان ریپو را پیدا می‌کند، از **کلید قبلی** (ذخیره‌شده در `~/salvia-signing/`) استفاده می‌کند و فقط رمز آن را از شما می‌گیرد؛ سپس تگ جدید را پوش می‌کند و APK جدید با همان امضای قبلی ساخته می‌شود — یعنی نصب روی نسخه قدیمی بدون حذف انجام می‌شود.

## عیب‌یابی

| مشکل | راه‌حل |
|---|---|
| `refusing to allow ... to create or update workflow` هنگام پوش | اسکریپت خودش `gh auth refresh -s workflow` را اجرا می‌کند؛ کد جدید را در github.com/login/device وارد کنید |
| مرورگر برای ورود به GitHub باز نمی‌شود | آدرس `https://github.com/login/device` را دستی باز کنید و کد نمایش‌داده‌شده را وارد کنید |
| build شکست خورد | `gh run view --repo <USER>/SalviaBrowxer --log-failed` — لاگ کامل در Artifact صفحه run هم هست |
| رمز keystore را فراموش کردم | متأسفانه بازیابی وجود ندارد؛ باید کلید جدید بسازید (ولی آن‌وقت نصب روی نسخه قبلی ممکن نیست و باید اول اپ را حذف کنید) |
| APK «نصب نمی‌شود» روی نسخه قبلی | یا امضا عوض شده، یا `versionCode` جدید پایین‌تر است — همیشه نسخه بالاتر بدهید |
| `termux-setup-storage` خطا می‌دهد | Termux را از F-Droid نصب کنید، نه Play Store |

## نکات امنیتی مهم

- فایل keystore در `~/salvia-signing/salvia-release.jks` ذخیره می‌شود و **هرگز وارد Git نمی‌شود** (`.gitignore` جلوی `*.jks` را می‌گیرد).
- رمز keystore **هیچ‌جا ذخیره نمی‌شود** — فقط در Secretهای ریپو (رمزگذاری‌شده سمت GitHub) قرار می‌گیرد.
- از keystore پشتیبان بگیرید (اسکریپت پیشنهاد کپی به Downloads را می‌دهد) و آن را جای امن نگه دارید. **اگر کلید یا رمزش را گم کنید، هرگز نمی‌توانید آپدیت اپ را روی نصب‌های قبلی منتشر کنید.**
- این کلید مخصوص همین پروژه است؛ برای پروژه‌های دیگر کلید جدا بسازید.

## ساخت دستی (بدون اسکریپت)

```bash
pkg install git gh openjdk-17 unzip
gh auth login
mkdir -p ~/salvia-signing && cd ~/salvia-signing
keytool -genkeypair -v -keystore salvia-release.jks -storetype PKCS12 \
  -alias salvia -keyalg RSA -keysize 2048 -validity 10000
# repo + push
gh repo create SalviaBrowxer --public
base64 -w0 salvia-release.jks | gh secret set SIGNING_KEYSTORE_BASE64 --repo <USER>/SalviaBrowxer
gh secret set SIGNING_KEYSTORE_PASSWORD --repo <USER>/SalviaBrowxer   # رمز را تایپ کنید
gh secret set SIGNING_KEY_ALIAS --repo <USER>/SalviaBrowxer
gh secret set SIGNING_KEY_PASSWORD --repo <USER>/SalviaBrowxer
git tag v1.0.0 && git push origin v1.0.0   # ← APK امضاشده خودکار ساخته می‌شود
```
