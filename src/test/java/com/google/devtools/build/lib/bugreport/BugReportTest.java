// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bugreport;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.testing.TestLogHandler;
import com.google.devtools.build.lib.bugreport.BugReport.BlazeRuntimeInterface;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Crash.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.CrashFailureDetails;
import com.google.devtools.build.lib.util.CustomExitCodePublisher;
import com.google.devtools.build.lib.util.CustomFailureDetailPublisher;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.protobuf.ExtensionRegistry;
import com.google.testing.junit.runner.util.GoogleTestSecurityManager;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Permission;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link BugReport}.
 *
 * <p>Assuming that {@link GoogleTestSecurityManager} is not already installed, uses {@link
 * ExitProhibitingSecurityManager} to exercise attempting to halt the JVM without aborting the whole
 * test.
 */
// TODO(b/222158599): Remove handling for GoogleTestSecurityManager.
@RunWith(TestParameterInjector.class)
public final class BugReportTest {

  private static final ExitCode EXIT_CODE_BLAZE_OOMING = ExitCode.OOM_ERROR;
  private static final Code FAILURE_DETAIL_CODE_BLAZE_OOMING = Code.CRASH_OOM;

  @TestParameter private boolean oomDetectorOverride;

  @BeforeClass
  public static void installCustomSecurityManager() {
    if (System.getSecurityManager() == null) {
      System.setSecurityManager(new ExitProhibitingSecurityManager());
    } else {
      assertThat(System.getSecurityManager()).isInstanceOf(GoogleTestSecurityManager.class);
    }
  }

  @Before
  public void maybeSetOomDetector() {
    if (oomDetectorOverride) {
      CrashFailureDetails.setOomDetector(() -> true);
    }
  }

  @AfterClass
  public static void uninstallCustomSecurityManager() {
    if (System.getSecurityManager() instanceof ExitProhibitingSecurityManager) {
      System.setSecurityManager(null);
    }
  }

  @After
  public void restoreDefaultOomDetector() {
    if (oomDetectorOverride) {
      CrashFailureDetails.setOomDetector(() -> false);
    }
  }

  private enum CrashType {
    CRASH(ExitCode.BLAZE_INTERNAL_ERROR, Code.CRASH_UNKNOWN) {
      @Override
      Throwable createThrowable() {
        return new IllegalStateException("Crashed");
      }
    },
    OOM(ExitCode.OOM_ERROR, Code.CRASH_OOM) {
      @Override
      Throwable createThrowable() {
        return new OutOfMemoryError("Java heap space");
      }
    };

    private final ExitCode expectedExitCode;
    private final Code expectedFailureDetailCode;

    CrashType(ExitCode expectedExitCode, Code expectedFailureDetailCode) {
      this.expectedExitCode = expectedExitCode;
      this.expectedFailureDetailCode = expectedFailureDetailCode;
    }

    abstract Throwable createThrowable();
  }

  private enum ExceptionType {
    FATAL(
        new RuntimeException("fatal exception"),
        /* isFatal= */ true,
        Level.SEVERE,
        "myProductName crashed with args: arg foo"),
    NONFATAL(
        new IllegalStateException("bug report"),
        /* isFatal= */ false,
        Level.WARNING,
        "myProductName had a non fatal error with args: arg foo"),
    OOM(
        new OutOfMemoryError("Java heap space"),
        /* isFatal= */ true,
        Level.SEVERE,
        "myProductName OOMError: arg foo");

    @SuppressWarnings("ImmutableEnumChecker") // I'm pretty sure no one will mutate this Throwable.
    private final Throwable throwable;

    @SuppressWarnings("ImmutableEnumChecker") // Same here.
    private final Level level;

    private final boolean isFatal;

    private final String expectedMessage;

    ExceptionType(Throwable throwable, boolean isFatal, Level level, String expectedMessage) {
      this.throwable = throwable;
      this.isFatal = isFatal;
      this.level = level;
      this.expectedMessage = expectedMessage;
    }

    private String getExpectedMessage() {
      return expectedMessage;
    }

    private String getExpectedMessageWhileOoming() {
      return "While OOMing, " + expectedMessage;
    }
  }

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  private final BlazeRuntimeInterface mockRuntime = mock(BlazeRuntimeInterface.class);

  private Path exitCodeFile;
  private Path failureDetailFile;

  @Before
  public void setup() throws Exception {
    when(mockRuntime.getProductName()).thenReturn("myProductName");
    BugReport.setRuntime(mockRuntime);

    exitCodeFile = tmp.newFolder().toPath().resolve("exit_code_to_use_on_abrupt_exit");
    failureDetailFile = tmp.newFolder().toPath().resolve("failure_detail");

    CustomExitCodePublisher.setAbruptExitStatusFileDir(exitCodeFile.getParent().toString());
    CustomFailureDetailPublisher.setFailureDetailFilePath(failureDetailFile.toString());
  }

  @After
  public void resetPublishers() {
    CustomExitCodePublisher.resetAbruptExitStatusFile();
    CustomFailureDetailPublisher.resetFailureDetailFilePath();
  }

