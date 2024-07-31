package com.cas.access.netty.dashboard.controller;

import com.cas.access.netty.dashboard.model.TcpConnection;
import com.cas.access.netty.dashboard.service.NettyServerService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Set;

/**
 * @author JHYe
 * @date 2024/7/31
 */
@RestController
@RequestMapping("/netty-server")
public class NettyServerController {

    @Resource
    private NettyServerService nettyServerService;

    @GetMapping("/connect")
    public Set<TcpConnection> getTcpConnectByServerPort(Integer serverPort) {
        Set<TcpConnection> set = nettyServerService.getTcpConnectByServerPort(serverPort);
        return set;
    }

    @PostMapping("/closeBind/{serverPort}")
    public String closeServerPort(@PathVariable Integer serverPort) {
        int code = nettyServerService.closeServerPort(serverPort);
        return code == 200 ? "成功" : "失败";
    }

    @PostMapping("/bind/{serverPort}")
    public String bindServerPort(@PathVariable Integer serverPort) {
        int code = nettyServerService.bindServerPort(serverPort);
        return code == 200 ? "成功" : "失败";
    }
}
