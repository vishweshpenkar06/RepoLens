package com.vishwesh.repoprism;

import org.springframework.boot.SpringApplication;

public class TestRepoPrismApplication {

  public static void main(String[] args) {
    SpringApplication.from(RepoPrismApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
