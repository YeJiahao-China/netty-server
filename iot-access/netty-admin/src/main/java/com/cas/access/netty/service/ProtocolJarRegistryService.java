package com.cas.access.netty.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cas.access.netty.entity.PortProtocolBinding;
import com.cas.cluster.node.entity.ProtocolJarRegistry;
import com.cas.access.netty.mapper.PortBindingMapper;
import com.cas.access.netty.mapper.ProtocolJarRegistryMapper;
import com.cas.access.netty.protocol.LoadedProtocol;
import com.cas.access.netty.protocol.ProtocolDbSync;
import com.cas.access.netty.protocol.ProtocolProperties;
import com.cas.access.netty.protocol.ProtocolStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.cas.cluster.node.constant.ProtocolConstants.STATUS_REGISTERED;
import static com.cas.cluster.node.constant.ProtocolConstants.STATUS_UNLOADED;

/**
 * 协议注册表数据库操作 Service。
 *
 * <p>实现 {@link ProtocolDbSync} 接口（接口定义在 netty-spi 模块），
 * 被 netty-core 的 ProtocolRegistry 通过 <code>@Autowired(required=false)</code> 注入。
 *
 * <p>所有写操作均「写库失败不阻断运行时」——保证 Registry 内存状态始终是 source of truth，
 * 数据库只是镜像。这样即使库短暂不可用（启动时 PG 未启动等），协议热插拔主流程也不受影响。
 *
 * @author yjh_c
 */
@Slf4j
@Service
public class ProtocolJarRegistryService implements ProtocolDbSync, ProtocolStore {

    @Resource
    private ProtocolJarRegistryMapper mapper;

    @Resource
    private PortBindingMapper portBindingMapper;

    @Resource
    private ProtocolProperties protocolProperties;

    /** 构造本节点协议 jar 的本地绝对路径：{@code <jarDir>/<name>.jar} */
    private String resolveLocalJarPath(String protocolName) {
        return protocolProperties.getJarDir() + File.separator + protocolName + ".jar";
    }

    /**
     * 同步协议注册到数据库。
     * 同名协议：更新元数据 + 重置 status=REGISTERED；否则插入新记录。
     */
    public void syncRegister(LoadedProtocol lp) {
        try {
            String providerClass = lp.getProvider() == null ? null
                    : lp.getProvider().getClass().getName();
            boolean isBuiltin = "builtin".equals(lp.getSource());

            ProtocolJarRegistry existing = mapper.selectByName(lp.getName());

            LocalDateTime loadedAt = LocalDateTime.now();
            if (existing == null) {
                ProtocolJarRegistry entity = new ProtocolJarRegistry();
                entity.setName(lp.getName());
                entity.setVersion(lp.getVersion());
                entity.setDescription(lp.getDescription());
                entity.setSource(isBuiltin ? "builtin" : "external");
                entity.setProviderClass(providerClass);
                entity.setStatus(STATUS_REGISTERED);
                entity.setLoadedAt(loadedAt);
                mapper.insert(entity);
            } else {
                // 已存在：更新元数据并标记活跃
                ProtocolJarRegistry update = new ProtocolJarRegistry();
                update.setId(existing.getId());
                update.setVersion(lp.getVersion());
                update.setDescription(lp.getDescription());
                update.setSource(isBuiltin ? "builtin" : "external");
                update.setProviderClass(providerClass);
                update.setStatus(STATUS_REGISTERED);
                update.setLoadedAt(loadedAt);
                mapper.updateFull(update);
            }
        } catch (Exception e) {
            log.warn("DB 同步协议注册失败（不影响运行时）: name={}, err={}", lp.getName(), e.getMessage());
        }
    }

    /**
     * 同步协议卸载：标记 status=UNLOADED（保留记录，便于后续 reload 重新启用）。
     * 同时把对应端口绑定置为 enabled=false。
     */
    public void syncUnload(String name) {
        try {
            ProtocolJarRegistry existing = mapper.selectByName(name);
            if (existing == null) {
                log.warn("DB 同步协议卸载失败：未找到协议记录 name={}", name);
                return;
            }
            ProtocolJarRegistry update = new ProtocolJarRegistry();
            update.setName(name);
            update.setStatus(STATUS_UNLOADED);
            update.setUpdatedAt(LocalDateTime.now());
            mapper.updateStatusByName(update);

            portBindingMapper.updateEnableByProtocol(name, Boolean.FALSE, LocalDateTime.now());

            log.debug("DB 标记协议卸载（status=UNLOADED）: name={}", name);
        } catch (Exception e) {
            log.warn("DB 同步协议卸载失败（不影响运行时）: name={}, err={}", name, e.getMessage());
        }
    }

    @Override
    public void clearProvider(String protocolName) {
        mapper.clearProvider(protocolName, LocalDateTime.now());
    }

