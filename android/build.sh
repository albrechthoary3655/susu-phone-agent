#!/bin/bash
# Build script for Susu Phone Agent APK (manual toolchain, no Gradle)
#
# Prerequisites:
#   - JDK 17+
#   - Android SDK API 34 (build-tools 34.0.0)
#   - Shizuku JARs in libs/ (see README for download commands)
#   - A signing keystore
#
# Usage:
#   1. Set ANDROID_HOME to your Android SDK path
#   2. Set KS to your keystore path
#   3. Set KS_PASS to your keystore password (or use a secrets manager)
#   4. Run: bash build.sh
set -e

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
AIDL=$BUILD_TOOLS/aidl

PROJECT="$(cd "$(dirname "$0")" && pwd)"
SRC=$PROJECT/app/src/main
OUT=$PROJECT/build
LIBS=$PROJECT/libs
PKG_PATH=com/susu/phoneagent

# ── Keystore (set via environment or edit here) ────────────────────────────
KS=${KS:-/path/to/your/keystore.jks}
KS_ALIAS=${KS_ALIAS:-susu}
KS_PASS=${KS_PASS:-YOUR_KEYSTORE_PASS}  # prefer: export KS_PASS=... before running

rm -rf $OUT
mkdir -p $OUT/{gen,gen_aidl,classes,apk,compiled_res}

echo "=== 1/7  aidl → IPhoneService.java ==="
$AIDL -I$SRC/aidl -o$OUT/gen_aidl $SRC/aidl/com/susu/phoneagent/IPhoneService.aidl

echo "=== 2/7  aapt2 compile resources ==="
$BUILD_TOOLS/aapt2 compile --dir $SRC/res -o $OUT/compiled_res/

echo "=== 3/7  aapt2 link → unsigned apk + R.java ==="
$BUILD_TOOLS/aapt2 link -o $OUT/apk/app.unsigned.apk -I $PLATFORM \
  --manifest $SRC/AndroidManifest.xml --java $OUT/gen --auto-add-overlay \
  -R $OUT/compiled_res/*.flat

echo "=== 4/7  javac ==="
find $SRC/java -name "*.java"     > $OUT/sources.txt
find $OUT/gen_aidl -name "*.java" >> $OUT/sources.txt
echo "$OUT/gen/$PKG_PATH/R.java"  >> $OUT/sources.txt
javac -source 11 -target 11 \
  -classpath "$PLATFORM:$LIBS/shizuku-api.jar:$LIBS/shizuku-provider.jar:$LIBS/shizuku-aidl.jar:$LIBS/shizuku-shared.jar" \
  -d $OUT/classes @$OUT/sources.txt

echo "=== 5/7  d8 ==="
CLASS_FILES=$(find $OUT/classes -name "*.class" | tr '\n' ' ')
$BUILD_TOOLS/d8 --release --min-api 28 --output $OUT/apk/ \
  --lib $PLATFORM \
  $CLASS_FILES \
  $LIBS/shizuku-api.jar $LIBS/shizuku-provider.jar \
  $LIBS/shizuku-aidl.jar $LIBS/shizuku-shared.jar

echo "=== 6/7  pack ==="
cd $OUT/apk && zip -j app.unsigned.apk classes.dex

echo "=== 7/7  sign ==="
$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk
$BUILD_TOOLS/apksigner sign --ks "$KS" --ks-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" \
  --out $PROJECT/output/susu-phone-agent.apk app.aligned.apk

echo ""
echo "=== BUILD SUCCESS ==="
ls -lh $PROJECT/output/susu-phone-agent.apk
sha256sum $PROJECT/output/susu-phone-agent.apk
