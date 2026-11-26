#!/usr/bin/env sh
# M13：一键安装 mcc。构建发行包 → 解压到 ~/.mcc/app → 在 ~/.local/bin 建立 mcc 软链接。
# 用法：sh scripts/install.sh [安装前缀，默认 ~/.mcc/app]
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PREFIX=${1:-"$HOME/.mcc/app"}
BIN_DIR="$HOME/.local/bin"

command -v java >/dev/null 2>&1 || { echo "需要 Java 17+（未找到 java）" >&2; exit 1; }
JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ "${JAVA_MAJOR:-0}" -lt 17 ]; then
  echo "需要 Java 17+，当前为 $JAVA_MAJOR" >&2; exit 1
fi

echo "==> 构建"
(cd "$ROOT" && ./gradlew -q installDist)

echo "==> 安装到 $PREFIX"
rm -rf "$PREFIX"
mkdir -p "$PREFIX" "$BIN_DIR"
cp -R "$ROOT/build/install/mcc/." "$PREFIX/"
ln -sf "$PREFIX/bin/mcc" "$BIN_DIR/mcc"

echo "==> 完成：$BIN_DIR/mcc"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "提示：把 $BIN_DIR 加入 PATH，例如：echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.zshrc" ;;
esac
echo "设置 OPENAI_API_KEY 后运行：mcc   或   mcc -p \"解释这个项目\""
