package com.cas.access.netty.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.cas.access.netty.entity.PortBinding;
import com.cas.access.netty.entity.ProtocolJarRegistry;
import com.cas.access.netty.mapper.PortBindingMapper;
import com.cas.access.netty.mapper.ProtocolJarRegistryMapper;
import com.cas.access.netty.protocol.LoadedProtocol;
import com.cas.access.netty.protocol.ProtocolDbSync;
import com.cas.access.netty.protocol.ProtocolStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 同步协议注册到数据库。
     * 同名协议：更新元数据 + 重置 active=TRUE、deleted=0；否则插入新记录。
     */
    public void syncRegister(LoadedProtocol lp) {
        try {
            String providerClass = lp.getProvider() == null ? null
                    : lp.getProvider().getClass().getName();
            boolean isBuiltin = "builtin".equals(lp.getSource());

            ProtocolJarRegistry existing = mapper.selectByNameIgnoreDeleted(lp.getName());

            LocalDateTime loadedAt = LocalDateTime.now();
            if (existing == null) {
                ProtocolJarRegistry entity = new ProtocolJarRegistry();
                entity.setName(lp.getName());
                entity.setVersion(lp.getVersion());
                entity.setDescription(lp.getDescription());
                entity.setSource(isBuiltin ? "builtin" : "external");
                entity.setJarPath(isBuiltin ? null : lp.getSource());
                entity.setProviderClass(providerClass);
                entity.setActive(Boolean.TRUE);
                entity.setDeleted(0);
                entity.setLoadedAt(loadedAt);
                mapper.insert(entity);
                log.info("DB INSERT 协议记录: name={}, version={}, source={}", lp.getName(), lp.getVersion(), entity.getSource());
            } else {
                // 已存在（含历史逻辑删除的）：更新元数据并恢复
                ProtocolJarRegistry update = new ProtocolJarRegistry();
                update.setId(existing.getId());
                update.setVersion(lp.getVersion());
                update.setDescription(lp.getDescription());
                update.setSource(isBuiltin ? "builtin" : "external");
                update.setJarPath(isBuiltin ? null : lp.getSource());
                update.setProviderClass(providerClass);
                update.setActive(Boolean.TRUE);
                update.setDeleted(0);
                update.setLoadedAt(loadedAt);
                mapper.updateIgnoreLogic(update);
                log.info("DB UPDATE 协议记录: id={}, name={}, version={}", existing.getId(), lp.getName(), lp.getVersion());
            }
        } catch (Exception e) {
            log.warn("DB 同步协议注册失败（不影响运行时）: name={}, err={}", lp.getName(), e.getMessage());
        }
    }

    /**
     * 同步协议卸载：逻辑删除（deleted=1）+ 标记 inactive。
     */
    public void syncUnload(String name) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<ProtocolJarRegistry>()
                            .eq(ProtocolJarRegistry::getName, name)
                            .set(ProtocolJarRegistry::getActive, Boolean.FALSE)
                            .set(ProtocolJarRegistry::getDeleted, 1)
                            .set(ProtocolJarRegistry::getUpdatedAt, LocalDateTime.now()));

            portBindingMapper.update(null,
                    new LambdaUpdateWrapper<PortBinding>()
                            .eq(PortBinding::getProtocolName, name)
                            .set(PortBinding::getEnabled, Boolean.FALSE)
                            .set(PortBinding::getDeleted, 1)
                            .set(PortBinding::getUpdatedAt, LocalDateTime.now()));

            log.debug("DB 软删 协议: name={}", name);
        } catch (Exception e) {
            log.warn("DB 同步协议卸载失败（不影响运行时）: name={}, err={}", name, e.getMessage());
        }
    }

    /**
     * 恢复协议：将已删除或未启用的协议恢复为启用状态。
     */
    public void restoreProtocol(String name) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<ProtocolJarRegistry>()
                            .eq(ProtocolJarRegistry::getName, name)
                            .set(ProtocolJarRegistry::getActive, Boolean.TRUE)
                            .set(ProtocolJarRegistry::getDeleted, 0)
                            .set(ProtocolJarRegistry::getUpdatedAt, LocalDateTime.now()));

            portBindingMapper.update(null,
                    new LambdaUpdateWrapper<PortBinding>()
                            .eq(PortBinding::getProtocolName, name)
                            .set(PortBinding::getEnabled, Boolean.TRUE)
                            .set(PortBinding::getDeleted, 0)
                            .set(PortBinding::getUpdatedAt, LocalDateTime.now()));

            log.info("DB 恢复协议: name={}", name);
        } catch (Exception e) {
            log.warn("DB 恢复协议失败: name={}, err={}", name, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /** 查询所有未逻辑删除的协议记录（按加载时间倒序） */
    public List<ProtocolJarRegistry> listAll() {
        return mapper.selectList(
                new LambdaQueryWrapper<ProtocolJarRegistry>()
                        .orderByDesc(ProtocolJarRegistry::getLoadedAt));
    }

    /** 查询数据库中所有协议记录（包括已删除的） */
    public List<ProtocolJarRegistry> listAllInDb() {
        List<ProtocolJarRegistry> result = mapper.selectAllIncludeDeleted();
        log.info("listAllInDb 查询结果: count={}", result.size());
        for (ProtocolJarRegistry p : result) {
            log.info("  协议: name={}, active={}, deleted={}, source={}", 
                    p.getName(), p.getActive(), p.getDeleted(), p.getSource());
        }
        return result;
    }

    public void deleteJar(String name) {
        ProtocolJarRegistry existing = mapper.selectByNameIgnoreDeleted(name);
        
        if (existing == null) {
            log.warn("未找到协议记录: name={}", name);
            return;
        }
        
        String jarPath = existing.getJarPath();
        if (jarPath == null || jarPath.isEmpty()) {
            log.warn("协议无 jar 路径，跳过删除: name={}", name);
            return;
        }
        
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
                            .eq(ProtocolJarRegistry::getActive, Boolean.TRUE)
                            .eq(ProtocolJarRegistry::getDeleted, 0));
            for (ProtocolJarRegistry p : list) {
                if (p.getJarPath() != null && !p.getJarPath().isEmpty()) {
                    result.put(p.getName(), p.getJarPath());
                }
            }
            log.info("从 DB 加载活跃外部协议: {} 条", result.size());
        } catch (Exception e) {
            log.warn("DB 加载外部协议失败（退化为空映射）: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<Integer> getEnabledPortsByProtocol(String protocolName) {
        try {
            return portBindingMapper.selectList(
                    new LambdaQueryWrapper<PortBinding>()
                            .eq(PortBinding::getProtocolName, protocolName)
                            .eq(PortBinding::getEnabled, Boolean.TRUE)
                            .eq(PortBinding::getDeleted, 0))
                    .stream()
                    .map(PortBinding::getPort)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("DB 查询协议端口绑定失败: protocol={}, err={}", protocolName, e.getMessage());
            return new ArrayList<Integer>();
        }
    }

    public void activeProtocol(String name) {
        try {
            mapper.update(null,
                    new LambdaUpdateWrapper<ProtocolJarRegistry>()
                            .eq(ProtocolJarRegistry::getName, name)
                            .set(ProtocolJarRegistry::getActive, Boolean.TRUE)
                            .set(ProtocolJarRegistry::getUpdatedAt, LocalDateTime.now()));
            log.info("DB 激活协议: name={}", name);
        } catch (Exception e) {
            log.warn("DB 激活协议失败: name={}, err={}", name, e.getMessage());
        }
    }

    public ProtocolJarRegistry selectByName(String name) {
        return mapper.selectByNameIgnoreDeleted(name);
    }
}
