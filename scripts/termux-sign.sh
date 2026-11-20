#!/usr/bin/env bash
# =============================================================================
# SalviaBrowxer — ساخت کلید امضای شخصی و ثبت آن در GitHub Secrets (Termux)
#
# این اسکریپت:
#   1) ابزارهای لازم Termux را نصب می‌کند (openjdk-17، gh)
#   2) یک keystore اختصاصی RSA-4096 با اعتبار ۳۰ سال می‌سازد (PKCS12)
#   3) آن را Base64 می‌کند
#   4) با GitHub CLI چهار Secret ریپو را ثبت می‌کند:
#        SIGNING_KEYSTORE_BASE64 / SIGNING_KEYSTORE_PASSWORD
#        SIGNING_KEY_ALIAS        / SIGNING_KEY_PASSWORD
#   5) از push یا tag بعدی (مثلاً v1.0.1) ورک‌فلو Build APK همان را می‌خواند و
#      APK امضاشده می‌سازد.
#
# اجرا (پس از ورود به ریپو یا داشتن نسخه‌ی دانلودی آن):
#     cd Salvia--Browxer
#     bash scripts/termux-sign.sh
# =============================================================================
set -euo pipefail

KEYSTORE="$HOME/salviabrowxer-release.keystore"
ALIAS="salviabrowxer"
B64="$HOME/keystore.b64"

banner() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

banner "0/5 ابزارها (Termux)"
if command -v pkg >/dev/null 2>&1; then
  if ! command -v keytool >/dev/null 2>&1; then
    echo "keytool not found → installing openjdk-17 …"
    pkg install -y openjdk-17
  fi
fi
command -v keytool >/dev/null 2>&1 || { echo "[!] keytool پیدا نشد؛ دستی نصب کنید: pkg install -y openjdk-17"; exit 1; }

banner "1/5 ساخت keystore (اگر از قبل ندارید)"
if [ -f "$KEYSTORE" ]; then
  echo "[$KEYSTORE] از قبل موجود است — از همان استفاده می‌کنیم."
  echo "(برای ساخت کلید تازه، ابتدا فایل را پشتیبان‌گیری و حذف کنید.)"
else
  echo "RSA-4096، اعتبار ۳۰ سال، فرمت PKCS12."
  echo "یک رمز قوی انتخاب کنید و آن را در جای امن یادداشت کنید (دو بار پرسیده می‌شود)."
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 \
    -validity 10950 \
    -storetype PKCS12
fi
ls -l "$KEYSTORE"

banner "2/5 کدگذاری Base64"
base64 -w0 "$KEYSTORE" > "$B64"
echo "نوشته شد: $B64 ($(wc -c < "$B64") کاراکتر)"

banner "3/5 GitHub CLI"
if ! command -v gh >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    pkg install -y gh
  fi
fi
if ! command -v gh >/dev/null 2>&1; then
  echo "[!] gh پیدا نشد؛ دستی نصب کنید: pkg install -y gh"
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "ابتدا باید وارد GitHub شوید. دستور:"
  echo "    gh auth login"
  echo "گزینه‌ها: GitHub.com → HTTPS → Login with a web browser"
  echo "پس از ورود، دوباره همین اسکریپت را اجرا کنید."
  exit 0
fi

# مطمئن می‌شویم که Secrets در ریپوی درست ثبت می‌شوند.
REPO_SLUG="${GH_REPO:-}"
if [ -z "$REPO_SLUG" ]; then
  REPO_SLUG=$(git remote get-url origin 2>/dev/null | sed -E 's#.*github.com[:/]##; s#\.git$##' || true)
fi
if [ -n "$REPO_SLUG" ]; then
  export GH_REPO="$REPO_SLUG"
  echo "ثبت Secrets در ریپو: $GH_REPO"
else
  echo "[!] ریپوی مقصد مشخص نشد؛ gh از ریپوی فعلی استفاده می‌کند."
  echo "    اگر در ریشه‌ی ریپو نیستید، متغیر GH_REPO را ست کنید:  GH_REPO=SaliSalvia/Salvia--Browxer bash scripts/termux-sign.sh"
fi

banner "4/5 ثبت Secrets در ریپو"
gh secret set SIGNING_KEYSTORE_BASE64 < "$B64"
echo "رمز keystore را وارد کنید (برای SIGNING_KEYSTORE_PASSWORD و SIGNING_KEY_PASSWORD):"
gh secret set SIGNING_KEYSTORE_PASSWORD
gh secret set SIGNING_KEY_ALIAS --body "$ALIAS"
gh secret set SIGNING_KEY_PASSWORD

banner "5/5 تمام شد ✔"
echo "از push بعدی، ورک‌فلو Build APK علاوه بر debug، این را هم می‌سازد:"
echo "    SalviaBrowxer-release.apk   ← امضاشده با کلید شخصی شما"
echo
echo "برای انتشار عمومی، یک tag بزنید تا Release ساخته شود:"
echo "    git tag v1.0.1 && git push origin v1.0.1"
echo
echo "یادآوری: keystore و رمز را در جای امن نگه دارید (هرگز commit نشود —"
echo ".gitignore الگوهای *.keystore و *.b64 و *.jks را مسدود می‌کند)."
echo "اگر keystore گم شود، کاربران آپدیت‌های بعدی را روی نسخه‌ی قبلی نصب"
echo "نخواهند توانست؛ پس حتماً پشتیبان بگیرید."
