package com.vibelex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VibeLexApplication {
  public static void main(String[] args) {
    SpringApplication.run(VibeLexApplication.class, args);
  }
}
