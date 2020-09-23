// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.http.v2;

import com.yahoo.cloud.config.ConfigserverConfig;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.ApplicationName;
import com.yahoo.config.provision.InstanceName;
import com.yahoo.config.provision.TenantName;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.jdisc.Response;
import com.yahoo.jdisc.http.HttpRequest;
import com.yahoo.text.Utf8;
import com.yahoo.vespa.config.server.ApplicationRepository;
import com.yahoo.vespa.config.server.TestComponentRegistry;
import com.yahoo.vespa.config.server.application.OrchestratorMock;
import com.yahoo.vespa.config.server.http.ContentHandlerTestBase;
import com.yahoo.vespa.config.server.http.SessionHandlerTest;
import com.yahoo.vespa.config.server.session.PrepareParams;
import com.yahoo.vespa.config.server.tenant.Tenant;
import com.yahoo.vespa.config.server.tenant.TenantRepository;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

/**
 * @author Ulf Lilleengen
 */
public class SessionContentHandlerTest extends ContentHandlerTestBase {
    private static final TenantName tenantName = TenantName.from("contenttest");
    private static final File testApp = new File("src/test/apps/content");

    private TestComponentRegistry componentRegistry;
    private TenantRepository tenantRepository;
    private SessionContentHandler handler = null;
    private long sessionId;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setupHandler() throws IOException {
        ConfigserverConfig configserverConfig = new ConfigserverConfig.Builder()
                .configServerDBDir(temporaryFolder.newFolder("serverdb").getAbsolutePath())
                .configDefinitionsDir(temporaryFolder.newFolder("configdefinitions").getAbsolutePath())
                .fileReferencesDir(temporaryFolder.newFolder().getAbsolutePath())
                .build();
        componentRegistry = new TestComponentRegistry.Builder()
                .configServerConfig(configserverConfig)
                .build();

        tenantRepository = new TenantRepository(componentRegistry);
        tenantRepository.addTenant(tenantName);

        ApplicationRepository applicationRepository = new ApplicationRepository.Builder()
                .withTenantRepository(tenantRepository)
                .withProvisioner(new SessionHandlerTest.MockProvisioner())
                .withOrchestrator(new OrchestratorMock())
                .withConfigserverConfig(configserverConfig)
                .build();
        applicationRepository.deploy(testApp, new PrepareParams.Builder().applicationId(applicationId()).build());
        Tenant tenant = applicationRepository.getTenant(applicationId());
        sessionId = applicationRepository.getActiveLocalSession(tenant, applicationId()).getSessionId();

        handler = createHandler();
        pathPrefix = "/application/v2/tenant/" + tenantName + "/session/";
        baseUrl = "http://foo:1337/application/v2/tenant/" + tenantName + "/session/" + sessionId + "/content/";
    }

    @Test
    public void require_that_directories_can_be_created() throws IOException {
        assertMkdir("/bar/");
        assertMkdir("/bar/brask/");
        assertMkdir("/bar/brask/");
        assertMkdir("/bar/brask/bram/");
        assertMkdir("/brask/og/bram/");
    }

    @Test
    @Ignore
    // TODO: Enable when we have a predictable way of checking request body existence.
    public void require_that_mkdir_with_body_is_illegal(){
        HttpResponse response = put("/foobio/", "foo");
        assertNotNull(response);
        assertThat(response.getStatus(), is(Response.Status.BAD_REQUEST));
    }

    @Test
    public void require_that_nonexistent_session_returns_not_found() {
        HttpResponse response = doRequest(HttpRequest.Method.GET, "/test.txt", 9999);
        assertNotNull(response);
        assertThat(response.getStatus(), is(Response.Status.NOT_FOUND));
    }

    protected HttpResponse put(String path, String content) {
        ByteArrayInputStream data = new ByteArrayInputStream(Utf8.toBytes(content));
        return doRequest(HttpRequest.Method.PUT, path, sessionId, data);
    }

    @Test
    public void require_that_file_write_without_body_is_illegal() {
        HttpResponse response = doRequest(HttpRequest.Method.PUT, "/foobio.txt");
        assertNotNull(response);
        assertThat(response.getStatus(), is(Response.Status.BAD_REQUEST));
    }

    @Test
    public void require_that_files_can_be_written() throws IOException {
        assertWriteFile("/foo/minfil.txt", "Mycontent");
        assertWriteFile("/foo/minfil.txt", "Differentcontent");
    }

