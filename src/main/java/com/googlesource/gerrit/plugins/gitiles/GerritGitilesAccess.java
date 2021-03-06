// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.gitiles;

import com.google.common.collect.Maps;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AnonymousCowardName;
import com.google.gerrit.server.project.ListProjects;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectJson;
import com.google.gerrit.server.project.ProjectJson.ProjectInfo;
import com.google.gerrit.server.project.ProjectState;
import com.google.gitiles.GitilesAccess;
import com.google.gitiles.GitilesUrls;
import com.google.gitiles.RepositoryDescription;
import com.google.inject.Inject;
import com.google.inject.Provider;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

class GerritGitilesAccess implements GitilesAccess {
  // Assisted injection doesn't work with the overridden method, so write the
  // factory manually.
  static class Factory implements GitilesAccess.Factory {
    private final ProjectCache projectCache;
    private final ProjectJson projectJson;
    private final Provider<ListProjects> listProjects;
    private final GitilesUrls urls;
    private final Provider<CurrentUser> userProvider;
    private final String anonymousCowardName;

    @Inject
    Factory(ProjectCache projectCache,
        ProjectJson projectJson,
        Provider<ListProjects> listProjects,
        GitilesUrls urls,
        Provider<CurrentUser> userProvider,
        @AnonymousCowardName String anonymousCowardName) {
      this.projectCache = projectCache;
      this.projectJson = projectJson;
      this.listProjects = listProjects;
      this.urls = urls;
      this.userProvider = userProvider;
      this.anonymousCowardName = anonymousCowardName;
    }

    @Override
    public GerritGitilesAccess forRequest(HttpServletRequest req) {
      return new GerritGitilesAccess(this, req);
    }
  }

  private final ProjectCache projectCache;
  private final ProjectJson projectJson;
  private final Provider<ListProjects> listProjects;
  private final GitilesUrls urls;
  private final Provider<CurrentUser> userProvider;
  private final String anonymousCowardName;
  private final HttpServletRequest req;

  @Inject
  GerritGitilesAccess(Factory factory, HttpServletRequest req) {
    this.projectCache = factory.projectCache;
    this.projectJson = factory.projectJson;
    this.listProjects = factory.listProjects;
    this.urls = factory.urls;
    this.userProvider = factory.userProvider;
    this.anonymousCowardName = factory.anonymousCowardName;
    this.req = req;
  }

  @Override
  public Map<String, RepositoryDescription> listRepositories(
      Set<String> branches) throws ServiceNotEnabledException,
      ServiceNotAuthorizedException, IOException {
    ListProjects lp = listProjects.get();
    lp.setShowDescription(true);
    lp.setAll(true);
    for (String branch : branches) {
      lp.addShowBranch(branch);
    }
    Map<String, ProjectInfo> projects = lp.apply();
    Map<String, RepositoryDescription> result = Maps.newLinkedHashMap();
    for (Map.Entry<String, ProjectInfo> e : projects.entrySet()) {
      result.put(e.getKey(), toDescription(e.getKey(), e.getValue()));
    }
    return Collections.unmodifiableMap(result);
  }

  private RepositoryDescription toDescription(String name, ProjectInfo info) {
    RepositoryDescription desc = new RepositoryDescription();
    desc.name = name;
    desc.cloneUrl = urls.getBaseGitUrl(req) + name;
    desc.description = info.description;
    if (info.branches != null) {
      desc.branches = Collections.unmodifiableMap(info.branches);
    }
    return desc;
  }

  @Override
  public Object getUserKey() {
    CurrentUser user = userProvider.get();
    if (user instanceof IdentifiedUser) {
      return ((IdentifiedUser) user).getAccountId();
    } else {
      return anonymousCowardName;
    }
  }

  @Override
  public String getRepositoryName() {
    return Resolver.getNameKey(req).get();
  }

  @Override
  public RepositoryDescription getRepositoryDescription() throws IOException {
    Project.NameKey nameKey = Resolver.getNameKey(req);
    ProjectState state = projectCache.get(nameKey);
    if (state == null) {
      throw new RepositoryNotFoundException(nameKey.get());
    }
    return toDescription(nameKey.get(), projectJson.format(state.getProject()));
  }
}
