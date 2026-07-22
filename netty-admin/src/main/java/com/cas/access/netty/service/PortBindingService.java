package com.cas.access.netty.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cas.access.netty.entity.PortBinding;
import com.cas.access.netty.mapper.PortBindingMapper;
import com.cas.access.netty.protocol.PortBindingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端口-协议绑定 Service。
 *
 * <p>实现 {@link PortBindingStore} SPI（接口在 netty-spi），被 netty-core 的
 * ProtocolRegistry / ProtocolBootstrap 通过 <code>@Autowired(required=false)</code> 注入。
 *
 * <p>所有写操作「写库失败不阻断运行时」，DB 不可用时退化为纯内存模式。
 *
 * @author yjh_c
 */
@Slf4j
@Service
public class PortBindingService implements PortBindingStore {

    @Resource
    private PortBindingMapper mapper;

    @Override
    public Map<Integer, String> loadEnabledBindings() {
        Map<Integer, String> result = new LinkedHashMap<>();
        try {
            List<PortBinding> list = mapper.selectList(
                    new LambdaQueryWrapper<PortBinding>()
                            .eq(PortBinding::getEnabled, Boolean.TRUE)
                            .orderByAsc(PortBinding::getPort));
            for (PortBinding b : list) {
                result.put(b.getPort(), b.getProtocolName());
//                log.info("从 DB 加载端口{}绑定协议{}", b.getPort(),b.getProtocolName());
            }
        } catch (Exception e) {
            log.warn("DB 加载端口绑定失败（退化为空映射）: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void persistBind(int port, String protocolName) {
        try {
            PortBinding existing = mapper.selectByName(protocolName);
            if (existing == null) {
                PortBinding entity = new PortBinding();
                entity.setPort(port);
                entity.setProtocolName(protocolName);
                entity.setEnabled(Boolean.TRUE);
                mapper.insert(entity);
            } else {
                mapper.updateByPort(port, protocolName, true, LocalDateTime.now());
            }
            log.debug("DB 持久化绑定: {} → {}", port, protocolName);
        } catch (Exception e) {
            log.warn("DB 持久化绑定失败（不影响运行时）: {} → {}, err={}", port, protocolName, e.getMessage());
        }
    }

    @Override
    public void persistUnbind(int port) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<PortBinding>()
                            .eq(PortBinding::getPort, port)
                            .set(PortBinding::getEnabled, Boolean.FALSE)
                            .set(PortBinding::getUpdatedAt, LocalDateTime.now()));
            log.debug("DB 禁用绑定: port={}", port);
        } catch (Exception e) {
            log.warn("DB 禁用绑定失败（不影响运行时）: port={}, err={}", port, e.getMessage());
        }
    }

    public void enabledBindings(String name) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<PortBinding>()
                            .eq(PortBinding::getProtocolName, name)
                            .set(PortBinding::getEnabled, Boolean.TRUE)
                            .set(PortBinding::getUpdatedAt, LocalDateTime.now()));
            log.info("DB 启用绑定: protocolName={}", name);
        } catch (Exception e) {
            throw new RuntimeException("DB 启用绑定失败: " + name, e);
        }
    }

    public PortBinding selectByName(String name) {
       return mapper.selectByName(name);
    }
}
