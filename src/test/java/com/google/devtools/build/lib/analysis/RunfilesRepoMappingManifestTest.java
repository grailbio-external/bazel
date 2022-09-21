// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Injected;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that the repo mapping manifest file is properly generated for runfiles. */
@RunWith(JUnit4.class)
public class RunfilesRepoMappingManifestTest extends BuildViewTestCase {
  private Path moduleRoot;
  private FakeRegistry registry;

  @Override
  protected ImmutableList<Injected> extraPrecomputedValues() {
    try {
      moduleRoot = scratch.dir("modules");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString());
    return ImmutableList.of(
        PrecomputedValue.injected(
            ModuleFileFunction.REGISTRIES, ImmutableList.of(registry.getUrl())),
        PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
        PrecomputedValue.injected(ModuleFileFunction.MODULE_OVERRIDES, ImmutableMap.of()),
        PrecomputedValue.injected(
            BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING));
  }

  @Override
  protected AnalysisMock getAnalysisMock() {
    // Make sure we don't have built-in modules affecting the dependency graph.
    return new AnalysisMock.Delegate(super.getAnalysisMock()) {
      @Override
      public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(
          BlazeDirectories directories) {
        return ImmutableMap.<SkyFunctionName, SkyFunction>builder()
            .putAll(
                Maps.filterKeys(
                    super.getSkyFunctions(directories),
                    fnName -> !fnName.equals(SkyFunctions.MODULE_FILE)))
            .put(
                SkyFunctions.MODULE_FILE,
                new ModuleFileFunction(
                    FakeRegistry.DEFAULT_FACTORY, directories.getWorkspace(), ImmutableMap.of()))
            .buildOrThrow();
      }
    };
  }

