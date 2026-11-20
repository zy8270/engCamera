# Cloudflare R2 镜像配置说明

国内直连 `github.com` 下载 APK 经常超时。本项目的做法是：**每次发版自动把 APK 镜像到 Cloudflare R2**，
官网默认从 R2 下载，同时保留 GitHub 作为备用链接。

```
本地 gh release create
        │
        ├─► GitHub Release（备用源，永远存在）
        │
        └─► Actions: mirror-to-r2.yml
                 ├─► R2  releases/<tag>/<apk>        永久保留，immutable 缓存
                 ├─► R2  latest/engCamera-latest.apk 固定别名（README 用）
                 └─► R2  latest.json                 元数据（官网判定"镜像已就绪"）
```

---

## 一次性配置（只需做一次）

### 1. 建 R2 桶并开公网访问 ✅ 已完成

- 桶名：`engcamera-releases`
- 公网地址：`https://pub-dd825b6251784cca9e7de8b1d0942078.r2.dev`
- CORS 已配置（允许 `engcamera.vgcad.com` / `zy8270.github.io` / localhost 的 GET、HEAD）

### 2. 建 R2 API 令牌（需要你手动点，约 2 分钟）

1. 打开 <https://dash.cloudflare.com/?to=/:account/r2/api-tokens>
2. 点 **Create API token**（或「Manage R2 API Tokens」→ Create）
3. 填：
   - Token name：`engcamera-ci`
   - Permissions：**Object Read & Write**
   - Specify bucket：`engcamera-releases`
   - TTL：留空（永不过期）
4. 创建后页面会显示 **Access Key ID** 和 **Secret Access Key**（Secret 只显示一次，立刻复制）

### 3. 填 3 个仓库 Secret

在本仓库 → Settings → Secrets and variables → Actions → **New repository secret**：

| Secret 名称 | 值 |
|---|---|
| `R2_ACCESS_KEY_ID` | 上一步的 Access Key ID |
| `R2_SECRET_ACCESS_KEY` | 上一步的 Secret Access Key |
| `R2_ACCOUNT_ID` | `afe700c113ff41c98b4ba436ed55b7ad` |

或用 CLI（把 `<...>` 换成真实值）：

```bash
gh secret set R2_ACCESS_KEY_ID     -R zy8270/engCamera --body '<Access Key ID>'
gh secret set R2_SECRET_ACCESS_KEY -R zy8270/engCamera --body '<Secret Access Key>'
gh secret set R2_ACCOUNT_ID        -R zy8270/engCamera --body 'afe700c113ff41c98b4ba436ed55b7ad'
```

---

## 日常发版（不变）

```bash
gh release create v0.6.39 --title "..." --notes "..." engCamera-v0.6.39-304.apk
```

Action 会自动跑：下载资产 → 传 R2 → 写 latest.json → **公网回读校验 SHA-256** → 失败则红叉告警。

补传历史版本（不重新发版）：

```bash
gh workflow run mirror-to-r2.yml -R zy8270/engCamera -f tag=v0.6.38
```

---

## 换成自定义域名（可选）

r2.dev 是 CF 官方标注的测试用域名，有速率限制。你账号下有 `eete.eu.org`、`mlmm.eu.org` 两个 zone，
想换成 `dl.eete.eu.org` 这类子域时：

```bash
# 1. 绑域名（会自动加 CNAME + 签证书，等几分钟生效）
wrangler r2 bucket domain add engcamera-releases \
  --domain dl.eete.eu.org \
  --zone-id <zone-id>

# 2. 改两个文件里的 R2_PUBLIC_BASE（共 2 处）
#    engCamera/.github/workflows/mirror-to-r2.yml
#    zy8270.github.io/docs/.vitepress/r2.ts
#    zy8270.github.io/scripts/fetch-release.mjs
```

zone id：`eete.eu.org` = `31fa181d36a11df10acdca1dabfb3bdb`，`mlmm.eu.org` = `189392eeaa8bfeac3768ff87814b4e48`

---

## 验证

```bash
# APK 可下且完整
curl -sIL https://pub-dd825b6251784cca9e7de8b1d0942078.r2.dev/latest/engCamera-latest.apk | head -3

# 元数据
curl -s https://pub-dd825b6251784cca9e7de8b1d0942078.r2.dev/latest.json

# 断点续传（Range 请求）
curl -s -r 0-99 -o /dev/null -w '%{http_code}\n' \
  https://pub-dd825b6251784cca9e7de8b1d0942078.r2.dev/latest/engCamera-latest.apk
```

---

## 注意事项

- **镜像就绪判定**：官网只在 R2 的 `latest.json` 里 `tag` 与 GitHub 最新 tag 一致时，才把 R2 设为首选下载源。
  否则按钮自动退回 GitHub —— 不会把访客送到 404。
- **缓存策略**：版本化路径 `releases/<tag>/<apk>` 是 `immutable` 一年；`latest.json` 与 `latest` 别名是 5 分钟，
  因为内容会变。
- **成本**：R2 存储约 $0.015/GB/月，egress 免费。40MB × 每个版本，留 100 个版本也就 4GB ≈ $0.06/月。
- **App 内更新检查未改**：仍走 `api.github.com` + Release 页面链接（用户决定本轮只优化下载体验）。
