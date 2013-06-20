package org.threadly.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import org.threadly.concurrent.collections.DynamicDelayQueue;
import org.threadly.concurrent.collections.DynamicDelayedUpdater;
import org.threadly.concurrent.lock.LockFactory;
import org.threadly.concurrent.lock.NativeLock;
import org.threadly.concurrent.lock.VirtualLock;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;

/**
 * Executor to run tasks, schedule tasks.  
 * Unlike {@link java.util.concurrent.ScheduledThreadPoolExecutor}
 * this scheduled executor's pool size can grow and shrink 
 * based off usage.  It also has the benefit that you can 
 * provide "low priority" tasks which will attempt to use 
 * existing workers and not instantly create new threads on 
 * demand.  Thus allowing you to better take the benefits 
 * of a thread pool for tasks which specific execution time 
 * is less important.
 * 
 * @author jent - Mike Jensen
 */
public class PriorityScheduledExecutor implements PrioritySchedulerInterface, 
                                                  LockFactory {
  protected static final TaskPriority DEFAULT_PRIORITY = TaskPriority.High;
  protected static final int DEFAULT_LOW_PRIORITY_MAX_WAIT = 500;
  protected static final boolean DEFAULT_NEW_THREADS_DAEMON = true;
  
  protected final TaskPriority defaultPriority;
  protected final VirtualLock highPriorityLock;
  protected final VirtualLock lowPriorityLock;
  protected final VirtualLock workersLock;
  protected final DynamicDelayQueue<TaskWrapper> highPriorityQueue;
  protected final DynamicDelayQueue<TaskWrapper> lowPriorityQueue;
  protected final Deque<Worker> availableWorkers;        // is locked around workersLock
  protected final ThreadFactory threadFactory;
  protected final TaskConsumer highPriorityConsumer;  // is locked around highPriorityLock
  protected final TaskConsumer lowPriorityConsumer;    // is locked around lowPriorityLock
  private volatile boolean running;
  private volatile int corePoolSize;
  private volatile int maxPoolSize;
  private volatile long keepAliveTimeInMs;
  private volatile long maxWaitForLowPriorityInMs;
  private volatile boolean allowCorePoolTimeout;
  private int currentPoolSize;  // is locked around workersLock

  /**
   * Constructs a new thread pool, though no threads will be started 
   * till it accepts it's first request.  This constructs a default 
   * priority of high (which makes sense for most use cases).  
   * It also defaults low priority worker wait as 500ms.  It also  
   * defaults to all newly created threads being daemon threads.
   * 
   * @param corePoolSize pool size that should be maintained
   * @param maxPoolSize maximum allowed thread count
   * @param keepAliveTimeInMs time to wait for a given thread to be idle before killing
   */
  public PriorityScheduledExecutor(int corePoolSize, int maxPoolSize,
                                   long keepAliveTimeInMs) {
    this(corePoolSize, maxPoolSize, keepAliveTimeInMs, 
         DEFAULT_PRIORITY, DEFAULT_LOW_PRIORITY_MAX_WAIT, 
         DEFAULT_NEW_THREADS_DAEMON);
  }
  
  /**
   * Constructs a new thread pool, though no threads will be started 
   * till it accepts it's first request.  This constructs a default 
   * priority of high (which makes sense for most use cases).  
   * It also defaults low priority worker wait as 500ms.
   * 
   * @param corePoolSize pool size that should be maintained
   * @param maxPoolSize maximum allowed thread count
   * @param keepAliveTimeInMs time to wait for a given thread to be idle before killing
   * @param useDaemonThreads boolean for if newly created threads should be daemon
   */
  public PriorityScheduledExecutor(int corePoolSize, int maxPoolSize,
                                   long keepAliveTimeInMs, boolean useDaemonThreads) {
    this(corePoolSize, maxPoolSize, keepAliveTimeInMs, 
         DEFAULT_PRIORITY, DEFAULT_LOW_PRIORITY_MAX_WAIT, 
         useDaemonThreads);
  }

  /**
   * Constructs a new thread pool, though no threads will be started 
   * till it accepts it's first request.  This provides the extra
   * parameters to tune what tasks submitted without a priority will be 
   * scheduled as.  As well as the maximum wait for low priority tasks.
   * The longer low priority tasks wait for a worker, the less chance they will
   * have to make a thread.  But it also makes low priority tasks execution time
   * less predictable.
   * 
   * @param corePoolSize pool size that should be maintained
   * @param maxPoolSize maximum allowed thread count
   * @param keepAliveTimeInMs time to wait for a given thread to be idle before killing
   * @param defaultPriority priority to give tasks which do not specify it
   * @param maxWaitForLowPriorityInMs time low priority tasks wait for a worker
   */
  public PriorityScheduledExecutor(int corePoolSize, int maxPoolSize,
                                   long keepAliveTimeInMs, TaskPriority defaultPriority, 
                                   long maxWaitForLowPriorityInMs) {
    this(corePoolSize, maxPoolSize, keepAliveTimeInMs, 
         defaultPriority, maxWaitForLowPriorityInMs, 
         DEFAULT_NEW_THREADS_DAEMON);
  }

  /**
   * Constructs a new thread pool, though no threads will be started 
   * till it accepts it's first request.  This provides the extra
   * parameters to tune what tasks submitted without a priority will be 
   * scheduled as.  As well as the maximum wait for low priority tasks.
   * The longer low priority tasks wait for a worker, the less chance they will
   * have to make a thread.  But it also makes low priority tasks execution time
   * less predictable.
   * 
   * @param corePoolSize pool size that should be maintained
   * @param maxPoolSize maximum allowed thread count
   * @param keepAliveTimeInMs time to wait for a given thread to be idle before killing
   * @param defaultPriority priority to give tasks which do not specify it
   * @param maxWaitForLowPriorityInMs time low priority tasks wait for a worker
   * @param useDaemonThreads boolean for if newly created threads should be daemon
   */
  public PriorityScheduledExecutor(int corePoolSize, int maxPoolSize,
                                   long keepAliveTimeInMs, TaskPriority defaultPriority, 
                                   long maxWaitForLowPriorityInMs, 
                                   final boolean useDaemonThreads) {
    
    this(corePoolSize, maxPoolSize, keepAliveTimeInMs, 
         defaultPriority, maxWaitForLowPriorityInMs, 
         new ThreadFactory() {
           private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
          
           @Override
           public Thread newThread(Runnable runnable) {
             Thread thread = defaultFactory.newThread(runnable);
             
             thread.setDaemon(useDaemonThreads);
             
             return thread;
           }
         });
  }

  /**
   * Constructs a new thread pool, though no threads will be started 
   * till it accepts it's first request.  This provides the extra
   * parameters to tune what tasks submitted without a priority will be 
   * scheduled as.  As well as the maximum wait for low priority tasks.
   * The longer low priority tasks wait for a worker, the less chance they will
   * have to make a thread.  But it also makes low priority tasks execution time
   * less predictable.
   * 
   * @param corePoolSize pool size that should be maintained
   * @param maxPoolSize maximum allowed thread count
   * @param keepAliveTimeInMs time to wait for a given thread to be idle before killing
   * @param defaultPriority priority to give tasks which do not specify it
   * @param maxWaitForLowPriorityInMs time low priority tasks wait for a worker
   * @param threadFactory thread factory for producing new threads within executor
   */
  public PriorityScheduledExecutor(int corePoolSize, int maxPoolSize,
                                   long keepAliveTimeInMs, TaskPriority defaultPriority, 
                                   long maxWaitForLowPriorityInMs, ThreadFactory threadFactory) {
    if (corePoolSize < 1) {
      throw new IllegalArgumentException("corePoolSize must be >= 1");
    } else if (maxPoolSize < corePoolSize) {
      throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");
    } else if (keepAliveTimeInMs < 0) {
      throw new IllegalArgumentException("keepAliveTimeInMs must be >= 0");
    } else if (maxWaitForLowPriorityInMs < 0) {
      throw new IllegalArgumentException("maxWaitForLowPriorityInMs must be >= 0");
    }
    
    if (defaultPriority == null) {
      defaultPriority = DEFAULT_PRIORITY;
    }
    if (threadFactory == null) {
      threadFactory = Executors.defaultThreadFactory();
    }
    
    this.defaultPriority = defaultPriority;
    highPriorityLock = makeLock();
    lowPriorityLock = makeLock();
    workersLock = makeLock();
    highPriorityQueue = new DynamicDelayQueue<TaskWrapper>(highPriorityLock);
    lowPriorityQueue = new DynamicDelayQueue<TaskWrapper>(lowPriorityLock);
    availableWorkers = new ArrayDeque<Worker>(maxPoolSize);
    this.threadFactory = threadFactory;
    highPriorityConsumer = new TaskConsumer(highPriorityQueue, highPriorityLock, 
                                            new TaskAcceptor() {
      @Override
      public void acceptTask(TaskWrapper task) throws InterruptedException {
        runHighPriorityTask(task);
      }
    });
    lowPriorityConsumer = new TaskConsumer(lowPriorityQueue, lowPriorityLock, 
                                           new TaskAcceptor() {
      @Override
      public void acceptTask(TaskWrapper task) throws InterruptedException {
        runLowPriorityTask(task);
      }
    });
    running = true;
    this.corePoolSize = corePoolSize;
    this.maxPoolSize = maxPoolSize;
    this.keepAliveTimeInMs = keepAliveTimeInMs;
    this.maxWaitForLowPriorityInMs = maxWaitForLowPriorityInMs;
    this.allowCorePoolTimeout = false;
    currentPoolSize = 0;
  }
  
  /**
   * If a section of code wants a different default priority, or wanting to provide 
   * a specific default priority in for {@link CallableDistributor}, 
   * {@link TaskExecutorDistributor}, or {@link TaskSchedulerDistributor}.
   * 
   * @param priority default priority for PrioritySchedulerInterface implementation
   * @return a PrioritySchedulerInterface with the default priority specified
   */
  public PrioritySchedulerInterface makeWithDefaultPriority(TaskPriority priority) {
    if (priority == defaultPriority) {
      return this;
    } else {
      return new PrioritySchedulerWrapper(this, priority);
    }
  }

  @Override
  public TaskPriority getDefaultPriority() {
    return defaultPriority;
  }
  
  /**
   * Getter for the current set core pool size.
   * 
   * @return current core pool size
   */
  public int getCorePoolSize() {
    return corePoolSize;
  }
  
  /**
   * Getter for the currently set max pool size.
   * 
   * @return current max pool size
   */
  public int getMaxPoolSize() {
    return maxPoolSize;
  }
  
  /**
   * Getter for the currently set keep alive time.
   * 
   * @return current keep alive time
   */
  public long getKeepAliveTime() {
    return keepAliveTimeInMs;
  }
  
  /**
   * Getter for the current qty of workers constructed (ether running or idle).
   * 
   * @return current worker count
   */
  public int getCurrentPoolSize() {
    synchronized (workersLock) {
      return currentPoolSize;
    }
  }
  
  /**
   * Change the set core pool size.
   * 
   * @param corePoolSize New pool size.  Must be >= 1 and <= the set max pool size.
   */
  public void setCorePoolSize(int corePoolSize) {
    if (corePoolSize < 1) {
      throw new IllegalArgumentException("corePoolSize must be >= 1");
    } else if (maxPoolSize < corePoolSize) {
      throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");
    }
    
    this.corePoolSize = corePoolSize;
  }
  
  /**
   * Change the set max pool size.
   * 
   * @param maxPoolSize New max pool size.  Must be >= 1 and >= the set core pool size.
   */
  public void setMaxPoolSize(int maxPoolSize) {
    if (maxPoolSize < 1) {
      throw new IllegalArgumentException("maxPoolSize must be >= 1");
    } else if (maxPoolSize < corePoolSize) {
      throw new IllegalArgumentException("maxPoolSize must be >= corePoolSize");
    }
    
    this.maxPoolSize = maxPoolSize;
  }
  
  /**
   * Change the set idle thread keep alive time.
   * 
   * @param keepAliveTimeInMs New keep alive time in milliseconds.  Must be >= 0.
   */
  public void setKeepAliveTime(long keepAliveTimeInMs) {
    if (keepAliveTimeInMs < 0) {
      throw new IllegalArgumentException("keepAliveTimeInMs must be >= 0");
    }
    
    this.keepAliveTimeInMs = keepAliveTimeInMs;
  }
  
  /**
   * Changes the max wait time for an idle worker for low priority tasks.
   * Changing this will only take effect for future low priority tasks, it 
   * will have no impact for the current low priority task attempting to get 
   * a worker.
   * 
   * @param maxWaitForLowPriorityInMs new time to wait for a thread in milliseconds.  Must be >= 0.
   */
  public void setMaxWaitForLowPriority(long maxWaitForLowPriorityInMs) {
    if (maxWaitForLowPriorityInMs < 0) {
      throw new IllegalArgumentException("maxWaitForLowPriorityInMs must be >= 0");
    }
    
    this.maxWaitForLowPriorityInMs = maxWaitForLowPriorityInMs;
  }
  
  /**
   * Getter for the maximum amount of time a low priority task will 
   * wait for an available worker.
   * 
   * @return currently set max wait for low priority task
   */
  public long getMaxWaitForLowPriority() {
    return maxWaitForLowPriorityInMs;
  }
  
  /**
   * Prestarts all core threads.  This will make new idle workers to accept future tasks.
   */
  public void prestartAllCoreThreads() {
    synchronized (workersLock) {
      boolean startedThreads = false;
      while (currentPoolSize < corePoolSize) {
        availableWorkers.addFirst(makeNewWorker());
        startedThreads = true;
      }
      
      if (startedThreads) {
        workersLock.signalAll();
      }
    }
  }

  /**
   * Changes the setting weather core threads are allowed to 
   * be killed if they remain idle.
   * 
   * @param value true if core threads should be expired when idle.
   */
  public void allowCoreThreadTimeOut(boolean value) {
    allowCorePoolTimeout = value;    
  }

  @Override
  public boolean isShutdown() {
    return ! running;
  }
  
  protected void clearTaskQueue() {
    synchronized (highPriorityLock) {
      synchronized (lowPriorityLock) {
        highPriorityConsumer.stop();
        lowPriorityConsumer.stop();
        
        synchronized (highPriorityQueue.getLock()) {
          Iterator<TaskWrapper> it = highPriorityQueue.iterator();
          while (it.hasNext()) {
            it.next().cancel();
          }
          lowPriorityQueue.clear();
        }
        synchronized (lowPriorityQueue.getLock()) {
          Iterator<TaskWrapper> it = lowPriorityQueue.iterator();
          while (it.hasNext()) {
            it.next().cancel();
          }
          lowPriorityQueue.clear();
        }
      }
    }
  }
  
  protected void shutdownAllWorkers() {
    synchronized (workersLock) {
      Iterator<Worker> it = availableWorkers.iterator();
      while (it.hasNext()) {
        killWorker(it.next());
        it.remove();
      }
    }
  }

  /**
   * Stops any tasks from continuing to run and destroys all worker threads.
   */
  public void shutdown() {
    running = false;
    clearTaskQueue();
    shutdownAllWorkers();
  }
  
  protected void verifyNotShutdown() {
    if (! running) {
      throw new IllegalStateException("Thread pool shutdown");
    }
  }
  
  /**
   * Makes a new {@link PrioritySchedulerLimiter} that uses this pool as it's execution source.
   * 
   * @param maxConcurrency maximum number of threads to run in parallel in sub pool
   * @return newly created {@link PrioritySchedulerLimiter} that uses this pool as it's execution source
   */
  public PrioritySchedulerInterface makeSubPool(int maxConcurrency) {
    if (maxConcurrency > corePoolSize) {
      throw new IllegalArgumentException("A sub pool should be smaller than the parent pool");
    }
    
    return new PrioritySchedulerLimiter(this, maxConcurrency);
  }
  
  protected static boolean removeFromTaskQueue(DynamicDelayQueue<TaskWrapper> queue, 
                                               Runnable task) {
    synchronized (queue.getLock()) {
      Iterator<TaskWrapper> it = queue.iterator();
      while (it.hasNext()) {
        TaskWrapper tw = it.next();
        if (tw.task.equals(task)) {
          tw.cancel();
          it.remove();
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Removes the task from the execution queue.  It is possible
   * for the task to still run until this call has returned.
   * 
   * @param task The original task provided to the executor
   * @return true if the task was found and removed
   */
  public boolean remove(Runnable task) {
    return removeFromTaskQueue(highPriorityQueue, task) || 
             removeFromTaskQueue(lowPriorityQueue, task);
  }

  @Override
  public void execute(Runnable task) {
    execute(task, defaultPriority);
  }

  @Override
  public void execute(Runnable task, TaskPriority priority) {
    schedule(task, 0, priority);
  }

  @Override
  public Future<?> submit(Runnable task) {
    return submit(task, defaultPriority);
  }

  @Override
  public Future<?> submit(Runnable task, TaskPriority priority) {
    return submitScheduled(task, 0, priority);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return submit(task, defaultPriority);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task, TaskPriority priority) {
    return submitScheduled(task, 0, priority);
  }

  @Override
  public void schedule(Runnable task, long delayInMs) {
    schedule(task, delayInMs, defaultPriority);
  }

  @Override
  public void schedule(Runnable task, long delayInMs, 
                       TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (delayInMs < 0) {
      throw new IllegalArgumentException("delayInMs must be >= 0");
    }
    if (priority == null) {
      priority = defaultPriority;
    }

    addToQueue(new OneTimeTaskWrapper(task, priority, delayInMs));
  }

  @Override
  public Future<?> submitScheduled(Runnable task, long delayInMs) {
    return submitScheduled(task, delayInMs, defaultPriority);
  }

  @Override
  public Future<?> submitScheduled(Runnable task, long delayInMs, 
                                   TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (delayInMs < 0) {
      throw new IllegalArgumentException("delayInMs must be >= 0");
    }
    if (priority == null) {
      priority = defaultPriority;
    }

    OneTimeFutureTaskWrapper<?> otftw = new OneTimeFutureTaskWrapper<Object>(task, 
                                                                             priority, 
                                                                             delayInMs, 
                                                                             makeLock());
    addToQueue(otftw);
    
    return otftw;
  }

  @Override
  public <T> Future<T> submitScheduled(Callable<T> task, long delayInMs) {
    return submitScheduled(task, delayInMs, defaultPriority);
  }

  @Override
  public <T> Future<T> submitScheduled(Callable<T> task, long delayInMs,
                                       TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (delayInMs < 0) {
      throw new IllegalArgumentException("delayInMs must be >= 0");
    }
    if (priority == null) {
      priority = defaultPriority;
    }

    OneTimeFutureTaskWrapper<T> otftw = new OneTimeFutureTaskWrapper<T>(task, 
                                                                        priority, 
                                                                        delayInMs, 
                                                                        makeLock());
    addToQueue(otftw);
    
    return otftw;
  }

  @Override
  public void scheduleWithFixedDelay(Runnable task, long initialDelay,
                                     long recurringDelay) {
    scheduleWithFixedDelay(task, initialDelay, recurringDelay, 
                           defaultPriority);
  }

  @Override
  public void scheduleWithFixedDelay(Runnable task, long initialDelay,
                                     long recurringDelay, TaskPriority priority) {
    if (task == null) {
      throw new IllegalArgumentException("Must provide a task");
    } else if (initialDelay < 0) {
      throw new IllegalArgumentException("initialDelay must be >= 0");
    } else if (recurringDelay < 0) {
      throw new IllegalArgumentException("recurringDelay must be >= 0");
    }
    if (priority == null) {
      priority = defaultPriority;
    }

    addToQueue(new RecurringTaskWrapper(task, priority, initialDelay, recurringDelay));
  }
  
  protected void addToQueue(TaskWrapper task) {
    switch (task.priority) {
      case High:
        verifyNotShutdown();
        ClockWrapper.stopForcingUpdate();
        try {
          ClockWrapper.updateClock();
          highPriorityQueue.add(task);
        } finally {
          ClockWrapper.resumeForcingUpdate();
        }
        highPriorityConsumer.maybeStart();
        break;
      case Low:
        verifyNotShutdown();
        ClockWrapper.stopForcingUpdate();
        try {
          ClockWrapper.updateClock();
          lowPriorityQueue.add(task);
        } finally {
          ClockWrapper.resumeForcingUpdate();
        }
        lowPriorityConsumer.maybeStart();
        break;
      default:
        throw new UnsupportedOperationException("Priority not implemented: " + task.priority);
    }
  }
  
  protected Worker getExistingWorker(long maxWaitForLowPriorityInMs) throws InterruptedException {
    synchronized (workersLock) {
      long startTime = ClockWrapper.getAccurateTime();
      long waitTime = maxWaitForLowPriorityInMs;
      while (availableWorkers.isEmpty() && waitTime > 0) {
        if (waitTime == Long.MAX_VALUE) {  // prevent overflow
          workersLock.await();
        } else {
          long elapsedTime = ClockWrapper.getAccurateTime() - startTime;
          waitTime = maxWaitForLowPriorityInMs - elapsedTime;
          if (waitTime > 0) {
            workersLock.await(waitTime);
          }
        }
      }
      
      if (availableWorkers.isEmpty()) {
        return null;  // we exceeded the wait time
      } else {
        // always remove from the front, to get the newest worker
        return availableWorkers.removeFirst();
      }
    }
  }
  
  protected Worker makeNewWorker() {
    synchronized (workersLock) {
      Worker w = new Worker();
      currentPoolSize++;
      w.start();
  
      // will be added to available workers when done with first task
      return w;
    }
  }
  
  protected void runHighPriorityTask(TaskWrapper task) throws InterruptedException {
    Worker w = null;
    synchronized (workersLock) {
      if (running) {
        if (currentPoolSize >= maxPoolSize) {
          // we can't make the pool any bigger
          w = getExistingWorker(Long.MAX_VALUE);
        } else {
          if (availableWorkers.isEmpty()) {
            w = makeNewWorker();
          } else {
            // always remove from the front, to get the newest worker
            w = availableWorkers.removeFirst();
          }
        }
      }
    }
    
    if (w != null) {  // may be null if shutdown
      w.nextTask(task);
    }
  }
  
  protected void runLowPriorityTask(TaskWrapper task) throws InterruptedException {
    Worker w = null;
    synchronized (workersLock) {
      if (running) {
        long waitTime;
        if (currentPoolSize >= maxPoolSize) {
          waitTime = Long.MAX_VALUE;
        } else {
          waitTime = maxWaitForLowPriorityInMs;
        }
        w = getExistingWorker(waitTime);
        if (w == null) {
          // this means we expired past our wait time, so just make a new worker
          if (currentPoolSize >= maxPoolSize) {
            // more workers were created while waiting, now have exceeded our max
            w = getExistingWorker(Long.MAX_VALUE);
          } else {
            w = makeNewWorker();
          }
        }
      }
    }
    
    if (w != null) {  // may be null if shutdown
      w.nextTask(task);
    }
  }
  
  protected void lookForExpiredWorkers() {
    synchronized (workersLock) {
      long now = ClockWrapper.getLastKnownTime();
      // we search backwards because the oldest workers will be at the back of the stack
      while ((currentPoolSize > corePoolSize || allowCorePoolTimeout) && 
             ! availableWorkers.isEmpty() && 
             now - availableWorkers.getLast().getLastRunTime() > keepAliveTimeInMs) {
        Worker w = availableWorkers.removeLast();
        killWorker(w);
      }
    }
  }
  
  private void killWorker(Worker w) {
    synchronized (workersLock) {
      w.stop();
      currentPoolSize--;
    }
  }
  
  protected void workerDone(Worker worker) {
    synchronized (workersLock) {
      if (running) {
        // always add to the front so older workers are at the back
        availableWorkers.addFirst(worker);
      
        lookForExpiredWorkers();
            
        workersLock.signalAll();
      } else {
        killWorker(worker);
      }
    }
  }

  @Override
  public VirtualLock makeLock() {
    return new NativeLock();
  }
  
  /**
   * Runnable which will consume tasks from the appropriate 
   * and given the provided implementation to get a worker 
   * and execute consumed tasks.
   * 
   * @author jent - Mike Jensen
   */
  protected class TaskConsumer implements Runnable {
    private final DynamicDelayQueue<TaskWrapper> workQueue;
    private final VirtualLock queueLock;
    private final TaskAcceptor acceptor;
    private volatile boolean started;
    private volatile boolean stopped;
    private volatile Thread runningThread;
    
    protected TaskConsumer(DynamicDelayQueue<TaskWrapper> workQueue, 
                           VirtualLock queueLock, TaskAcceptor acceptor) {
      this.workQueue = workQueue;
      this.queueLock = queueLock;
      this.acceptor = acceptor;
      started = false;
      stopped = false;
      runningThread = null;
    }

    public boolean isRunning() {
      return started && ! stopped;
    }
    
    public void maybeStart() {
      /* this looks like a double check but 
       * due to being volatile and only changing 
       * one direction should be safe, as well as the fact 
       * that started is a primitive (can't be half constructed)
       */
      if (started) {
        return;
      }
      
      synchronized (queueLock) {
        if (started) {
          return;
        }

        started = true;
        runningThread = threadFactory.newThread(this);
        runningThread.setDaemon(true);
        runningThread.setName("ScheduledExecutor task consumer thread");
        runningThread.start();
      }
    }
    
    public void stop() {
      /* this looks like a double check but 
       * due to being volatile and only changing 
       * one direction should be safe, as well as the fact 
       * that started and stopped are primitives
       */
      if (stopped || ! started) {
        return;
      }
      
      synchronized (queueLock) {
        if (stopped || ! started) {
          return;
        }

        stopped = true;
        Thread runningThread = this.runningThread;
        this.runningThread = null;
        runningThread.interrupt();
      }
    }
    
    @Override
    public void run() {
      while (! stopped) {
        try {
          TaskWrapper task;
          /* must lock as same lock for removal to 
           * ensure that task can be found for removal
           */
          synchronized (queueLock) {
            task = workQueue.take();
            task.executing();  // for recurring tasks this will put them back into the queue
          }
          try {
            acceptor.acceptTask(task);
          } catch (InterruptedException e) {
            stop();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (Throwable t) {
          Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
        }
      }
    }
  }
  
  /**
   * Interface for an implementation which can accept
   * consumed tasks.
   * 
   * @author jent - Mike Jensen
   */
  protected interface TaskAcceptor {
    public void acceptTask(TaskWrapper task) throws InterruptedException;
  }
  
  /**
   * Runnable which will run on pool threads.  It 
   * accepts runnables to run, and tracks usage.
   * 
   * @author jent - Mike Jensen
   */
  protected class Worker implements Runnable {
    private final Thread thread;
    private volatile long lastRunTime;
    private volatile boolean running;
    private volatile TaskWrapper nextTask;
    
    protected Worker() {
      thread = threadFactory.newThread(this);
      running = true;
      lastRunTime = ClockWrapper.getLastKnownTime();
      nextTask = null;
    }
    
    public void stop() {
      running = false;
      
      LockSupport.unpark(thread);
    }

    public void start() {
      if (thread.isAlive()) {
        return;
      } else {
        thread.start();
      }
    }
    
    public void nextTask(TaskWrapper task) {
      if (! running) {
        throw new IllegalStateException("Worker has been killed");
      } else if (nextTask != null) {
        throw new IllegalStateException("Already has a task");
      }
        
      nextTask = task;
      
      LockSupport.unpark(thread);
    }
    
    public void blockTillNextTask() throws InterruptedException {
      if (nextTask != null) {
        return;
      }
      
      while (nextTask == null && running) {
        LockSupport.park();
      }
    }
    
    @Override
    public void run() {
      while (running) {
        try {
          blockTillNextTask();
          
          if (nextTask != null) {
            nextTask.run();
          }
        } catch (Throwable t) {
          if (t instanceof InterruptedException || 
              t instanceof OutOfMemoryError) {
           // this will stop the worker, and thus prevent it from calling workerDone
            killWorker(this);
          }
        } finally {
          nextTask = null;
          if (running) {
            lastRunTime = ClockWrapper.getLastKnownTime();
            workerDone(this);
          }
        }
      }
    }
    
    public long getLastRunTime() {
      return lastRunTime;
    }
  }
  
  /**
   * Behavior for task after it finishes completion.
   * 
   * @author jent - Mike Jensen
   */
  protected enum TaskType {OneTime, Recurring};
  
  /**
   * Abstract implementation for all tasks handled by this pool.
   * 
   * @author jent - Mike Jensen
   */
  protected abstract static class TaskWrapper implements Delayed, Runnable {
    public final TaskType taskType;
    public final TaskPriority priority;
    protected final Runnable task;
    protected volatile boolean canceled;
    
    public TaskWrapper(TaskType taskType, 
                          Runnable task, 
                          TaskPriority priority) {
      this.taskType = taskType;
      this.priority = priority;
      this.task = task;
      canceled = false;
    }
    
    public void cancel() {
      canceled = true;
    }
    
    public abstract void executing();

    @Override
    public int compareTo(Delayed o) {
      if (this == o) {
        return 0;
      } else {
        long thisDelay = this.getDelay(TimeUnit.MILLISECONDS);
        long otherDelay = o.getDelay(TimeUnit.MILLISECONDS);
        if (thisDelay == otherDelay) {
          return 0;
        } else if (thisDelay > otherDelay) {
          return 1;
        } else {
          return -1;
        }
      }
    }
    
    @Override
    public String toString() {
      return task.toString();
    }
  }
  
  /**
   * Wrapper for tasks which only executes once.
   * 
   * @author jent - Mike Jensen
   */
  protected static class OneTimeTaskWrapper extends TaskWrapper {
    private final long runTime;
    
    protected OneTimeTaskWrapper(Runnable task, TaskPriority priority, long delay) {
      super(TaskType.OneTime, task, priority);
      
      runTime = ClockWrapper.getAccurateTime() + delay;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return TimeUnit.MILLISECONDS.convert(runTime - ClockWrapper.getAccurateTime(), unit);
    }
    
    @Override
    public void executing() {
      // ignored
    }

    @Override
    public void run() {
      if (! canceled) {
        task.run();
      }
    }
  }
  
  /**
   * Wrapper for tasks which only executes once, and also implements 
   * the {@link Future} interface.
   * 
   * @author jent - Mike Jensen
   */
  protected static class OneTimeFutureTaskWrapper<T> extends OneTimeTaskWrapper 
                                                     implements Future<T> {
    private final Callable<T> callable;
    private final VirtualLock lock;
    private boolean started;
    private boolean done;
    private Exception failure;
    private T result;
    
    protected OneTimeFutureTaskWrapper(Runnable task, TaskPriority priority,
                                       long delay, VirtualLock lock) {
      super(task, priority, delay);
      
      callable = null;
      this.lock = lock;
      started = false;
      done = false;
      failure = null;
      result = null;
    }

    
    protected OneTimeFutureTaskWrapper(Callable<T> callable, TaskPriority priority,
                                       long delay, VirtualLock lock) {
      super(null, priority, delay);
      
      this.callable = callable;
      this.lock = lock;
      started = false;
      done = false;
      failure = null;
      result = null;
    }

    @Override
    public void run() {
      try {
        boolean shouldRun = false;
        synchronized (lock) {
          if (! canceled) {
            started = true;
            shouldRun = true;
          }
        }
        
        if (shouldRun) {
          if (task != null) {
            task.run();
          } else {
            result = callable.call();
          }
        }
        
        synchronized (lock) {
          done = true;
          lock.signalAll();
        }
      } catch (Exception e) {
        synchronized (lock) {
          done = true;
          failure = e;
          lock.signalAll();
        }
        
        throw ExceptionUtils.makeRuntime(e);
      }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      synchronized (lock) {
        canceled = true;
        
        lock.signalAll();
      }
      return ! started;
    }

    @Override
    public boolean isDone() {
      synchronized (lock) {
        return done;
      }
    }

    @Override
    public boolean isCancelled() {
      synchronized (lock) {
        return canceled && ! started;
      }
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
      try {
        return get(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        // basically impossible
        throw ExceptionUtils.makeRuntime(e);
      }
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException,
                                                     ExecutionException,
                                                     TimeoutException {
      long startTime = Clock.accurateTime();
      long timeoutInMs = TimeUnit.MILLISECONDS.convert(timeout, unit);
      synchronized (lock) {
        long waitTime = timeoutInMs - (Clock.accurateTime() - startTime);
        while (! done && waitTime > 0) {
          lock.await(waitTime);
          waitTime = timeoutInMs - (Clock.accurateTime() - startTime);
        }
        if (failure != null) {
          throw new ExecutionException(failure);
        } else if (! done) {
          throw new TimeoutException();
        }
        return result;
      }
    }
  }
  
  /**
   * Wrapper for tasks which reschedule after completion.
   * 
   * @author jent - Mike Jensen
   */
  protected class RecurringTaskWrapper extends TaskWrapper 
                                       implements DynamicDelayedUpdater {
    private final long recurringDelay;
    //private volatile long maxExpectedRuntime;
    private volatile boolean executing;
    private long nextRunTime;
    
    protected RecurringTaskWrapper(Runnable task, TaskPriority priority, 
                                   long initialDelay, long recurringDelay) {
      super(TaskType.Recurring, task, priority);
      
      this.recurringDelay = recurringDelay;
      //maxExpectedRuntime = -1;
      executing = false;
      this.nextRunTime = ClockWrapper.getAccurateTime() + initialDelay;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      if (executing) {
        return Long.MAX_VALUE;
      } else {
        return TimeUnit.MILLISECONDS.convert(getNextDelayInMillis(), unit);
      }
    }
    
    private long getNextDelayInMillis() {
      return nextRunTime - ClockWrapper.getAccurateTime();
    }

    @Override
    public void allowDelayUpdate() {
      executing = false;
    }
    
    @Override
    public void executing() {
      if (canceled) {
        return;
      }
      executing = true;
      /* add to queue before started, so that it can be removed if necessary
       * We add to the end because the task wont re-run till it has finished, 
       * so there is no reason to sort at this point
       */
      switch (priority) {
        case High:
          highPriorityQueue.addLast(this);
          break;
        case Low:
          lowPriorityQueue.addLast(this);
          break;
        default:
          throw new UnsupportedOperationException("Not implemented for priority: " + priority);
      }
    }
    
    private void reschedule() {
      nextRunTime = ClockWrapper.getAccurateTime() + recurringDelay;
      
      // now that nextRunTime has been set, resort the queue
      switch (priority) {
        case High:
          synchronized (highPriorityLock) {
            if (running) {
              ClockWrapper.stopForcingUpdate();
              try {
                ClockWrapper.updateClock();
                highPriorityQueue.reposition(this, getNextDelayInMillis(), this);
              } finally {
                ClockWrapper.resumeForcingUpdate();
              }
            }
          }
          break;
        case Low:
          synchronized (lowPriorityLock) {
            if (running) {
              ClockWrapper.stopForcingUpdate();
              try {
                ClockWrapper.updateClock();
                lowPriorityQueue.reposition(this, getNextDelayInMillis(), this);
              } finally {
                ClockWrapper.resumeForcingUpdate();
              }
            }
          }
          break;
        default:
          throw new UnsupportedOperationException("Not implemented for priority: " + priority);
      }
    }

    @Override
    public void run() {
      if (canceled) {
        return;
      }
      try {
        //long startTime = ClockWrapper.getLastKnownTime();
        
        task.run();
        
        /*long runTime = ClockWrapper.getLastKnownTime() - startTime;
        if (runTime > maxExpectedRuntime) {
          maxExpectedRuntime = runTime;
        }*/
      } finally {
        if (! canceled) {
          reschedule();
        }
      }
    }
  }
}