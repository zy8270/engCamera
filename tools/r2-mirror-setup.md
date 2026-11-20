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
- 公网地址：`https://dl.mlmm.eu.org`（自定义域名，走 CF 边缘节点；r2.dev 开发域名 `pub-dd825b…r2.dev` 仍可用但有限速，仅作备用）
- 域名绑定：`wrangler r2 bucket domain add engcamera-releases --domain dl.mlmm.eu.org --zone-id 189392eeaa8bfeac3768ff87814b4e48 --min-tls 1.2`
- CORS 已配置（允许 `engcamera.vgcad.com` / `zy8270.github.io` / localhost 的 GET、HEAD）——**CORS 约束的是调用方页面 origin，与下载域名无关，换域名不用改**

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

## 自定义域名（已启用）

当前用的是 `dl.mlmm.eu.org`（绑在 `mlmm.eu.org` zone 上）。根域 `mlmm.eu.org` 本身有个 302
跳转到自建服务，所以刻意用子域，不动根域。

再换域名时（比如换成 `dl.eete.eu.org`，zone id `31fa181d36a11df10acdca1dabfb3bdb`）：

```bash
# 1. 绑新域名（自动加 CNAME + 签证书，几分钟生效）
wrangler r2 bucket domain add engcamera-releases \
  --domain dl.eete.eu.org \
  --zone-id 31fa181d36a11df10acdca1dabfb3bdb \
  --min-tls 1.2

# 2. 改 3 个文件里的 R2_PUBLIC_BASE
#    engCamera/.github/workflows/mirror-to-r2.yml
#    zy8270.github.io/docs/.vitepress/r2.ts
#    zy8270.github.io/scripts/fetch-release.mjs

# 3. 重新写一遍 R2 上的 latest.json（里面的 downloadUrl 是绝对地址），
#    或者直接跑一次 gh workflow run mirror-to-r2.yml -f tag=<当前版本>

# 4. 查状态：ssl_status 从 pending 变 active 才算生效
wrangler r2 bucket domain list engcamera-releases
```

旧域名可以留着并存（两个域名指向同一个桶），也可以 `wrangler r2 bucket domain remove` 摘掉。

---

## 验证

```bash
# APK 可下且完整
curl -sIL https://dl.mlmm.eu.org/latest/engCamera-latest.apk | head -3

# 元数据
curl -s https://dl.mlmm.eu.org/latest.json

# 断点续传（Range 请求）
curl -s -r 0-99 -o /dev/null -w '%{http_code}\n' \
  https://dl.mlmm.eu.org/latest/engCamera-latest.apk
```

---

## 注意事项

- **镜像就绪判定**：官网只在 R2 的 `latest.json` 里 `tag` 与 GitHub 最新 tag 一致时，才把 R2 设为首选下载源。
  否则按钮自动退回 GitHub —— 不会把访客送到 404。
- **缓存策略**：版本化路径 `releases/<tag>/<apk>` 是 `immutable` 一年；`latest.json` 与 `latest` 别名是 5 分钟，
  因为内容会变。
- **成本**：R2 存储约 $0.015/GB/月，egress 免费。40MB × 每个版本，留 100 个版本也就 4GB ≈ $0.06/月。
- **App 内更新检查未改**：仍走 `api.github.com` + Release 页面链接（用户决定本轮只优化下载体验）。
