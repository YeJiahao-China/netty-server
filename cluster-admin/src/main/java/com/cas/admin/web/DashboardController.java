package com.cas.admin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理中心 SSR 页面路由（仅渲染空模板，动态数据统一由前端 JS 调 JSON API 拉取）。
 */
@Controller
public class DashboardController {

    @GetMapping("/")                 public String dashboard()    { return "dashboard"; }
    @GetMapping("/protocols-page")   public String protocols()    { return "protocols-page"; }
    @GetMapping("/port-topics-page") public String portTopics()   { return "port-topics-page"; }
    @GetMapping("/bridge-logs-page") public String bridgeLogs()   { return "bridge-logs-page"; }
    @GetMapping("/nodes-page")       public String nodes()        { return "nodes-page"; }
    @GetMapping("/connections")      public String connections()  { return "connections"; }
    @GetMapping("/ports")            public String ports()        { return "ports"; }
    @GetMapping("/traffic")          public String traffic()      { return "traffic"; }
    @GetMapping("/logs")             public String logs()         { return "logs"; }
    @GetMapping("/api-docs")         public String apiDocs()      { return "api-docs"; }
}
