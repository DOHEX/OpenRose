# OpenRose 网站

OpenRose 的用户文档站点，基于 [Astro](https://docs.astro.build) + [Starlight](https://starlight.astro.build)，部署在 <https://dohex.github.io/OpenRose/>。

## 命令

在 `website/` 下运行：

| 命令 | 作用 |
| --- | --- |
| `pnpm install` | 安装依赖 |
| `pnpm dev` | 本地预览，地址为 `http://localhost:4321/OpenRose/` |
| `pnpm build` | 构建到 `dist/` |
| `pnpm preview` | 预览构建结果 |

## 结构

- `src/content/docs/`：简体中文页面（默认语言，位于站点根路径）
- `src/content/docs/en/`：英文页面，与中文页面一一对应、路径相同
- `astro.config.mjs`：站点地址、语言与侧边栏；新增页面要在 `sidebar` 中登记

## 写作约定

- 站内链接用相对路径（如 `../background-control/`）。站点部署在 `/OpenRose/` 子路径下，以 `/` 开头的链接会丢掉这个前缀。
- 指向仓库文件的链接用 `https://github.com/DOHEX/OpenRose/blob/main/...` 完整地址。
- 术语与 [GLOSSARY.md](../GLOSSARY.md) 一致；功能与验证状态以仓库 [README](../README.md) 为准，两边需同步更新。

## 部署

推送到 `main` 且改动涉及 `website/**` 时，[Deploy Docs](../.github/workflows/deploy-docs.yml) 工作流会构建并发布到 GitHub Pages，也可以在 Actions 页面手动触发。
