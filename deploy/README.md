# Super Agent 本地 Docker 部署

把「文档问答 Agent」整套跑起来所需的编排都在这个目录里：基础设施（MySQL / Redis / Kafka / Elasticsearch / Neo4j / PGVector / MinIO）+ Docling 文档解析镜像 + 后端 Spring Boot 镜像 + 前端 Nginx 镜像。

## 一、目录结构

```
deploy/
├── docker-compose.yml       # 整套编排（10 个服务）
├── .env                     # 账号密码、模型 Key、数据源、上游地址、端口映射
├── backend/                 # 后端镜像：Dockerfile + app.jar
├── frontend/                # 前端镜像：Dockerfile + nginx.conf.template + dist/
├── docling/                 # Docling Serve 镜像：基于 v1.38.1，配置 HF 镜像与模型缓存
├── elasticsearch/           # ES 镜像：官方 8.18.0 + IK 中文分词插件
├── init/mysql/              # MySQL 首次启动建表脚本
├── init/postgres/           # PGVector 首次启动建表脚本
├── scripts/                 # 构建 / 启停 / 看日志
└── logs/                    # 后端运行日志（容器挂载出来）
```

镜像与数据卷全部由 Docker Desktop 管理，数据根目录是 `F:\Docker\DockerDesktopWSL`（Docker 设置里的 `CustomWslDistroDir`），**不写 C 盘**。

## 二、两种运行方式

基础设施（MySQL / Redis / Kafka / ES / Neo4j / PGVector / MinIO）和 Docling 解析服务始终跑在 Docker 里。

**前端容器不绑定后端容器**：它把 `/api`、`/manage`、`/admin/auth` 反代到 `.env` 里的 `BACKEND_UPSTREAM`（默认 `host.docker.internal:9082`，也就是宿主机 9082）。所以不管后端跑在容器里还是跑在 IDE 里，**http://localhost:8088 都能用**，只要 9082 上有进程在听。

### 方式 A：整套跑在容器里

```powershell
cd E:\nex-agent\super-agent\deploy
docker compose --profile app up -d
```

### 方式 B：后端在 IDE / 本地脚本里跑（开发调试）

```powershell
cd E:\nex-agent\super-agent\deploy
docker compose stop backend     # 释放 9082
```

然后在 IDEA 里 Run `SuperBusinessChatAgentApplication`（或跑 `start-backend.bat`），照样访问 http://localhost:8088 。也可以额外用 `start-frontend.bat`（vite dev，5173，带热更新）。

`application.yaml` 的默认连接地址在这套编排下全部可用：

| 应用里写的地址 | 实际连到 |
| --- | --- |
| `127.0.0.1:3306`（MySQL） | 本机原生 MySQL 8.0.34（`E:\mysql\mysql-8.0.34-winx64`，库表齐全） |
| `127.0.0.1:6378`（Redis） | 容器 `sa-redis` |
| `127.0.0.1:9092`（Kafka） | 容器 `sa-kafka`（宿主机 9092 → 容器 29092） |
| `127.0.0.1:9201`（ES） | 容器 `sa-elasticsearch`（含 IK 插件） |
| `127.0.0.1:7687`（Neo4j） | 容器 `sa-neo4j` |
| `127.0.0.1:5432`（PGVector） | 容器 `sa-pgvector` |
| `127.0.0.1:9000`（MinIO） | 容器 `sa-minio` |

切回方式 A：`docker compose --profile app up -d`（直接 `docker compose up -d` 只起基础设施 + 前端，不会占用 9082）。

> `docling` 不在 `app` profile 中：无论是否带 `--profile app`，`up.ps1` 都会启动 `sa-docling`。首次启动需要下载 Docling 模型，健康检查（`/ready`）通过前状态为 `starting`，变为 `healthy` 才表示模型已加载完成。

