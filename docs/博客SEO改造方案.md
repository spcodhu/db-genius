# 博客 SEO 收录改造方案（v2）

**站点**：https://blogs.hlt.cab （主站：https://www.hlt.cab）
**撰写日期**：2026-07-29（v2 修订：纳入 Bing 站长工具建议 + 收录现状更正 + 全站范围排查方向）
**状态**：待实施

---

## 一、背景与诊断结论

### 1.1 现象

- Google Search Console（GSC）中 sitemap（`https://blogs.hlt.cab/sitemap.xml`）状态为"成功"，已发现 9 个网页（2026-06-28 提交）。
- **更正（v2）**：`site:hlt.cab` 并非完全没有结果——主站（个人主页）、博客站首页及其他业务子站**已被收录**，问题是：
  1. **博客文章页全部未被收录**（sitemap 中的 9 篇文章均无搜索结果）；
  2. 已收录页面的**搜索结果信息很少**（标题/摘要空洞）。

### 1.2 根因（已实测验证）

**sitemap "成功" ≠ 收录。** GSC 的"成功"只表示 sitemap 文件被解析，抓取、渲染、索引是后续独立环节。

实测模拟 Googlebot 抓取 `https://blogs.hlt.cab/post/5`，返回的完整 HTML 仅 **686 字节**：

```html
<title>Spcodhu</title>
<meta name="description" content="AI 时代下的个人博客" />
<body>
  <div id="app"></div>
</body>
```

结论：

1. **纯前端渲染（CSR）的 Vite/Vue SPA**——博客站所有 URL 返回同一个空壳 HTML，正文 100% 依赖浏览器执行 JS。
2. **全站所有页面 `<title>` / `description` 完全相同**（"Spcodhu" / "AI 时代下的个人博客"），HTML 层面无法区分任何页面。
3. 对搜索引擎而言，文章页抓取到的是一堆几乎一模一样、近乎空白的页面 → 被判"低价值/重复内容"，停留在 **"已发现/已抓取 – 尚未编入索引"**；而已收录的首页等页面，搜索结果里也只能展示那套通用 meta → **"信息很少"**。
4. 排除项（已验证无问题）：robots.txt 无拦截、无 noindex、HTTP 200、无 X-Robots-Tag。

### 1.3 Bing 站长工具建议的印证（2026-07-29 截图）

Bing Webmaster Tools 给出的三条 Top Recommendations：

| Bing 建议 | 对应根因 |
|---|---|
| ⚠️ Too many pages with **insufficient content**（内容不足） | Bing 爬虫同样只抓到 686 字节空壳，判定为"薄内容"。Bing 的 JS 渲染能力弱于 Google，受害更严重 |
| ⚠️ Many page **titles are too short**（标题过短） | 全站共用一个 7 字符标题 "Spcodhu" |
| ⚠️ **Meta descriptions too short**（描述过短） | 全站共用一个 12 字描述 "AI 时代下的个人博客" |

**三条建议与本文诊断完全互相印证**：这不是 Google 一家的问题，而是所有爬虫在 HTML 层面看到的都是同一个"薄内容壳"。修掉 CSR + 通用 meta 这两个根因，Bing 的三条警告会同时消失。

> 💡 收录现状的合理解释：主站/首页被收录，是因为单页应用首页 URL 少、Google 最终完成了 JS 渲染（首页渲染队列优先级相对高）；9 篇文章页则因"空壳 + 重复"被直接放弃。这也说明域名本身**没有被惩罚**，问题解决后收录上限没有封顶。

---

## 二、对标分析：高收录博客做对了什么

**对标对象**：阮一峰的网络日志（ruanyifeng.com）。以下均为 2026-07-29 实测数据。

### 2.1 实测样本

