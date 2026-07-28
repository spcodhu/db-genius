## 背景

DB-Genius 接入了 MySQL、MongoDB、PostgreSQL 三种数据库，对应简单聊天、数据库查询工作流、数据库对比三种模式的 AI 会话。均需要测试。

## 附：SQLite 支持移除的决策记录

SQLite 曾作为第四种目标数据库接入，现已移除。调研结论与原因如下，备查：

- **SQLite 不支持远程连接**：它是嵌入式数据库，没有服务端进程、不监听端口、
  没有网络协议和账号体系，应用只能通过文件路径直接读写 `.db` 文件
  （[SQLite 官方说明](https://sqlite.org/useovernet.html)）。
- **与 SaaS 形态不符**：本系统按 SaaS 形态设计，用户的目标库都是远程独立部署的；
  而 SQLite 要求 `.db` 文件与 DB-Genius 应用同机（或网络挂载，官方明确不推荐，
  文件锁不可靠有损坏风险），真实用户场景不存在。
- **安全隐患**：原实现 `jdbc:sqlite:<dbName>` 由 xerial 驱动以 `READWRITE|CREATE`
  模式打开——用户填一个不存在的路径（父目录存在即可）时，会在 DB-Genius 服务器上
  误建空数据库文件且"验证连接"误报成功，既是排错陷阱也是文件系统攻击面。

因此 SQLite 支持已整体移除（枚举、适配器、驱动依赖、文档），测试范围收敛为
MySQL / PostgreSQL / MongoDB 三种。
