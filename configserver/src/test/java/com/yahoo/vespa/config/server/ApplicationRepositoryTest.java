// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.model.api.ApplicationRoles;
import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.Deployment;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.NetworkPorts;
import com.yahoo.config.provision.Provisioner;
import com.yahoo.config.provision.TenantName;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.docproc.jdisc.metric.NullMetric;
import com.yahoo.io.IOUtils;
import com.yahoo.jdisc.Metric;
import com.yahoo.test.ManualClock;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.server.application.OrchestratorMock;
import com.yahoo.vespa.config.server.deploy.DeployTester;
import com.yahoo.vespa.config.server.http.InternalServerException;
import com.yahoo.vespa.config.server.http.SessionHandlerTest;
import com.yahoo.vespa.config.server.http.v2.PrepareResult;
import com.yahoo.vespa.config.server.session.LocalSession;
import com.yahoo.vespa.config.server.session.LocalSessionRepo;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.session.RemoteSession;
import com.yahoo.vespa.config.server.session.SilentDeployLogger;
import com.yahoo.vespa.config.server.tenant.ApplicationRolesStore;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import com.yahoo.vespa.curator.Curator;
import com.yahoo.vespa.curator.mock.MockCurator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author hmusum
 */
public class ApplicationRepositoryTest {

    private final static File testApp = new File("src/test/apps/app");
    private final static File testAppJdiscOnly = new File("src/test/apps/app-jdisc-only");
    private final static File testAppJdiscOnlyRestart = new File("src/test/apps/app-jdisc-only-restart");
    private final static File testAppLogServerWithContainer = new File("src/test/apps/app-logserver-with-container");

    private final static TenantName tenant1 = TenantName.from("test1");
    private final static TenantName tenant2 = TenantName.from("test2");
    private final static TenantName tenant3 = TenantName.from("test3");
    private final static Clock clock = Clock.systemUTC();

    private ApplicationRepository applicationRepository;
    private TenantRepository tenantRepository;
    private SessionHandlerTest.MockProvisioner  provisioner;
    private OrchestratorMock orchestrator;
    private TimeoutBudget timeoutBudget;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setup() {
        Curator curator = new MockCurator();
        tenantRepository = new TenantRepository(new TestComponentRegistry.Builder()
                                                                .curator(curator)
                                                                .build());
        tenantRepository.addTenant(TenantRepository.HOSTED_VESPA_TENANT);
        tenantRepository.addTenant(tenant1);
        tenantRepository.addTenant(tenant2);
        tenantRepository.addTenant(tenant3);
        orchestrator = new OrchestratorMock();
        provisioner = new SessionHandlerTest.MockProvisioner();
        applicationRepository = new ApplicationRepository(tenantRepository,
                                                          provisioner,
                                                          orchestrator,
                                                          clock);
        timeoutBudget = new TimeoutBudget(clock, Duration.ofSeconds(60));
    }

    @Test
    public void prepareAndActivate() {
        PrepareResult result = prepareAndActivate(testApp);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertTrue(result.configChangeActions().getRestartActions().isEmpty());

        TenantName tenantName = applicationId().tenant();
        Tenant tenant = tenantRepository.getTenant(tenantName);
        LocalSession session = tenant.getLocalSessionRepo().getSession(tenant.getApplicationRepo()
                                                                               .requireActiveSessionOf(applicationId()));
        session.getAllocatedHosts();
    }

    @Test
    public void prepareAndActivateWithRestart() {
        prepareAndActivate(testAppJdiscOnly);
        PrepareResult result = prepareAndActivate(testAppJdiscOnlyRestart);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertFalse(result.configChangeActions().getRestartActions().isEmpty());
    }

    @Test
    public void createAndPrepareAndActivate() {
        PrepareResult result = deployApp(testApp);
        assertTrue(result.configChangeActions().getRefeedActions().isEmpty());
        assertTrue(result.configChangeActions().getRestartActions().isEmpty());
    }

    @Test
    public void createFromActiveSession() {
        PrepareResult result = deployApp(testApp);
        long sessionId = applicationRepository.createSessionFromExisting(applicationId(),
                                                                         new SilentDeployLogger(),
                                                                         false,
                                                                         timeoutBudget);
        long originalSessionId = result.sessionId();
        ApplicationMetaData originalApplicationMetaData = getApplicationMetaData(applicationId(), originalSessionId);
        ApplicationMetaData applicationMetaData = getApplicationMetaData(applicationId(), sessionId);

        assertNotEquals(sessionId, originalSessionId);
        assertEquals(originalApplicationMetaData.getApplicationId(), applicationMetaData.getApplicationId());
        assertEquals(originalApplicationMetaData.getGeneration().longValue(), applicationMetaData.getPreviousActiveGeneration());
        assertNotEquals(originalApplicationMetaData.getGeneration(), applicationMetaData.getGeneration());
        assertEquals(originalApplicationMetaData.getDeployedByUser(), applicationMetaData.getDeployedByUser());
    }

