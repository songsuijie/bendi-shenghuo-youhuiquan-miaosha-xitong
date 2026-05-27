# 本地生活优惠券秒杀系统

本项目是一个面向本地生活服务场景的后端系统，围绕商户查询、优惠券管理、秒杀下单、用户登录、关注关系、探店内容与签到统计等业务能力展开。系统使用 Spring Boot 构建 REST API，通过 MySQL 持久化业务数据，并结合 Redis 完成验证码登录、缓存治理、分布式锁、秒杀库存预扣、消息流异步下单、点赞排行、关注集合和地理位置检索等高频能力。

## 功能模块

- 用户认证：手机号验证码登录、Token 会话续期、登录拦截与用户上下文维护
- 商户服务：商户分页查询、详情缓存、缓存穿透处理、逻辑过期缓存重建、商户坐标检索
- 优惠券服务：普通券查询、秒杀券创建、库存同步到 Redis
- 秒杀下单：Lua 脚本原子校验库存和一人一单、Redis Stream 异步消费、数据库事务扣库存和落单
- 社交互动：关注/取关、共同关注、笔记发布、点赞/取消点赞、点赞用户排行
- Feed 流：发布笔记后推送到粉丝收件箱，支持基于时间戳的滚动分页
- 用户签到：Redis Bitmap 记录签到状态，统计当月连续签到天数

## 技术栈

- Java 8
- Spring Boot 2.3.x
- MyBatis-Plus
- MySQL 8.x
- Redis / Redis Stream / Redis GEO / Bitmap / ZSet
- Redisson
- Hutool
- Springdoc OpenAPI
- Maven

## 核心链路

### 登录与鉴权

1. 用户提交手机号，服务端校验格式后生成验证码并写入 Redis。
2. 登录时校验 Redis 中的验证码，首次登录自动创建用户。
3. 登录成功后生成 Token，将脱敏后的用户信息写入 Redis Hash。
4. `RefreshTokenInterceptor` 在每次请求中刷新 Token 有效期，并把用户信息放入 `UserHolder`。
5. `LoginInterceptor` 对需要登录的接口进行拦截。

### 商户缓存

1. 查询商户详情优先读取 Redis。
2. 未命中时回源 MySQL，并写入缓存。
3. 空值短期缓存用于拦截不存在数据的重复穿透请求。
4. 热点商户使用逻辑过期策略：过期后先返回旧数据，再由后台线程抢锁重建缓存。
5. 更新商户后删除对应缓存，保证后续查询重新加载最新数据。

### 秒杀下单

1. 请求进入后先校验登录状态、秒杀券有效期。
2. 使用 Lua 脚本在 Redis 中原子完成库存判断、重复下单判断、库存预扣和 Stream 消息写入。
3. 接口立即返回订单号，数据库写入由后台消费者异步完成。
4. 消费者从 Redis Stream 读取订单消息，通过 Redisson 用户维度锁兜底防重复。
5. 数据库事务内再次校验一人一单，并使用 `stock > 0` 条件扣减库存，成功后保存订单。
6. 消费失败的消息保留在 pending-list，后续循环补偿处理。

## 目录结构

```text
src/main/java
├── config          # Web、MyBatis、Redisson、异常处理配置
├── controller      # REST 接口层
├── dto             # 接口入参与返回模型
├── entity          # 数据库实体
├── mapper          # MyBatis-Plus Mapper
├── service         # 业务接口
├── service/impl    # 业务实现
└── utils           # Redis、缓存、拦截器、ID、上下文等工具

src/main/resources
├── mapper          # XML SQL
├── db              # 数据库初始化脚本
├── *.lua           # Redis 秒杀/锁相关脚本
└── application.yaml
```

## 环境要求

- JDK 1.8+
- Maven 3.6+
- MySQL 8.x
- Redis 6.x+

## 快速启动

1. 创建数据库并导入脚本：

```sql
CREATE DATABASE local_life_coupon DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

导入 `src/main/resources/db/` 目录下的初始化 SQL，如需增量字段再导入 `alter.sql`。

2. 配置运行环境变量：

```bash
MYSQL_URL=jdbc:mysql://127.0.0.1:3306/local_life_coupon?useSSL=false&serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=your_password
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
```

3. 启动项目：

```bash
mvn spring-boot:run
```

默认服务端口为 `8081`。接口文档地址：

```text
http://localhost:8081/swagger-ui.html
```

## 常用配置

配置项集中在 `src/main/resources/application.yaml`，支持通过环境变量覆盖：

- `MYSQL_URL`：MySQL 连接地址
- `MYSQL_USERNAME` / `MYSQL_PASSWORD`：数据库账号与密码
- `REDIS_HOST` / `REDIS_PORT`：Redis 地址与端口
- `REDIS_TIMEOUT` / `REDIS_CONNECT_TIMEOUT`：Redis 读写与连接超时
- `REDIS_POOL_MAX_ACTIVE` / `REDIS_POOL_MAX_IDLE` / `REDIS_POOL_MIN_IDLE`：Redis 连接池参数

## 验证命令

```bash
mvn test
```

如只需要验证编译：

```bash
mvn -DskipTests package
```
