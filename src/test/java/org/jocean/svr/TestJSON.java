package org.jocean.svr;

import com.alibaba.fastjson.JSON;

public class TestJSON {

    public static void main(final String[] args) {
        System.out.println(JSON.toJSONString(new String[]{"hello", "world"}));
    }

}
