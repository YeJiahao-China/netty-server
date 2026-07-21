package com.example.netty.modbus;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Modbus TCP 测试客户端。
 *
 * <p>发送一个标准的「读保持寄存器」请求（FC=0x03），打印服务端的响应。
 *
 * <p>用法：
 * <pre>
 *   # 默认连 localhost:10103
 *   java -cp target/modbus-protocol-1.0.0.jar com.example.netty.modbus.ModbusTestClient
 *
 *   # 指定 host 和 port
 *   java -cp target/modbus-protocol-1.0.0.jar com.example.netty.modbus.ModbusTestClient 192.168.1.10 10103
 * </pre>
 *
 * @author example
 */
public class ModbusTestClient {

    public static void main(String[] args) throws Exception {
        String host = args.length >= 1 ? args[0] : "localhost";
        int port = args.length >= 2 ? Integer.parseInt(args[1]) : 10103;

        // 标准 Modbus TCP 「读保持寄存器」请求
        // 请求从 0x0000 开始读 1 个寄存器（FC=0x03）
        byte[] request = new byte[]{
                0x00, 0x01,   // Transaction ID = 1
                0x00, 0x00,   // Protocol ID = 0 (Modbus)
                0x00, 0x06,   // Length = 6
                0x01,         // Unit ID = 1
                0x03,         // Function Code = Read Holding Registers
                0x00, 0x00,   // Start Address = 0
                0x00, 0x01    // Quantity = 1
        };

        System.out.println("连接到 " + host + ":" + port + " ...");
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            System.out.println("发送 Modbus 请求（" + request.length + " 字节）:");
            System.out.println("  " + bytesToHex(request));

            out.write(request);
            out.flush();

            // 读响应（预期 9 字节的固定 ACK）
            byte[] buffer = new byte[64];
            int n = in.read(buffer);
            if (n > 0) {
                byte[] response = new byte[n];
                System.arraycopy(buffer, 0, response, 0, n);
                System.out.println("收到响应（" + n + " 字节）:");
                System.out.println("  " + bytesToHex(response));
            } else {
                System.out.println("未收到响应");
            }
        }
        System.out.println("已断开");
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
