#!/bin/sh
# 调研题验证：答案写进 ANSWER.md，且源码不得被改动。
set -e
test -f ANSWER.md
grep -q "APP_TIMEOUT" ANSWER.md
grep -q "client.py" ANSWER.md
grep -q "worker.py" ANSWER.md
cat > /tmp/mcc-eval-legacy.sha <<'SHA'
0ca38a5f95f599ee5f644dfa44c28365814fb9753beb0d74d6670785948b77c8  app/settings.py
45e041620d4517c58b485f421796be9e791154b461e375905b0d5251cdda960a  app/client.py
841de3e49c3d25b14cb85b89f2a529fd68538e23f5a31e53d33be75e90cf8397  app/worker.py
SHA
shasum -a 256 -c /tmp/mcc-eval-legacy.sha >/dev/null
echo ok
