# local-versioned-object-store-java

基于 Spring Boot 的本地版本化对象存储服务：用本地磁盘模拟存储后端，通过 HTTP 接口读写对象，支持多版本、内容去重和并发安全访问。

## 存储模型

存储根目录由 `object-store.root` 配置（默认 `./data`），布局如下：

```
data/
  blobs/{sha256}          # 内容寻址的实际数据，一份内容只存一个文件
  meta/{base64url(key)}.json  # 每个对象的版本链元数据
```

- **对象（object）**：由 key 标识（支持含 `/` 的层级 key），元数据记录完整版本链。
- **版本（version）**：每次上传产生一个单调递增、永不复用的版本号；版本记录 `sha256`、`size`、`createdAt`、`deleted` 墓碑标记。
- **数据块（blob）**：以内容 SHA-256 命名的文件，被版本引用；内容相同的版本/对象共享同一 blob（去重）。

## 版本与去重规则

- 上传：计算内容 SHA-256，blob 不存在则原子写入（临时文件 + move），随后追加版本记录并原子更新元数据。
- 下载：默认返回最新存活版本；`?version=n` 取指定历史版本。读取时重新计算 SHA-256 校验，校验失败拒绝返回（`DATA_INTEGRITY_ERROR`），绝不给出坏数据。
- 删除版本：打墓碑标记（版本号保留，可区分“已删除”与“不存在”）；若其 blob 不再被任何存活版本引用则回收。
- 删除对象：全部版本打墓碑，回收不再被引用的 blob。共享 blob 的其它对象/版本不受影响。
- 并发：所有变更在公平写锁下串行化，读取走读锁；版本号分配、blob 写入与引用回收构成原子序列，任意并发顺序下不会出现内容误删、版本丢失或引用泄漏。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `PUT` | `/objects/{key}` | 上传新版本，可选请求头 `X-Content-Sha256` 校验，返回 `{key, version, sha256, size}` |
| `GET` | `/objects/{key}` | 下载最新存活版本，响应头带 `X-Object-Version` / `X-Content-Sha256` |
| `GET` | `/objects/{key}?version=n` | 下载指定历史版本 |
| `GET` | `/objects/{key}?list=versions` | 查看完整版本链（含墓碑） |
| `DELETE` | `/objects/{key}?version=n` | 删除指定版本（墓碑化） |
| `DELETE` | `/objects/{key}` | 删除整个对象 |
| `GET` | `/stats` | 对象数、存活/已删版本数、blob 数与实际占用字节数 |

错误以不同 HTTP 状态码和 `code` 字段区分：

| 场景 | 状态码 | code |
| --- | --- | --- |
| 上传校验失败 | 400 | `CHECKSUM_MISMATCH` |
| 对象不存在 | 404 | `OBJECT_NOT_FOUND` |
| 版本不存在 | 404 | `VERSION_NOT_FOUND` |
| 版本已删除 | 410 | `VERSION_DELETED` |
| 存储数据校验失败 | 422 | `DATA_INTEGRITY_ERROR` |

## 本地验证

```bash
# 运行全部单测（日志可见各业务场景覆盖情况）
mvn test

# 启动服务
mvn spring-boot:run

# 手动验证
curl -X PUT --data-binary 'hello-v1' localhost:8080/objects/demo/a.txt
curl -X PUT --data-binary 'hello-v2' localhost:8080/objects/demo/a.txt
curl localhost:8080/objects/demo/a.txt              # hello-v2
curl 'localhost:8080/objects/demo/a.txt?version=1'  # hello-v1
curl -X DELETE 'localhost:8080/objects/demo/a.txt?version=1' -i   # 204
curl -i 'localhost:8080/objects/demo/a.txt?version=1'             # 410 VERSION_DELETED
curl localhost:8080/stats
```

## 测试覆盖

- `LocalObjectStoreTest`：版本链（递增版本/历史取回/墓碑回退）、内容去重（共享 blob、删除安全性、引用回收）、错误类型区分、磁盘数据篡改后的读取拒绝、8 线程并发上传+删除下版本链与引用一致性、统计可解释性。
- `ObjectControllerTest`：HTTP 全链路（上传/下载/版本列表/删除/统计）与四类错误的状态码映射。
