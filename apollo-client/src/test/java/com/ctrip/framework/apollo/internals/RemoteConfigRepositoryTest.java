package com.ctrip.framework.apollo.internals;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.SettableFuture;

import com.ctrip.framework.apollo.core.dto.ApolloConfig;
import com.ctrip.framework.apollo.core.dto.ApolloConfigNotification;
import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.exceptions.ApolloConfigException;
import com.ctrip.framework.apollo.util.ConfigUtil;
import com.ctrip.framework.apollo.util.http.HttpRequest;
import com.ctrip.framework.apollo.util.http.HttpResponse;
import com.ctrip.framework.apollo.util.http.HttpUtil;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.unidal.lookup.ComponentTestCase;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletResponse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by Jason on 4/9/16.
 */
@RunWith(MockitoJUnitRunner.class)
public class RemoteConfigRepositoryTest extends ComponentTestCase {
  @Mock
  private ConfigServiceLocator configServiceLocator;
  private String someNamespace;
  @Mock
  private static HttpResponse<ApolloConfig> someResponse;
  @Mock
  private static HttpResponse<List<ApolloConfigNotification>> pollResponse;
  private RemoteConfigLongPollService remoteConfigLongPollService;

  @Before
  public void setUp() throws Exception {
    super.setUp();
    someNamespace = "someName";

    when(pollResponse.getStatusCode()).thenReturn(HttpServletResponse.SC_NOT_MODIFIED);

    defineComponent(ConfigUtil.class, MockConfigUtil.class);
    defineComponent(ConfigServiceLocator.class, MockConfigServiceLocator.class);
    defineComponent(HttpUtil.class, MockHttpUtil.class);

    remoteConfigLongPollService = lookup(RemoteConfigLongPollService.class);
  }

  @Test
  public void testLoadConfig() throws Exception {
    String someKey = "someKey";
    String someValue = "someValue";
    Map<String, String> configurations = Maps.newHashMap();
    configurations.put(someKey, someValue);
    ApolloConfig someApolloConfig = assembleApolloConfig(configurations);

    when(someResponse.getStatusCode()).thenReturn(200);
    when(someResponse.getBody()).thenReturn(someApolloConfig);

    RemoteConfigRepository remoteConfigRepository = new RemoteConfigRepository(someNamespace);

    Properties config = remoteConfigRepository.getConfig();

    assertEquals(configurations, config);
    remoteConfigLongPollService.stopLongPollingRefresh();
  }

  @Test(expected = ApolloConfigException.class)
  public void testGetRemoteConfigWithServerError() throws Exception {

    when(someResponse.getStatusCode()).thenReturn(500);

    RemoteConfigRepository remoteConfigRepository = new RemoteConfigRepository(someNamespace);

    //must stop the long polling before exception occurred
    remoteConfigLongPollService.stopLongPollingRefresh();

    remoteConfigRepository.getConfig();
  }

  @Test
  public void testRepositoryChangeListener() throws Exception {
    Map<String, String> configurations = ImmutableMap.of("someKey", "someValue");
    ApolloConfig someApolloConfig = assembleApolloConfig(configurations);

    when(someResponse.getStatusCode()).thenReturn(200);
    when(someResponse.getBody()).thenReturn(someApolloConfig);

    RepositoryChangeListener someListener = mock(RepositoryChangeListener.class);
    RemoteConfigRepository remoteConfigRepository = new RemoteConfigRepository(someNamespace);
    remoteConfigRepository.addChangeListener(someListener);
    final ArgumentCaptor<Properties> captor = ArgumentCaptor.forClass(Properties.class);

    Map<String, String> newConfigurations = ImmutableMap.of("someKey", "anotherValue");
    ApolloConfig newApolloConfig = assembleApolloConfig(newConfigurations);

    when(someResponse.getBody()).thenReturn(newApolloConfig);

    remoteConfigRepository.sync();

    verify(someListener, times(1)).onRepositoryChange(eq(someNamespace), captor.capture());

    assertEquals(newConfigurations, captor.getValue());

    remoteConfigLongPollService.stopLongPollingRefresh();
  }

  @Test
  public void testLongPollingRefresh() throws Exception {
    Map<String, String> configurations = ImmutableMap.of("someKey", "someValue");
    ApolloConfig someApolloConfig = assembleApolloConfig(configurations);

    when(someResponse.getStatusCode()).thenReturn(200);
    when(someResponse.getBody()).thenReturn(someApolloConfig);

    final SettableFuture<Boolean> longPollFinished = SettableFuture.create();
    RepositoryChangeListener someListener = mock(RepositoryChangeListener.class);
    doAnswer(new Answer<Void>() {

      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        longPollFinished.set(true);
        return null;
      }

    }).when(someListener).onRepositoryChange(any(String.class), any(Properties.class));

