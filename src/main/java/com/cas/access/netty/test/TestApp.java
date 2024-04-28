package com.cas.access.netty.test;

import java.util.HashMap;

/**
 * @author JHYe
 * @date 2023/12/1
 */
public class TestApp {

    public static void main(String[] args) {
        HashMap<String, String> map = new HashMap<>(8);
        map.put("1-10","1");
        map.put("1-10","2");
        map.put("1-10","3");
        map.put("1-10","4");
        map.put("1-10","5");
        map.put("1-10","6");
        map.put("1-10","7");
        map.put("1-10","8");
        map.put("1-10","9");
        map.put("1-10","10");
        System.out.println("map.size = "+map.size());
    }

}
