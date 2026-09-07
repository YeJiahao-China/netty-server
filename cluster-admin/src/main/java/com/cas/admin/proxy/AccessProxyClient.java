package com.cas.admin.proxy;

import com.cas.admin.service.PrimaryNodeRouter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 通用"同源代理到 primary iot-access"工具。
 * <p>集群内所有节点共用同库：查询类接口代理到 primary 即可返回一致数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessProxyClient {

    private final PrimaryNodeRouter router;
    private final RestClient.Builder restClientBuilder;

    /**
     * 代理：转发当前请求（method + URI + body）到 primary iot-access 的同一路径，
     * 并把上游响应体 / 状态码 / Content-Type 原样回传给浏览器。
     */
    public ResponseEntity<byte[]> proxyToAccess(HttpServletRequest request) {
        String base = router.resolveAccessBase();
        if (base == null) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"success\":false,\"reason\":\"没有可用的 iot-access 节点\"}".getBytes());
        }
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String target = base + uri + (query == null ? "" : ("?" + query));

        HttpMethod method = HttpMethod.valueOf(request.getMethod().toUpperCase());
        byte[] bodyBytes = readBody(request);

        RestClient client = restClientBuilder.build();
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(target);
            String contentType = request.getContentType();
            if (bodyBytes.length > 0) {
                if (contentType != null) spec.contentType(MediaType.parseMediaType(contentType));
                spec.body(bodyBytes);
            }
            ResponseEntity<byte[]> resp = spec
                    .retrieve()
                    .toEntity(byte[].class);
            return ResponseEntity.status(resp.getStatusCode())
                    .contentType(resp.getHeaders().getContentType())
                    .body(resp.getBody());
        } catch (RestClientResponseException e) {
            org.springframework.http.HttpHeaders h = e.getResponseHeaders();
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(h != null ? h.getContentType() : MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsByteArray());
        } catch (Exception e) {
            log.warn("代理 iot-access 失败: {} {} -> {}, err={}", method, uri, target, e.getMessage());
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{\"success\":false,\"reason\":\"iot-access 不可达: " + escape(e.getMessage()) + "\"}").getBytes());
        }
    }

    private static byte[] readBody(HttpServletRequest req) {
        try (InputStream in = req.getInputStream()) {
            if (in == null) return new byte[0];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }
}