    RemoteConfigRepository remoteConfigRepository = new RemoteConfigRepository(someNamespace);
    remoteConfigRepository.addChangeListener(someListener);
    final ArgumentCaptor<Properties> captor = ArgumentCaptor.forClass(Properties.class);

    Map<String, String> newConfigurations = ImmutableMap.of("someKey", "anotherValue");
    ApolloConfig newApolloConfig = assembleApolloConfig(newConfigurations);
    ApolloConfigNotification someNotification = mock(ApolloConfigNotification.class);
    when(someNotification.getNamespaceName()).thenReturn(someNamespace);

    when(pollResponse.getStatusCode()).thenReturn(HttpServletResponse.SC_OK);
    when(pollResponse.getBody()).thenReturn(Lists.newArrayList(someNotification));
    when(someResponse.getBody()).thenReturn(newApolloConfig);

    longPollFinished.get(500, TimeUnit.MILLISECONDS);

    remoteConfigLongPollService.stopLongPollingRefresh();

    verify(someListener, times(1)).onRepositoryChange(eq(someNamespace), captor.capture());
    assertEquals(newConfigurations, captor.getValue());
  }

  @Test
  public void testAssembleQueryConfigUrl() throws Exception {
    String someUri = "http://someServer";
    String someAppId = "someAppId";
    String someCluster = "someCluster+ &.-_someSign";
    String someReleaseKey = "20160705193346-583078ef5716c055+20160705193308-31c471ddf9087c3f";

    RemoteConfigRepository remoteConfigRepository = new RemoteConfigRepository(someNamespace);
    ApolloConfig someApolloConfig = mock(ApolloConfig.class);
    when(someApolloConfig.getReleaseKey()).thenReturn(someReleaseKey);

    String queryConfigUrl = remoteConfigRepository
        .assembleQueryConfigUrl(someUri, someAppId, someCluster, someNamespace, null,
            someApolloConfig);

    remoteConfigLongPollService.stopLongPollingRefresh();
    assertTrue(queryConfigUrl
        .contains(
            "http://someServer/configs/someAppId/someCluster+%20&.-_someSign/" + someNamespace));
    assertTrue(queryConfigUrl
        .contains("releaseKey=20160705193346-583078ef5716c055%2B20160705193308-31c471ddf9087c3f"));

  }

  private ApolloConfig assembleApolloConfig(Map<String, String> configurations) {
    String someAppId = "appId";
    String someClusterName = "cluster";
    String someReleaseKey = "1";
    ApolloConfig apolloConfig =
        new ApolloConfig(someAppId, someClusterName, someNamespace, someReleaseKey);

    apolloConfig.setConfigurations(configurations);

    return apolloConfig;
  }

  public static class MockConfigUtil extends ConfigUtil {
    @Override
    public String getAppId() {
      return "someApp";
    }

    @Override
    public String getCluster() {
      return "someCluster";
    }

    @Override
    public String getDataCenter() {
      return null;
    }

    @Override
    public int getLoadConfigQPS() {
      return 200;
    }

    @Override
    public int getLongPollQPS() {
      return 200;
    }
  }

  public static class MockConfigServiceLocator extends ConfigServiceLocator {
    @Override
    public List<ServiceDTO> getConfigServices() {
      String someServerUrl = "http://someServer";

      ServiceDTO serviceDTO = mock(ServiceDTO.class);

      when(serviceDTO.getHomepageUrl()).thenReturn(someServerUrl);
      return Lists.newArrayList(serviceDTO);
    }

    @Override
    public void initialize() throws InitializationException {
      //do nothing
    }
  }

  public static class MockHttpUtil extends HttpUtil {
    @Override
    public <T> HttpResponse<T> doGet(HttpRequest httpRequest, Class<T> responseType) {
      return (HttpResponse<T>) someResponse;
    }

    @Override
    public <T> HttpResponse<T> doGet(HttpRequest httpRequest, Type responseType) {
      try {
        TimeUnit.MILLISECONDS.sleep(50);
      } catch (InterruptedException e) {
      }
      return (HttpResponse<T>) pollResponse;
    }
  }

}