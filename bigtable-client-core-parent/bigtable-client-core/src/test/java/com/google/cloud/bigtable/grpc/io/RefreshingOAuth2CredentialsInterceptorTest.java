/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.bigtable.grpc.io;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.util.Clock;
import com.google.api.client.util.NanoClock;
import com.google.api.client.util.Sleeper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.cloud.bigtable.config.Logger;
import com.google.cloud.bigtable.config.RetryOptions;
import com.google.cloud.bigtable.config.RetryOptionsUtil;
import com.google.cloud.bigtable.grpc.io.RefreshingOAuth2CredentialsInterceptor.CacheState;
import com.google.cloud.bigtable.grpc.io.RefreshingOAuth2CredentialsInterceptor.HeaderCacheElement;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;



@RunWith(JUnit4.class)
public class RefreshingOAuth2CredentialsInterceptorTest {

  private static ExecutorService executorService;

  @BeforeClass
  public static void setup() {
    executorService = Executors.newCachedThreadPool();
  }

  @AfterClass
  public static void shtudown() {
    executorService.shutdownNow();
  }

  private RefreshingOAuth2CredentialsInterceptor underTest;

  @Mock
  private OAuth2Credentials credentials;

  @Mock
  private NanoClock nanoClock;

  @Mock
  private Logger logger;

  private RetryOptions retryOptions;

  @Before
  public void setupMocks() {
    MockitoAnnotations.initMocks(this);
    when(nanoClock.nanoTime()).then(new Answer<Long>() {
      @Override
      public Long answer(InvocationOnMock invocation) throws Throwable {
        return getTimeInMilliseconds() * 1000000;
      }
    });
    retryOptions = RetryOptionsUtil.createTestRetryOptions(nanoClock);
    setTimeInMillieconds(0L);
  }

  private void setTimeInMillieconds(final long timeMs) {
    RefreshingOAuth2CredentialsInterceptor.clock = new Clock() {
      @Override
      public long currentTimeMillis() {
        return timeMs;
      }
    };
  }

  private long getTimeInMilliseconds() {
    return RefreshingOAuth2CredentialsInterceptor.clock.currentTimeMillis();
  }

  @Test
  public void testSyncRefresh() throws IOException {
    initialize(HeaderCacheElement.TOKEN_STALENESS_MS + 1);
    Assert.assertEquals(CacheState.Good,
      RefreshingOAuth2CredentialsInterceptor.getCacheState(underTest.headerCache.get()));
  }

  @Test
  public void testStaleAndExpired() throws IOException {
    int expiration = HeaderCacheElement.TOKEN_STALENESS_MS + 1;
    initialize(expiration);
    Assert.assertEquals(CacheState.Good,
      RefreshingOAuth2CredentialsInterceptor.getCacheState(underTest.headerCache.get()));
    long startTime = 2L;
    setTimeInMillieconds(startTime);
    Assert.assertEquals(CacheState.Stale,
      RefreshingOAuth2CredentialsInterceptor.getCacheState(underTest.headerCache.get()));
    long expiredStaleDiff =
        HeaderCacheElement.TOKEN_STALENESS_MS - HeaderCacheElement.TOKEN_EXPIRES_MS;
    setTimeInMillieconds(startTime + expiredStaleDiff);
    Assert.assertEquals(CacheState.Expired,
      RefreshingOAuth2CredentialsInterceptor.getCacheState(underTest.headerCache.get()));
  }

  @Test
  public void testRetriesExhausted() throws Exception {
    IOException ioException = new IOException("something bad happend");
    Mockito.when(credentials.refreshAccessToken()).thenThrow(ioException);
    final int startTime = 100000000;
    setTimeInMillieconds(startTime);
    final int maxElaspedBackoffMillis = retryOptions.getMaxElaspedBackoffMillis();
    final int max_end = startTime + maxElaspedBackoffMillis * 10;

    underTest = new RefreshingOAuth2CredentialsInterceptor(executorService, credentials,
        retryOptions, logger);

    underTest.sleeper = new Sleeper() {
      @Override
      public void sleep(long ms) throws InterruptedException {
        long now = getTimeInMilliseconds() + ms;
        setTimeInMillieconds(now);
        // Make sure that the system "slept" for more than the retryOption max millis. The Backoff logic
        // adds some random variability to the exact elapsed time, so add in a bit of wiggle room.
        Assert.assertTrue(String.format("%d > %d", now, max_end), now < max_end);
      }
    };
    HeaderCacheElement header = underTest.refreshCredentialsWithRetry();

    Assert.assertNull(header.header);
    Assert.assertSame(ioException, header.exception);

    // Make sure that the system "slept" for more than the retryOption max millis. The Backoff logic
    // adds some random variability to the exact elapsed time, so add in a bit of wiggle room.
    long timeInMillis = getTimeInMilliseconds();
    Assert.assertTrue(timeInMillis > startTime + maxElaspedBackoffMillis);

    verify(logger, atLeast(1)).warn(any(String.class), eq(ioException));
    verify(credentials, atLeast(2)).refreshAccessToken();
  }

