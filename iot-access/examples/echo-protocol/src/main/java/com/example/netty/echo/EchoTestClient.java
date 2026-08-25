package com.example.netty.echo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Echo 协议测试客户端。
 *
 * 用法：
 * <pre>
 *   # 默认连 localhost:10102
 *   mvn test-compile exec:java -Dexec.mainClass=com.example.netty.echo.EchoTestClient
 *
 *   # 或者打包后用 java 直接跑（注意要带上 netty 依赖，本测试不依赖 netty）
 *   java -cp target/echo-protocol-1.0.0.jar com.example.netty.echo.EchoTestClient localhost 10102
 * </pre>
 *
 * 交互：建立连接后会自动收到欢迎语，然后从控制台读取用户输入并发送，收到回复后打印。
 *
 * @author example
 */
public class EchoTestClient {

    public static void main(String[] args) throws Exception {
        String host = args.length >= 1 ? args[0] : "localhost";
        int port = args.length >= 2 ? Integer.parseInt(args[1]) : 10102;

        System.out.println("连接到 " + host + ":" + port + " ...");
        try (Socket socket = new Socket(host, port)) {
            socket.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

            // 先读一条欢迎语
            String welcome = in.readLine();
            System.out.println("← " + welcome);

            // 进入交互循环
            String input;
            System.out.println("（输入 quit 退出）");
            while (true) {
                System.out.print("→ ");
                input = stdin.readLine();
                if (input == null || "quit".equalsIgnoreCase(input.trim())) {
                    break;
                }
                out.println(input);
                String reply = in.readLine();
                if (reply == null) {
                    System.out.println("（服务端关闭了连接）");
                    break;
                }
                System.out.println("← " + reply);
            }
        }
        System.out.println("已断开");
    }
}
