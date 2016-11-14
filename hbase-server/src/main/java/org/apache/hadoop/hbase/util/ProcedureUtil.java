/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.procedure.MasterProcedureManager;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.NameStringPair;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos.ProcedureDescription;
import org.apache.hadoop.hbase.snapshot.SnapshotDescriptionUtils;

@InterfaceAudience.Private
@InterfaceStability.Evolving
public final class ProcedureUtil {
  private static final Log LOG = LogFactory.getLog(ProcedureUtil.class);

  public static ProcedureDescription buildProcedure(String signature, String instance,
      Map<String, String> props) {
    ProcedureDescription.Builder builder = ProcedureDescription.newBuilder();
    builder.setSignature(signature).setInstance(instance);
    for (Entry<String, String> entry : props.entrySet()) {
      NameStringPair pair = NameStringPair.newBuilder().setName(entry.getKey())
          .setValue(entry.getValue()).build();
      builder.addConfiguration(pair);
    }
    ProcedureDescription desc = builder.build();
    return desc;
  }

  public static long execProcedure(MasterProcedureManager mpm, String signature, String instance,
      Map<String, String> props) throws IOException {
    if (mpm == null) {
      throw new IOException("The procedure is not registered: " + signature);
    }
    ProcedureDescription desc = buildProcedure(signature, instance, props);
    mpm.execProcedure(desc);

    // send back the max amount of time the client should wait for the procedure
    // to complete
    long waitTime = SnapshotDescriptionUtils.DEFAULT_MAX_WAIT_TIME;
    return waitTime;
  }

  public static void waitForProcedure(MasterProcedureManager mpm, String signature, String instance,
      Map<String, String> props, long max, int numRetries, long pause) throws IOException {
    ProcedureDescription desc = buildProcedure(signature, instance, props);
    long start = EnvironmentEdgeManager.currentTime();
    long maxPauseTime = max / numRetries;
    int tries = 0;
    LOG.debug("Waiting a max of " + max + " ms for procedure '" +
        signature + " : " + instance + "'' to complete. (max " + maxPauseTime + " ms per retry)");
    boolean done = false;
    while (tries == 0
        || ((EnvironmentEdgeManager.currentTime() - start) < max && !done)) {
      try {
        // sleep a backoff <= pauseTime amount
        long sleep = getPauseTime(tries++, pause);
        sleep = sleep > maxPauseTime ? maxPauseTime : sleep;
        LOG.debug("(#" + tries + ") Sleeping: " + sleep +
          "ms while waiting for procedure completion.");
        Thread.sleep(sleep);
      } catch (InterruptedException e) {
        throw (InterruptedIOException) new InterruptedIOException("Interrupted").initCause(e);
      }
      LOG.debug("Getting current status of procedure from master...");
      done = mpm.isProcedureDone(desc);
    }
    if (!done) {
      throw new IOException("Procedure '" + signature + " : " + instance
          + "' wasn't completed in expectedTime:" + max + " ms");
    }
  }

  private static long getPauseTime(int tries, long pause) {
    int triesCount = tries;
    if (triesCount >= HConstants.RETRY_BACKOFF.length) {
      triesCount = HConstants.RETRY_BACKOFF.length - 1;
    }
    return pause * HConstants.RETRY_BACKOFF[triesCount];
  }
}
