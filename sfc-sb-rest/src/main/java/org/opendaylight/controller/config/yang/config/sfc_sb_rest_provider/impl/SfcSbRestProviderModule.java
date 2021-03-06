/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.config.yang.config.sfc_sb_rest_provider.impl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.opendaylight.sfc.sbrest.provider.keepalive.SbRestKeepAliveSocket;

public class SfcSbRestProviderModule {
    protected static ExecutorService executor = Executors.newFixedThreadPool(5);

    public void startSbRestKeepAliveSocket() {
        executor.execute(new SbRestKeepAliveSocket());
    }
}