> 两套 MySQL 是两份数据：方式 A 写在容器 MySQL（宿主机 13306），方式 B 写在本机原生 MySQL（3306）。想共用一份数据，把 `.env` 里的 `SPRING_DATASOURCE_URL` 改成注释里给的 `host.docker.internal:3306` 那行，再 `docker compose --profile app up -d backend`。

## 三、端口一览

| 组件 | 宿主机地址 | 说明 |
| --- | --- | --- |
| 前端 | http://localhost:8088 | 两种方式都用它；反代到 `BACKEND_UPSTREAM` |
| 管理台登录 | http://localhost:8088/admin/login | 账号 `admin` / `admin123456` |
| 后端 | http://localhost:9082 | 容器版或 IDE 版，二选一（同时开就端口冲突） |
| MySQL | localhost:13306 | root / 123456，映射端口由 `.env` 的 `MYSQL_PORT` 控制 |
| Redis | localhost:6378 | 密码 123456 |
| Kafka | localhost:9092 | 容器内为 `kafka:9092`，宿主机映射 9092→29092 |
| Elasticsearch | http://localhost:9201 | elastic / 5KfP2kTHyp7sHoDmIBqL |
| Neo4j Browser | http://localhost:7474 | neo4j / 12345678，bolt 7687 |
| PGVector | localhost:5432 | postgres / postgres，库 `super_agent_pgvector` |
| MinIO 控制台 | http://localhost:9001 | minioadmin / minioadmin，API 端口 9000 |
| Docling | http://localhost:5001 | docling-serve（含 UI）；后端容器通过 `docling:5001` 访问 |

## 四、常用命令

```powershell
# 一键构建（后端 jar + 前端资源 + 四个应用镜像，含 Docling）
powershell -ExecutionPolicy Bypass -File .\scripts\build-images.ps1

# 启动（默认不含后端容器）/ 整套启动（-Full）
powershell -ExecutionPolicy Bypass -File .\scripts\up.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\up.ps1 -Full
powershell -ExecutionPolicy Bypass -File .\scripts\down.ps1

# 看后端日志
powershell -ExecutionPolicy Bypass -File .\scripts\logs.ps1
```

也可以直接在本目录使用 docker compose：

```powershell
docker compose ps
docker compose logs -f backend
docker compose restart backend
docker compose up -d --build

# 只处理 Docling
docker compose build docling
docker compose up -d docling
docker compose logs -f docling
docker compose restart docling
```

> 注意：`docker compose up -d frontend` 会因为 `depends_on` 顺带把后端容器也拉起来，占用 9082。开发时只用 `docker compose start frontend` 即可。

## 五、配置说明

- 账号密码、模型 Key、数据源、上游地址、端口映射统一放在 `.env`，`docker-compose.yml` 通过 `${VAR}` 引用。
- 后端连接信息全部用环境变量覆盖 `application.yaml`，容器之间走服务名：`SPRING_DATASOURCE_URL`、`SPRING_DATA_REDIS_HOST/PORT`、`SPRING_KAFKA_BOOTSTRAP_SERVERS`、`APP_MANAGE_PGVECTOR_HOST`、`APP_MANAGE_ELASTICSEARCH_URIS`、`APP_MANAGE_NEO4J_URI`、`APP_MANAGE_MINIO_ENDPOINT`。
- Docling 解析：`DOCUMENT_STRUCTURE_PROVIDER=docling` 时后端调用 `DOCLING_SERVE_URL`（容器内固定 `http://docling:5001`）；`DOCLING_DO_OCR`、`DOCLING_OCR_ENGINE`、`DOCLING_TABLE_MODE` 会以 `APP_MANAGE_STRUCTURE_PARSING_DOCLING_*` 注入，对应 `app.manage.structure-parsing.docling.*`。
- 前端上游：`BACKEND_UPSTREAM`（默认 `host.docker.internal:9082`），由启动时的 envsubst 渲染进 nginx 配置；改成 `backend:9082` 就走容器网络。
- 数据库表结构首次启动自动初始化：
  - `init/mysql/01_schema.sql` → 业务库 `super_agent_business_chat`
  - `init/postgres/01_schema.sql` → 向量库 `super_agent_pgvector`（含 `CREATE EXTENSION vector`）
