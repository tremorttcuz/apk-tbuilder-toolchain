#!/usr/bin/env bash
# Собирает app/src/main/assets/jvm-tools.zip — компиляторы, которые приложение запускает на телефоне:
#   kotlinc.jar  Kotlin 2.1 (kotlin-compiler-embeddable) + его зависимости, переведённые в dex
#   composec.jar плагин Compose-компилятора
#   kpatch.jar   заплатки совместимости с Android (замена ConcurrentLongObjectHashMap, заглушка SourceVersion)
#   ecj.jar      компилятор Java (Eclipse)
#   d8.jar       d8/r8 — превращает .class в classes.dex
# Нужно на компьютере: JDK 17+, curl, python3, ~3 ГБ свободной RAM (процесс идёт 3–6 минут). Результат одинаков для любого телефона с Android 8+.
set -euo pipefail

KOTLIN="${KOTLIN:-2.1.21}"; ECJ="${ECJ:-3.37.0}"; R8="${R8:-8.5.35}"
HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="${OUT:-$PWD/toolchain-out}"
M=https://repo1.maven.org/maven2
mkdir -p "$OUT/dl" "$OUT/assets"; cd "$OUT/dl"
get() { [ -s "$(basename "$1")" ] || curl -fsSLO "$1"; }

get $M/org/jetbrains/kotlin/kotlin-compiler-embeddable/$KOTLIN/kotlin-compiler-embeddable-$KOTLIN.jar
for a in kotlin-stdlib kotlin-script-runtime kotlin-daemon-embeddable kotlin-compose-compiler-plugin-embeddable; do get $M/org/jetbrains/kotlin/$a/$KOTLIN/$a-$KOTLIN.jar; done
get $M/org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar
get $M/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
get $M/org/jetbrains/annotations/13.0/annotations-13.0.jar
get $M/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar
get $M/javax/xml/stream/stax-api/1.0-2/stax-api-1.0-2.jar
get $M/com/fasterxml/woodstox/woodstox-core/6.6.2/woodstox-core-6.6.2.jar
get $M/org/codehaus/woodstox/stax2-api/4.2.2/stax2-api-4.2.2.jar
get $M/org/eclipse/jdt/ecj/$ECJ/ecj-$ECJ.jar
get https://maven.google.com/com/android/tools/r8/$R8/r8-$R8.jar
get https://github.com/Sable/android-platforms/raw/master/android-34/android.jar

# куча и потоки ограничены: dex компилятора Kotlin иначе съедает 4+ ГБ и на машине с 8 ГБ RAM срабатывает OOM-killer
D8() { java -Xmx2g -cp r8-$R8.jar com.android.tools.r8.D8 --thread-count 2 --release --min-api 26 --lib android.jar "$@" 2>&1 | grep -v -E '^(Warning|Info|MethodHandle|Classes with)' || true; }

# заплатки: javax.swing.Icon и javax.lang.model.SourceVersion отсутствуют в Android, ConcurrentLongObjectHashMap ходит в Unsafe
rm -rf patch && mkdir -p patch/cls1 patch/cls2 patch/cls3
javac --patch-module java.desktop="$HERE/patch-src" -d patch/cls1 "$HERE/patch-src/javax/swing/Icon.java"
javac -cp kotlin-compiler-embeddable-$KOTLIN.jar -d patch/cls2 "$HERE/patch-src/org/jetbrains/kotlin/com/intellij/util/ConcurrentLongObjectHashMap.java"
javac --patch-module java.compiler="$HERE/patch-src" -d patch/cls3 "$HERE/patch-src/javax/lang/model/SourceVersion.java"
jar cf patch/swing.jar -C patch/cls1 . ; jar cf patch/clm.jar -C patch/cls2 . ; jar cf patch/sv.jar -C patch/cls3 .

K="kotlin-compiler-embeddable-$KOTLIN.jar kotlin-stdlib-$KOTLIN.jar kotlin-script-runtime-$KOTLIN.jar kotlin-reflect-1.6.10.jar kotlin-daemon-embeddable-$KOTLIN.jar"
D8 --output kc-dex.jar $K trove4j-1.0.20200330.jar annotations-13.0.jar kotlinx-coroutines-core-jvm-1.8.0.jar stax-api-1.0-2.jar woodstox-core-6.6.2.jar stax2-api-4.2.2.jar patch/swing.jar
D8 --classpath kotlin-compiler-embeddable-$KOTLIN.jar --classpath kotlin-stdlib-$KOTLIN.jar --output cp-dex.jar kotlin-compose-compiler-plugin-embeddable-$KOTLIN.jar
D8 --classpath kotlin-compiler-embeddable-$KOTLIN.jar --output clm-dex.jar patch/clm.jar
D8 --output sv-dex.jar patch/sv.jar
D8 --output ecj-dex.jar ecj-$ECJ.jar
D8 --output r8-dex.jar r8-$R8.jar

# kotlinc.jar: dex + ресурсы (META-INF/extensions и т. п.) + пустые «маркеры» двух классов, по которым Kotlin ищет свой jar
python3 "$HERE/merge-jar.py" "$OUT/assets/kotlinc.jar" kc-dex.jar $K woodstox-core-6.6.2.jar stax2-api-4.2.2.jar --markers
python3 "$HERE/merge-jar.py" "$OUT/assets/composec.jar" cp-dex.jar kotlin-compose-compiler-plugin-embeddable-$KOTLIN.jar
python3 "$HERE/merge-jar.py" "$OUT/assets/ecj.jar" ecj-dex.jar ecj-$ECJ.jar
python3 "$HERE/merge-jar.py" "$OUT/assets/d8.jar" r8-dex.jar r8-$R8.jar
python3 - "$OUT" <<'PY'
import sys, zipfile, os
o = sys.argv[1]
def two(name, a, b):  # kpatch.jar — две dex-заплатки; ecj.jar дополнительно получает заглушку SourceVersion
    z = zipfile.ZipFile(f"{o}/assets/{name}", "a" if name == "ecj.jar" else "w", zipfile.ZIP_DEFLATED)
    if name == "kpatch.jar":
        z.writestr("classes.dex", zipfile.ZipFile(f"{o}/dl/clm-dex.jar").read("classes.dex"))
        z.writestr("classes2.dex", zipfile.ZipFile(f"{o}/dl/sv-dex.jar").read("classes.dex"))
    else:
        z.writestr("classes2.dex", zipfile.ZipFile(f"{o}/dl/sv-dex.jar").read("classes.dex"))
    z.close()
two("kpatch.jar", 0, 0); two("ecj.jar", 0, 0)
z = zipfile.ZipFile(f"{o}/jvm-tools.zip", "w", zipfile.ZIP_STORED)
for n in sorted(os.listdir(f"{o}/assets")): z.write(f"{o}/assets/{n}", n)
z.close(); print("готово:", f"{o}/jvm-tools.zip", os.path.getsize(f"{o}/jvm-tools.zip") // 1048576, "МБ")
PY
echo "Скопируй jvm-tools.zip в app/src/main/assets/ и поменяй размер в assets.json (поле size) и имя path (jvm-tools-vN.zip), чтобы приложение распаковало новую версию."
