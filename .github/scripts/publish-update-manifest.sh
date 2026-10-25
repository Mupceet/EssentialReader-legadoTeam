#!/usr/bin/env bash

# 把指定版本的 GitHub Release 投影为 update-manifests 分支的 official.json
# （GithubRelease 同构小文件；应用内检查链路 manifest 优先、经 gh-proxy
# 加速拉取——raw.githubusercontent 国内直连不可达是常态）。
# 用法: publish-update-manifest.sh <version>
# 依赖: GH_TOKEN（repo contents 写权限）、gh、jq、git

set -euo pipefail

version="${1:?release version required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"

manifest_branch="update-manifests"
workdir="$(mktemp -d)"
clone_url="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"

if git clone --depth 1 -b "$manifest_branch" "$clone_url" "$workdir/m" 2>/dev/null; then
  manifest_dir="$workdir/m"
else
  # 首次发版：分支不存在，从当前默认分支建孤儿分支（分支上只放 manifest）
  git clone --depth 1 "$clone_url" "$workdir/m"
  manifest_dir="$workdir/m"
  git -C "$manifest_dir" checkout --orphan "$manifest_branch"
  git -C "$manifest_dir" rm -rf --quiet . || true
fi

gh api "repos/${GITHUB_REPOSITORY}/releases/tags/${version}" > "$workdir/release.json"

# 校验 + 投影：正式版契约（tag 匹配且非 pre-release），资产只保留可安装
# APK，字段对齐 AppReleaseInfo 的 GithubRelease 模型
jq -e --arg version "$version" '
  if .tag_name == $version and (.prerelease == false) then
    {
      tag_name,
      name,
      body: (.body // ""),
      prerelease,
      assets: [.assets[]
        | select(.content_type == "application/vnd.android.package-archive" and .state == "uploaded")
        | {
            browser_download_url,
            content_type,
            created_at,
            download_count: 0,
            id,
            name,
            size,
            state
          }]
    }
  else
    error("release 校验失败：tag/prerelease 不符合正式版契约")
  end
' "$workdir/release.json" > "$manifest_dir/official.json"

git -C "$manifest_dir" config user.name "github-actions[bot]"
git -C "$manifest_dir" config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git -C "$manifest_dir" add official.json
git -C "$manifest_dir" commit -m "manifest: ${version}"
git -C "$manifest_dir" push origin "$manifest_branch"

echo "official.json 已发布：${version}"
