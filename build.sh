#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
mkdir -p target/classes dist
find src/main/java -name '*.java' -print > target/sources.txt
compiler_release=(--release 17)
if [[ ${JAVA_RELEASE_FALLBACK:-0} == 1 ]]; then compiler_release=(-source 17 -target 17); fi
java com.sun.tools.javac.Main "${compiler_release[@]}" -encoding UTF-8 -d target/classes @target/sources.txt
cp -R src/main/resources/. target/classes/
java --module jdk.jartool/sun.tools.jar.Main --create --file dist/lostfound.jar --main-class edu.usc.lostfound.App -C target/classes .
printf '%s\n' 'Built dist/lostfound.jar. Start with ./run.sh.'
