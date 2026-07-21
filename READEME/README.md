# netty-server

基于 SpringBoot 2.6.6 + Netty 4.1.97.Final 的 TCP 数据接入服务，支持**协议热插拔**——新增协议无需重启、无需修改核心代码。

## 特性

- 多端口 TCP 接入（默认 10101）
- 协议与端口解耦：一个端口绑一种协议，运行时可调整
- **协议热插拔**：把协议 jar 丢到 `protocols/` 目录、调一次 HTTP 接口即可生效
- 内置 HJ212 环保传输协议解码器
- 内嵌 HTTP 管理面（端口 2310），支持加载/卸载/绑定/解绑协议
- 读写空闲超时检测，异常连接自动关闭

## 快速开始

```bash
mvn clean package
java -jar target/netty-server.jar
```

启动后：
- TCP 接入端口：`10101`（默认绑定 HJ212 协议）
- HTTP 管理端口：`2310`

## 内置协议

| 协议名 | 说明 | 默认端口 |
|---|---|---|
| `hj212` | HJ212 环保传输协议 | 10101 |

## 协议管理接口

| 接口 | 作用 |
|---|---|
| `POST   /protocols/reload` | 扫描 `protocols/` 目录并加载所有 jar |
| `GET    /protocols` | 列出已加载协议与端口绑定 |
| `DELETE /protocols/{name}` | 卸载协议（自动关闭相关端口与连接） |
| `POST   /protocols/{name}/bind/{port}` | 绑定协议到端口 |
| `DELETE /protocols/bind/{port}` | 解绑端口并踢所有连接 |

示例：

```bash
# 加载新协议
curl -X POST http://localhost:2310/protocols/reload

# 把 echo 协议绑定到 10102 端口
curl -X POST http://localhost:2310/protocols/echo/bind/10102

# 查看当前状态
curl http://localhost:2310/protocols
```

## 新增协议

详见 **[协议开发指南.md](协议开发指南.md)**。最简流程：

1. 实现 SPI 接口 `ProtocolDecoderProvider`（详见指南）
2. 在 `META-INF/services/com.cas.access.netty.protocol.ProtocolDecoderProvider` 注册
3. 打 jar，丢到 `protocols/` 目录
4. 调 `POST /protocols/reload` + `POST /protocols/{name}/bind/{port}`

**全程无需重启服务，无需修改核心代码。**

## 架构设计

详见 **[协议热插拔改造方案.md](协议热插拔改造方案.md)**。

```
HTTP 管理面 (2310)
    │
    ▼
ProtocolRegistry ◄── ProtocolJarLoader (URLClassLoader + ServiceLoader)
    │
    ▼
ProtocolDecoderProvider (SPI 接口)
    │
    ▼
NettyChannelInitializer (按端口路由 → 装配 Pipeline)
```

核心思路：把"协议-解码器-Handler 链"封装为 `ProtocolDecoderProvider`，外部 jar 通过 Java SPI 发现，主项目通过 HTTP 接口热加载/卸载。每个端口独立绑定一种协议，连接到来时按端口动态装配 Pipeline。

## 目录结构

```
netty-server/
├── protocols/                       # 运行时协议 jar 目录（自动创建）
├── src/main/java/com/cas/access/
│   ├── NettyServerApplication.java
│   └── netty/
│       ├── NettyServerBootstrap.java
│       ├── config/
│       ├── handler/                 # HJ212 等解码器与内置 Provider
│       ├── protocol/                # 协议热插拔核心模块
│       │   ├── ProtocolDecoderProvider.java   # SPI 接口
│       │   ├── LoadedProtocol.java
│       │   ├── ProtocolRegistry.java
│       │   ├── ProtocolJarLoader.java
│       │   ├── ProtocolBootstrap.java
│       │   └── ProtocolController.java
│       ├── server/
│       └── util/
└── src/main/resources/
    └── application.yml
```

## 配置

`application.yml` 关键配置项：

```yaml
netty:
  server:
    ports: 10101                       # 启动时绑定的端口
    protocol:
      bindings:                        # 默认 端口→协议 映射
        "10101": hj212
      jar-dir: ./protocols             # 协议 jar 扫描目录
      auto-load-on-startup: true       # 启动时是否自动扫描外部协议
```

## 相关文档

- [协议热插拔改造方案.md](协议热插拔改造方案.md) — 架构设计与实施细节
- [协议开发指南.md](协议开发指南.md) — 外部协议 jar 开发者指南
