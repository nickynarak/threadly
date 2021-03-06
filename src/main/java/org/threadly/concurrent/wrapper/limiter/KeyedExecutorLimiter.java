package org.threadly.concurrent.wrapper.limiter;

import java.util.concurrent.Executor;

import org.threadly.concurrent.wrapper.traceability.ThreadRenamingExecutor;
import org.threadly.util.StringUtils;

/**
 * This is a cross between the {@link org.threadly.concurrent.wrapper.KeyDistributedExecutor} and 
 * an {@link ExecutorLimiter}.  This is designed to limit concurrency for a given thread, but 
 * permit more than one thread to run at a time for a given key.  If the desired effect is to have 
 * a single thread per key, {@link org.threadly.concurrent.wrapper.KeyDistributedExecutor} is a 
 * much better option.
 * <p>
 * The easiest way to use this class would be to have it distribute out executors through 
 * {@link #getSubmitterExecutorForKey(Object)}.
 * 
 * @since 4.6.0 (since 4.3.0 at org.threadly.concurrent.limiter)
 */
public class KeyedExecutorLimiter extends AbstractKeyedLimiter<ExecutorLimiter> {
  /**
   * Construct a new {@link KeyedExecutorLimiter} providing only the backing executor and the 
   * maximum concurrency per unique key.  By default this will not rename threads for tasks 
   * executing.
   * 
   * @param executor Executor to execute tasks on
   * @param maxConcurrency Maximum concurrency allowed per task key
   */
  public KeyedExecutorLimiter(Executor executor, int maxConcurrency) {
    this(executor, maxConcurrency, null, false);
  }

  /**
   * Construct a new {@link KeyedExecutorLimiter} providing the backing executor, the maximum 
   * concurrency per unique key, and how keyed limiter threads should be named.
   * 
   * @param executor Executor to execute tasks on to
   * @param maxConcurrency Maximum concurrency allowed per task key
   * @param subPoolName Name prefix for sub pools, {@code null} to not change thread names
   * @param addKeyToThreadName If {@code true} the key's .toString() will be added in the thread name
   */
  public KeyedExecutorLimiter(Executor executor, int maxConcurrency, 
                              String subPoolName, boolean addKeyToThreadName) {
    this(executor, maxConcurrency, subPoolName, addKeyToThreadName, DEFAULT_LOCK_PARALISM);
  }

  /**
   * Construct a new {@link KeyedExecutorLimiter} providing the backing executor, the maximum 
   * concurrency per unique key, and how keyed limiter threads should be named.
   * <p>
   * The parallelism value should be a factor of how many keys are submitted to the pool during any 
   * given period of time.  Depending on task execution duration, and quantity of threads executing 
   * tasks this value may be able to be smaller than expected.  Higher values result in less lock 
   * contention, but more memory usage.  Most systems will run fine with this anywhere from 4 to 64.
   * 
   * @param executor Executor to execute tasks on to
   * @param maxConcurrency Maximum concurrency allowed per task key
   * @param subPoolName Name prefix for sub pools, {@code null} to not change thread names
   * @param addKeyToThreadName If {@code true} the key's .toString() will be added in the thread name
   * @param expectedParallism Expected concurrent task addition access, used for performance tuning
   */
  public KeyedExecutorLimiter(Executor executor, int maxConcurrency, 
                              String subPoolName, boolean addKeyToThreadName, 
                              int expectedParallism) {
    super(executor, maxConcurrency, subPoolName, addKeyToThreadName, expectedParallism);
  }
  
  @Override
  protected ExecutorLimiter makeLimiter(String limiterThreadName) {
    return new ExecutorLimiter(StringUtils.isNullOrEmpty(limiterThreadName) ? 
                                 executor : new ThreadRenamingExecutor(executor, limiterThreadName, false), 
                               maxConcurrency);
  }
  
  /**********
   * 
   * NO IMPLEMENTATION SHOULD EXIST HERE, THIS SHOULD ALL BE IN {@link AbstractKeyedLimiter}
   * 
   **********/
}