- `sql/Mysql/星联智服与XX200知识路由初始化脚本.sql` 依赖真实上传后的 `document_id`，未纳入自动初始化，需要按脚本内注释在管理台操作后手动执行。

### Docling 文档解析

- 镜像：`super-agent-docling:1.38.1`，由 `docling/Dockerfile` 基于 `quay.nju.edu.cn/docling-project/docling-serve:v1.38.1` 构建；官方 `quay.io` 直连慢时，改 `.env` 的 `DOCLING_BASE_IMAGE` 后执行 `docker compose build docling`。
- 访问：宿主机 `http://localhost:5001`（带 Docling UI）；容器内后端固定使用 `http://docling:5001`。
- 模型：首次启动从 `HF_ENDPOINT`（默认 `https://hf-mirror.com`）下载，缓存在 `sa-docling-cache` 卷（容器内 `/opt/app-root/src/.cache`），重建容器不会重复下载。
- 开关：`.env` 的 `DOCUMENT_STRUCTURE_PROVIDER=docling` 启用 Docling，改为 `tika` 回退到原 Tika 规则解析；单次 Docling 调用失败时后端默认自动回退 Tika。

## 六、已知取舍与注意事项

- **Elasticsearch 用自建镜像**：工程用 Spring Boot 3.5 自带的 `elasticsearch-java 8.18` 客户端，服务端选 8.18.0；配置里 `analyzer=ik_max_word` / `search_analyzer=ik_smart`，官方镜像不带 IK，缺插件时 ES 直接抛 `mapper_parsing_exception` 导致后端起不来（代码里的回退只捕获 `IOException`，兜不住这个异常），因此 `elasticsearch/Dockerfile` 预装了 IK（`https://get.infini.cloud/elasticsearch/analysis-ik/8.18.0`）。
  - 想退回标准分词：给后端加 `APP_MANAGE_ELASTICSEARCH_ANALYZER=standard`、`APP_MANAGE_ELASTICSEARCH_SEARCH_ANALYZER=standard`，删掉三个索引后重启。
- **镜像下载走国内镜像源**：本机直连 `registry-1.docker.io` 不通，Docker Desktop 配了加速源；Neo4j 官方 tag 经加速源会卡住，实际是 `docker.m.daocloud.io/library/neo4j:5.26.4` 拉取后重打 `neo4j:5.26.4` 标签。
- **Docling 镜像走南京大学 quay 镜像**：`quay.m.daocloud.io` 未收录 `docling-serve`，因此默认使用 `quay.nju.edu.cn/docling-project/docling-serve:v1.38.1`；换源只需改 `.env` 的 `DOCLING_BASE_IMAGE` 后重建 `docling`。模型缓存卷 `sa-docling-cache` 首次下载后建议保留。
- **宿主机 3306 已被原生 MySQL 占用**：容器版 MySQL 映射到 `MYSQL_PORT`（默认 13306）；方式 B 直接复用原生实例。
- **Milvus 未启用**：当前后端只走 PGVector + Elasticsearch 双通道检索，仓库根目录那套 Milvus compose 不是运行依赖，未纳入，省约 2GB 镜像空间。

## 七、首次使用建议

1. 打开 http://localhost:8088/admin/login ，用 `admin / admin123456` 登录。
2. 在「文档接入」里上传 `需要的例子演示/` 下的示例文档。
3. 在文档详情页完成「确认策略方案」→「构建索引执行」，等 Kafka 异步流水线跑完。
4. 到对话页选择文档提问。

> 对话与向量化会真实调用阿里云百炼（DashScope）的 `qwen-plus-latest` 与 `text-embedding-v4`，额度消耗记在 `.env` 里的 `ALI_BAI_LIAN_API_KEY` 上。