    @Test
    public void require_that_nonexistent_file_returns_not_found_when_deleted() throws IOException {
        assertDeleteFile(Response.Status.NOT_FOUND, "/test2.txt", "{\"error-code\":\"NOT_FOUND\",\"message\":\"Session " + sessionId + " does not contain a file 'test2.txt'\"}");
    }

    @Test
    public void require_that_files_can_be_deleted() throws IOException {
        assertDeleteFile(Response.Status.OK, "/test.txt");
        assertDeleteFile(Response.Status.NOT_FOUND, "/test.txt", "{\"error-code\":\"NOT_FOUND\",\"message\":\"Session "  + sessionId + " does not contain a file 'test.txt'\"}");
        assertDeleteFile(Response.Status.BAD_REQUEST, "/newtest", "{\"error-code\":\"BAD_REQUEST\",\"message\":\"File 'newtest' is not an empty directory\"}");
        assertDeleteFile(Response.Status.OK, "/newtest/testfile.txt");
        assertDeleteFile(Response.Status.OK, "/newtest");
    }

    @Test
    public void require_that_status_is_given_for_new_files() throws IOException {
        assertStatus("/test.txt?return=status",
                     "{\"status\":\"new\",\"md5\":\"d3b07384d113edec49eaa6238ad5ff00\",\"name\":\"http://foo:1337" + pathPrefix + sessionId + "/content/test.txt\"}");
        assertWriteFile("/test.txt", "Mycontent");
        assertStatus("/test.txt?return=status",
                     "{\"status\":\"changed\",\"md5\":\"01eabd73c69d78d0009ec93cd62d7f77\",\"name\":\"http://foo:1337" + pathPrefix + sessionId + "/content/test.txt\"}");
    }

    private void assertWriteFile(String path, String content) throws IOException {
        HttpResponse response = put(path, content);
        assertNotNull(response);
        assertThat(response.getStatus(), is(Response.Status.OK));
        assertContent(path, content);
        assertThat(SessionHandlerTest.getRenderedString(response),
                   is("{\"prepared\":\"http://foo:1337" + pathPrefix + sessionId + "/prepared\"}"));
    }

    private void assertDeleteFile(int statusCode, String filePath) throws IOException {
        assertDeleteFile(statusCode, filePath, "{\"prepared\":\"http://foo:1337" + pathPrefix + sessionId +  "/prepared\"}");
    }

    private void assertDeleteFile(int statusCode, String filePath, String expectedResponse) throws IOException {
        HttpResponse response = doRequest(HttpRequest.Method.DELETE, filePath);
        assertNotNull(response);
        assertThat(response.getStatus(), is(statusCode));
        assertThat(SessionHandlerTest.getRenderedString(response), is(expectedResponse));
    }

    private void assertMkdir(String path) throws IOException {
        HttpResponse response = doRequest(HttpRequest.Method.PUT, path);
        assertNotNull(response);
        assertThat(response.getStatus(), is(Response.Status.OK));
        assertThat(SessionHandlerTest.getRenderedString(response),
                   is("{\"prepared\":\"http://foo:1337" + pathPrefix + sessionId + "/prepared\"}"));
    }

    protected HttpResponse doRequest(HttpRequest.Method method, String path) {
        return doRequest(method, path, sessionId);
    }

    private HttpResponse doRequest(HttpRequest.Method method, String path, long sessionId) {
        return handler.handle(SessionHandlerTest.createTestRequest(pathPrefix, method, Cmd.CONTENT, sessionId, path));
    }

    private HttpResponse doRequest(HttpRequest.Method method, String path, long sessionId, InputStream data) {
        return handler.handle(SessionHandlerTest.createTestRequest(pathPrefix, method, Cmd.CONTENT, sessionId, path, data));
    }

    private SessionContentHandler createHandler() {
        return new SessionContentHandler(
                SessionContentHandler.testOnlyContext(),
                new ApplicationRepository.Builder()
                        .withTenantRepository(tenantRepository)
                        .withProvisioner(new SessionHandlerTest.MockProvisioner())
                        .withOrchestrator(new OrchestratorMock())
                        .withClock(componentRegistry.getClock())
                        .build()
        );
    }

    private ApplicationId applicationId() {
        return ApplicationId.from(tenantName, ApplicationName.defaultName(), InstanceName.defaultName());
    }

}
