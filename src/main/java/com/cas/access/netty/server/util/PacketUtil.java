package com.cas.access.netty.server.util;

import com.alibaba.fastjson2.JSONObject;

/**
 * @author JHYe
 * @date 2023/10/25
 */
public class PacketUtil {

    static String pattern = "^(.{4})(.{13})(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})(.{4})(.{2})(.{2})@@@(.*?)tek(.{2})";

//    public static void parseDataStr(String dataStr) {
//        Pattern compile = Pattern.compile(pattern);
//        Matcher matcher = compile.matcher(dataStr);
//        boolean matches = matcher.matches();
//        if (matches) {
//            String cmd = matcher.group(1);
//            String sc = matcher.group(2);
//            String dt = matcher.group(3);
//            String dsl = matcher.group(4);
//            String packets = matcher.group(5);
//            String packetId = matcher.group(6);
//            String group = matcher.group(7);
//            String group = matcher.group(8);
//            String group = matcher.group(9);
//            System.out.println(group);
//        }
//    }

    public static JSONObject msgToJsonOb(String dataStr) {
        JSONObject jsonObject = new JSONObject();
        // 命令编码
        String command = dataStr.substring(0, 4);
        jsonObject.put("cmd", command);
        // 站点编号
        String siteCode = dataStr.substring(4, 17);
        jsonObject.put("siteCode", siteCode);
        // 数据时标
        String dataTime = dataStr.substring(17, 36);
        jsonObject.put("dataTime", dataTime);
        // 数据段长度
        String dataLength = dataStr.substring(36, 40);
        jsonObject.put("dataLength", dataLength);
        // 包数
        String packets = dataStr.substring(40, 42);
        jsonObject.put("packets", packets);
        // 包号
        String packetNum = dataStr.substring(42, 44);
        jsonObject.put("packetNum", packetNum);
        // 数据段
        String dataSegment = dataStr.substring(47, dataStr.length() - 5);
        splitDataSegment(dataSegment, jsonObject);
        return jsonObject;
    }

    public static void splitDataSegment(String dataSegment, JSONObject jsonObject) {
        String[] array = dataSegment.split(";");
        for (int i = 0; i < array.length; i++) {
            String one = array[i];
            String[] subOne = one.split(",");
            String factor = subOne[0];
            double value = Double.parseDouble(subOne[1]);
            String flag = subOne[2];
            JSONObject subJsonObject = new JSONObject();
            subJsonObject.put("flag", flag);
            subJsonObject.put("value", value);
            jsonObject.put(factor, subJsonObject);
        }
    }

}
