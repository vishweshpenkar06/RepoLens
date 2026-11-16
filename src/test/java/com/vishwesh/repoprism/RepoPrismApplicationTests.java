package com.vishwesh.repoprism;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RepoPrismApplicationTests {

  @Test
  void contextLoads() {}
}
