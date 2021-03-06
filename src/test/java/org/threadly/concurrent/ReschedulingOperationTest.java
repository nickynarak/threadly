package org.threadly.concurrent;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.test.concurrent.TestRunnable;
import org.threadly.test.concurrent.TestableScheduler;

@SuppressWarnings("javadoc")
public class ReschedulingOperationTest {
  private static final int SCHEDULE_DELAY = 100;
  private TestableScheduler scheduler;
  
  @Before
  public void setup() {
    scheduler = new TestableScheduler();
  }
  
  @After
  public void cleanup() {
    scheduler = null;
  }
  
  @Test
  @SuppressWarnings("unused")
  public void constructorFail() {
    try {
      new TestReschedulingOperation(null, SCHEDULE_DELAY, false);
      fail("Exception should have thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
    try {
      new TestReschedulingOperation(scheduler, -1, false);
      fail("Exception should have thrown");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }
  
  @Test
  public void runAfterSignaledTest() {
    TestReschedulingOperation testOp = new TestReschedulingOperation(scheduler, SCHEDULE_DELAY, false);
    
    assertEquals(0, scheduler.advance(SCHEDULE_DELAY));
    
    testOp.signalToRun();
    
    // should run once, but not again
    assertEquals(1, scheduler.advance(SCHEDULE_DELAY));
    assertEquals(0, scheduler.advance(SCHEDULE_DELAY));
    assertEquals(0, scheduler.getQueuedTaskCount());
    
    testOp.signalToRun();
    
    // should run again, but not again
    assertEquals(1, scheduler.advance(SCHEDULE_DELAY));
    assertEquals(0, scheduler.advance(SCHEDULE_DELAY));
    assertEquals(0, scheduler.getQueuedTaskCount());
    
    assertEquals(2, testOp.tr.getRunCount());
  }

  @Test
  public void autoRescheduleTest() {
    TestReschedulingOperation testOp = new TestReschedulingOperation(scheduler, SCHEDULE_DELAY, true);
    
    testOp.signalToRun();
    
    // should run every time
    assertEquals(1, scheduler.advance(SCHEDULE_DELAY));
    assertEquals(1, scheduler.advance(SCHEDULE_DELAY));
    assertEquals(1, scheduler.getQueuedTaskCount());
    
    assertEquals(2, testOp.tr.getRunCount());
  }

  @Test
  public void changeScheduleDelayTest() {
    TestReschedulingOperation testOp = new TestReschedulingOperation(scheduler, SCHEDULE_DELAY, true);
    testOp.setScheduleDelay(SCHEDULE_DELAY / 2);
    
    testOp.signalToRun();

    assertEquals(1, scheduler.advance(SCHEDULE_DELAY / 2));
    assertEquals(1, testOp.tr.getRunCount());
  }
  
  private static class TestReschedulingOperation extends ReschedulingOperation {
    public final TestRunnable tr = new TestRunnable();
    private final boolean alwaysReschedule;

    protected TestReschedulingOperation(SubmitterScheduler scheduler, 
                                        long scheduleDelay, boolean alwaysReschedule) {
      super(scheduler, scheduleDelay);
      
      this.alwaysReschedule = alwaysReschedule;
    }

    @Override
    protected void run() {
      tr.run();
      if (alwaysReschedule) {
        signalToRun();
      }
    }
  }
}
