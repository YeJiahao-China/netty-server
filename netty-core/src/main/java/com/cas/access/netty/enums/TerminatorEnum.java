package com.cas.access.netty.enums;

/**
 * @author JHYe
 * @date 2023/9/20
 */
public enum TerminatorEnum {

    LineBasedFrameDecoder("\r\n","换行+回车"),

    Line_Break("\n","换行，光标下移一格"),

    Enter("\r","光标移动到行首")
    ;
    private String symbol;

    private String explain;

    TerminatorEnum(String symbol, String explain) {
        this.symbol = symbol;
        this.explain = explain;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getExplain() {
        return explain;
    }

    public void setExplain(String explain) {
        this.explain = explain;
    }
}
