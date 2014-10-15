package org.apache.lens.server.session;

/*
 * #%L
 * Grill Server
 * %%
 * Copyright (C) 2014 Inmobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hive.service.cli.CLIService;
import org.apache.lens.api.GrillSessionHandle;
import org.apache.lens.server.GrillServerConf;
import org.apache.lens.server.api.GrillConfConstants;
import org.apache.lens.server.session.GrillSessionImpl;
import org.apache.lens.server.session.HiveSessionService;
import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(groups="unit-test")
public class TestSessionExpiry {
  public void testSessionExpiry() throws Exception {
    HiveConf conf = GrillServerConf.get();
    conf.setVar(HiveConf.ConfVars.HIVE_SESSION_IMPL_CLASSNAME, GrillSessionImpl.class.getName());
    conf.setLong(GrillConfConstants.SESSION_TIMEOUT_SECONDS, 1L);
    CLIService cliService = new CLIService();
    cliService.init(conf);
    HiveSessionService lensService = new HiveSessionService(cliService);
    lensService.init(conf);
    lensService.start();

    try {
      GrillSessionHandle sessionHandle =
        lensService.openSession("foo", "bar", new HashMap<String, String>());
      GrillSessionImpl session = lensService.getSession(sessionHandle);
      assertTrue(session.isActive());
      session.setLastAccessTime(session.getLastAccessTime()
        - 2000 * conf.getLong(GrillConfConstants.SESSION_TIMEOUT_SECONDS,
        GrillConfConstants.SESSION_TIMEOUT_SECONDS_DEFAULT));
      assertFalse(session.isActive());

      // run the expiry thread
      lensService.getSessionExpiryRunnable().run();
      try {
        lensService.getSession(sessionHandle);
        // should throw exception since session should be expired by now
        fail("Expected get session to fail for session " + sessionHandle.getPublicId());
      } catch (Exception e) {
        // pass
      }
    } finally {
      lensService.stop();
    }
  }
}
