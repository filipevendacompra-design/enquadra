#!/bin/bash
# Runs the app on the emulator: open, find a shot (local AI), lock, change lens, shoot. Screenshots + logcat go to ci-out/
set -x
OUT=ci-out; mkdir -p $OUT
PKG=pt.filipe.enquadra.nativa
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb install -r -g android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $PKG android.permission.CAMERA
adb logcat -c
shot() { adb exec-out screencap -p > $OUT/$1.png; }
tap_text() {  # tap the view whose text is $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1
  B=$(python3 - "$1" <<'PY'
import re,sys
s=open("ci-out/ui.xml",encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', s):
    n=m.group(0)
    if 'text="%s"'%sys.argv[1] in n:
        x0,y0,x1,y1=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()); print((x0+x1)//2,(y0+y1)//2); break
PY
)
  echo "tap $1 at $B"; [ -n "$B" ] && adb shell input tap $B
}
tap_desc() {  # tap the view whose content-desc is $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1
  B=$(python3 - "$1" <<'PY'
import re,sys
s=open("ci-out/ui.xml",encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>', s):
    n=m.group(0)
    if 'content-desc="%s"'%sys.argv[1] in n:
        x0,y0,x1,y1=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n).groups()); print((x0+x1)//2,(y0+y1)//2); break
PY
)
  echo "tap $1 at $B"; [ -n "$B" ] && adb shell input tap $B
}
adb shell am start -n $PKG/.MainActivity --ez autolock true
sleep 12; shot 1-aberto
tap_desc "Encontrar a foto"; sleep 1.5; shot 2-a-procurar
sleep 4.0; shot 3-quadrado
sleep 0.5; shot 3b-zoom
sleep 1.2; shot 3c-lente
sleep 3; shot 3d-lente-final
tap_desc "Tirar foto"; sleep 6; shot 4-resultado
tap_text "NOIR"; sleep 2; tap_text "Polaroid"; sleep 3; shot 4b-noir-polaroid
tap_text "Ficha técnica"; sleep 3; shot 4c-ficha
tap_text "Guardar"; sleep 4; shot 4d-guardada
tap_desc "Galeria"; sleep 3; shot 4e-galeria
adb shell input keyevent KEYCODE_BACK; sleep 2
tap_desc "Definições"; sleep 2; shot 5-definicoes; adb shell uiautomator dump /sdcard/ui2.xml >/dev/null 2>&1; adb pull /sdcard/ui2.xml $OUT/ui2.xml >/dev/null 2>&1
adb shell input swipe 500 1800 500 300 300; sleep 1; adb shell input swipe 500 1800 500 300 300; sleep 1; shot 6-definicoes-fim; adb shell uiautomator dump /sdcard/ui3.xml >/dev/null 2>&1; adb pull /sdcard/ui3.xml $OUT/ui3.xml >/dev/null 2>&1
adb logcat -d -v brief > $OUT/logcat.txt
grep -E "FATAL|AndroidRuntime|Enquadra|CameraX|Camera2CameraImpl.*(ERROR|error)" $OUT/logcat.txt | head -80 > $OUT/resumo.txt
adb shell run-as $PKG cat files/crash.txt > $OUT/crash.txt 2>/dev/null || true
exit 0
# v14