    @Test
    public void testSuspension() {
        deployApp(testApp);
        assertFalse(applicationRepository.isSuspended(applicationId()));
        orchestrator.suspend(applicationId());
        assertTrue(applicationRepository.isSuspended(applicationId()));
    }

    @Test
    public void getLogs() {
        applicationRepository = createApplicationRepository();
        deployApp(testAppLogServerWithContainer);
        HttpResponse response = applicationRepository.getLogs(applicationId(), Optional.empty(), "");
        assertEquals(200, response.getStatus());
    }

    @Test
    public void getLogsForHostname() {
        applicationRepository = createApplicationRepository();
        ApplicationId applicationId = ApplicationId.from("hosted-vespa", "tenant-host", "default");
        deployApp(testAppLogServerWithContainer, new PrepareParams.Builder().applicationId(applicationId).build());
        HttpResponse response = applicationRepository.getLogs(applicationId, Optional.of("localhost"), "");
        assertEquals(200, response.getStatus());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refuseToGetLogsFromHostnameNotInApplication() {
        applicationRepository = createApplicationRepository();
        deployApp(testAppLogServerWithContainer);
        HttpResponse response = applicationRepository.getLogs(applicationId(), Optional.of("host123.fake.yahoo.com"), "");
        assertEquals(200, response.getStatus());
    }

    @Test
    public void deleteUnusedTenants() {
        // Set clock to epoch plus hour, as mock curator will always return epoch as creation time
        Instant now = ManualClock.at("1970-01-01T01:00:00");

        // 3 tenants exist, tenant1 and tenant2 has applications deployed:
        deployApp(testApp);
        deployApp(testApp, new PrepareParams.Builder().applicationId(applicationId(tenant2)).build());

        // Should not be deleted, not old enough
        Duration ttlForUnusedTenant = Duration.ofHours(1);
        assertTrue(applicationRepository.deleteUnusedTenants(ttlForUnusedTenant, now).isEmpty());
        // Should be deleted
        ttlForUnusedTenant = Duration.ofMillis(1);
        assertEquals(tenant3, applicationRepository.deleteUnusedTenants(ttlForUnusedTenant, now).iterator().next());

        // Delete app used by tenant1, tenant2 still has an application
        applicationRepository.delete(applicationId());
        Set<TenantName> tenantsDeleted = applicationRepository.deleteUnusedTenants(Duration.ofMillis(1), now);
        assertTrue(tenantsDeleted.contains(tenant1));
        assertFalse(tenantsDeleted.contains(tenant2));
    }

    @Test
    public void deleteUnusedFileReferences() throws IOException {
        File fileReferencesDir = temporaryFolder.newFolder();

        // Add file reference that is not in use and should be deleted (older than 14 days)
        File filereferenceDir = createFilereferenceOnDisk(new File(fileReferencesDir, "foo"), Instant.now().minus(Duration.ofDays(15)));
        // Add file reference that is not in use, but should not be deleted (not older than 14 days)
        File filereferenceDir2 = createFilereferenceOnDisk(new File(fileReferencesDir, "baz"), Instant.now());

        tenantRepository.addTenant(tenant1);
        Provisioner provisioner = new SessionHandlerTest.MockProvisioner();
        applicationRepository = new ApplicationRepository(tenantRepository, provisioner, orchestrator, clock);
        timeoutBudget = new TimeoutBudget(clock, Duration.ofSeconds(60));

        // TODO: Deploy an app with a bundle or file that will be a file reference, too much missing in test setup to get this working now
        PrepareParams prepareParams = new PrepareParams.Builder().applicationId(applicationId()).ignoreValidationErrors(true).build();
        deployApp(new File("src/test/apps/app"), prepareParams);

        Set<String> toBeDeleted = applicationRepository.deleteUnusedFiledistributionReferences(fileReferencesDir, Duration.ofHours(48));
        assertEquals(Collections.singleton("foo"), toBeDeleted);
        assertFalse(filereferenceDir.exists());
        assertTrue(filereferenceDir2.exists());
    }

    private File createFilereferenceOnDisk(File filereferenceDir, Instant lastModifiedTime) {
        assertTrue(filereferenceDir.mkdir());
        File bar = new File(filereferenceDir, "file");
        IOUtils.writeFile(bar, Utf8.toBytes("test"));
        assertTrue(filereferenceDir.setLastModified(lastModifiedTime.toEpochMilli()));
        return filereferenceDir;
    }

    @Test
    public void delete() {
        {
            PrepareResult result = deployApp(testApp);
            long sessionId = result.sessionId();
            Tenant tenant = tenantRepository.getTenant(applicationId().tenant());
            LocalSession applicationData = tenant.getLocalSessionRepo().getSession(sessionId);
            assertNotNull(applicationData);
            assertNotNull(applicationData.getApplicationId());
            assertNotNull(tenant.getRemoteSessionRepo().getSession(sessionId));
            assertNotNull(applicationRepository.getActiveSession(applicationId()));

            // Delete app and verify that it has been deleted from repos and provisioner
            assertTrue(applicationRepository.delete(applicationId()));
            assertNull(applicationRepository.getActiveSession(applicationId()));
            assertNull(tenant.getLocalSessionRepo().getSession(sessionId));
            assertNull(tenant.getRemoteSessionRepo().getSession(sessionId));
            assertTrue(provisioner.removed);
            assertEquals(tenant.getName(), provisioner.lastApplicationId.tenant());
            assertEquals(applicationId(), provisioner.lastApplicationId);

            assertFalse(applicationRepository.delete(applicationId()));
        }

        {
            deployApp(testApp);
            assertTrue(applicationRepository.delete(applicationId()));
            deployApp(testApp);

            // Deploy another app (with id fooId)
            ApplicationId fooId = applicationId(tenant2);
            PrepareParams prepareParams2 = new PrepareParams.Builder().applicationId(fooId).build();
            deployApp(testApp, prepareParams2);
            assertNotNull(applicationRepository.getActiveSession(fooId));

            // Delete app with id fooId, should not affect original app
            assertTrue(applicationRepository.delete(fooId));
            assertEquals(fooId, provisioner.lastApplicationId);
            assertNotNull(applicationRepository.getActiveSession(applicationId()));

            assertTrue(applicationRepository.delete(applicationId()));
        }

        {
            PrepareResult prepareResult = deployApp(testApp);
            try {
                applicationRepository.delete(applicationId(), Duration.ZERO);
                fail("Should have gotten an exception");
            } catch (InternalServerException e) {
                assertEquals("test1.testapp was not deleted (waited PT0S), session " + prepareResult.sessionId(), e.getMessage());
            }

            // No active session or remote session (deleted in step above), but an exception was thrown above
            // A new delete should cleanup and be successful
            RemoteSession activeSession = applicationRepository.getActiveSession(applicationId());
            assertNull(activeSession);
            Tenant tenant = tenantRepository.getTenant(applicationId().tenant());
            assertNull(tenant.getRemoteSessionRepo().getSession(prepareResult.sessionId()));

            assertTrue(applicationRepository.delete(applicationId()));
        }
    }

    @Test
    public void testDeletingInactiveSessions() throws IOException {
        ManualClock clock = new ManualClock(Instant.now());
        ConfigserverConfig configserverConfig =
                new ConfigserverConfig(new ConfigserverConfig.Builder()
                                               .configServerDBDir(temporaryFolder.newFolder("serverdb").getAbsolutePath())
                                               .configDefinitionsDir(temporaryFolder.newFolder("configdefinitions").getAbsolutePath())
                                               .sessionLifetime(60));
        DeployTester tester = new DeployTester(configserverConfig, clock);
        tester.deployApp("src/test/apps/app", clock.instant()); // session 2 (numbering starts at 2)

        clock.advance(Duration.ofSeconds(10));
        Optional<Deployment> deployment2 = tester.redeployFromLocalActive();

        assertTrue(deployment2.isPresent());
        deployment2.get().activate(); // session 3
        long activeSessionId = tester.tenant().getApplicationRepo().requireActiveSessionOf(tester.applicationId());

        clock.advance(Duration.ofSeconds(10));
        Optional<com.yahoo.config.provision.Deployment> deployment3 = tester.redeployFromLocalActive();
        assertTrue(deployment3.isPresent());
        deployment3.get().prepare();  // session 4 (not activated)

        LocalSession deployment3session = ((com.yahoo.vespa.config.server.deploy.Deployment) deployment3.get()).session();
        assertNotEquals(activeSessionId, deployment3session);
        // No change to active session id
        assertEquals(activeSessionId, tester.tenant().getApplicationRepo().requireActiveSessionOf(tester.applicationId()));
        LocalSessionRepo localSessionRepo = tester.tenant().getLocalSessionRepo();
        assertEquals(3, localSessionRepo.getSessions().size());

        clock.advance(Duration.ofHours(1)); // longer than session lifetime

        // All sessions except 3 should be removed after the call to deleteExpiredLocalSessions
        tester.applicationRepository().deleteExpiredLocalSessions();
        Collection<LocalSession> sessions = localSessionRepo.getSessions();
        assertEquals(1, sessions.size());
        ArrayList<LocalSession> localSessions = new ArrayList<>(sessions);
        LocalSession localSession = localSessions.get(0);
        assertEquals(3, localSession.getSessionId());

        // There should be no expired remote sessions in the common case
        assertEquals(0, tester.applicationRepository().deleteExpiredRemoteSessions(clock, Duration.ofSeconds(0)));

        // Deploy, but do not activate
        Optional<com.yahoo.config.provision.Deployment> deployment4 = tester.redeployFromLocalActive();
        assertTrue(deployment4.isPresent());
        deployment4.get().prepare();  // session 5 (not activated)

        assertEquals(2, localSessionRepo.getSessions().size());
        localSessionRepo.deleteSession(localSession);
        assertEquals(1, localSessionRepo.getSessions().size());

        // Check that trying to expire when there are no active sessions works
        tester.applicationRepository().deleteExpiredLocalSessions();
    }

    @Test
    public void testMetrics() {
        MockMetric actual = new MockMetric();
        applicationRepository = new ApplicationRepository(tenantRepository,
                                                          provisioner,
                                                          orchestrator,
                                                          new ConfigserverConfig(new ConfigserverConfig.Builder()),
                                                          new MockLogRetriever(),
                                                          new ManualClock(),
                                                          new MockTesterClient(),
                                                          actual);
        deployApp(testAppLogServerWithContainer);
        Map<String, ?> context = Map.of("applicationId", "test1.testapp.default",
                                        "tenantName", "test1",
                                        "app", "testapp.default",
                                        "zone", "prod.default");
        MockMetric expected = new MockMetric();
        expected.set("deployment.prepareMillis", 0L, expected.createContext(context));
        expected.set("deployment.activateMillis", 0L, expected.createContext(context));
        assertEquals(expected.values, actual.values);
    }

    @Test
    public void deletesApplicationRoles() {
        var tenant = tenantRepository.getTenant(tenant1);
        var applicationId = applicationId(tenant1);
        var prepareParams = new PrepareParams.Builder().applicationId(applicationId)
                .applicationRoles(ApplicationRoles.fromString("hostRole","containerRole")).build();
        deployApp(testApp, prepareParams);
        var approlesStore = new ApplicationRolesStore(tenant.getCurator(), tenant.getPath());
        var appRoles = approlesStore.readApplicationRoles(applicationId);

        // App roles present after deploy
        assertTrue(appRoles.isPresent());
        assertEquals("hostRole", appRoles.get().applicationHostRole());
        assertEquals("containerRole", appRoles.get().applicationContainerRole());

        assertTrue(applicationRepository.delete(applicationId));

        // App roles deleted on application delete
        assertTrue(approlesStore.readApplicationRoles(applicationId).isEmpty());
    }

    @Test
    public void require_that_provision_info_can_be_read() {
        prepareAndActivate(testAppJdiscOnly);

        TenantName tenantName = applicationId().tenant();
        Tenant tenant = tenantRepository.getTenant(tenantName);
        LocalSession session = tenant.getLocalSessionRepo().getSession(tenant.getApplicationRepo().requireActiveSessionOf(applicationId()));

        List<NetworkPorts.Allocation> list = new ArrayList<>();
        list.add(new NetworkPorts.Allocation(8080, "container", "container/container.0", "http"));
        list.add(new NetworkPorts.Allocation(19070, "configserver", "admin/configservers/configserver.0", "rpc"));
        list.add(new NetworkPorts.Allocation(19071, "configserver", "admin/configservers/configserver.0", "http"));
        list.add(new NetworkPorts.Allocation(19080, "logserver", "admin/logserver", "rpc"));
        list.add(new NetworkPorts.Allocation(19081, "logserver", "admin/logserver", "unused/1"));
        list.add(new NetworkPorts.Allocation(19082, "logserver", "admin/logserver", "unused/2"));
        list.add(new NetworkPorts.Allocation(19083, "logserver", "admin/logserver", "unused/3"));
        list.add(new NetworkPorts.Allocation(19089, "logd", "hosts/mytesthost/logd", "http"));
        list.add(new NetworkPorts.Allocation(19090, "configproxy", "hosts/mytesthost/configproxy", "rpc"));
        list.add(new NetworkPorts.Allocation(19092, "metricsproxy-container", "admin/metrics/mytesthost", "http"));
        list.add(new NetworkPorts.Allocation(19093, "metricsproxy-container", "admin/metrics/mytesthost", "http/1"));
        list.add(new NetworkPorts.Allocation(19094, "metricsproxy-container", "admin/metrics/mytesthost", "rpc/admin"));
        list.add(new NetworkPorts.Allocation(19095, "metricsproxy-container", "admin/metrics/mytesthost", "rpc/metrics"));
        list.add(new NetworkPorts.Allocation(19097, "config-sentinel", "hosts/mytesthost/sentinel", "rpc"));
        list.add(new NetworkPorts.Allocation(19098, "config-sentinel", "hosts/mytesthost/sentinel", "http"));
        list.add(new NetworkPorts.Allocation(19099, "slobrok", "admin/slobrok.0", "rpc"));
        list.add(new NetworkPorts.Allocation(19100, "container", "container/container.0", "http/1"));
        list.add(new NetworkPorts.Allocation(19101, "container", "container/container.0", "messaging"));
        list.add(new NetworkPorts.Allocation(19102, "container", "container/container.0", "rpc/admin"));
        list.add(new NetworkPorts.Allocation(19103, "slobrok", "admin/slobrok.0", "http"));

        AllocatedHosts info = session.getAllocatedHosts();
        assertNotNull(info);
        assertThat(info.getHosts().size(), is(1));
        assertTrue(info.getHosts().contains(new HostSpec("mytesthost",
                                                         Collections.emptyList(),
                                                         Optional.empty())));
        Optional<NetworkPorts> portsCopy = info.getHosts().iterator().next().networkPorts();
        assertTrue(portsCopy.isPresent());
        assertThat(portsCopy.get().allocations(), is(list));
    }

    private ApplicationRepository createApplicationRepository() {
        return new ApplicationRepository(tenantRepository,
                                         provisioner,
                                         orchestrator,
                                         new ConfigserverConfig(new ConfigserverConfig.Builder()),
                                         new MockLogRetriever(),
                                         clock,
                                         new MockTesterClient(),
                                         new NullMetric());
    }

    private PrepareResult prepareAndActivate(File application) {
        return applicationRepository.deploy(application, prepareParams(), false, Instant.now());
    }

    private PrepareResult deployApp(File applicationPackage) {
        return deployApp(applicationPackage, prepareParams());
    }

    private PrepareResult deployApp(File applicationPackage, PrepareParams prepareParams) {
        return applicationRepository.deploy(applicationPackage, prepareParams);
    }

    private PrepareParams prepareParams() {
        return new PrepareParams.Builder().applicationId(applicationId()).build();
    }

    private ApplicationId applicationId() {
        return ApplicationId.from(tenant1, ApplicationName.from("testapp"), InstanceName.defaultName());
    }

    private ApplicationId applicationId(TenantName tenantName) {
        return ApplicationId.from(tenantName, ApplicationName.from("testapp"), InstanceName.defaultName());
    }

    private ApplicationMetaData getApplicationMetaData(ApplicationId applicationId, long sessionId) {
        Tenant tenant = tenantRepository.getTenant(applicationId.tenant());
        return applicationRepository.getMetadataFromLocalSession(tenant, sessionId);
    }


    /** Stores all added or set values for each metric and context. */
    static class MockMetric implements Metric {

        final Map<String, Map<Map<String, ?>, Number>> values = new HashMap<>();

        @Override
        public void set(String key, Number val, Metric.Context ctx) {
            values.putIfAbsent(key, new HashMap<>());
            values.get(key).put(((Context) ctx).point, val);
        }

        @Override
        public void add(String key, Number val, Metric.Context ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Context createContext(Map<String, ?> properties) {
            return new Context(properties);
        }


        private static class Context implements Metric.Context {

            private final Map<String, ?> point;

            public Context(Map<String, ?> point) {
                this.point = Map.copyOf(point);
            }

        }

    }

}
