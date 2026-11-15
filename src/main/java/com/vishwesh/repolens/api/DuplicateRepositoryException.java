package com.vishwesh.repolens.api;

public class DuplicateRepositoryException extends RuntimeException {

  public DuplicateRepositoryException(String slug) {
    super("Repository " + slug + " is already registered");
  }
}
