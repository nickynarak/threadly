package org.threadly.concurrent.wrapper.traceability;

import org.threadly.concurrent.PrioritySchedulerTest.PrioritySchedulerFactory;
import org.threadly.concurrent.SchedulerService;
import org.threadly.concurrent.SchedulerServiceInterfaceTest;
import org.threadly.concurrent.SubmitterExecutor;
import org.threadly.concurrent.SubmitterScheduler;

@SuppressWarnings("javadoc")
public class ThreadRenamingSchedulerServiceTest extends SchedulerServiceInterfaceTest {
  @Override
  protected SchedulerServiceFactory getSchedulerServiceFactory() {
    return new ThreadRenamingPoolWrapperFactory();
  }
  
  @Override
  protected boolean isSingleThreaded() {
    return false;
  }

  private static class ThreadRenamingPoolWrapperFactory implements SchedulerServiceFactory {
    private final PrioritySchedulerFactory schedulerFactory = new PrioritySchedulerFactory();

    @Override
    public SubmitterExecutor makeSubmitterExecutor(int poolSize, boolean prestartIfAvailable) {
      return makeSubmitterScheduler(poolSize, prestartIfAvailable);
    }
    
    @Override
    public SubmitterScheduler makeSubmitterScheduler(int poolSize, boolean prestartIfAvailable) {
      return makeSchedulerService(poolSize, prestartIfAvailable);
    }

    @Override
    public SchedulerService makeSchedulerService(int poolSize, boolean prestartIfAvailable) {
      SchedulerService scheduler = schedulerFactory.makeSchedulerService(poolSize, prestartIfAvailable);

      return new ThreadRenamingSchedulerService(scheduler, "foo", false);
    }

    @Override
    public void shutdown() {
      schedulerFactory.shutdown();
    }
  }
}
