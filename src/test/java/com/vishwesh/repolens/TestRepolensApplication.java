package com.vishwesh.repolens;

import org.springframework.boot.SpringApplication;

public class TestRepolensApplication {

  public static void main(String[] args) {
    SpringApplication.from(RepolensApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
