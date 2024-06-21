package com.cas.access.netty.client;

import java.io.File;

/**
 * @author JHYe
 * @date 2024/6/18
 */
public class App {
    public static void main(String[] args) {
        // 指定文件夹路径
        String directoryPath = "C:\\Users\\yjh_c\\Downloads\\440791";
        File directory = new File(directoryPath);

        // 检查目录是否存在
        if (!directory.isDirectory()) {
            System.out.println("指定的路径不是一个目录");
            return;
        }

        // 获取目录中的所有文件
        File[] files = directory.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("目录中没有文件");
            return;
        }

        // 遍历目录中的所有文件
        for (
                File file : files) {
            if (file.isFile()) {
                // 获取文件名和扩展名
                String fileName = file.getName();
                int lastDotIndex = fileName.lastIndexOf('.');
                if (lastDotIndex == -1) {
                    // 没有扩展名的情况
                    String newFileName = fileName + ".jpg";
                    File newFile = new File(directory, newFileName);
                    if (file.renameTo(newFile)) {
                        System.out.println("成功重命名文件: " + fileName + " 为 " + newFileName);
                    } else {
                        System.out.println("重命名文件失败: " + fileName);
                    }
                } else {
                    // 有扩展名的情况
                    String nameWithoutExtension = fileName.substring(0, lastDotIndex);
                    String newFileName = nameWithoutExtension + ".jpg";
                    File newFile = new File(directory, newFileName);
                    if (file.renameTo(newFile)) {
                        System.out.println("成功重命名文件: " + fileName + " 为 " + newFileName);
                    } else {
                        System.out.println("重命名文件失败: " + fileName);
                    }
                }
            }
        }

    }
}
