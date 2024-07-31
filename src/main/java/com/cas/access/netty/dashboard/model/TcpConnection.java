package com.cas.access.netty.dashboard.model;

import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.InetSocketAddress;

/**
 * @author JHYe
 * @date 202
 * @4/7/31
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class TcpConnection {

    private String clientIp;

    private Integer clientPort;

    private String serverIP;

    private Integer serverPort;

    public TcpConnection(NioSocketChannel channel) {
        InetSocketAddress serverAddress = channel.localAddress();
        this.serverIP =  serverAddress.getHostString();
        this.serverPort = serverAddress.getPort();
        InetSocketAddress clientAddress = channel.remoteAddress();
        this.clientPort =  clientAddress.getPort();
        this.clientIp = clientAddress.getHostString();
    }
}
