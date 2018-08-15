package com.yahoo.vespa.hosted.controller.persistence;

import com.google.common.collect.ImmutableMap;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.vespa.config.SlimeUtils;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.JobType;
import com.yahoo.vespa.hosted.controller.api.integration.deployment.RunId;
import com.yahoo.vespa.hosted.controller.deployment.Run;
import com.yahoo.vespa.hosted.controller.deployment.RunStatus;
import com.yahoo.vespa.hosted.controller.deployment.Step;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;

import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.aborted;
import static com.yahoo.vespa.hosted.controller.deployment.RunStatus.running;
import static com.yahoo.vespa.hosted.controller.deployment.Step.Status.failed;
import static com.yahoo.vespa.hosted.controller.deployment.Step.Status.succeeded;
import static com.yahoo.vespa.hosted.controller.deployment.Step.Status.unfinished;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deactivateReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deactivateTester;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployInitialReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.deployTester;
import static com.yahoo.vespa.hosted.controller.deployment.Step.installInitialReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.installReal;
import static com.yahoo.vespa.hosted.controller.deployment.Step.installTester;
import static com.yahoo.vespa.hosted.controller.deployment.Step.report;
import static com.yahoo.vespa.hosted.controller.deployment.Step.startTests;
import static com.yahoo.vespa.hosted.controller.deployment.Step.endTests;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunSerializerTest {

    private static final RunSerializer serializer = new RunSerializer();
    private static final Path runFile = Paths.get("src/test/java/com/yahoo/vespa/hosted/controller/persistence/testdata/run-status.json");
    private static final RunId id = new RunId(ApplicationId.from("tenant", "application", "default"),
                                               JobType.productionUsEast3,
                                               (long) 112358);
    private static final Instant start = Instant.parse("2007-12-03T10:15:30.00Z");

    @Test
    public void testSerialization() throws IOException {
        for (Step step : Step.values())
            assertEquals(step, RunSerializer.stepOf(RunSerializer.valueOf(step)));

        for (Step.Status status : Step.Status.values())
            assertEquals(status, RunSerializer.stepStatusOf(RunSerializer.valueOf(status)));

        for (RunStatus status : RunStatus.values())
            assertEquals(status, RunSerializer.runStatusOf(RunSerializer.valueOf(status)));

        // The purpose of this serialised data is to ensure a new format does not break everything, so keep it up to date!
        Run run = serializer.runsFromSlime(SlimeUtils.jsonToSlime(Files.readAllBytes(runFile))).get(id);
        for (Step step : Step.values())
            assertTrue(run.steps().containsKey(step));

        assertEquals(id, run.id());
        assertEquals(start, run.start());
        assertFalse(run.hasEnded());
        assertEquals(running, run.status());
        assertEquals(ImmutableMap.<Step, Step.Status>builder()
                             .put(deployInitialReal, unfinished)
                             .put(installInitialReal, failed)
                             .put(deployReal, succeeded)
                             .put(installReal, unfinished)
                             .put(deactivateReal, failed)
                             .put(deployTester, succeeded)
                             .put(installTester, unfinished)
                             .put(deactivateTester, failed)
                             .put(startTests, succeeded)
                             .put(endTests, unfinished)
                             .put(report, failed)
                             .build(),
                     run.steps());

        run = run.aborted().finished(Instant.now());
        assertEquals(aborted, run.status());
        assertTrue(run.hasEnded());

        Run phoenix = serializer.runsFromSlime(serializer.toSlime(Collections.singleton(run))).get(id);
        assertEquals(run.id(), phoenix.id());
        assertEquals(run.start(), phoenix.start());
        assertEquals(run.end(), phoenix.end());
        assertEquals(run.status(), phoenix.status());
        assertEquals(run.steps(), phoenix.steps());
    }

}