    /**
     * 恢复协议：将未启用的协议恢复为启用状态。
     */
    public void restoreProtocol(String name) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<ProtocolJarRegistry>()
                            .eq(ProtocolJarRegistry::getName, name)
                            .set(ProtocolJarRegistry::getStatus, STATUS_REGISTERED)
                            .set(ProtocolJarRegistry::getUpdatedAt, LocalDateTime.now()));

            portBindingMapper.update(null,
                    new LambdaUpdateWrapper<PortProtocolBinding>()
                            .eq(PortProtocolBinding::getProtocolName, name)
                            .set(PortProtocolBinding::getEnabled, Boolean.TRUE)
                            .set(PortProtocolBinding::getUpdatedAt, LocalDateTime.now()));

            log.info("DB 恢复协议: name={}", name);
        } catch (Exception e) {
            log.warn("DB 恢复协议失败: name={}, err={}", name, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 查询所有协议记录（按加载时间倒序）
     */
    public List<ProtocolJarRegistry> listAll() {
        return mapper.selectList(
                new LambdaQueryWrapper<ProtocolJarRegistry>()
                        .orderByDesc(ProtocolJarRegistry::getLoadedAt));
    }

    /**
     * 查询数据库中所有协议记录
     */
    public List<ProtocolJarRegistry> listAllInDb() {
        List<ProtocolJarRegistry> result = mapper.selectAll();
//        log.info("listAllInDb 查询结果: count={}", result.size());
//        for (ProtocolJarRegistry p : result) {
//            log.info("  协议: name={}, status={}, source={}",
//                    p.getName(), p.getStatus(), p.getSource());
//        }
        return result;
    }

    public void deleteJar(String name) {
        ProtocolJarRegistry existing = mapper.selectByName(name);
        if (existing == null) {
            log.warn("未找到协议记录: name={}", name);
            return;
        }

        // 本地 jar 路径由配置 + 协议名推导，不再从 DB 读取
        String jarPath = resolveLocalJarPath(name);
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            log.warn("jar 文件不存在: {}", jarPath);
            return;
        }

        try {
            if (jarFile.delete()) {
                log.info("成功删除协议 jar: {}", jarPath);
            } else {
                log.warn("删除 jar 文件失败（可能被占用）: {}", jarPath);
            }
        } catch (SecurityException e) {
            log.warn("删除 jar 文件权限不足: {}, err={}", jarPath, e.getMessage());
        }
    }

    @Override
    public Map<String, String> getActiveExternalProtocols() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            List<ProtocolJarRegistry> list = mapper.selectList(
                    new LambdaQueryWrapper<ProtocolJarRegistry>()
                            .eq(ProtocolJarRegistry::getSource, "external")
                            .eq(ProtocolJarRegistry::getStatus, STATUS_REGISTERED));
            for (ProtocolJarRegistry p : list) {
                // jar 路径不再存表，由本节点配置 jarDir + 协议名推导
                result.put(p.getName(), resolveLocalJarPath(p.getName()));
            }
            log.info("从 DB 加载活跃外部协议: {} 条", result.size());
        } catch (Exception e) {
            log.warn("DB 加载外部协议失败（退化为空映射）: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public byte[] getExternalJarBytes(String protocolName) {
        try {
            ProtocolJarRegistry p = mapper.selectByName(protocolName);
            if (p == null || p.getJarBytes() == null) {
                return null;
            }
            return p.getJarBytes();
        } catch (Exception e) {
            log.warn("DB 查询协议 jar_bytes 失败: name={}, err={}", protocolName, e.getMessage());
            return null;
        }
    }

    @Override
    public List<Integer> getEnabledPortsByProtocol(String protocolName) {
        try {
            return portBindingMapper.selectList(
                            new LambdaQueryWrapper<PortProtocolBinding>()
                                    .eq(PortProtocolBinding::getProtocolName, protocolName)
                                    .eq(PortProtocolBinding::getEnabled, Boolean.TRUE)
                    )
                    .stream()
                    .map(PortProtocolBinding::getPort)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("DB 查询协议端口绑定失败: protocol={}, err={}", protocolName, e.getMessage());
            return new ArrayList<>();
        }
    }

    public void activeProtocol(String name) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<ProtocolJarRegistry>()
                            .eq(ProtocolJarRegistry::getName, name)
                            .set(ProtocolJarRegistry::getStatus, STATUS_REGISTERED)
                            .set(ProtocolJarRegistry::getUpdatedAt, LocalDateTime.now()));
            log.info("DB 激活协议: name={}", name);
        } catch (Exception e) {
            log.warn("DB 激活协议失败: name={}, err={}", name, e.getMessage());
        }
    }

    public ProtocolJarRegistry selectByName(String name) {
        return mapper.selectByName(name);
    }

    /**
     * 物理删除协议记录（含端口绑定）。
     *
     * <p>调用方必须保证协议已卸载（status=UNLOADED 且内存中无注册）。
     * 本方法仅做 DB 物理删除，不触碰文件系统。
     *
     * @return true=删除成功；false=协议不存在
     */
    public boolean purgeByName(String name) {
        ProtocolJarRegistry existing = mapper.selectByName(name);
        if (existing == null) {
            log.warn("物理删除协议失败：未找到协议记录 name={}", name);
            return false;
        }
        // 物理删除 protocol_jar_registry
        int deletedRows = mapper.delete(new LambdaQueryWrapper<ProtocolJarRegistry>()
                .eq(ProtocolJarRegistry::getName, name));
        // 物理删除 port_protocol_binding（可能多条）
        portBindingMapper.delete(new LambdaQueryWrapper<PortProtocolBinding>()
                .eq(PortProtocolBinding::getProtocolName, name));
        log.info("DB 物理删除协议: name={}", name);
        return deletedRows > 0;
    }
}
