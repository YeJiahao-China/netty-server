# Echo 协议示例

一个用于调试 netty-server 协议热插拔流程的最小示例。

## 功能

- 客户端发什么，服务端就回什么（按行处理，加 `Echo: ` 前缀）
- 客户端连上时推送欢迎语
- 60 秒读空闲后断开

## 工程结构

```
echo-protocol/
├── pom.xml
└── src/main/
    ├── java/com/example/netty/echo/
    │   ├── EchoProtocolProvider.java   # SPI 入口
    │   ├── EchoHandler.java            # 业务 Handler
    │   └── EchoTestClient.java         # 测试客户端（JDK 原生 Socket，无需第三方依赖）
    └── resources/META-INF/services/
        └── com.cas.access.netty.protocol.ProtocolDecoderProvider
            ↑ 内容: com.example.netty.echo.EchoProtocolProvider
```

---

## 完整 debug 流程

> 假设你在 `netty-server/` 项目根目录下。

### 步骤 1：打 Echo 协议 jar

```bash
cd examples/echo-protocol
mvn clean package
```

产物在 `target/echo-protocol-1.0.0.jar`。

> 说明：echo 项目完全自包含，SPI 接口以源码形式直接放在工程内
> （`com.cas.access.netty.protocol.ProtocolDecoderProvider`，与主项目保持全限定名一致）。
> 运行时通过 parent-first 类加载，JVM 会用主项目中的同一份接口，无冲突。

### 步骤 2：把 jar 放到 netty-server 的协议目录

```bash
mkdir -p ../../protocols
cp target/echo-protocol-1.0.0.jar ../../protocols/
```

### 步骤 3：启动 netty-server

```bash
cd ../..
java -jar target/netty-server.jar
```

启动日志应有：
```
内置协议注册: hj212-1.0.0 (HJ212 环保传输协议（内置））
外部协议扫描完成: 共 1 个 jar，1 个 Provider
协议注册成功: name=echo, version=1.0.0, source=.../protocols/echo-protocol-1.0.0.jar
默认端口绑定: 10101 → 协议 hj212
```

> 如果 `auto-load-on-startup=true`（默认值），上面会自动加载 `protocols/` 下的 jar，无需手动 reload。

### 步骤 4：把 echo 协议绑定到 10102 端口

新开一个终端：

```bash
curl -X POST http://localhost:2310/protocols/echo/bind/10102
```

netty-server 日志应输出：
```
NettyServer - Add Port10102 Bind Success
协议[echo]绑定到端口[10102]
```

### 步骤 5：连客户端测试

**方式 A：用自带测试客户端**（最简单）

```bash
cd examples/echo-protocol
java -cp target/echo-protocol-1.0.0.jar com.example.netty.echo.EchoTestClient localhost 10102
```

交互效果：
```
连接到 localhost:10102 ...
← Echo server ready. Send me anything...
（输入 quit 退出）
→ hello
← Echo: hello
→ world
← Echo: world
→ quit
已断开
```

**方式 B：用 nc / telnet**

```bash
nc localhost 10102
# 或
telnet localhost 10102
```

然后输入任意字符串回车，会看到 `Echo: xxx` 回复。

**方式 C：Windows 用 PowerShell**

```powershell
$client = New-Object System.Net.Sockets.TcpClient('localhost', 10102)
$stream = $client.GetStream()
$reader = New-Object System.IO.StreamReader($stream)
$writer = New-Object System.IO.StreamWriter($stream)
$writer.AutoFlush = $true
$reader.ReadLine()                                    # 读欢迎语
$writer.WriteLine("hello")
$reader.ReadLine()                                    # 读回复
$client.Close()
```

### 步骤 6：观察 netty-server 日志

发数据时应有：
```
[Echo] 客户端连接: /127.0.0.1:54321
[Echo] 收到数据: hello
[Echo] 收到数据: world
[Echo] 客户端断开: /127.0.0.1:54321
```

---

## 验证热卸载流程

> 卸载协议时框架会**自动**关闭该协议绑定的所有端口监听、踢掉所有活跃连接、释放 jar 文件句柄。
> 调用方无需先解绑端口。

### 1. 直接卸载

确保仍有客户端连着 10102 端口（用来观察连接被强制断开），然后：

```bash
curl -X DELETE http://localhost:2310/protocols/echo
```

返回示例：
```json
{
  "success": true,
  "closedPorts": [10102],
  "message": "已自动关闭端口监听并断开所有活跃连接: [10102]"
}
```

netty-server 日志应输出：
```
协议[echo]绑定的端口 [10102] 将被关闭，所有活跃连接会被强制断开
NettyServer关闭服务端口[...:10102]监听
NettyServer关闭客户端[...:xxx]成功
协议[echo]已卸载
```

此时客户端 TCP 连接被服务端主动断开。

### 2. 验证文件锁已释放（Windows 上重要）

```bash
# 应能成功删除（如果失败说明 ClassLoader 没关）
rm protocols/echo-protocol-1.0.0.jar
```

---

## 验证热升级流程

### 1. 修改版本号

编辑 `EchoProtocolProvider.java`：

```java
@Override
public String version() {
    return "2.0.0";   // 原 1.0.0 改成 2.0.0
}
```

### 2. 重新打包并覆盖

```bash
cd examples/echo-protocol
mvn clean package
cp target/echo-protocol-1.0.0.jar ../../protocols/
```

### 3. 触发 reload

```bash
curl -X POST http://localhost:2310/protocols/reload
```

netty-server 日志应输出：
```
协议[echo]被覆盖: 旧版本 1.0.0 ← 新版本 2.0.0
协议[echo]已卸载
协议注册成功: name=echo, version=2.0.0, source=...
```

### 4. 再次绑定并连接

```bash
curl -X POST http://localhost:2310/protocols/echo/bind/10103
java -cp target/echo-protocol-1.0.0.jar com.example.netty.echo.EchoTestClient localhost 10103
```

新连接走新版本。

---

## 常见问题排查

### Q1：reload 后没看到 echo 协议

检查：
1. jar 是否真的在 `protocols/` 目录下：`ls protocols/`
2. jar 是否有 `META-INF/services/com.cas.access.netty.protocol.ProtocolDecoderProvider` 文件：
   ```bash
   jar tf protocols/echo-protocol-1.0.0.jar | grep META-INF/services
   ```
3. 该文件内容是否正确（应为 `com.example.netty.echo.EchoProtocolProvider`）

### Q2：bind 端口失败

可能原因：端口已被占用。换一个端口（如 10103、10104）。

### Q3：连接被立即关闭

可能原因：协议名拼写错误，或 jar 没加载。检查：
```bash
curl http://localhost:2310/protocols
```

返回的 protocols 列表里应有 echo，且 bindings 中应有 `"10102":"echo"`。

### Q4：日志里看不到 [Echo] 前缀

日志级别是 info（主项目 logback.xml 中 `com.cas` 包配置为 info）。检查你的日志配置是否被改成了 warn 或 error。
