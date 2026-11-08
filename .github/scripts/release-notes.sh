#!/usr/bin/env bash
# 从 CHANGELOG.md 里抽出某个 tag 对应的段落,作为 GitHub 发行版(Release)的说明。
#
# 用法:
#   bash .github/scripts/release-notes.sh <tag>            # 打印到标准输出
#   bash .github/scripts/release-notes.sh <tag> <输出文件>  # 写入文件
#
# 退出码:
#   0 找到并输出
#   2 CHANGELOG.md 不存在
#   3 CHANGELOG.md 里没有该 tag 的段落
#
# 约定:CHANGELOG.md 里每个版本用二级标题起头,如 `## v1.1(2026-11-08)`,
# 段落范围是「该标题之后」到「下一个二级标题之前」。
set -euo pipefail

TAG="${1:-}"
OUT="${2:-}"
SRC="${CHANGELOG:-CHANGELOG.md}"

if [ -z "$TAG" ]; then
  echo "用法: release-notes.sh <tag> [输出文件]" >&2
  exit 1
fi

if [ ! -f "$SRC" ]; then
  echo "找不到 $SRC" >&2
  exit 2
fi

# 只认 `## <tag>` 开头且 tag 后面不是数字或点的标题,
# 否则 v1.1 会误匹配到 v1.10 / v1.11 的标题。
# 注意:用 index() 而不是正则做前缀判断 —— 正则里的 ^ 会算进 length(),导致边界字符取错一位。
NOTES=$(
  awk -v tag="$TAG" '
    {
      if (!inblock) {
        pref = "## " tag
        if (index($0, pref) == 1) {
          nxt = substr($0, length(pref) + 1, 1)
          if (nxt !~ /[0-9.]/) { inblock = 1; next }
        }
      } else {
        if (index($0, "## ") == 1) { exit }
        print
      }
    }
  ' "$SRC" | sed -e '/./,$!d'
)
# sed 去掉段落开头的空行;结尾空行交给 printf 统一收口
NOTES=$(printf '%s' "$NOTES" | sed -e 's/[[:space:]]*$//')

if [ -z "$NOTES" ]; then
  echo "$SRC 里没有 $TAG 的段落" >&2
  exit 3
fi

if [ -n "$OUT" ]; then
  printf '%s\n' "$NOTES" >"$OUT"
else
  printf '%s\n' "$NOTES"
fi
