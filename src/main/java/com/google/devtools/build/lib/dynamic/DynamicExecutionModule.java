package com.google.devtools.build.lib.dynamic;

import com.google.devtools.build.lib.actions.ExecutorInitException;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.ExecutorBuilder;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.AbruptExitException;

/**
 * {@link BlazeModule} providing support for dynamic spawn execution and scheduling.
 */
public class DynamicExecutionModule extends BlazeModule {

  @Override
  public void beforeCommand(CommandEnvironment env) throws AbruptExitException {
    super.beforeCommand(env);
  }

  @Override
  public void executorInit(CommandEnvironment env, BuildRequest request, ExecutorBuilder builder)
      throws ExecutorInitException {
    super.executorInit(env, request, builder);
  }

  @Override
  public void afterCommand() {
    super.afterCommand();
  }
}
