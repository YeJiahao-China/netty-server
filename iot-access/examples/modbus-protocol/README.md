# Modbus TCP 协议示例

一个展示**长度字段前置拆包**的协议接入示例，与 echo-protocol（行拆包）和 HJ212（自定义长度+校验）形成对比。

## 功能

- 按 Modbus TCP 标准的 MBAP header 拆包（`LengthFieldBasedFrameDecoder`）
- 解析帧字段（TxId/ProtoId/Length/UnitId/FC/Data），打印为可读字符串
- 回复固定的 9 字节异常响应（不根据请求动态构造）

## Modbus TCP 帧结构

```
+-----------------+-----------------+-----------------+-------------+--------------+----------+
| Transaction ID  | Protocol ID     | Length          | Unit ID     | Function Code| Data     |
| 2 bytes (BE)    | 2 bytes (BE)    | 2 bytes (BE)    | 1 byte      | 1 byte       | N bytes  |
+-----------------+-----------------+-----------------+-------------+--------------+----------+
                  MBAP Header (7 bytes)              |                PDU (Length - 1 bytes)
```

- `Length` 字段值 = Unit ID(1) + PDU 字节数
- 总帧长度 = 6（MBAP 除 Length 内容外的字节数）+ Length 字段值

## 工程结构

```
modbus-protocol/
├── pom.xml
└── src/main/
    ├── java/
    │   ├── com/cas/access/netty/protocol/
    │   │   └── ProtocolDecoderProvider.java    # SPI 接口副本（同包名）
    │   └── com/example/netty/modbus/
    │       ├── ModbusProtocolProvider.java     # SPI 入口
    │       ├── ModbusDecoder.java              # ByteBuf → 可读字符串
    │       ├── ModbusHandler.java              # 打印 + 固定 ACK
    │       └── ModbusTestClient.java           # 测试客户端
    └── resources/META-INF/services/
        └── com.cas.access.netty.protocol.ProtocolDecoderProvider
            ↑ 内容: com.example.netty.modbus.ModbusProtocolProvider
```

## Handler 链路

```
[入站] TCP 字节流
   │
   ▼
LengthFieldBasedFrameDecoder     # 按 Length 字段拆包，输出完整帧 ByteBuf
   │
   ▼
ModbusDecoder                    # ByteBuf → "TxId=1, ProtoId=0, ..." 字符串
   │
   ▼
ModbusHandler                    # 打印字符串 + ctx.writeAndFlush(固定ACK ByteBuf)
   │
   ▼
[出站] 9 字节固定 ACK
```

## LengthFieldBasedFrameDecoder 参数（Modbus TCP 标准配置）

| 参数 | 值 | 含义 |
|---|---|---|
| `maxFrameLength` | 260 | Modbus TCP 单帧最大长度 |
| `lengthFieldOffset` | 4 | Length 字段从第 5 字节开始（跳过 TxId 2 + ProtoId 2） |
| `lengthFieldLength` | 2 | Length 字段本身占 2 字节（大端 unsigned short） |
| `lengthAdjustment` | 0 | Length 字段值即"后续字节数"，无需调整 |
| `initialBytesToStrip` | 0 | 不剥离，整包传给下游 |

---

## 完整 debug 流程

> 假设你在 `netty-server/` 项目根目录下，且服务已启动（端口 2310 监听中）。

### 步骤 1：打 jar

```bash
cd examples/modbus-protocol
mvn clean package
```

产物：`target/modbus-protocol-1.0.0.jar`

### 步骤 2：放 jar 到协议目录

```bash
mkdir -p ../../protocols
cp target/modbus-protocol-1.0.0.jar ../../protocols/
```

### 步骤 3：热加载

```bash
curl -X POST http://localhost:2310/protocols/reload
```

netty-server 日志应有：
```
协议注册成功: name=modbus-tcp, version=1.0.0, source=.../modbus-protocol-1.0.0.jar
```

### 步骤 4：绑端口

```bash
curl -X POST http://localhost:2310/protocols/modbus-tcp/bind/10103
```

### 步骤 5：跑测试客户端

```bash
cd examples/modbus-protocol
java -cp target/modbus-protocol-1.0.0.jar com.example.netty.modbus.ModbusTestClient localhost 10103
```

预期输出：
```
连接到 localhost:10103 ...
发送 Modbus 请求（12 字节）:
  00 01 00 00 00 06 01 03 00 00 00 01
收到响应（9 字节）:
  FF FF 00 00 00 03 FF 81 00
已断开
```

### 步骤 6：观察 netty-server 日志

应有 `[Modbus]` 前缀的日志：
```
[Modbus] 客户端连接: /127.0.0.1:54321
[Modbus] 收到报文: TxId=1, ProtoId=0, Length=6, UnitId=1, FC=3, Data=00 00 00 01
[Modbus] 回复固定 ACK: 9 字节
[Modbus] 客户端断开: /127.0.0.1:54321
```

---

## 与其他协议的对比

| 协议 | 拆包方式 | 业务复杂度 | 适用场景 |
|---|---|---|---|
| HJ212（内置） | 自定义：`## + 4位长度 + 数据 + CRC` | 解析字段到 JSON | 环保监测 |
| echo-protocol | 行拆包（`\r\n`） | 原样回显 | 通讯链路调试 |
| **modbus-protocol** | **Length 字段前置** | 解析 MBAP header + 固定 ACK | **工业控制** |

三种拆包方式覆盖了 TCP 协议设计的主流模式，可作为开发新协议的参考。

---

## 故障排查

### Q1：客户端发了 12 字节但服务端没收到报文日志

可能原因：发送的字节不构成一个完整的 Modbus 帧，被 `LengthFieldBasedFrameDecoder` 缓冲等待。检查字节顺序：
- TxId 2 字节
- ProtoId 2 字节
- Length 2 字节（其值要正确，等于 `1 + len(PDU)`）
- Unit ID 1 字节
- FC + Data = Length - 1 字节

### Q2：服务端报 `DecoderException`

最常见原因是 `LengthFieldBasedFrameDecoder` 的参数配错。Modbus TCP 必须用 `lengthFieldOffset=4, lengthFieldLength=2`。如果你要适配其他协议，重新计算这两个值。

### Q3：客户端收不到 ACK

检查 ModbusHandler 是否正常执行——看日志是否有 `[Modbus] 回复固定 ACK`。如果业务 Handler 抛了异常，会走到 `exceptionCaught` 并关闭连接，客户端会读到 EOF。

### Q4：与真实 Modbus 设备联调时注意事项

本示例的 ACK 是**固定字节**，与请求不匹配。真实 Modbus 设备要求：
- 响应的 Transaction ID 必须与请求一致
- 响应的 FC 不能直接用 0x81（除非真的是异常）
- 响应的 Data 长度与请求匹配

如需对接真实设备，参考 ModbusProtocolProvider 修改 ModbusHandler，根据请求构造合法响应。
