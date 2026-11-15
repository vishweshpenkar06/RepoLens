package com.vishwesh.repolens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RepolensApplication {

  public static void main(String[] args) {
    SpringApplication.run(RepolensApplication.class, args);
  }
}
