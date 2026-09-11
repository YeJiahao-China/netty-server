package com.cas.access.netty.api;

import com.cas.access.netty.cluster.ProtocolSyncReporter;
import com.cas.access.netty.mapper.ProtocolJarSyncMapper;
import com.cas.access.netty.service.ProtocolCompensationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.cas.cluster.node.constant.ProtocolConstants.MODE_BIND;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_CLEANUP_UPLOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_PURGE;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_RELOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_ROLLBACK_UPDATE;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_SYNC_UPDATE;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_SYNC_UPLOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_UNBIND;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_UNLOAD;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_UPDATE;
import static com.cas.cluster.node.constant.ProtocolConstants.MODE_UPLOAD;

/**
 * 协议管理 V2 内部分发接口（端口 2310）。
 *
 * <p>由 cluster-admin 的 {@code ProtocolAdminV2Controller} 广播调用，
 * 支持 upload/update 的备份与回滚补偿。
 *
 * <p>本类作为新增 Controller 存在，不修改现有 {@link } 代码。
 */
@Slf4j
@RestController
@RequestMapping("/protocols/v2/internal")
public class ProtocolV2Controller {

    @Resource
    private ProtocolCompensationService compensationService;

    @Resource
    private ProtocolSyncReporter syncReporter;

    @Resource
    private ProtocolJarSyncMapper protocolJarSyncMapper;

    /**
     * 管理中心广播式协议操作落地入口（V2）。
     *
     * <p>body.mode 取值：
     * <ul>
     *   <li>sync-upload：从 DB 拉 jar_bytes → 落盘 → probe → 注册 → 绑定端口</li>
     *   <li>sync-update：从 DB 拉新 jar → probe → 热替换 → 重绑端口</li>
     *   <li>upload：上传 jar（带 jarBase64）并绑定端口</li>
     *   <li>update：更新 jar（带 jarBase64，自动备份旧 jar）</li>
     *   <li>rollback-update：从备份恢复旧 jar</li>
     *   <li>cleanup-upload：unload + 删 jar + 删 DB 记录</li>
     *   <li>bind / unbind / unload / purge / reload</li>
     * </ul>
     */
    @PostMapping("/distribute")
    public Map<String, Object> distribute(@RequestBody Map<String, Object> body) {
        String mode = (String) body.get("mode");
        if (mode == null || mode.isBlank()) return fail("mode 不能为空");
        String name = (String) body.get("protocolName");
        Map<String, Object> result;
        try {
            switch (mode) {
                case MODE_SYNC_UPLOAD:
                    result = doSyncUpload(body);
                    break;
                case MODE_SYNC_UPDATE:
                    result = doSyncUpdate(body);
                    break;
                case MODE_UPLOAD:
                    result = doInternalUpload(body);
                    break;
                case MODE_UPDATE:
                    result = doInternalUpdate(body);
                    break;
                case MODE_ROLLBACK_UPDATE: {
                    if (name == null) return fail("protocolName 为空");
                    result = compensationService.rollbackUpdate(name);
                    break;
                }
                case MODE_CLEANUP_UPLOAD: {
                    if (name == null) return fail("protocolName 为空");
                    result = compensationService.cleanupUpload(name);
                    break;
                }
                case MODE_RELOAD: {
                    if (name == null) return fail("protocolName 为空");
                    // 协议重启仅允许单协议维度：全量扫描式重载会无差别加载目录下所有 jar，
                    // 已卸载协议（jar 保留用于单协议恢复）会被意外复活，故不提供
                    result = compensationService.reload(name);
                    break;
                }
                case MODE_BIND: {
                    Object p = body.get("port");
                    int port = p == null ? 0 : ((Number) p).intValue();
                    if (name == null || port <= 0) return fail("protocolName/port 非法");
                    result = compensationService.bind(name, port);
                    break;
                }
                case MODE_UNBIND: {
                    Object p = body.get("port");
                    int port = p == null ? 0 : ((Number) p).intValue();
                    if (port <= 0) return fail("port 非法");
                    result = compensationService.unbind(port);
                    break;
                }
                case MODE_UNLOAD: {
                    if (name == null) return fail("protocolName 为空");
                    result = compensationService.unload(name);
                    break;
                }
                case MODE_PURGE: {
                    if (name == null) return fail("protocolName 为空");
                    result = compensationService.purge(name);
                    break;
                }
                default:
                    result = fail("未知 mode: " + mode);
            }
        } catch (Exception e) {
            log.error("V2 distribute 执行失败: mode={}, protocolName={}, err={}", mode, name, e.getMessage(), e);
            result = fail("distribute 失败: " + e.getMessage());
        }
        reportIfApplicable(mode, name, result);
        return result;
    }

