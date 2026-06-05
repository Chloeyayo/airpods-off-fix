package com.maxine.airpodsofffix.shizuku;

import java.io.InputStream;

final class AapReader implements Runnable {
    private final InputStream is;
    private final AapOff owner;

    AapReader(InputStream is, AapOff owner) {
        this.is = is;
        this.owner = owner;
    }

    @Override
    public void run() {
        try {
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) >= 0) {
                if (n > 0) owner.scan(buf, n);
            }
        } catch (Throwable ignored) {
        } finally {
            owner.markChannelDead();
        }
    }
}
