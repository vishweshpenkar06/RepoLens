package com.vishwesh.repolens.ingest;

import java.util.regex.Pattern;

/** A validated {@code https://github.com/{owner}/{name}} repository reference. */
public record GithubUrl(String owner, String name) {

  private static final Pattern OWNER =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  public GithubUrl {
    if (!OWNER.matcher(owner).matches()) {
      throw new InvalidRepositoryUrlException("Invalid GitHub owner: " + owner);
    }
    if (!NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
      throw new InvalidRepositoryUrlException("Invalid GitHub repository name: " + name);
    }
  }

  public String slug() {
    return owner + "/" + name;
  }

  public String htmlUrl() {
    return "https://github.com/" + slug();
  }
}
