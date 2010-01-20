/**
 *
 * Copyright (C) 2009 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.vcloud.hostingdotcom.compute;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.jclouds.compute.domain.OperatingSystem;
import org.jclouds.logging.log4j.config.Log4JLoggingModule;
import org.jclouds.ssh.jsch.config.JschSshClientModule;
import org.jclouds.vcloud.domain.ResourceType;
import org.jclouds.vcloud.domain.VApp;
import org.jclouds.vcloud.domain.VAppStatus;
import org.jclouds.vcloud.hostingdotcom.HostingDotComVCloudClient;
import org.jclouds.vcloud.hostingdotcom.HostingDotComVCloudContextBuilder;
import org.jclouds.vcloud.hostingdotcom.HostingDotComVCloudPropertiesBuilder;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.Test;

import com.google.common.base.CaseFormat;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.internal.ImmutableMap;

/**
 * Tests behavior of {@code HostingDotComVCloudClient}
 * 
 * @author Adrian Cole
 */
@Test(groups = "live", enabled = true, sequential = true, testName = "vcloud.HostingDotComVCloudClientLiveTest")
public class HostingDotComVCloudComputeClientLiveTest {
   HostingDotComVCloudComputeClient client;
   HostingDotComVCloudClient hostingClient;

   private String id;
   private InetAddress privateAddress;

   public static final String PREFIX = System.getProperty("user.name") + "-terremark";

   private static class Expectation {
      final long hardDisk;
      final String os;

      public Expectation(long hardDisk, String os) {
         this.hardDisk = hardDisk;
         this.os = os;
      }
   }

   private Map<OperatingSystem, Expectation> expectationMap = ImmutableMap
            .<OperatingSystem, Expectation> builder().put(OperatingSystem.CENTOS,
                     new Expectation(4194304 / 2 * 10, "Red Hat Enterprise Linux 5 (64-bit)"))
            .build();

   private Predicate<InetAddress> addressTester;

   @Test(enabled = true)
   public void testPowerOn() throws InterruptedException, ExecutionException, TimeoutException,
            IOException {
      OperatingSystem toTest = OperatingSystem.CENTOS;

      String serverName = getCompatibleServerName(toTest);
      int processorCount = 1;
      int memory = 512;
      long disk = 10 * 1025 * 1024;
      Map<String, String> properties = ImmutableMap.of("foo", "bar");

      id = client.start(hostingClient.getDefaultVDC().getId(), serverName, "3", processorCount,
               memory, disk, properties).get("id");
      Expectation expectation = expectationMap.get(toTest);

      VApp vApp = hostingClient.getVApp(id);
      verifyConfigurationOfVApp(vApp, serverName, expectation.os, processorCount, memory,
               expectation.hardDisk);
      assertEquals(vApp.getStatus(), VAppStatus.ON);
   }

   private String getCompatibleServerName(OperatingSystem toTest) {
      String serverName = CaseFormat.UPPER_UNDERSCORE
               .to(CaseFormat.LOWER_HYPHEN, toTest.toString()).substring(0,
                        toTest.toString().length() <= 15 ? toTest.toString().length() : 14);
      return serverName;
   }

   @Test(dependsOnMethods = "testPowerOn", enabled = true)
   public void testGetAnyPrivateAddress() {
      privateAddress = client.getAnyPrivateAddress(id);
      assert !addressTester.apply(privateAddress);
   }

   private void verifyConfigurationOfVApp(VApp vApp, String serverName, String expectedOs,
            int processorCount, int memory, long hardDisk) {
      // assertEquals(vApp.getName(), serverName);
      // assertEquals(vApp.getOperatingSystemDescription(), expectedOs);
      assertEquals(
               Iterables.getOnlyElement(
                        vApp.getResourceAllocationByType().get(ResourceType.PROCESSOR))
                        .getVirtualQuantity(), processorCount);
      assertEquals(Iterables.getOnlyElement(
               vApp.getResourceAllocationByType().get(ResourceType.SCSI_CONTROLLER))
               .getVirtualQuantity(), 1);
      assertEquals(Iterables.getOnlyElement(
               vApp.getResourceAllocationByType().get(ResourceType.MEMORY)).getVirtualQuantity(),
               memory);
      assertEquals(Iterables.getOnlyElement(
               vApp.getResourceAllocationByType().get(ResourceType.DISK_DRIVE))
               .getVirtualQuantity(), hardDisk);
   }

   @AfterTest
   void cleanup() throws InterruptedException, ExecutionException, TimeoutException {
      if (id != null)
         client.stop(id);
   }

   @BeforeGroups(groups = { "live" })
   public void setupClient() {

      String account = checkNotNull(System.getProperty("jclouds.test.user"), "jclouds.test.user");
      String key = checkNotNull(System.getProperty("jclouds.test.key"), "jclouds.test.key");
      Injector injector = new HostingDotComVCloudContextBuilder(
               new HostingDotComVCloudPropertiesBuilder(account, key).build()).withModules(
               new Log4JLoggingModule(), new JschSshClientModule()).buildInjector();
      client = injector.getInstance(HostingDotComVCloudComputeClient.class);
      hostingClient = injector.getInstance(HostingDotComVCloudClient.class);
      addressTester = injector.getInstance(Key.get(new TypeLiteral<Predicate<InetAddress>>() {
      }));
   }

}
