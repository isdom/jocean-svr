package org.jocean.svr;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.function.BiFunction;

import org.apache.commons.beanutils.BeanUtils;

//import org.springframework.beans.BeanUtils;

public class BeansDemo {

    public static class A {


        public int getX() {
            return x;
        }

        public void setX(final int x) {
            this.x = x;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("A [x=").append(x).append("]");
            return builder.toString();
        }

        private int x;
    }

    public static void main(final String[] args) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        final A a = new A();

        a.setX(10);

//        target.put("x", null);

         final Map<String, String> target = BeanUtils.describe(a);

        System.out.println("A:" + a);
        System.out.println("target:" + target);

        final BiFunction<Class<?>, Object[], A> serviceBuilder = null;

        final A xa = serviceBuilder.apply(A.class, new Object[]{1});
    }

}
