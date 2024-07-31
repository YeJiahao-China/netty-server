package com.cas.access.netty.client;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author JHYe
 * @date 2024/6/18
 */
public class App {

//    static Pattern pattern = Pattern.compile("CP=&&(?<CP>[^&]*(?:&[^&]*)*)&&");
//    static Pattern pattern = Pattern.compile("CP=&&(?<CP>[\\w|\\W]+?)&&");
    static Pattern pattern = Pattern.compile("CP=&&(?<CP>(\\w|\\W)+)&&");

    static Pattern p = Pattern.compile("ABCD&&(\\w|\\W)+&&");

    public static void main(String[] args) {
//        String text = "QN=20240606000200000;ST=22;CN=2061;PW=M13HKC9FU8P9304EOD4250BY;MN=340100001V009;Flag=5;CP=&&DataTime&&=20240605230000;a24001-Avg=1.851,a24001-Flag=N;a24045-Avg=0.826,a24045-Flag=N;a24002-Avg=1.056,a24002-Flag=N;a24053-Avg=0.159,a24053-Flag=N;a24038-Avg=0.448,a24038-Flag=N;a24037-Avg=0.629,a24037-Flag=N;a24079-Avg=0.790,a24079-Flag=N;a24064-Avg=0.105,a24064-Flag=N;a24059-Avg=0.000,a24059-Flag=N;a24063-Avg=0.070,a24063-Flag=N;a24514-Avg=0.836,a24514-Flag=N;a24041-Avg=0.102,a24041-Flag=N;a24039-Avg=0.371,a24039-Flag=N;a24074-Avg=0.105,a24074-Flag=N;a24077-Avg=0.111,a24077-Flag=N;a24076-Avg=0.000,a24076-Flag=N;a24503-Avg=0.092,a24503-Flag=N;a24537-Avg=0.945,a24537-Flag=N;a24011-Avg=0.105,a24011-Flag=N;a24513-Avg=0.164,a24513-Flag=N;a24042-Avg=0.302,a24042-Flag=N;a24061-Avg=0.000,a24061-Flag=N;a24502-Avg=0.439,a24502-Flag=N;a25002-Avg=0.169,a25002-Flag=N;a25003-Avg=0.505,a25003-Flag=N;a24070-Avg=0.055,a24070-Flag=N;a25004-Avg=0.210,a25004-Flag=N;a25504-Avg=0.311,a25504-Flag=N;a25006-Avg=0.193,a25006-Flag=N;a25038-Avg=0.302,a25038-Flag=N;a24515-Avg=0.301,a24515-Flag=N;a24036-Avg=0.117,a24036-Flag=N;a24510-Avg=0.121,a24510-Flag=N;a24504-Avg=0.000,a24504-Flag=N;a24506-Avg=0.000,a24506-Flag=N;a24512-Avg=0.154,a24512-Flag=N;a24012-Avg=0.127,a24012-Flag=N;a24043-Avg=0.229,a24043-Flag=N;a24084-Avg=0.141,a24084-Flag=N;a24536-Avg=0.000,a24536-Flag=N;a24507-Avg=0.000,a24507-Flag=N;a24511-Avg=0.129,a24511-Flag=N;a24044-Avg=0.034,a24044-Flag=N;a25034-Avg=0.000,a25034-Flag=N;a25033-Avg=0.099,a25033-Flag=N;a25501-Avg=0.100,a25501-Flag=N;a25014-Avg=0.131,a25014-Flag=N;a25021-Avg=0.122,a25021-Flag=N;a25500-Avg=0.125,a25500-Flag=N;a25019-Avg=0.000,a25019-Flag=N;a24068-Avg=0.152,a24068-Flag=N;a25020-Avg=0.194,a25020-Flag=N;a25506-Avg=0.000,a25506-Flag=N;a25503-Avg=0.274,a25503-Flag=N;a24517-Avg=0.443,a24517-Flag=N;a24516-Avg=0.853,a24516-Flag=N&&";
//        System.out.println(text.length());
//        Matcher matcher = pattern.matcher(text);
//        if (matcher.find()){
//            String group = matcher.group(1);
//            System.out.println(group);
//        }

        String text = "ABCD&&jdklajdsiodjsaoidjkasdhjksahdkjsahdusdhsjkdhajksdhkajsdhkjashdkjashdkajdhawuidhkajdhksjdhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy&&";
        Matcher matcher = p.matcher(text);
        if (matcher.find()){
            String group = matcher.group(0);
            System.out.println(group);
        }
    }


//    public static void main(String[] args) {
//        String str = "QN=20240606000056000;ST=22;CN=2061;PW=O447JGPYQ75V3C58D138L2WX;MN=340100001V008;Flag=5;CP=&&DataTime=20240605230000;a34514-Avg=305.000,a34514-Flag=N;a34515-Avg=268.333,a34515-Flag=N;a34516-Avg=276.667,a34516-Flag=N;a34517-Avg=455.000,a34517-Flag=N;a34518-Avg=163.333,a34518-Flag=N;a34519-Avg=156.667,a34519-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34520-Avg=298.333,a34520-Flag=N;a34533-Avg=298.333,a34533-Flag=N";
////        String str = "QN=20240606000056000;ST=22;CN=2061;PW=O447JGPYQ75V3C58D138L2WX;MN=340100001V008;Flag=5;CP=&&DataTime=20240605230000;a34514-Avg=305.000,a34514-Flag=N;a34515-Avg=268.333,a34515-Flag=N";
////        String str = "CP=&&DataTime=20240605230000;a34514-Avg=305.000,a34514-Flag=N;a34515-Avg=268.333,a34515-Flag=N;a34516-Avg=276.667,a34516-Flag=N;a34500-Avg=276.667,a34500-Flag=N;a34501-Avg=276.667,a34501-Flag=N;a34502-Avg=276.667,a34502-Flag=N;a34502-Avg=276.667,a34502-Flag=N;a34503-Avg=276.667,a34503-Flag=N;a34504-Avg=276.667,a34504-Flag=N;a34502-Avg=276.667,a34502-Flag=N"
//        int length = str.length();
//        System.out.println(length);
//
//        int ret = (6 & 4) << 1;
//        System.out.println(ret);
//    }

//    public static void main(String[] args) {
//        // 指定文件夹路径
//        String directoryPath = "C:\\Users\\yjh_c\\Downloads\\440791";
//        File directory = new File(directoryPath);
//
//        // 检查目录是否存在
//        if (!directory.isDirectory()) {
//            System.out.println("指定的路径不是一个目录");
//            return;
//        }
//
//        // 获取目录中的所有文件
//        File[] files = directory.listFiles();
//        if (files == null || files.length == 0) {
//            System.out.println("目录中没有文件");
//            return;
//        }
//
//        // 遍历目录中的所有文件
//        for (
//                File file : files) {
//            if (file.isFile()) {
//                // 获取文件名和扩展名
//                String fileName = file.getName();
//                int lastDotIndex = fileName.lastIndexOf('.');
//                if (lastDotIndex == -1) {
//                    // 没有扩展名的情况
//                    String newFileName = fileName + ".jpg";
//                    File newFile = new File(directory, newFileName);
//                    if (file.renameTo(newFile)) {
//                        System.out.println("成功重命名文件: " + fileName + " 为 " + newFileName);
//                    } else {
//                        System.out.println("重命名文件失败: " + fileName);
//                    }
//                } else {
//                    // 有扩展名的情况
//                    String nameWithoutExtension = fileName.substring(0, lastDotIndex);
//                    String newFileName = nameWithoutExtension + ".jpg";
//                    File newFile = new File(directory, newFileName);
//                    if (file.renameTo(newFile)) {
//                        System.out.println("成功重命名文件: " + fileName + " 为 " + newFileName);
//                    } else {
//                        System.out.println("重命名文件失败: " + fileName);
//                    }
//                }
//            }
//        }
//
//    }
}
