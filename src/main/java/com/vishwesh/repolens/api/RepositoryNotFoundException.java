package com.vishwesh.repolens.api;

public class RepositoryNotFoundException extends RuntimeException {

  public RepositoryNotFoundException(long repositoryId) {
    super("Repository " + repositoryId + " not found");
  }
}
