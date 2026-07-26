package com.cuiyi.passwordnotebook;

import java.security.MessageDigest;
import java.security.SecureRandom;
import android.util.Base64;

/**
 * 密码生成引擎 v2.1
 * - generateHashed:   基于核心词+年份-周数的SHA-256哈希密码
 * - generatePeriodic: 基于时间+自定义盐的轮换密码
 * - generateRandom:   可配置随机密码（常见符号/扩展符号分离）
 * - evaluateStrength: 密码强度评估 (0-5星)
 */
public class PasswordGenerator {

    /**
     * 哈希生成：核心词 + 年份-周数 → SHA-256 → Base64 → 取前16位
     */
    public static String generateHashed(String coreWord, int year, int week) {
        try {
            String input = coreWord + ":" + year + "-W" + week;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            String b64 = Base64.encodeToString(hash, Base64.NO_WRAP)
                .replace('+', 'A')
                .replace('/', 'z')
                .replace('=', '');
            return b64.substring(0, Math.min(16, b64.length()));
        } catch (Exception e) {
            return "ERROR";
        }
    }

    /**
     * 周期密码：自定义盐 + 年份-周数 → SHA-256 → Base64 → 取前10位
     */
    public static String generatePeriodic(int year, int week, String customSalt) {
        try {
            String input = (customSalt != null && !customSalt.isEmpty() ? customSalt : "pwdnb")
                + ":" + year + "-W" + week;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            String b64 = Base64.encodeToString(hash, Base64.NO_WRAP)
                .replace('+', 'A')
                .replace('/', 'z')
                .replace('=', '');
            return b64.substring(0, Math.min(10, b64.length()));
        } catch (Exception e) {
            return "ERROR";
        }
    }

    /**
     * 随机密码生成
     * @param length        密码长度
     * @param upper         包含大写字母
     * @param lower         包含小写字母
     * @param digits        包含数字
     * @param specialCommon 包含常见符号 !@#$%^&*-_=+.
     * @param specialExt    包含扩展符号 <>[]{}()/\
     * @return 生成的密码字符串
     */
    public static String generateRandom(int length, boolean upper, boolean lower, boolean digits,
                                          boolean specialCommon, boolean specialExt) {
        String upperChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lowerChars = "abcdefghijklmnopqrstuvwxyz";
        String digitChars = "0123456789";
        String commonChars = "!@#$%^&*-_=+.";
        String extChars = "<>[]{}()/\\";

        StringBuilder pool = new StringBuilder();
        if (upper) pool.append(upperChars);
        if (lower) pool.append(lowerChars);
        if (digits) pool.append(digitChars);
        if (specialCommon) pool.append(commonChars);
        if (specialExt) pool.append(extChars);

        if (pool.length() == 0) return "";

        SecureRandom r = new SecureRandom();
        StringBuilder pwd = new StringBuilder();

        // 先各取一个确保每个启用类别至少出现一次
        if (upper) pwd.append(upperChars.charAt(r.nextInt(upperChars.length())));
        if (lower) pwd.append(lowerChars.charAt(r.nextInt(lowerChars.length())));
        if (digits) pwd.append(digitChars.charAt(r.nextInt(digitChars.length())));
        if (specialCommon) pwd.append(commonChars.charAt(r.nextInt(commonChars.length())));
        if (specialExt) pwd.append(extChars.charAt(r.nextInt(extChars.length())));

        // 填充剩余长度
        while (pwd.length() < length) {
            pwd.append(pool.charAt(r.nextInt(pool.length())));
        }

        // 打乱顺序
        char[] arr = pwd.toString().toCharArray();
        for (int i = arr.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            char tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }

        return new String(arr).substring(0, Math.min(length, arr.length));
    }

    /**
     * 密码强度评估 (0-5星)
     */
    public static int evaluateStrength(String pwd) {
        if (pwd == null || pwd.isEmpty()) return 0;
        int score = 0;
        if (pwd.length() >= 8) score++;
        if (pwd.length() >= 12) score++;
        if (pwd.matches(".*[a-z].*")) score++;
        if (pwd.matches(".*[A-Z].*")) score++;
        if (pwd.matches(".*\\d.*")) score++;
        if (pwd.matches(".*[!@#$%^&*\\-_.=+<>\\[\\]{}()/\\\\].*")) score++;
        return Math.min(score, 5);
    }
}