| 页面 | 原始 HTML 大小 | 正文在原始 HTML 中 | 独立 `<title>` |
|---|---|---|---|
| 周刊第 405 期 | 112,933 字节 | ✅ 完整 | `科技爱好者周刊（第 405 期）：资源，社会公平与算力 - 阮一峰的网络日志` |
| Anthropic 文章 | 132,663 字节 | ✅ 完整 | `Dario Amodei：AI 开源是伪命题 - 阮一峰的网络日志` |
| 首页 | 20,303 字节 | ✅ 最新文章列表 | `阮一峰的网络日志` |
| 归档页 | 22,657 字节 | ✅ 全部文章链接 | — |

### 2.2 值得学习的做法清单

1. **服务端渲染静态 HTML（最根本）**——正文、标题、链接 100% 在原始 HTML 中。
2. **每页唯一且描述性的 `<title>`**——`文章标题 - 站点名`。
3. **语义化、人类可读的 URL**——`/blog/2026/07/weekly-issue-405.html`。
4. **清晰标题层级**——每页一个 `<h1>`，小节用 `<h2>`。
5. **完整内链网络**——首页文章列表、上一篇/下一篇、月度+总归档页，**每篇文章从首页 ≤2 次点击可达**。
6. **RSS / Atom Feed**。
7. **robots.txt 放开搜索爬虫**，页面轻量、几乎无阻塞 JS。

> 💡 该站甚至没有 meta description、canonical、JSON-LD，收录依然极好——**"内容在 HTML 里 + 唯一标题 + 可爬链接结构"是根本，meta 优化是加分项**。

### 2.3 差距对比（v2 扩展：覆盖博客首页与主站）

| 维度 | 阮一峰博客 | blogs.hlt.cab | www.hlt.cab（主站） |
|---|---|---|---|
| 正文渲染 | 服务端静态 HTML | ❌ 纯 CSR 空壳（686B） | ❌ 纯 CSR 空壳（483B） |
| 每页 `<title>` | 唯一、含关键词 | ❌ 全站相同 "Spcodhu"（过短） | ❌ 仅 "Spcodh个人主页" |
| `description` | 未设（Google 自动摘取） | ❌ 全站相同、过短（12 字） | ❌ 未设置 |
| 首页 HTML 中的内容链接 | ✅ 文章列表 | ❌ 无任何链接 | ❌ 无任何链接 |
| 内链导航 | 上/下篇 + 归档 | ❌ 无 | ❌ 无 |
| 裸域 hlt.cab 可用性 | — | — | ❌ **502 Bad Gateway**（见 §六） |

---

## 三、改造方案（按优先级）

### P0-1 渲染层改造：让正文出现在原始 HTML 中

| 方案 | 成本 | 效果 | 适用场景 |
|---|---|---|---|
| **A. 构建时预渲染**（`vite-plugin-prerender`） | ★ 低 | 每个路由生成独立静态 HTML | 文章少、更新频率低（**短期推荐**） |
| B. 迁移 SSR（Nuxt 3） | ★★★ 高 | 任意内容服务端渲染 | 内容来自后端 API、动态发布 |
| C. 迁移 SSG（Astro / VitePress） | ★★ 中 | 全静态站 | Markdown 写作流，长期最优 |

**方案 A 关键实施点**：

1. 安装 `vite-plugin-prerender` + `@prerenderer/renderer-puppeteer`，在 `vite.config.js` 配置 `routes`（`/` + 全部 `/post/:id` 文章页）。
2. 渲染等待条件设为正文容器出现（`renderAfterElementExists`）。
3. **验收**：构建产物中 `post/5/index.html` 必须包含文章标题与正文文本（grep 验证）。

### P0-2 全站 Metadata 体系（v2 新增，覆盖所有页面类型）

为每种页面类型定义独立 meta 模板（预渲染时快照 / SSR 用 `useHead`）：

