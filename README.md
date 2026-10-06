# local-versioned-object-store-java

基于本地磁盘的版本化对象存储服务（Spring Boot, Java 21）。通过 HTTP 接口上传、
下载、删除对象，支持多版本、内容去重和并发安全，所有行为均有单元测试验证。

## 存储模型

存储根目录由 `store.root` 配置（默认 `./data`），布局：

```
data/
  blobs/<sha256>                 # 去重后的内容块，按 SHA-256 寻址，全 store 唯一
  objects/<base64url(key)>.json  # 每个对象的版本链元数据（JSON，原子写）
```

- **对象（object）**：由 key 标识，持有一条递增编号的版本链。
- **版本（version）**：记录 `version / sha256 / size / createdAt / deleted`。
  删除的版本保留为墓碑（`deleted=true`），因此“版本已删除”（410）与
  “版本不存在”（404）可区分。
- **内容块（blob）**：实际字节只按 SHA-256 存一份。服务为每个 blob 维护
  引用计数（指向它的存活版本数），归零时才物理删除；启动时会从元数据
  重建引用计数并清扫孤儿 blob。

## 版本与去重规则

- 每次 `PUT` 生成一个新版本（版本号单调递增），历史版本可按号取回；
  不带版本号的 `GET` 返回最新的存活版本。
- 内容完全相同的对象/版本共享同一个 blob（`PUT` 响应中 `deduplicated=true`）。
- 删除某个版本只递减引用计数，不影响引用同一内容的其它对象和版本。
- 上传可携带 `X-Content-Sha256` 头，校验失败（400）时不落任何数据；
  每次读取都会重新校验 blob 摘要，校验不通过拒绝返回（500），绝不给出坏数据。
- 并发一致性由全局公平读写锁保证：任意上传/删除/读取交错下，版本链、
  引用计数与磁盘文件始终一致。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/objects/{key}` | 上传新版本；可选头 `X-Content-Sha256` 启用上传校验 |
| `GET` | `/objects/{key}?version=N` | 下载最新或指定版本（响应头带 `X-Object-Version`、`X-Content-Sha256`） |
| `GET` | `/objects/{key}/versions` | 查看版本链（含墓碑） |
| `DELETE` | `/objects/{key}?version=N` | 删除指定版本（不带 `version` 删除整个对象） |
| `GET` | `/stats` | 对象数、存活版本数、墓碑数、blob 数、物理/逻辑字节数 |

错误通过状态码 + 错误体 `error` 字段区分：

| 场景 | 状态码 | error |
| --- | --- | --- |
| 上传校验失败 | 400 | `CHECKSUM_MISMATCH` |
| 对象不存在 | 404 | `OBJECT_NOT_FOUND` |
| 版本不存在 | 404 | `VERSION_NOT_FOUND` |
| 版本已删除 | 410 | `VERSION_DELETED` |
| 读取校验失败（数据损坏） | 500 | `INTEGRITY_VIOLATION` |

## 本地验证

```bash
# 运行全部单元测试（21 个用例）
mvn test
```

测试按业务分组，日志中可直接看到每个业务的用例与结论：

- `VersionChainTests`（版本链）：版本递增、历史版本取回、墓碑语义、整体删除、重启持久化
- `ContentDedupTests`（内容去重）：跨对象共享 blob、引用计数、覆盖同内容、统计可解释
- `ConcurrencyTests`（并发访问）：并发上传不丢版本、并发上传+删除引用一致、并发去重、并发读写
- `ChecksumTests`（校验与错误区分）：上传校验失败零副作用、损坏 blob 拒绝读取、异常类型独立
- `HttpApiTests`（HTTP 端到端）：REST 全链路及 400/404/410 状态码

手动体验：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

curl -X PUT --data-binary 'hello v1' localhost:8080/objects/demo
curl -X PUT --data-binary 'hello v2' localhost:8080/objects/demo
curl localhost:8080/objects/demo?version=1        # 取回历史版本
curl localhost:8080/objects/demo/versions         # 版本链
curl -X DELETE 'localhost:8080/objects/demo?version=1'
curl localhost:8080/stats                         # 存储状态统计
```
