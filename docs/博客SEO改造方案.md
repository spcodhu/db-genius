# 博客 SEO 收录改造方案

**站点**：https://blogs.hlt.cab
**撰写日期**：2026-07-29
**状态**：待实施

---

## 一、背景与诊断结论

### 1.1 现象

- Google Search Console（GSC）中 sitemap（`https://blogs.hlt.cab/sitemap.xml`）状态为"成功"，已发现 9 个网页（2026-06-28 提交）。
- 但在 Google 搜索 `site:hlt.cab` / `site:blogs.hlt.cab` 均无任何结果，即 **sitemap 已提交一个月，页面 0 收录**。

### 1.2 根因（已实测验证）

**sitemap "成功" ≠ 收录。** GSC 的"成功"只表示 sitemap 文件被成功解析，URL 进入了待抓取清单；抓取、渲染、索引是后续独立环节。

实测模拟 Googlebot 抓取 `https://blogs.hlt.cab/post/5`，返回的完整 HTML 仅 **686 字节**：

```html
<title>Spcodhu</title>
<meta name="description" content="AI 时代下的个人博客" />
<script type="module" crossorigin src="/assets/index-CAl005SO.js"></script>
<body>
  <div id="app"></div>
</body>
```

结论：

1. **纯前端渲染（CSR）的 Vite/Vue SPA**——所有 URL 返回同一个空壳 HTML，正文 100% 依赖浏览器执行 JS 后才出现。
2. **所有页面的 `<title>` / `description` 完全相同**（均为 "Spcodhu" / "AI 时代下的个人博客"），HTML 层面无法区分任何文章。
3. 对 Google 而言，9 个 URL 抓取到的是 9 份几乎一模一样、近乎空白的页面 → 极易被判为"低价值/重复内容"，状态停留在 **"已发现 – 尚未编入索引"** 或 **"已抓取 – 尚未编入索引"**。
4. 排除项（已验证无问题）：robots.txt 无拦截、无 noindex、HTTP 200、无 X-Robots-Tag。

> ⚠️ 新域名 + 无外链 + 低权重进一步放大了上述问题：Google 对新站的 JS 二次渲染排期极慢（可数周至数月），且对"渲染前几乎无内容"的页面倾向直接放弃。

---

## 二、对标分析：高收录博客做对了什么

**对标对象**：阮一峰的网络日志（ruanyifeng.com）——国内技术博客中收录率与搜索可见度最高的站点之一。以下均为 2026-07-29 实测数据。

### 2.1 实测样本

| 页面 | 原始 HTML 大小 | 正文是否在原始 HTML 中 | 独立 `<title>` |
|---|---|---|---|
| 周刊第 405 期 | 112,933 字节 | ✅ 完整正文（"周刊"出现 13 次） | `科技爱好者周刊（第 405 期）：资源，社会公平与算力 - 阮一峰的网络日志` |
| Anthropic 文章 | 132,663 字节 | ✅ 完整正文 | `Dario Amodei：AI 开源是伪命题 - 阮一峰的网络日志` |
| 首页 | 20,303 字节 | ✅ 最新文章列表 | `阮一峰的网络日志` |
| 归档页 | 22,657 字节 | ✅ 全部文章链接 | — |

### 2.2 值得学习的做法清单

1. **服务端渲染的静态 HTML（最根本的一条）**
   文章正文、标题、链接 100% 存在于原始 HTML 中，爬虫不执行任何 JS 即可读到全部内容。抓取成本几乎为零。
2. **每页唯一且描述性的 `<title>`**
   格式为 `文章标题 - 站点名`，标题本身包含关键词（期号、主题词），直接决定搜索结果中的展示文案。
3. **语义化、人类可读的 URL**
   `/blog/2026/07/weekly-issue-405.html`：含日期 + 语义 slug，搜索引擎和用户都能从 URL 预判内容。
4. **清晰的标题层级**
   每页仅一个 `<h1>`（文章标题），小节用 `<h2>`（封面图 / 科技动态 / 工具 / 资源……），帮助 Google 理解页面结构与提炼摘要。
5. **完整的内链网络（不依赖 sitemap 的抓取路径）**
   - 首页直接列出最新文章链接；
   - 每篇文章有"上一篇 / 下一篇"导航（带完整标题的锚文本）；
   - 月度归档 + 总归档页，使**每篇文章都能通过 ≤2 次点击从首页爬到**。
6. **RSS / Atom Feed**
   提供 `atom.xml`，既是用户订阅入口，也是搜索引擎发现新内容的辅助渠道。
7. **robots.txt 对搜索引擎完全放开**（`Allow: /`），仅屏蔽 AI 训练爬虫；页面轻量、几乎无阻塞 JS。

