package com.vishwesh.repoprism;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RepoPrismApplication {

  public static void main(String[] args) {
    SpringApplication.run(RepoPrismApplication.class, args);
  }
}
