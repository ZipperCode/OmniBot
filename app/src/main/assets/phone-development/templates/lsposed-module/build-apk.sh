#!/bin/sh
set -eu

PROJECT=${1:-.}
cd "$PROJECT"

MIN_SDK=${MIN_SDK:-26}
TARGET_SDK=${TARGET_SDK:-35}
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_ALIAS=${KS_ALIAS:-androiddebugkey}
KS_PASS=${KS_PASS:-android}
export KS_PASS

fail() { echo "build-apk: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "$1 is not installed."; }

for tool in aapt2 javac jar d8 apksigner keytool; do need "$tool"; done
[ -f AndroidManifest.xml ] || fail "AndroidManifest.xml not found in $(pwd)"

if [ -z "${ANDROID_JAR:-}" ]; then
    ANDROID_JAR=$(ls -d "$HOME"/android-sdk/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1 || true)
fi
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "$ANDROID_JAR" ]; then
    ANDROID_JAR=$(find / -name "android.jar" 2>/dev/null | head -n 1 || true)
fi
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] || fail "android.jar not found. Please install platform SDK first."

PACKAGE=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' AndroidManifest.xml | head -n 1)
[ -n "$PACKAGE" ] || fail "AndroidManifest.xml has no package attribute"

rm -rf build
mkdir -p build/classes build/dex build/gen

LINK_INPUTS=""
if [ -d res ] && [ -n "$(ls -A res 2>/dev/null)" ]; then
    aapt2 compile --dir res -o build/res.zip
    LINK_INPUTS="build/res.zip"
fi
ASSET_ARGS=""
[ -d assets ] && ASSET_ARGS="-A assets"

aapt2 link -I "$ANDROID_JAR" --manifest AndroidManifest.xml $ASSET_ARGS \
    --java build/gen --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o build/base.apk $LINK_INPUTS

BOOTCLASSPATH="$ANDROID_JAR"
JAVAC_CLASSPATH="$BOOTCLASSPATH"
PROGRAM_LIB_JARS=""
D8_CLASSPATH_ARGS=""

if [ -d libs ]; then
    for lib in libs/*.jar; do
        [ -f "$lib" ] || continue
        # 所有类库（包括普通依赖和框架存根）都必须提供给 javac 进行编译类型推导
        JAVAC_CLASSPATH="$JAVAC_CLASSPATH:$lib"
        case "$(basename "$lib")" in
            *api*|*xposed*|*Xposed*|*compileOnly*)
                # 框架 compileOnly 依赖：仅传给 d8 作为 --classpath，绝不打入产物 classes.dex
                D8_CLASSPATH_ARGS="$D8_CLASSPATH_ARGS --classpath $lib"
                ;;
            *)
                # 普通运行时依赖：传给 d8 作为程序输入进行 dex 打包
                PROGRAM_LIB_JARS="$PROGRAM_LIB_JARS $lib"
                ;;
        esac
    done
fi

find src build/gen -name '*.java' > build/sources.txt
[ -s build/sources.txt ] || fail "no .java sources under src/"
javac -source 8 -target 8 -bootclasspath "$BOOTCLASSPATH" -cp "$JAVAC_CLASSPATH" -Xlint:-options \
    -d build/classes @build/sources.txt

jar cf build/classes.jar -C build/classes .

# d8 命令：compileOnly 仅作为 --classpath 引用，仅 classes.jar 与 PROGRAM_LIB_JARS 作为 dex program inputs
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" $D8_CLASSPATH_ARGS --output build/dex build/classes.jar $PROGRAM_LIB_JARS
cp build/base.apk build/unsigned.apk
for dex in build/dex/classes*.dex; do
    [ -f "$dex" ] || continue
    jar --update --file build/unsigned.apk -C build/dex "$(basename "$dex")"
done

if command -v zipalign >/dev/null 2>&1; then
    zipalign -f -p 4 build/unsigned.apk build/aligned.apk
    mv build/aligned.apk build/unsigned.apk
fi

if [ ! -f "$KEYSTORE" ]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

# 使用官方真实 ApkSignerTool CLI 统一执行 APK 签名与验签
OUT="build/$PACKAGE.apk"
apksigner sign --min-sdk-version "$MIN_SDK" --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass env:KS_PASS --key-pass env:KS_PASS --out "$OUT" build/unsigned.apk
apksigner verify --verbose "$OUT"
echo "BUILD_SUCCESS"
echo "APK_PATH: $(pwd)/$OUT"
echo "PACKAGE: $PACKAGE"
