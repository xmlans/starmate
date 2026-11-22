# 星伴介绍页

线上地址：<https://starmate.xmyun.org>

纯静态页面，不需要构建，把整个 `web` 文件夹放到任何静态托管上就能用。

| 文件 | 内容 |
|---|---|
| `index.html` | 页面结构和全部文案 |
| `style.css` | 样式，浅色和深色两套配色 |
| `main.js` | 浅色/深色切换、进场动画、从 GitHub Releases 读取最新安装包 |
| `assets/` | 图标、压缩后的截图、字体子集和字体许可 |
| `robots.txt`、`sitemap.xml` | 给搜索引擎的说明，里面写着线上地址 |
| `tools/build_assets.py` | 重新生成截图和字体子集 |

## 本地预览

```bash
python -m http.server 8000 --directory web
```

然后打开 <http://localhost:8000>。直接双击 `index.html` 也能看，但浏览器可能不加载字体。

## 下载按钮怎么工作

页面打开时，`main.js` 会请求 GitHub API 读取 `xmlans/starmate` 的 Releases：

- 正式版按钮：指向最新正式发布里文件名不含 `beta`、`play` 的 `.apk`。
- Beta 版按钮：指向最新的文件名含 `beta` 的 `.apk`，放在预发布或正式发布里都可以。
- 按钮下方显示版本号、大小和发布日期。GitHub 提供校验值时，还能展开查看 SHA-256。

读不到时（离线、被限流、还没发版），按钮保持指向 Releases 页面。所以发版时把 APK 作为附件上传到 Release 就行，不用改页面。

## 改了文案之后

字体只保留了页面上用到的字符。改了 `index.html` 或 `main.js` 里的文字后，在仓库根目录运行：

```bash
pip install fonttools brotli pillow
python web/tools/build_assets.py
```

不重新生成也能显示，只是新加的字会用系统字体。

## 托管

现在用 GitHub Pages 发布，绑定域名 `starmate.xmyun.org`：

- `.github/workflows/pages.yml` 在 `main` 分支的 `web/` 有改动时自动发布，也可以在 Actions 页面手动运行。发布时会去掉 `tools/` 和这份 README。
- 仓库 Settings → Pages 的 Source 选 **GitHub Actions**，Custom domain 填 `starmate.xmyun.org`。
- DNS 里有一条 CNAME：`starmate` 指向 `xmlans.github.io`。

换域名的话，同时改 `index.html` 里的 `canonical`、`og:url` 和 `url`，以及 `robots.txt`、`sitemap.xml`。

也可以放到自己的服务器或任意静态托管上，上传 `web` 文件夹的内容即可。