| 页面类型 | title 模板 | description 模板 | 示例 |
|---|---|---|---|
| 博客首页 | `Spcodhu 的博客 - AI 与前端开发实践记录`（20–30 字，含核心关键词） | 博客定位 + 内容范围 + 更新频率（50–80 字） | "记录 AI 应用、Vue/Vite 前端工程化与云部署的实战笔记，每周更新。" |
| 文章页 | `文章标题 - Spcodhu 的博客`（≤60 字符） | 文章摘要首段（120–160 字符） | 由文章内容自动生成 |
| 归档/列表页 | `文章归档 - Spcodhu 的博客` | 文章数量 + 时间范围说明 | — |
| 主站首页（www.hlt.cab） | 现 "Spcodh个人主页" 过短，扩为含身份/方向关键词的完整标题 | 补充 50–80 字个人介绍 | — |

同时每页加：`<link rel="canonical">`、`og:title` / `og:description` / `og:type`（文章页为 `article`）/ `og:url`。

### P0-3 博客首页与主站首页的 HTML 内容（v2 新增）

首页同样是空壳，即使被收录，搜索结果也没有可展示的摘要。需保证**原始 HTML** 中至少包含：

- 博客首页：一个 `<h1>`（如 "Spcodhu 的博客"）+ 一段站点简介文本 + **最新文章列表的静态 `<a>` 链接**（标题作锚文本）；
- 主站首页：`<h1>` + 个人简介段落 + 指向各子站（含 blogs.hlt.cab）的静态链接——**主站到博客的链接本身就是重要的站内权重传递与爬虫入口**；
- 主站与博客互相链接（博客页头/页脚放"返回主页"链接）。

### P1-1 结构化数据（JSON-LD）

- 文章页注入 `BlogPosting`（headline / datePublished / dateModified / author / mainEntityOfPage）；
- 首页注入 `WebSite` + `Person`（author）。

### P1-2 内链结构

- 文章页底部"上一篇 / 下一篇"导航（完整标题作锚文本）；
- 新增归档页（按月分组列出全部文章）；
- 正文相关文章互链；目标：每篇文章从首页 ≤2 次点击可达。

### P1-3 URL 与 sitemap 规范

- 新文章统一 `/post/{id}/{slug}`；旧 `/post/5` 形式保留即可；
- sitemap `<lastmod>` 填**真实修改时间**（当前部分日期需核对，如 2026-03-11）；
- 文章发布/更新后重新生成 sitemap。

### P2 增强项

- RSS / Atom feed（`/feed.xml`）并在 robots 或首页 `<link rel="alternate">` 声明；
- 性能优化（JS 体积、图片懒加载）；
- 外链建设：GitHub / 掘金 / 知乎 / V2EX 发摘要引流（新域名效果显著）；
- Bing 侧：改造后在 Bing WMT 用 URL Inspection 提交，并利用 Bing 的 **IndexNow** 协议（新内容秒级推送，Bing/Yandex 支持，实现成本极低）。

---

## 四、GSC / Bing WMT 操作流程（改造上线后）

1. **Google**：重新提交 sitemap → 逐篇 URL 检查 → "测试实际网址" → "查看被抓取的页面"确认截图含正文 → "请求编入索引"（9 篇分 2–3 天提交）。
2. **Bing**：URL Inspection 逐篇提交 + 接入 IndexNow；观察三条 Top Recommendations 是否消除。
3. **监控**：GSC"网页编入索引"报告中"已发现/已抓取 – 尚未编入索引"应转为"已编入索引"；正常节奏 1–2 周起效，自然爬取 4–8 周。
4. **验证**：`site:blogs.hlt.cab` 出现文章页；用文章标题精确搜索可命中；摘要显示文章独有内容。

---

## 五、实施路线图

| 阶段 | 时间 | 内容 |
|---|---|---|
| 第 1 周 Day 1–2 | | 博客站接入预渲染；全页面类型 meta 模板落地（含首页） |
| 第 1 周 Day 3 | | JSON-LD、内链导航、首页静态文章列表；主站首页补 meta + 静态链接 |
| 第 1 周 Day 4–5 | | 裸域 502 修复（见 §六）；构建验收 → 部署 → GSC/Bing 重新提交 |
| 第 2 周 | | RSS、归档页、IndexNow；外部平台引流 |
| 第 3–4 周 | | 监控收录；仍 0 收录则复查抓取截图与服务器日志 |
| 中期（可选） | | 评估迁移 Nuxt 3 / Astro；主站同样预渲染 |

