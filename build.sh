#!/bin/bash
set -e
set -o pipefail
# Public-build layout: SRC is this repo (wherever it is cloned).
# WORKSPACE holds the big third-party checkouts (Xray lib, ics-openvpn
# sources, WireGuard aar) and the Android SDK/JDK; override it with
# WORKSPACE=/path/to/workspace when building elsewhere.
SRC=$(cd "$(dirname "$0")" && pwd)
WORKSPACE=${WORKSPACE:-$HOME/workspace}
ROOT=$WORKSPACE
JDK=${JDK:-$ROOT/tools/jdk}
BT=${BT:-$ROOT/tools/sdk/android-14}
ANDROID_JAR=${ANDROID_JAR:-$ROOT/tools/sdk/android-34-ext12/android.jar}
OUT=$SRC/out
OVPN=${OVPN_SRC:-$ROOT/.tmp/ovpn_lib/vpnLib/src/main}
OVPN_TMP=/tmp/ovpn_build
XRAY_AAR=${XRAY_AAR:-$ROOT/.tmp/xray/aar}
WG_AAR=${WG_AAR:-$ROOT/.tmp/wg/aar}
AWG_AAR=${AWG_AAR:-$ROOT/.tmp/awg/aar}
rm -rf $OUT && mkdir -p $OUT/apk $OUT/dex $OVPN_TMP/ovpn_res $OVPN_TMP/aidl_gen $OVPN_TMP/classes
export PATH=$JDK/bin:$PATH
XRAY_JAR=$XRAY_AAR/classes.jar
ZXING_JAR=$SRC/third_party/zxing-core.jar
WG_JAR=$SRC/third_party/wg-tunnel-stripped.jar
AWG_JAR=$SRC/third_party/awg-tunnel.jar
ANDROIDX_STUB_JAR=$SRC/third_party/androidx-collection-stub.jar
JSCH_JAR=$SRC/third_party/jsch-0.1.55.jar
ANN_JAR=$OVPN_TMP/deps/annotation-jvm-1.8.0.jar
CORE_JAR=$OVPN_TMP/deps/core/classes.jar
LOCALBM_JAR=$OVPN_TMP/deps/localbm/classes.jar
mkdir -p $OVPN_TMP/deps/core $OVPN_TMP/deps/localbm
cp $SRC/third_party/ovpn_deps/annotation-jvm-1.8.0.jar $OVPN_TMP/deps/
cp $SRC/third_party/ovpn_deps/classes.jar $OVPN_TMP/deps/core/classes.jar
cp $SRC/third_party/ovpn_deps/localbm-classes.jar $OVPN_TMP/deps/localbm/classes.jar

