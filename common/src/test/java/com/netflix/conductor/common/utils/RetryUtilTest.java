package com.netflix.conductor.common.utils;

import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.*;

public class RetryUtilTest {

    @Test
    public void retryOnException() {
        SupplierProbe supplier = new SupplierProbe("hi");

        Object val = new RetryUtil<>().retryOnException(supplier, null, null, 2, "retryOnException test", "null args, 2 retries");

        assertEquals(1, supplier.getInvocations());
        assertEquals("hi", val);
    }

    private static class SupplierProbe implements Supplier<Object> {
        private Object obj;
        private int invocations;

        SupplierProbe(Object obj) {
            this.obj = obj;
        }

        public int getInvocations() {
            return invocations;
        }

        @Override
        public Object get() {
            invocations++;
            return obj;
        }
    }

}
