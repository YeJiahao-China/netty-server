package com.cas.access.netty.enums;


/**
 * @author JHYe
 * @date 2023/10/25
 */
public enum DelimiterEnum {

    SP_10101(10000, "\r\n"),
    SP_20202(20000, "####");

    private int serverPort;
    private String delimiter;

    DelimiterEnum(int serverPort, String delimiter) {
        this.serverPort = serverPort;
        this.delimiter = delimiter;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public static String delimiter(int serverPort) {
        if (serverPort == SP_10101.serverPort) return SP_10101.delimiter;
        if (serverPort == SP_20202.serverPort) return SP_20202.delimiter;
        return null;
    }
}
