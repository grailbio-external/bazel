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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.DeterministicWriter;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.OsPathPolicy;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/** Creates a manifest file describing the repos and mappings relevant for a runfile tree. */
public class RepoMappingManifestAction extends AbstractFileWriteAction {
  private static final UUID MY_UUID = UUID.fromString("458e351c-4d30-433d-b927-da6cddd4737f");

  private final ImmutableSortedMap<RepositoryName, ImmutableSortedMap<String, RepositoryName>>
      reposAndMappings;
  private final String workspaceName;

  public RepoMappingManifestAction(
      ActionOwner owner,
      Artifact output,
      Map<RepositoryName, RepositoryMapping> reposAndMappings,
      String workspaceName) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output, /*makeExecutable=*/ false);
    this.reposAndMappings =
        ImmutableSortedMap.copyOf(
            Maps.transformValues(
                reposAndMappings, repoMapping -> ImmutableSortedMap.copyOf(repoMapping.entries())),
            (a, b) -> OsPathPolicy.getFilePathOs().compare(a.getName(), b.getName()));
    this.workspaceName = workspaceName;
  }

  @Override
  public String getMnemonic() {
    return "RepoMappingManifest";
  }

  @Override
  protected String getRawProgressMessage() {
    return "writing repo mapping manifest for " + getOwner().getLabel();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, EvalException, InterruptedException {
    fp.addUUID(MY_UUID);
    fp.addString(workspaceName);
    for (Entry<RepositoryName, ImmutableSortedMap<String, RepositoryName>> repoAndMapping :
        reposAndMappings.entrySet()) {
      fp.addString(repoAndMapping.getKey().getName());
      fp.addInt(repoAndMapping.getValue().size());
      for (Entry<String, RepositoryName> mappingEntry : repoAndMapping.getValue().entrySet()) {
        fp.addString(mappingEntry.getKey());
        fp.addString(mappingEntry.getValue().getName());
      }
    }
  }

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx)
      throws InterruptedException, ExecException {
    return out -> {
      Writer writer = new BufferedWriter(new OutputStreamWriter(out, ISO_8859_1));
      for (Entry<RepositoryName, ImmutableSortedMap<String, RepositoryName>> repoAndMapping :
          reposAndMappings.entrySet()) {
        for (Entry<String, RepositoryName> mappingEntry : repoAndMapping.getValue().entrySet()) {
          if (mappingEntry.getKey().isEmpty()) {
            // The apparent repo name can only be empty for the main repo. We skip this line.
            continue;
          }
          writer.write(repoAndMapping.getKey().getName());
          writer.write(',');
          writer.write(mappingEntry.getKey());
          writer.write(',');
          if (mappingEntry.getValue().isMain()) {
            writer.write(workspaceName);
          } else {
            writer.write(mappingEntry.getValue().getName());
          }
          writer.write('\n');
        }
      }
      writer.flush();
    };
  }
}