  @Test
  public void testNullExpiration(){
    setTimeInMillieconds(100);
    HeaderCacheElement element = new HeaderCacheElement(new AccessToken("", null));
    Assert.assertNull(element.expiresTimeMs);
    Assert.assertNull(element.staleTimeMs);
    Assert.assertEquals(CacheState.Good, element.getCacheState());

    // Make sure that the header doesn't expire in the distant future.
    setTimeInMillieconds(100000000000L);
    Assert.assertEquals(CacheState.Good, element.getCacheState());
  }

  @Test
  /*
   * Test that checks that concurrent requests to RefreshingOAuth2CredentialsInterceptor refresh
   * logic doesn't cause hanging behavior.  Specifically, when an Expired condition occurs it
   * triggers a call to syncRefresh() which potentially waits for refresh that was initiated
   * from another thread either through syncRefresh() or asyncRefresh().  This test case simulates
   * that condition.
   */
  public void testRefreshDoesntHang() throws Exception {
    // Assume that the user starts at this time... it's an arbitrarily big number which will
    // assure that subtracting HeaderCacheElement.TOKEN_STALENESS_MS and TOKEN_EXPIRES_MS will not
    // be negative.
    long start = HeaderCacheElement.TOKEN_STALENESS_MS * 10;
    setTimeInMillieconds(start);

    // RefreshingOAuth2CredentialsInterceptor will show that the access token is stale.
    final long expiration = start + HeaderCacheElement.TOKEN_EXPIRES_MS + 1;

    // Create a mechanism that will allow us to control when the accessToken is returned.
    // credentials.refreshAccessToken() will get called asynchronously and will wait until the
    // lock is notified before returning.  That will allow us to set up multiple concurrent calls
    final Object lock = new Object();
    Mockito.when(credentials.refreshAccessToken()).thenAnswer(new Answer<AccessToken>() {
      @Override
      public AccessToken answer(InvocationOnMock invocation) throws Throwable {
        synchronized (lock) {
          lock.wait();
        }
        return new AccessToken("", new Date(expiration));
      }
    });

    // Force a synchronous refresh.  This ought to wait until a refresh happening in another thread
    // completes.
    Callable<Void> syncRefreshCallable = new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        underTest.syncRefresh();
        return null;
      }
    };

    underTest =
        new RefreshingOAuth2CredentialsInterceptor(executorService, credentials,
            new RetryOptions.Builder().build(), logger);

    // At this point, the access token wasn't retrieved yet. The
    // RefreshingOAuth2CredentialsInterceptor considers null to be Expired.
    Assert.assertEquals(CacheState.Expired,
      RefreshingOAuth2CredentialsInterceptor.getCacheState(underTest.headerCache.get()));

    syncCall(lock, syncRefreshCallable);

    // Check to make sure that the AccessToken was retrieved.
    Assert.assertEquals(CacheState.Stale,
      RefreshingOAuth2CredentialsInterceptor.getCacheState(underTest.headerCache.get()));

    // Check to make sure we're no longer refreshing.
    Assert.assertFalse(underTest.isRefreshing.get());

    // Kick off a couple of asynchronous refreshes. Kicking off more than one shouldn't be
    // necessary, but also should not be harmful, since there are likely to be multiple concurrent
    // requests that call asyncRefresh() when the token turns stale.
    underTest.asyncRefresh();
    underTest.asyncRefresh();
    underTest.asyncRefresh();

    syncCall(lock, syncRefreshCallable);
    Assert.assertFalse(underTest.isRefreshing.get());
  }

  private void syncCall(final Object lock, Callable<Void> syncRefreshCallable)
      throws InterruptedException, ExecutionException, TimeoutException {
    Future<Void> future = executorService.submit(syncRefreshCallable);
    // let the Thread running syncRefreshCallable() have a turn so that it can initiate the call
    // to refreshAccessToken().
    try {
      future.get(100, TimeUnit.MILLISECONDS);
    } catch (TimeoutException ignored) {
    }

    // There should be a single thread kicked off by the underTest.asyncRefresh() calls about
    // actually doing a refresh at this point; the other ones will have see that a refresh is in
    // progress and finish the invocation of the Thread without performing a refres().. Make sure
    // that at least 1 refresh process is in progress.
    Assert.assertTrue(underTest.isRefreshing.get());

    synchronized(lock) {
      lock.notifyAll();
    }

    // Wait for no more than a second to make sure that the call to underTest.syncRefresh()
    // completes properly.  If a second passes without syncRefresh() completing, future.get(..)
    // will throw a TimeoutException.
    future.get(1, TimeUnit.SECONDS);
  }

  private void initialize(long expiration) throws IOException {
    Mockito.when(credentials.refreshAccessToken()).thenReturn(
      new AccessToken("", new Date(expiration)));
    underTest = new RefreshingOAuth2CredentialsInterceptor(executorService, credentials,
        retryOptions, logger);
    underTest.syncRefresh();
  }
}
