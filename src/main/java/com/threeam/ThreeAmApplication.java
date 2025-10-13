package com.threeam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class ThreeAmApplication {

	public static void main(String[] args) {
		SpringApplication.run(ThreeAmApplication.class, args);
	}

}
