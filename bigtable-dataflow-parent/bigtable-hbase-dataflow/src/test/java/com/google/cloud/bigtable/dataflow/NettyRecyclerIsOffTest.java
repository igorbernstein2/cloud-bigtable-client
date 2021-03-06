/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.bigtable.dataflow;

import java.io.IOException;
import java.lang.reflect.Field;

import org.junit.Assert;
import org.junit.Test;

import com.google.bigtable.repackaged.com.google.cloud.grpc.BigtableSession;
import com.google.bigtable.repackaged.io.netty.util.Recycler;

/**
 * There are 2 rounds of shading done on cloud-bigtable-client classes before the dataflow project
 * gets a Jar. Make sure that Netty Recycler is turned off even after those 2 rounds of shading.
 *
 * @author sduskis
 */
public class NettyRecyclerIsOffTest {

  @Test
  public void testRecyclerIsOff() throws NoSuchFieldException, SecurityException,
      IllegalArgumentException, IllegalAccessException, IOException {
    // Make sure BigtableSession does its setup.  This call is just to make sure that the BigtableSession's static
    // code snippets are invoked.
    BigtableSession.isAlpnProviderEnabled();
    Field field = Recycler.class.getDeclaredField("DEFAULT_MAX_CAPACITY_PER_THREAD");
    field.setAccessible(true);
    Assert.assertEquals(0, field.getInt(null));
  }
}