  /**
   * Sets up a Bazel module simple_rule@1.0, which provides a simple_binary rule that passes along
   * runfiles in the data attribute. It also uses a toolchain (type //:toolchain_type, default
   * toolchain //:simple_toolchain; the macro //:defs.bzl%simple_toolchain can be used to create
   * more).
   */
  @Before
  public void setupSimpleBinaryRule() throws Exception {
    setBuildLanguageOptions("--enable_bzlmod");
    registry.addModule(
        createModuleKey("simple_rule", "1.0"),
        "module(name='simple_rule',version='1.0')",
        "register_toolchains('//:all')");
    scratch.overwriteFile(moduleRoot.getRelative("simple_rule~1.0/WORKSPACE").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("simple_rule~1.0/defs.bzl").getPathString(),
        "def _simple_binary_impl(ctx):",
        "  exe = ctx.actions.declare_file(ctx.label.name)",
        "  ctx.actions.write(exe, ctx.toolchains['//:toolchain_type'].simple_info, True)",
        "  runfiles = ctx.runfiles(files=ctx.files.data)",
        "  for data in ctx.attr.data:",
        "    runfiles.merge(data[DefaultInfo].default_runfiles)",
        "  return DefaultInfo(files=depset(direct=[exe]), executable=exe, runfiles=runfiles)",
        "simple_binary=rule(",
        "  implementation=_simple_binary_impl,",
        "  attrs={'data':attr.label_list(allow_files=True)},",
        "  executable=True,",
        "  toolchains=['//:toolchain_type'],",
        ")",
        "",
        "def _simple_toolchain_rule_impl(ctx):",
        "  return [platform_common.ToolchainInfo(simple_info = ctx.attr.string)]",
        "simple_toolchain_rule=rule(_simple_toolchain_rule_impl, attrs={'string':attr.string()})",
        "def simple_toolchain(name, string):",
        "  simple_toolchain_rule(name=name+'_impl',string=string)",
        "  native.toolchain(",
        "    name=name,",
        "    toolchain=':'+name+'_impl',",
        "    toolchain_type=Label('//:toolchain_type'),",
        "  )");
    scratch.overwriteFile(
        moduleRoot.getRelative("simple_rule~1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'simple_toolchain')",
        "toolchain_type(name='toolchain_type')",
        "simple_toolchain(name='simple_toolchain', string='simple')");
  }

  private ImmutableList<String> getRepoMappingManifestForTarget(String label) throws Exception {
    Action action = getGeneratingAction(getRunfilesSupport(label).getRepoMappingManifest());
    assertThat(action).isInstanceOf(RepoMappingManifestAction.class);
    return ((RepoMappingManifestAction) action)
        .newDeterministicWriter(null)
        .getBytes()
        .toStringUtf8()
        .lines()
        .collect(toImmutableList());
  }

  private void writeBuildFile(String repo, String... lines) throws Exception {
    scratch.overwriteFile(moduleRoot.getRelative(repo).getRelative("WORKSPACE").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative(repo).getRelative("BUILD").getPathString(),
        "load('@simple_rule//:defs.bzl', 'simple_binary')");
    scratch.appendFile(moduleRoot.getRelative(repo).getRelative("BUILD").getPathString(), lines);
  }

  @Test
  public void diamond() throws Exception {
    rewriteWorkspace("workspace(name='aaa')");
    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "bazel_dep(name='simple_rule',version='1.0')");
    registry.addModule(
        createModuleKey("bbb", "1.0"),
        "module(name='bbb',version='1.0')",
        "bazel_dep(name='ddd',version='1.0')",
        "bazel_dep(name='simple_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ccc", "2.0"),
        "module(name='ccc',version='2.0')",
        "bazel_dep(name='ddd',version='2.0')",
        "bazel_dep(name='simple_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ddd", "1.0"),
        "module(name='ddd',version='1.0')",
        "bazel_dep(name='simple_rule',version='1.0')");
    registry.addModule(
        createModuleKey("ddd", "2.0"),
        "module(name='ddd',version='2.0')",
        "bazel_dep(name='simple_rule',version='1.0')");

    scratch.overwriteFile(
        "BUILD",
        "load('@simple_rule//:defs.bzl', 'simple_binary')",
        "simple_binary(name='aaa',data=['@bbb'])");
    writeBuildFile("bbb~1.0", "simple_binary(name='bbb',data=['@ddd'])");
    writeBuildFile("ccc~2.0", "simple_binary(name='ccc',data=['@ddd'])");
    writeBuildFile("ddd~1.0", "simple_binary(name='ddd')");
    writeBuildFile("ddd~2.0", "simple_binary(name='ddd')");

    assertThat(getRepoMappingManifestForTarget("//:aaa"))
        .containsExactly(
            ",aaa,aaa",
            ",bbb,bbb~1.0",
            ",ccc,ccc~2.0",
            ",simple_rule,simple_rule~1.0",
            "bbb~1.0,bbb,bbb~1.0",
            "bbb~1.0,ddd,ddd~2.0",
            "bbb~1.0,simple_rule,simple_rule~1.0",
            "ddd~2.0,ddd,ddd~2.0",
            "ddd~2.0,simple_rule,simple_rule~1.0",
            "simple_rule~1.0,simple_rule,simple_rule~1.0") // Required because of the toolchain
        .inOrder();
    assertThat(getRepoMappingManifestForTarget("@@ccc~2.0//:ccc"))
        .containsExactly(
            "ccc~2.0,ccc,ccc~2.0",
            "ccc~2.0,ddd,ddd~2.0",
            "ccc~2.0,simple_rule,simple_rule~1.0",
            "ddd~2.0,ddd,ddd~2.0",
            "ddd~2.0,simple_rule,simple_rule~1.0",
            "simple_rule~1.0,simple_rule,simple_rule~1.0")
        .inOrder();
  }

  @Test
  public void toolchainDep() throws Exception {
    rewriteWorkspace("workspace(name='main')");
    scratch.overwriteFile(
        "MODULE.bazel",
        "bazel_dep(name='simple_rule',version='1.0')",
        "bazel_dep(name='my_simple_toolchain',version='1.0')",
        "bazel_dep(name='unrelated_rule',version='1.0')",
        "register_toolchains('@my_simple_toolchain//:all', '@unrelated_rule//:all')");
    registry.addModule(
        createModuleKey("my_simple_toolchain", "1.0"),
        "module(name='my_simple_toolchain',version='1.0')",
        "bazel_dep(name='simple_rule',version='1.0')");
    registry.addModule(
        createModuleKey("unrelated_rule", "1.0"), "module(name='unrelated_rule',version='1.0')");

    scratch.overwriteFile(
        "BUILD",
        "load('@simple_rule//:defs.bzl', 'simple_binary')",
        "load('@unrelated_rule//:defs.bzl', 'unrelated_binary')",
        "simple_binary(name='simple')",
        "unrelated_binary(name='unrelated')");
    scratch.overwriteFile(
        moduleRoot.getRelative("my_simple_toolchain~1.0/WORKSPACE").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("my_simple_toolchain~1.0/BUILD").getPathString(),
        "load('@simple_rule//:defs.bzl', 'simple_toolchain')",
        "simple_toolchain(name='custom_toolchain',string='custom')");
    scratch.overwriteFile(moduleRoot.getRelative("unrelated_rule~1.0/WORKSPACE").getPathString());
    scratch.overwriteFile(
        moduleRoot.getRelative("unrelated_rule~1.0/defs.bzl").getPathString(),
        "def _unrelated_binary_impl(ctx):",
        "  exe = ctx.actions.declare_file(ctx.label.name)",
        "  ctx.actions.write(exe, ctx.toolchains['//:toolchain_type'].unrelated_info, True)",
        "  return DefaultInfo(files=depset(direct=[exe]), executable=exe)",
        "unrelated_binary=rule(",
        "  implementation=_unrelated_binary_impl,",
        "  executable=True,",
        "  toolchains=['//:toolchain_type'],",
        ")",
        "",
        "def _unrelated_toolchain_impl(ctx):",
        "  return [platform_common.ToolchainInfo(unrelated_info = '3')]",
        "unrelated_toolchain=rule(_unrelated_toolchain_impl)");
    scratch.overwriteFile(
        moduleRoot.getRelative("unrelated_rule~1.0/BUILD").getPathString(),
        "load('//:defs.bzl', 'unrelated_toolchain')",
        "toolchain_type(name='toolchain_type')",
        "unrelated_toolchain(name='unrelated')",
        "toolchain(name='toolchain',toolchain=':unrelated',toolchain_type=':toolchain_type')");

    // Very importantly, the transitive repos for //:simple do not include "unrelated_rule".
    assertThat(getRepoMappingManifestForTarget("//:simple"))
        .containsExactly(
            ",main,main",
            ",my_simple_toolchain,my_simple_toolchain~1.0",
            ",simple_rule,simple_rule~1.0",
            ",unrelated_rule,unrelated_rule~1.0",
            "my_simple_toolchain~1.0,my_simple_toolchain,my_simple_toolchain~1.0",
            "my_simple_toolchain~1.0,simple_rule,simple_rule~1.0")
        .inOrder();
  }
}
