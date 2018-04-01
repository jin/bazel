// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.rules;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.Builder;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.RuleSet;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.config.ConfigRules;
import com.google.devtools.build.lib.rules.core.CoreRules;
import com.google.devtools.build.lib.rules.cpp.transitions.LipoDataTransitionRuleSet;
import com.google.devtools.build.lib.rules.platform.PlatformRules;
import com.google.devtools.build.lib.rules.repository.CoreWorkspaceRules;

/** A rule class provider implementing the rules Bazel knows. */
public class BazelRuleClassProvider {
  public static final String TOOLS_REPOSITORY = "@bazel_tools";

  /** Used by the build encyclopedia generator. */
  public static ConfiguredRuleClassProvider create() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    builder.setToolsRepository(TOOLS_REPOSITORY);
    setup(builder);
    return builder.build();
  }

  public static void setup(ConfiguredRuleClassProvider.Builder builder) {
    for (RuleSet ruleSet : RULE_SETS) {
      ruleSet.init(builder);
    }
  }

  public static final RuleSet BAZEL_SETUP =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder
              .setPrelude("//tools/build_rules:prelude_bazel")
              .setNativeLauncherLabel("//tools/launcher:launcher")
              .setRunfilesPrefix(Label.DEFAULT_REPOSITORY_DIRECTORY)
              .setPrerequisiteValidator(new BazelPrerequisiteValidator());

          builder.setUniversalConfigurationFragment(BazelConfiguration.class);
          builder.addConfigurationFragment(new BazelConfiguration.Loader());
          builder.addConfigurationOptions(BazelConfiguration.Options.class);
          builder.addConfigurationOptions(BuildConfiguration.Options.class);
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of();
        }
      };

  private static final ImmutableSet<RuleSet> RULE_SETS =
      ImmutableSet.of(
          // Rules defined before LipoDataTransitionRuleSet will fail when trying to declare a data
          // transition.
          // TODO(b/73071922): remove this when LIPO support is phased out
          LipoDataTransitionRuleSet.INSTANCE,
          BAZEL_SETUP,
          CoreRules.INSTANCE,
          CoreWorkspaceRules.INSTANCE,
          GenericRules.INSTANCE,
          ConfigRules.INSTANCE,
          PlatformRules.INSTANCE,
          CcRules.INSTANCE,
          ObjcRules.INSTANCE);
}