> 💡 **值得注意**：该站甚至没有写 `meta description`、`canonical`、结构化数据（JSON-LD），收录依然极好。这印证了 SEO 的优先级：**"内容在 HTML 里 + 唯一标题 + 可爬链接结构"是根本，meta 优化是加分项，不能本末倒置。**

### 2.3 与 blogs.hlt.cab 的差距对比

| 维度 | 阮一峰博客 | blogs.hlt.cab（现状） |
|---|---|---|
| 正文渲染 | 服务端静态 HTML | ❌ 纯 CSR，原始 HTML 为空壳 |
| 每页 `<title>` | 唯一、含关键词 | ❌ 全部相同（"Spcodhu"） |
| 每页 `description` | 未设置（Google 自动摘取正文） | ❌ 全部相同，且无正文可摘 |
| URL 结构 | 日期 + 语义 slug | ⚠️ 部分为 `/post/5` 纯数字 ID |
| 标题层级 h1/h2 | 规范 | ❌ 原始 HTML 中不存在 |
| 内链导航 | 上/下篇 + 归档 + 首页列表 | ❌ 原始 HTML 中无任何链接 |
| robots.txt | 放开搜索爬虫 | ✅ 基本正常 |
| sitemap | 正常 | ✅ 正常 |

---

## 三、改造方案

按优先级分为 P0（不做则无法收录）、P1（显著提升收录质量）、P2（锦上添花）。

### P0-1 渲染层改造：让正文出现在原始 HTML 中

三个方案，按改造成本排序：

| 方案 | 成本 | 效果 | 适用场景 |
|---|---|---|---|
| **A. 构建时预渲染**（`vite-plugin-prerender` / `prerender-spa-plugin`） | ★ 低 | 每个路由生成独立静态 HTML | 文章数量少、更新频率低的纯静态博客（**推荐**） |
| B. 迁移 SSR 框架（Nuxt 3） | ★★★ 高 | 任意内容服务端渲染 | 有后端 API、内容动态变化的站点 |
| C. 迁移 SSG 框架（Astro / VitePress） | ★★ 中 | 构建时生成全静态站 | 以 Markdown 写作为主的博客，长期最优 |

**推荐路线**：

- **短期（本周内）**：方案 A。在现有 Vite 项目中加入 `vite-plugin-prerender`，构建时遍历文章列表（从后端 API 或本地数据取全部文章 ID），为 `/`、`/post/:id` 各路由预渲染出含完整正文的静态 HTML。改动最小，不动现有架构。
- **中期（可选）**：若文章数据来自后端接口、希望写作即发布，可迁 Nuxt 3 SSR；若可接受 Markdown/静态数据工作流，Astro 是最省心且 SEO 天然友好的终态。

**方案 A 关键实施点**：

1. 安装 `vite-plugin-prerender`，在 `vite.config.js` 中配置 `routes`（含全部文章页）与 `renderer`（`@prerenderer/renderer-puppeteer`）。
2. 渲染等待条件设为正文容器出现（`renderAfterElementExists`），保证抓取到的是渲染后的完整 HTML。
3. 构建后**验收**：`curl https://localhost/post/5` 返回的 HTML 中必须能直接看到文章标题与正文。

### P0-2 每页独立的 meta 信息

预渲染/SSR 的同时，为每个路由注入独立的 head：

- `<title>`：`文章标题 - Spcodhu`（≤ 60 字符，关键词前置）；
- `<meta name="description">`：文章摘要（120–160 字符，取正文首段或手动撰写）；
- `<link rel="canonical" href="完整 URL">`：防止带参数/斜杠变体造成重复收录；
- Open Graph：`og:title` / `og:description` / `og:type=article` / `og:url`（利于社交分享，间接引流）。

实现方式：预渲染场景用路由守卫 + 手动操作 `document.head` 后由 prerenderer 快照；SSR/SSG 场景用框架自带的 head 管理（Nuxt `useHead` / Astro frontmatter）。

### P1-1 结构化数据（JSON-LD）

每篇文章注入 `BlogPosting` schema，提升富媒体搜索结果出现概率：

```json
{
  "@context": "https://schema.org",
  "@type": "BlogPosting",
  "headline": "文章标题",
  "datePublished": "2026-05-22",
  "dateModified": "2026-05-22",
  "author": { "@type": "Person", "name": "作者名" },
  "mainEntityOfPage": "https://blogs.hlt.cab/post/5"
}
```

### P1-2 内链结构

- 首页原始 HTML 中直接输出文章列表的 `<a>` 链接（当前为空）；
- 文章页底部加"上一篇 / 下一篇"导航，锚文本用完整标题；
- 增加归档页（按月份分组列出全部文章）；
- 正文中的相关文章互相链接。