echo "== aapt2 (app res + openvpn res) =="
mkdir -p $SRC/res/values
cat > $SRC/res/values/strings.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources><string name="app_name">calculator</string></resources>
EOF
# OpenVPN resources, without its launcher icons (ours win).
rm -rf $OVPN_TMP/ovpn_res && mkdir -p $OVPN_TMP/ovpn_res
cp -r $OVPN/res/* $OVPN_TMP/ovpn_res/
rm -rf $OVPN_TMP/ovpn_res/mipmap-*
$BT/aapt2 compile --dir $SRC/res -o $OUT/res.zip
$BT/aapt2 compile --dir $OVPN_TMP/ovpn_res -o $OUT/ovpn_res.zip
$BT/aapt2 link -o $OUT/unsigned.apk -I $ANDROID_JAR --manifest $SRC/AndroidManifest.xml -R $OUT/res.zip -R $OUT/ovpn_res.zip --auto-add-overlay --java $OUT/gen

echo "== ics-openvpn java (against final merged R) =="
mkdir -p $OUT/gen/de/blinkt/openvpn
sed 's/^package com\.renamer;/package de.blinkt.openvpn;/' $OUT/gen/com/renamer/R.java > $OUT/gen/de/blinkt/openvpn/R.java
rm -rf $OVPN_TMP/aidl_gen && mkdir -p $OVPN_TMP/aidl_gen
for f in $(find $OVPN/aidl -name "*.aidl"); do
  $BT/aidl -p $ROOT/tools/sdk/android-34-ext12/framework.aidl -I $OVPN/aidl -o $OVPN_TMP/aidl_gen "$f" 2>/dev/null || true
done
mkdir -p $OVPN_TMP/stub/de/blinkt/openvpn
cat > $OVPN_TMP/stub/de/blinkt/openvpn/BuildConfig.java <<'EOF'
package de.blinkt.openvpn;
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String BUILD_TYPE = "release";
    public static final String APPLICATION_ID = "com.renamer";
    public static final String VERSION_NAME = "1.38";
    public static final int VERSION_CODE = 39;
}
EOF
find $OVPN/java -name "*.java" > $OUT/ovpn_sources.txt
find $OVPN_TMP/aidl_gen -name "*.java" >> $OUT/ovpn_sources.txt
echo $OUT/gen/de/blinkt/openvpn/R.java >> $OUT/ovpn_sources.txt
echo $OVPN_TMP/stub/de/blinkt/openvpn/BuildConfig.java >> $OUT/ovpn_sources.txt
rm -rf $OVPN_TMP/classes && mkdir -p $OVPN_TMP/classes
$JDK/bin/javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR:$ANN_JAR:$CORE_JAR:$LOCALBM_JAR -d $OVPN_TMP/classes @$OUT/ovpn_sources.txt 2>&1 | tee $OUT/ovpn_javac.log
cd $OVPN_TMP/classes && $JDK/bin/jar cf $OUT/ovpn.jar . && cd $ROOT

echo "== ovpn3 java (SWIG bindings, net.openvpn.ovpn3) =="
find $SRC/src/net/openvpn/ovpn3 -name "*.java" > $OUT/ovpn3_sources.txt
mkdir -p $OVPN_TMP/ovpn3_classes
$JDK/bin/javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR -d $OVPN_TMP/ovpn3_classes @$OUT/ovpn3_sources.txt 2>&1 | tee $OUT/ovpn3_javac.log
cd $OVPN_TMP/ovpn3_classes && $JDK/bin/jar cf $OUT/ovpn3.jar . && cd $ROOT

echo "== slipnet bindings (gomobile java) =="
mkdir -p $OUT/slipnet_classes
$JDK/bin/javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR -d $OUT/slipnet_classes $SRC/src/mobile/*.java $SRC/src/vaydns/*.java 2>&1 | tee $OUT/slipnet_javac.log
cd $OUT/slipnet_classes && $JDK/bin/jar cf $OUT/slipnet.jar . && cd $ROOT

echo "== kotlinc =="
$ROOT/tools/kotlin/kotlinc/bin/kotlinc -cp $ANDROID_JAR:$XRAY_JAR:$ZXING_JAR:$WG_JAR:$AWG_JAR:$ANDROIDX_STUB_JAR:$OUT/ovpn.jar:$OUT/ovpn3.jar:$OUT/slipnet.jar:$JSCH_JAR:$ANN_JAR:$CORE_JAR:$LOCALBM_JAR -d $OUT/classes.jar $SRC/src/com/renamer/*.kt $SRC/src/app/slipnet/tunnel/*.kt $OUT/gen/com/renamer/R.java 2>&1 | tee $OUT/kotlinc.log
echo "== d8 =="
java -cp $BT/lib/d8.jar com.android.tools.r8.D8 --lib $ANDROID_JAR --output $OUT/dex $OUT/classes.jar $ROOT/tools/kotlin/kotlinc/lib/kotlin-stdlib.jar $XRAY_JAR $ZXING_JAR $WG_JAR $AWG_JAR $ANDROIDX_STUB_JAR $OUT/ovpn.jar $OUT/ovpn3.jar $OUT/slipnet.jar $JSCH_JAR $ANN_JAR $CORE_JAR $LOCALBM_JAR 2>&1 | tee $OUT/d8.log
echo "== pack =="
cp $OUT/dex/classes.dex $OUT/apk/classes.dex
mkdir -p $OUT/apk/assets $OUT/apk/lib/arm64-v8a
cp $SRC/assets/*.png $SRC/assets/*.jpg $SRC/assets/*.js $OUT/apk/assets/ 2>/dev/null || true
cp $XRAY_AAR/jni/arm64-v8a/libgojni.so $OUT/apk/lib/arm64-v8a/ 2>/dev/null || true
cp $WG_AAR/jni/arm64-v8a/libwg-go.so $OUT/apk/lib/arm64-v8a/ 2>/dev/null || true
cp $AWG_AAR/jni/arm64-v8a/libawg-go.so $OUT/apk/lib/arm64-v8a/ 2>/dev/null || true
cp $OVPN/jniLibs/arm64-v8a/*.so $OUT/apk/lib/arm64-v8a/ 2>/dev/null || true
cp $OVPN/assets/pie_openvpn.arm64-v8a $OUT/apk/assets/ 2>/dev/null || true
cp $XRAY_AAR/assets/geoip.dat $XRAY_AAR/assets/geosite.dat $OUT/apk/assets/ 2>/dev/null || true
cd $OUT/apk && zip -q -r $OUT/unsigned.apk classes.dex assets lib
cd $ROOT
$BT/zipalign -f 4 $OUT/unsigned.apk $OUT/aligned.apk
# Public builds sign with a debug keystore (generated on first run).
# NEVER commit a release keystore to a public repo.
DEBUG_KS=$SRC/debug.keystore
if [ ! -f "$DEBUG_KS" ]; then
  "$JDK/bin/keytool" -genkey -v -keystore "$DEBUG_KS" -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/apksigner sign --ks "$DEBUG_KS" --ks-pass pass:android --key-pass pass:android --out $OUT/ConfigRenamer.apk $OUT/aligned.apk
$BT/apksigner verify $OUT/ConfigRenamer.apk
ls -lh $OUT/ConfigRenamer.apk
$BT/aapt2 dump badging $OUT/ConfigRenamer.apk | grep -E "package:|application:"
unzip -l $OUT/ConfigRenamer.apk | grep -E "lib/arm64|OpenVPNService|pie_openvpn" | head -20