---

## 六、下一轮改造的排查清单（v2 新增）

下一轮动手前，按此清单逐项确认，可快速进入正确方向：

### 6.1 必须先定位的问题

- [ ] **裸域 `https://hlt.cab` 返回 502 Bad Gateway**（2026-07-29 实测，`www.hlt.cab` 正常）。需确认：nginx 是否配置了裸域 server block？是否应 301 到 www？裸域在 GSC 里是否单独验证过？——裸域若长期 502，会浪费已收录数据并损害域名整体信任度。
- [ ] GSC 中验证的**资源类型**是 Domain（hlt.cab）还是 URL prefix（blogs.hlt.cab）？影响数据口径。
- [ ] GSC"网页编入索引"报告里 9 篇文章的**具体状态文案**（"已发现 – 尚未编入索引" vs "已抓取 – 尚未编入索引"）——前者说明 Google 连抓都还没抓，后者说明抓了但拒绝收，两者都指向本方案，但优先级解读不同。
- [ ] 服务器访问日志中 Googlebot / Bingbot 对 `/post/*` 的实际抓取记录（频率、状态码）。

### 6.2 实施前需要确认的输入

- [ ] 文章数据来源：后端 API（动态）还是本地 Markdown/JSON（静态）？——决定方案 A（预渲染）是否够用，还是直接上 SSR。
- [ ] 构建与部署方式：nginx 静态托管（当前迹象）还是 Node 服务？——决定预渲染产物的部署路径。
- [ ] 现有路由清单：除 `/`、`/post/:id` 外是否还有归档、标签、关于页？——meta 模板需全覆盖。
- [ ] 是否已验证 Bing WMT 的网站所有权 + 提交 sitemap？

### 6.3 验收清单（改造完成后逐项打勾）

- [ ] `curl https://blogs.hlt.cab/post/5` 原始 HTML 含文章标题和正文
- [ ] `curl https://blogs.hlt.cab` 原始 HTML 含 h1、简介、文章链接列表
- [ ] 每种页面类型 title/description 唯一且达到长度要求（title ≥ 20 字、description ≥ 50 字）
- [ ] 每篇文章有 canonical、og:*、BlogPosting JSON-LD（富媒体测试工具通过）
- [ ] 裸域 hlt.cab 恢复 200 或 301 到 www
- [ ] GSC/Bing 抓取截图显示完整正文
- [ ] Bing WMT 三条 Recommendations 消除
- [ ] 2–4 周后 `site:blogs.hlt.cab` 出现文章页

---

## 附录：实测证据汇总（2026-07-29）

| 检查项 | 结果 |
|---|---|
| Googlebot 抓取 `/post/5` | HTTP 200，**686 字节空壳**，无正文 |
| 博客首页原始 HTML | 同样 686 字节空壳，title "Spcodhu" |
| 主站 www.hlt.cab | HTTP 200，**483 字节空壳**，同为 CSR SPA，title "Spcodh个人主页"，无 description |
| **裸域 hlt.cab** | **HTTP 502 Bad Gateway**（www 正常） |
| robots.txt（blogs） | `Allow: /post/`，sitemap 已声明 ✅ |
| noindex / X-Robots-Tag | 不存在 ✅ |
| GSC sitemap | "成功"，已发现 9 网页（2026-06-28） |
| Bing WMT | 三条警告：薄内容 / 标题过短 / 描述过短 |
| 收录现状 | 主站、博客首页、部分子站已收录；9 篇文章 0 收录 |
| 对标站文章页 | 113KB 静态 HTML，正文完整，title 独立 |
