#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
(cd neoforge && ./gradlew --no-configuration-cache -I ../tools/blender/runtime-classpath.gradle blenderProbeClasspath)
probe_classpath=$(cat neoforge/build/blender-runtime-classpath.txt)
java -cp "$probe_classpath" tools/blender/GeckoProbe.java tools/blender/evidence/galath-roundtrip.geo.json tools/blender/evidence/curved_shell.geo.json
