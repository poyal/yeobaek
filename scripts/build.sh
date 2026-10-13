#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .tools/env.sh ]]; then source .tools/env.sh; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$PWD/.tools/gradle-home}"
export ANDROID_HOME="${ANDROID_HOME:-$PWD/.tools/android-sdk}"
if [[ -x .tools/gradle-8.13/bin/gradle ]]; then
  exec .tools/gradle-8.13/bin/gradle --console=plain "$@"
fi
exec ./gradlew --console=plain "$@"