    private void reportIfApplicable(String mode, String protocolName, Map<String, Object> result) {
        if (protocolName == null || protocolName.isBlank()) {
            return;
        }
        if (MODE_CLEANUP_UPLOAD.equals(mode) && Boolean.TRUE.equals(result.get("success"))) {
            // cleanup 成功后该协议在本节点已不存在，暂不删除状态记录，而是标为 UNKNOWN
            syncReporter.reportUnknown(protocolName, "cleanup-upload completed");
            return;
        }
        syncReporter.report(protocolName, mode, result);
    }

    private Map<String, Object> doInternalUpload(Map<String, Object> body) throws Exception {
        Object p = body.get("port");
        int port = p == null ? 0 : ((Number) p).intValue();
        String name = (String) body.get("protocolName");
        String b64  = (String) body.get("jarBase64");
        String fileName = (String) body.getOrDefault("fileName", name + ".jar");
        if (name == null || name.isBlank()) return fail("protocolName 为空");
        if (port < 1024 || port > 65535) return fail("port 非法");
        if (b64 == null || b64.isBlank()) return fail("jarBase64 为空");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException e) { return fail("jarBase64 非法"); }
        return compensationService.upload(name, port, bytes, fileName);
    }

    private Map<String, Object> doInternalUpdate(Map<String, Object> body) throws Exception {
        String name = (String) body.get("protocolName");
        String b64  = (String) body.get("jarBase64");
        String fileName = (String) body.getOrDefault("fileName", name + ".jar");
        if (name == null) return fail("protocolName 为空");
        if (b64 == null || b64.isBlank()) return fail("jarBase64 为空");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException e) { return fail("jarBase64 非法"); }
        return compensationService.update(name, bytes, fileName);
    }

    /** sync-upload: 从 DB 拉 jar_bytes → 调 compensationService.upload */
    private Map<String, Object> doSyncUpload(Map<String, Object> body) throws Exception {
        Object p = body.get("port");
        int port = p == null ? 0 : ((Number) p).intValue();
        String protocolName = (String) body.get("protocolName");
        String fileName = (String) body.getOrDefault("fileName", protocolName + ".jar");
        if (protocolName == null || protocolName.isBlank()) return fail("协议名称为空");
        if (port < 1024 || port > 65535) return fail("端口非法");

        Map<String, Object> row = protocolJarSyncMapper.selectJarByName(protocolName);
        if (row == null) return fail("DB 仓库中未找到协议[" + protocolName + "]");
        byte[] bytes = extractBytes(row);
        if (bytes == null) return fail("DB 仓库中 jar_bytes 为空");
        return compensationService.upload(protocolName, port, bytes, fileName);
    }

    /** sync-update: 从 DB 拉 jar_bytes → 调 compensationService.update */
    private Map<String, Object> doSyncUpdate(Map<String, Object> body) throws Exception {
        String name = (String) body.get("protocolName");
        String fileName = (String) body.getOrDefault("fileName", name + ".jar");
        if (name == null || name.isBlank()) return fail("protocolName 为空");

        Map<String, Object> row = protocolJarSyncMapper.selectJarByName(name);
        if (row == null) return fail("DB 仓库中未找到协议[" + name + "]");
        byte[] bytes = extractBytes(row);
        if (bytes == null) return fail("DB 仓库中 jar_bytes 为空");
        return compensationService.update(name, bytes, fileName);
    }

    /** 从 DB 查询结果 Map 中提取 jar_bytes */
    private byte[] extractBytes(Map<String, Object> row) {
        Object obj = row.get("jar_bytes");
        if (obj == null) obj = row.get("jarBytes");
        if (obj == null) return null;
        if (obj instanceof byte[]) return (byte[]) obj;
        if (obj instanceof String s) {
            try { return Base64.getDecoder().decode(s); }
            catch (Exception e) { return null; }
        }
        return null;
    }

    private Map<String, Object> fail(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("reason", reason);
        return m;
    }
}