目标：**每篇文章从首页出发 ≤ 2 次点击可达**，让 Google 不依赖 sitemap 也能发现全部内容。

### P1-3 URL 与 sitemap 规范

- 新文章统一使用 `/post/{id}/{slug}` 语义化 URL（已有的 `/post/5` 形式保留并做 301 或维持现状均可，不必强制迁移旧链接）；
- sitemap 的 `<lastmod>` 填写**真实的文章修改时间**（当前部分日期可疑，如 2026-03-11 的 lastmod 与实际情况需核对）；
- 文章发布/更新后重新生成 sitemap，保持 GSC 读取到新鲜数据。

### P2 增强项

- **RSS / Atom feed**（`/feed.xml`），并在 sitemap 或 robots 中声明；
- **性能**：压缩 JS 体积、图片懒加载——Core Web Vitals 是排名信号之一；
- **外链建设**：在 GitHub、掘金、知乎、V2EX 等已收录平台发布文章摘要并链接回博客，加速新站信任度积累（对新域名效果显著）；
- robots.txt 保持现状即可（已对 `/post/` 放开、声明了 sitemap）。

---

## 四、GSC 操作流程（改造上线后）

1. **重新提交 sitemap**：确认 sitemap 内容更新后，在 GSC 删除旧 sitemap 再重新提交，触发重新读取。
2. **逐篇验证 + 手动请求索引**：
   - 网址检查（URL Inspection）输入文章 URL → "测试实际网址" → **"查看被抓取的页面"**，截图中必须能看到完整正文（改造前此处应为空白/壳）；
   - 确认渲染正常后点 **"请求编入索引"**（每 URL 每日有配额，9 篇可分 2–3 天提交完）。
3. **监控"网页编入索引"报告**：
   - 改造前预期的"已发现/已抓取 – 尚未编入索引"应逐步转为"已编入索引"；
   - 正常节奏：请求索引后 **1–2 周内**开始收录；自然爬取则可能需要 4–8 周。
4. **验证搜索可见**：`site:blogs.hlt.cab` 应逐步出现结果；用文章标题精确搜索应能命中。

---

## 五、实施路线图

| 阶段 | 时间 | 内容 |
|---|---|---|
| 第 1 周 | Day 1–2 | 接入 `vite-plugin-prerender`，全路由预渲染；每页独立 title/description/canonical |
| 第 1 周 | Day 3 | JSON-LD、内链导航（上/下篇）、首页静态文章列表 |
| 第 1 周 | Day 4–5 | 构建验收（curl 验证原始 HTML 含正文）→ 部署 → GSC 重新提交 sitemap + 逐篇请求索引 |
| 第 2 周 | — | RSS feed、归档页；开始在 2–3 个外部平台发文引流 |
| 第 3–4 周 | — | 监控 GSC 收录状态；若 2 周后仍 0 收录，复查抓取截图与服务器日志中 Googlebot 访问记录 |
| 中期（可选） | — | 评估迁移 Nuxt 3 SSR 或 Astro SSG |

---

## 六、验收清单（Checklist）

- [ ] `curl https://blogs.hlt.cab/post/5` 返回的原始 HTML 中包含文章标题和正文段落
- [ ] 每篇文章 `<title>` 唯一且包含文章标题
- [ ] 每篇文章有独立 `description`、`canonical`、`og:*`
- [ ] 每篇文章有 `BlogPosting` JSON-LD（可用 Google 富媒体测试工具验证：https://search.google.com/test/rich-results）
- [ ] 首页原始 HTML 中可直接看到文章链接列表
- [ ] 文章页有上一篇/下一篇导航
- [ ] sitemap `lastmod` 为真实修改时间
- [ ] GSC"查看被抓取的页面"截图显示完整正文
- [ ] 全部 9 篇已"请求编入索引"
- [ ] 2–4 周后 `site:blogs.hlt.cab` 有结果

---

## 附录：本次诊断实测证据（2026-07-29）

| 检查项 | 方法 | 结果 |
|---|---|---|
| Googlebot 抓取文章页 | `curl -A "Googlebot"` → `/post/5` | HTTP 200，**686 字节空壳** |
| 文章页原始 HTML | 全文检查 | 仅 `index.html` 模板，无正文、无独立 title |
| robots.txt | 直接获取 | `Allow: /post/`，sitemap 已声明 ✅ |
| noindex / X-Robots-Tag | 响应头与 HTML 检查 | 不存在 ✅ |
| sitemap 内容 | 直接获取 | 10 个 URL，格式合法 ✅ |
| GSC sitemap 状态 | 用户截图 | "成功"，已发现 9 网页（2026-06-28） |
| 对标站文章页 | 获取原始 HTML | 113KB，正文完整，title 独立 ✅ |
