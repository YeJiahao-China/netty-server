package com.cas.access.netty.dashboard.service;

import com.cas.access.netty.dashboard.model.TcpConnection;
import com.cas.access.netty.server.ServerConnectionManager;
import com.cas.access.netty.server.util.ServerSupport;
import io.netty.channel.ChannelId;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author JHYe
 * @date 2024/7/31
 */
@Service
public class NettyServerService {


    public int closeServerPort(Integer serverPort) {
        int code = ServerSupport.closeListen(serverPort);
        return code;
    }

    public int bindServerPort(Integer serverPort) {
        int code = ServerSupport.bindPort(serverPort);
        return code;
    }

    public Set<TcpConnection> getTcpConnectByServerPort(Integer serverPort) {
        Set<TcpConnection> set = new HashSet<>();
        if (serverPort != null && ServerConnectionManager.portServerChannelMap.containsKey(serverPort)) {
            Set<NioSocketChannel> childChannels = ServerConnectionManager.portSocketChannelMap.get(serverPort);
            for (NioSocketChannel channel : childChannels
            ) {
                TcpConnection tcpConnection = new TcpConnection(channel);
                set.add(tcpConnection);
            }
        } else {
            ConcurrentHashMap<ChannelId, NioSocketChannel> totalConnectionMap = ServerConnectionManager.totalConnectionMap;
            for (Map.Entry<ChannelId, NioSocketChannel> entry : totalConnectionMap.entrySet()
            ) {
                NioSocketChannel value = entry.getValue();
                TcpConnection tcpConnection = new TcpConnection(value);
                set.add(tcpConnection);
            }
        }
        return set;
    }


}
