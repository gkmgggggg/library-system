package com.example.library.catalog;
import com.example.library.common.BusinessException;
public final class Isbn {
    private Isbn() {}
    public static String normalize(String input) {
        if(input==null || input.isBlank()) return null;
        String value=input.replaceAll("[\\s-]","").toUpperCase();
        if(value.matches("[0-9]{9}[0-9X]")) {
            int sum=0;for(int i=0;i<10;i++) sum+=(10-i)*(value.charAt(i)=='X'?10:value.charAt(i)-'0');
            BusinessException.check(sum%11==0,"INVALID_ISBN","ISBN-10 校验位不正确");
            value="978"+value.substring(0,9); int total=0;
            for(int i=0;i<12;i++) total+=(value.charAt(i)-'0')*(i%2==0?1:3);
            return value+(10-total%10)%10;
        }
        BusinessException.check(value.matches("97[89][0-9]{10}"),"INVALID_ISBN","ISBN 应为有效的 10 或 13 位编号");
        int total=0;for(int i=0;i<13;i++) total+=(value.charAt(i)-'0')*(i%2==0?1:3);
        BusinessException.check(total%10==0,"INVALID_ISBN","ISBN-13 校验位不正确");return value;
    }
}
