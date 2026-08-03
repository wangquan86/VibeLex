package com.vibelex.crawling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class CrawlSchedulerTest {

  @Test
  void doesNotSchedulePopCidianWhenItsScheduleIsDisabled() {
    CrawlProperties properties = enabledPopCidianProperties();
    properties.getPopcidian().setScheduledEnabled(false);
    CrawlExecutionService executions = mock(CrawlExecutionService.class);

    new CrawlScheduler(properties, executions).schedulePopCidianSync();

    verify(executions, never()).startSync(PopCidianConnector.SOURCE_CODE);
  }

  @Test
  void schedulesPopCidianWhenItsScheduleIsEnabled() {
    CrawlProperties properties = enabledPopCidianProperties();
    properties.getPopcidian().setScheduledEnabled(true);
    CrawlExecutionService executions = mock(CrawlExecutionService.class);

    new CrawlScheduler(properties, executions).schedulePopCidianSync();

    verify(executions).startSync(PopCidianConnector.SOURCE_CODE);
  }

  private CrawlProperties enabledPopCidianProperties() {
    CrawlProperties properties = new CrawlProperties();
    properties.setEnabled(true);
    properties.getPopcidian().setEnabled(true);
    return properties;
  }
}