  @Test
  public void logException(@TestParameter ExceptionType exceptionType) {
    TestLogHandler handler = new TestLogHandler();
    Logger logger = Logger.getLogger("build.lib.bugreport");
    logger.addHandler(handler);
    LoggingUtil.installRemoteLoggerForTesting(immediateFuture(logger));

    BugReport.logException(
        exceptionType.throwable, exceptionType.isFatal, ImmutableList.of("arg", "foo"));
    LogRecord got = handler.getStoredLogRecords().get(0);
    if (oomDetectorOverride) {
      assertThat(got.getMessage()).isEqualTo(exceptionType.getExpectedMessageWhileOoming());
    } else {
      assertThat(got.getMessage()).isEqualTo(exceptionType.getExpectedMessage());
    }
    assertThat(got.getThrown()).isSameInstanceAs(exceptionType.throwable);
    assertThat(got.getLevel()).isEqualTo(exceptionType.level);
  }

  /**
   * Regression test verifying that {@link BugReport#handleCrash(Crash, CrashContext)} is re-entrant
   * and can be called from multiple threads even if the first caller is blocking.
   */
  @Test
  public void handleCrash_reentrant(@TestParameter CrashType crashType)
      throws InterruptedException {
    ////// Arrange:
    // A separate thread will block until `firstCrashWaitsLatch` is ready, then crash using the
    // `crashType` test parameter.
    CountDownLatch firstCrashStartedReportingLatch = new CountDownLatch(1);
    CountDownLatch firstCrashWaitsLatch = new CountDownLatch(1);
    doAnswer(
            (inv) -> {
              firstCrashStartedReportingLatch.countDown();
              awaitUninterruptibly(firstCrashWaitsLatch);
              return null;
            })
        .when(mockRuntime)
        .cleanUpForCrash(any());

    Throwable t = crashType.createThrowable();
    TestThread blockingCrashThread =
        new TestThread(
            () -> {
              SecurityException e =
                  assertThrows(SecurityException.class, () -> BugReport.handleCrash(t));
              if (e instanceof ExitException) {
                int code = ((ExitException) e).code;
                assertThat(code).isEqualTo(crashType.expectedExitCode.getNumericExitCode());
              }
            });
    blockingCrashThread.start();
    // Wait for the TestThread to start, call BugReport#handleCrash(), and call
    // BlazeRuntimeInterface#cleanUpForCrash().
    assertThat(firstCrashStartedReportingLatch.await(TestUtils.WAIT_TIMEOUT_SECONDS, SECONDS))
        .isTrue();
    assertThat(blockingCrashThread.isAlive()).isTrue();

    ////// Act:
    // Crash on the main test thread while the first crash is still blocking forever.
    Throwable secondThreadCrash = new UnsupportedOperationException("Second thread crashing.");
    SecurityException secondThreadSecurityException =
        assertThrows(SecurityException.class, () -> BugReport.handleCrash(secondThreadCrash));

    ////// Assert:
    if (secondThreadSecurityException instanceof ExitException) {
      int code = ((ExitException) secondThreadSecurityException).code;
      assertThat(code).isEqualTo(ExitCode.BLAZE_INTERNAL_ERROR.getNumericExitCode());
    }
    // Verify the latest exception stored is the main-thread crashing throwable.
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest())
        .isSameInstanceAs(secondThreadCrash);

