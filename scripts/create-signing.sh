#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ ! -f signing.properties && ! -e .tools/signing/yeobaek.jks ]] || { echo '기존 서명을 보존합니다.'; exit 0; }
if [[ -f .tools/env.sh ]]; then source .tools/env.sh; fi
mkdir -p .tools/signing
umask 077
python3 - <<'PY'
import secrets, pathlib, subprocess, os
password = secrets.token_urlsafe(32)
env = dict(os.environ, YEOBAEK_KEY_PASSWORD=password)
keytool = os.environ['JAVA_HOME']+'/bin/keytool' if os.environ.get('JAVA_HOME') else 'keytool'
subprocess.run([keytool,'-genkeypair','-keystore','.tools/signing/yeobaek.jks','-storetype','JKS',
 '-storepass:env','YEOBAEK_KEY_PASSWORD','-keypass:env','YEOBAEK_KEY_PASSWORD','-alias','yeobaek','-keyalg','RSA','-keysize','3072',
 '-validity','10000','-dname','CN=Yeobaek Local APK'], env=env, check=True)
pathlib.Path('signing.properties').write_text('storeFile=.tools/signing/yeobaek.jks\nstorePassword='+password+'\nkeyAlias=yeobaek\nkeyPassword='+password+'\n')
PY
