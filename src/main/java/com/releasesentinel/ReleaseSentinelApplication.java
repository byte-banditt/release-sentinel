package com.releasesentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReleaseSentinelApplication {
    public static void main(String[] args) { SpringApplication.run(ReleaseSentinelApplication.class, args); }
}