    // Unblock the first crashing thread and verify that it completes. Asserting its state will
    // ensure the test fails if the wrong exit code is returned to the first call to handleCrash().
    firstCrashWaitsLatch.countDown();
    blockingCrashThread.joinAndAssertState(TestUtils.WAIT_TIMEOUT_MILLISECONDS);
    verify(mockRuntime).cleanUpForCrash(any());
  }

  @Test
  public void convenienceMethod(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType, oomDetectorOverride);
    // TODO(b/222158599): This should always be ExitException.
    SecurityException e = assertThrows(SecurityException.class, () -> BugReport.handleCrash(t));
    if (e instanceof ExitException) {
      int code = ((ExitException) e).code;
      assertThat(code).isEqualTo(crashType.expectedExitCode.getNumericExitCode());
    }
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(mockRuntime)
        .cleanUpForCrash(
            DetailedExitCode.of(
                oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode,
                expectedFailureDetail));
    verifyExitCodeWritten(
        oomDetectorOverride
            ? EXIT_CODE_BLAZE_OOMING.getNumericExitCode()
            : crashType.expectedExitCode.getNumericExitCode());
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void halt(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType, oomDetectorOverride);

    // TODO(b/222158599): This should always be ExitException.
    SecurityException e =
        assertThrows(
            SecurityException.class,
            () -> BugReport.handleCrash(Crash.from(t), CrashContext.halt()));
    if (e instanceof ExitException) {
      int code = ((ExitException) e).code;
      assertThat(code).isEqualTo(crashType.expectedExitCode.getNumericExitCode());
    }
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(mockRuntime)
        .cleanUpForCrash(
            DetailedExitCode.of(
                oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode,
                expectedFailureDetail));
    verifyExitCodeWritten(
        oomDetectorOverride
            ? EXIT_CODE_BLAZE_OOMING.getNumericExitCode()
            : crashType.expectedExitCode.getNumericExitCode());
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void keepAlive(@TestParameter CrashType crashType) throws Exception {
    Throwable t = crashType.createThrowable();
    FailureDetail expectedFailureDetail =
        createExpectedFailureDetail(t, crashType, oomDetectorOverride);

    BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive());
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(mockRuntime)
        .cleanUpForCrash(
            DetailedExitCode.of(
                oomDetectorOverride ? EXIT_CODE_BLAZE_OOMING : crashType.expectedExitCode,
                expectedFailureDetail));
    verifyNoExitCodeWritten();
    verifyFailureDetailWritten(expectedFailureDetail);
  }

  @Test
  public void customContext_setUpFront(@TestParameter CrashType crashType) {
    Throwable t = crashType.createThrowable();
    EventHandler handler = mock(EventHandler.class);
    ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);

    BugReport.handleCrash(
        Crash.from(t),
        CrashContext.keepAlive().withExtraOomInfo("Build fewer targets!").reportingTo(handler));
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(handler).handle(event.capture());
    assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL);
    assertThat(event.getValue().getMessage()).contains(Throwables.getStackTraceAsString(t));
    if (oomDetectorOverride || crashType == CrashType.OOM) {
      assertThat(event.getValue().getMessage()).contains("ran out of memory and crashed.");
      assertThat(event.getValue().getMessage()).contains("Build fewer targets!");
    } else {
      assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!");
    }
  }

  @Test
  public void customContext_filledInByRuntime(@TestParameter CrashType crashType) {
    Throwable t = crashType.createThrowable();
    EventHandler handler = mock(EventHandler.class);
    ArgumentCaptor<Event> event = ArgumentCaptor.forClass(Event.class);
    doAnswer(
            inv ->
                inv.getArgument(0, CrashContext.class)
                    .withExtraOomInfo("Build fewer targets!")
                    .reportingTo(handler))
        .when(mockRuntime)
        .fillInCrashContext(any());

    BugReport.handleCrash(Crash.from(t), CrashContext.keepAlive());
    assertThat(BugReport.getAndResetLastCrashingThrowableIfInTest()).isSameInstanceAs(t);

    verify(handler).handle(event.capture());
    assertThat(event.getValue().getKind()).isEqualTo(EventKind.FATAL);
    assertThat(event.getValue().getMessage()).contains(Throwables.getStackTraceAsString(t));

    if (oomDetectorOverride || crashType == CrashType.OOM) {
      assertThat(event.getValue().getMessage()).contains("ran out of memory and crashed.");
      assertThat(event.getValue().getMessage()).contains("Build fewer targets!");
    } else {
      assertThat(event.getValue().getMessage()).doesNotContain("Build fewer targets!");
    }
  }

  private void verifyExitCodeWritten(int exitCode) throws Exception {
    assertThat(Files.readAllLines(exitCodeFile)).containsExactly(String.valueOf(exitCode));
  }

  private void verifyNoExitCodeWritten() {
    assertThat(exitCodeFile.toFile().exists()).isFalse();
  }

  private void verifyFailureDetailWritten(FailureDetail expected) throws Exception {
    assertThat(
            FailureDetail.parseFrom(
                Files.readAllBytes(failureDetailFile), ExtensionRegistry.getEmptyRegistry()))
        .isEqualTo(expected);
  }

  private static FailureDetail createExpectedFailureDetail(
      Throwable t, CrashType crashType, boolean oomDetectorOverride) {
    FailureDetails.Crash.Builder crash =
        FailureDetails.Crash.newBuilder()
            .setCode(
                oomDetectorOverride
                    ? FAILURE_DETAIL_CODE_BLAZE_OOMING
                    : crashType.expectedFailureDetailCode)
            .addCauses(
                FailureDetails.Throwable.newBuilder()
                    .setThrowableClass(t.getClass().getName())
                    .setMessage(t.getMessage())
                    .addAllStackTrace(
                        Lists.transform(
                            Arrays.asList(t.getStackTrace()), StackTraceElement::toString)));
    if (oomDetectorOverride && crashType == CrashType.CRASH) {
      crash.setOomDetectorOverride(true);
    }
    return FailureDetail.newBuilder()
        .setMessage(String.format("Crashed: (%s) %s", t.getClass().getName(), t.getMessage()))
        .setCrash(crash)
        .build();
  }

  private static final class ExitException extends SecurityException {
    private final int code;

    ExitException(int code) {
      super("Tried to exit JVM with code " + code);
      this.code = code;
    }
  }

  /** Instead of exiting the JVM, throws {@link ExitException} to keep the test alive. */
  private static final class ExitProhibitingSecurityManager extends SecurityManager {

    @Override
    public void checkExit(int code) {
      throw new ExitException(code);
    }

    @Override
    public void checkPermission(Permission p) {} // Allow everything else.
  }
}
