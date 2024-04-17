package com.devcharly.onedev.plugin.imports.redmine;

import io.onedev.server.util.JerseyUtils;

public interface MyPageDataConsumer extends JerseyUtils.PageDataConsumer {
    void setTotal(int total);
    int getTotal();
}
